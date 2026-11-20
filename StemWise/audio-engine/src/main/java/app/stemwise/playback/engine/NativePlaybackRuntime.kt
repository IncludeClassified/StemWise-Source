package app.stemwise.playback.engine

import kotlinx.coroutines.ensureActive
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import app.stemwise.model.StemRoleContract
import app.stemwise.MAX_NATIVE_BEAT_GRID_POINTS

internal fun musicLaneMaskForStemCount(stemCount: Int): Int {
    require(stemCount in 0..StemRoleContract.maximumStemCount)
    return (1 shl (stemCount + 1)) - 1
}

enum class NativeRuntimeBank(val nativeCode: Int) {
    FIRST(NativePacketSchema.ENUM_NATIVE_RUNTIME_BANK_FIRST),
    SECOND(NativePacketSchema.ENUM_NATIVE_RUNTIME_BANK_SECOND);

    companion object {
        fun fromNative(code: Int): NativeRuntimeBank = entries.firstOrNull {
            it.nativeCode == code
        } ?: error("Unknown native runtime bank $code")
    }
}

data class NativeRuntimeOwnership(
    val selectionToken: Long,
    val generation: Long
) {
    init {
        require(selectionToken > 0L) { "A native selection token must be positive" }
        require(generation > 0L) { "A native generation must be positive" }
    }
}

internal data class NativeRuntimeBankIntent(
    val revision: Long,
    val ownership: NativeRuntimeOwnership? = null,
    val firstTimelineFrame: Long = 0L,
    val outputClockStartFrame: Long = 0L,
    val songIdentityHash: Long = 0L,
    val originalContentHash: Long = 0L,
    val stemPackageHash: Long = 0L,
    val stemLaneCount: Int = 0,
    val cacheFrameUpperBound: Long = 0L,
    val playbackEndFrameExclusive: Long = 0L
) {
    val assigned: Boolean get() = ownership != null

    init {
        require(revision > 0L) { "A bank intent revision must be positive" }
        require(firstTimelineFrame >= 0L)
        require(outputClockStartFrame >= 0L)
        require(cacheFrameUpperBound >= 0L)
        require(playbackEndFrameExclusive == 0L || playbackEndFrameExclusive > firstTimelineFrame)
        require(stemLaneCount in 0..StemRoleContract.maximumStemCount)
        require((stemPackageHash == 0L) == (stemLaneCount == 0)) {
            "A declared stem package requires its exact active lane count"
        }
        if (!assigned) {
            require(
                firstTimelineFrame == 0L && outputClockStartFrame == 0L &&
                    songIdentityHash == 0L && originalContentHash == 0L && stemPackageHash == 0L &&
                    cacheFrameUpperBound == 0L && playbackEndFrameExclusive == 0L
            ) {
                "An unassigned bank cannot retain timeline or content identity"
            }
        } else {
            require(cacheFrameUpperBound > 0L) { "An assigned bank requires its explicit cache frame bound" }
            require(songIdentityHash != 0L && originalContentHash != 0L) {
                "An assigned bank requires stable song and Original content identity"
            }
        }
    }

    companion object {
        fun unassigned(revision: Long) = NativeRuntimeBankIntent(revision)
    }
}

enum class NativeRuntimeSourceMode(val nativeCode: Int) {
    ORIGINAL(NativePacketSchema.ENUM_NATIVE_RUNTIME_SOURCE_MODE_ORIGINAL),
    STEMS(NativePacketSchema.ENUM_NATIVE_RUNTIME_SOURCE_MODE_STEMS);

    companion object {
        fun fromNative(code: Int): NativeRuntimeSourceMode = entries.firstOrNull {
            it.nativeCode == code
        } ?: error("Unknown native source mode $code")
    }
}

data class NativeRuntimeStemControl(
    val gain: Float = 1f,
    val muted: Boolean = false,
    val soloed: Boolean = false
) {
    init {
        require(gain.isFinite() && gain in 0f..2f)
    }
}

enum class NativeRuntimeMetronomeMode(val nativeCode: Int) {
    OFF(NativePacketSchema.ENUM_NATIVE_RUNTIME_METRONOME_MODE_OFF),
    FREE_BPM(NativePacketSchema.ENUM_NATIVE_RUNTIME_METRONOME_MODE_FREE_BPM),
    BEAT_GRID(NativePacketSchema.ENUM_NATIVE_RUNTIME_METRONOME_MODE_BEAT_GRID)
}

data class NativeRuntimeMetronomeControl(
    val mode: NativeRuntimeMetronomeMode = NativeRuntimeMetronomeMode.OFF,
    val bpm: Float = 120f,
    val gain: Float = .35f,
    val subdivision: Int = 1,
    val beatsPerBar: Int = 4,
    val beatUnitDenominator: Int = 4
) {
    init {
        require(bpm.isFinite() && bpm in 20f..400f)
        require(gain.isFinite() && gain in 0f..1f)
        require(subdivision in setOf(1, 2, 4))
        require(beatsPerBar in 1..32)
        require(beatUnitDenominator in setOf(1, 2, 4, 8, 16, 32))
    }
}

data class NativeRuntimeControls(
    val revision: Long,
    /** Exact source/package fences. Command snapshots require 0/0 unselected or positive/positive. */
    val sourceIntentRevision: Long = 0L,
    val stemIntentRevision: Long = 0L,
    val sourceMode: NativeRuntimeSourceMode = NativeRuntimeSourceMode.ORIGINAL,
    val stems: List<NativeRuntimeStemControl> = List(STEM_COUNT) {
        NativeRuntimeStemControl()
    },
    val musicGain: Float = 1f,
    val mixHeadroom: Float = .9f,
    val sourceTransitionFrames: Int = 240,
    val laneRampFrames: Int = 240,
    val masterRampFrames: Int = 240,
    val defaultDeclickFrames: Int = 240,
    val metronome: NativeRuntimeMetronomeControl = NativeRuntimeMetronomeControl(),
    val tempo: Float = 1f,
    val pitchSemitones: Float = 0f
) {
    init {
        require(revision > 0L) { "A control revision must be positive" }
        require(sourceIntentRevision >= 0L && stemIntentRevision >= 0L)
        require(stems.size == STEM_COUNT) { "Native controls require the declared ABI lane capacity" }
        require(musicGain.isFinite() && musicGain in 0f..2f)
        require(mixHeadroom.isFinite() && mixHeadroom in 0f..1f)
        require(sourceTransitionFrames in 1..MAXIMUM_TRANSITION_FRAMES)
        require(laneRampFrames in 1..MAXIMUM_TRANSITION_FRAMES)
        require(masterRampFrames in 1..MAXIMUM_TRANSITION_FRAMES)
        require(defaultDeclickFrames in 1..MAXIMUM_TRANSITION_FRAMES)
        require(tempo.isFinite() && tempo in app.stemwise.playback.MINIMUM_PLAYBACK_TEMPO..app.stemwise.playback.MAXIMUM_PLAYBACK_TEMPO)
        require(pitchSemitones.isFinite() && pitchSemitones in -12f..12f)
    }

    companion object {
        const val STEM_COUNT = StemRoleContract.maximumStemCount
        private const val MAXIMUM_TRANSITION_FRAMES = 96_000
    }
}

/**
 * Content-addressed identity for the bounded native loop-head cache.
 *
 * Hash values are raw unsigned 64-bit bit patterns carried in Kotlin [Long]s. Zero is reserved by
 * the native ABI, while a negative Kotlin value is otherwise valid and must not be normalised.
 */
data class NativeRuntimeManagedLoopContentKey(
    val songIdentityHash: Long,
    val originalContentHash: Long,
    val stemPackageHash: Long,
    val contentRevision: Long,
    val loopRevision: Long,
    val startTimelineFrame: Long,
    val endTimelineFrameExclusive: Long
) {
    init {
        require(songIdentityHash != 0L)
        require(originalContentHash != 0L)
        require(contentRevision > 0L && loopRevision > 0L)
        require(startTimelineFrame >= 0L && endTimelineFrameExclusive > startTimelineFrame)
    }
}

enum class NativeRuntimeManagedLoopBoundaryMode(val nativeCode: Int) {
    UNRESOLVED(NativePacketSchema.ENUM_NATIVE_RUNTIME_MANAGED_LOOP_BOUNDARY_MODE_UNRESOLVED),
    ORIGINAL_EOS(NativePacketSchema.ENUM_NATIVE_RUNTIME_MANAGED_LOOP_BOUNDARY_MODE_ORIGINAL_EOS),
    RETAINED_WINDOW(NativePacketSchema.ENUM_NATIVE_RUNTIME_MANAGED_LOOP_BOUNDARY_MODE_RETAINED_WINDOW);

    companion object {
        fun fromNative(code: Int) = entries.firstOrNull { it.nativeCode == code }
            ?: error("Unknown native managed-loop boundary mode $code")
    }
}

/** Monotonic loop command plus an independently versioned immutable cache key. */
internal data class NativeRuntimeManagedLoopIntent(
    val revision: Long,
    val ownership: NativeRuntimeOwnership,
    val enabled: Boolean,
    val cacheKey: NativeRuntimeManagedLoopContentKey? = null,
    val requiredLaneMask: Int = 0,
    val declickFrames: Int = 240,
    val boundaryMode: NativeRuntimeManagedLoopBoundaryMode =
        NativeRuntimeManagedLoopBoundaryMode.ORIGINAL_EOS
) {
    init {
        require(revision > 0L)
        if (enabled) {
            requireNotNull(cacheKey)
            require(requiredLaneMask in 1..ALL_LANES_MASK)
            require(requiredLaneMask and ORIGINAL_LANE_MASK != 0)
            require(requiredLaneMask and ALL_LANES_MASK == requiredLaneMask)
            require(requiredLaneMask == ORIGINAL_LANE_MASK || cacheKey.stemPackageHash != 0L)
            require(declickFrames in 0..48_000)
            require(boundaryMode == NativeRuntimeManagedLoopBoundaryMode.ORIGINAL_EOS ||
                boundaryMode == NativeRuntimeManagedLoopBoundaryMode.RETAINED_WINDOW)
            if (boundaryMode == NativeRuntimeManagedLoopBoundaryMode.ORIGINAL_EOS)
                require(cacheKey.endTimelineFrameExclusive - cacheKey.startTimelineFrame ==
                    HEAD_CACHE_CAPACITY_FRAMES) { "Repeat cache keys describe the fixed four-second runway" }
        } else {
            require(cacheKey == null && requiredLaneMask == 0)
        }
    }

    companion object {
        const val ORIGINAL_LANE_MASK = 1
        const val ALL_LANES_MASK = (1 shl NativeSongLaneIngressBank.LANE_COUNT) - 1
        const val HEAD_CACHE_CAPACITY_FRAMES = 192_000L

        fun disabled(revision: Long, ownership: NativeRuntimeOwnership) =
            NativeRuntimeManagedLoopIntent(
                revision = revision,
                ownership = ownership,
                enabled = false,
                declickFrames = 0
            )
    }
}

internal data class NativeRuntimeManagedLoopIngressReady(
    val requestSequence: Long,
    val loopRevision: Long,
    val targetCycle: Long,
    val ownership: NativeRuntimeOwnership,
    val ingressEpoch: Long,
    val readyLaneMask: Int,
    val eosPaddedLaneMask: Int = 0,
    val firstTimelineFrame: Long,
    val endTimelineFrameExclusive: Long
) {
    init {
        require(requestSequence > 0L && loopRevision > 0L && targetCycle > 0L)
        require(ingressEpoch > 0L)
        require(readyLaneMask in 1..NativeRuntimeManagedLoopIntent.ALL_LANES_MASK)
        require(readyLaneMask and NativeRuntimeManagedLoopIntent.ORIGINAL_LANE_MASK != 0)
        require(eosPaddedLaneMask in 0..NativeRuntimeManagedLoopIntent.ALL_LANES_MASK)
        require(eosPaddedLaneMask and readyLaneMask == eosPaddedLaneMask)
        require(eosPaddedLaneMask and NativeRuntimeManagedLoopIntent.ORIGINAL_LANE_MASK == 0)
        require(firstTimelineFrame >= 0L && endTimelineFrameExclusive > firstTimelineFrame)
    }
}

enum class NativeRuntimeLoopCycleMode(val nativeCode: Int) {
    DISABLED(NativePacketSchema.ENUM_NATIVE_RUNTIME_LOOP_CYCLE_MODE_DISABLED), CACHE_ONLY(NativePacketSchema.ENUM_NATIVE_RUNTIME_LOOP_CYCLE_MODE_CACHE_ONLY), STREAMED(NativePacketSchema.ENUM_NATIVE_RUNTIME_LOOP_CYCLE_MODE_STREAMED);

    companion object {
        fun fromNative(code: Int) = entries.firstOrNull { it.nativeCode == code }
            ?: error("Unknown native loop-cycle mode $code")
    }
}

enum class NativeRuntimeLoopCyclePhase(val nativeCode: Int) {
    DISABLED(NativePacketSchema.ENUM_NATIVE_RUNTIME_LOOP_CYCLE_PHASE_DISABLED),
    WAITING_FOR_CACHE(NativePacketSchema.ENUM_NATIVE_RUNTIME_LOOP_CYCLE_PHASE_WAITING_FOR_CACHE),
    WAITING_FOR_SAFE_WINDOW(NativePacketSchema.ENUM_NATIVE_RUNTIME_LOOP_CYCLE_PHASE_WAITING_FOR_SAFE_WINDOW),
    WAITING_FOR_INGRESS(NativePacketSchema.ENUM_NATIVE_RUNTIME_LOOP_CYCLE_PHASE_WAITING_FOR_INGRESS),
    AWAITING_RENDER_COMMIT(NativePacketSchema.ENUM_NATIVE_RUNTIME_LOOP_CYCLE_PHASE_AWAITING_RENDER_COMMIT),
    AWAITING_CALLBACK_PROOF(NativePacketSchema.ENUM_NATIVE_RUNTIME_LOOP_CYCLE_PHASE_AWAITING_CALLBACK_PROOF),
    ACTIVE(NativePacketSchema.ENUM_NATIVE_RUNTIME_LOOP_CYCLE_PHASE_ACTIVE),
    DEADLINE_FALLBACK(NativePacketSchema.ENUM_NATIVE_RUNTIME_LOOP_CYCLE_PHASE_DEADLINE_FALLBACK),
    WAITING_FOR_BOUNDARY(NativePacketSchema.ENUM_NATIVE_RUNTIME_LOOP_CYCLE_PHASE_WAITING_FOR_BOUNDARY);

    companion object {
        fun fromNative(code: Int) = entries.firstOrNull { it.nativeCode == code }
            ?: error("Unknown native loop-cycle phase $code")
    }
}

enum class NativeRuntimeLoopCycleActivation(val nativeCode: Int) {
    NONE(NativePacketSchema.ENUM_NATIVE_RUNTIME_LOOP_CYCLE_ACTIVATION_NONE), WARM_CURRENT_PASS(NativePacketSchema.ENUM_NATIVE_RUNTIME_LOOP_CYCLE_ACTIVATION_WARM_CURRENT_PASS), COLD_AT_LOOP_START(NativePacketSchema.ENUM_NATIVE_RUNTIME_LOOP_CYCLE_ACTIVATION_COLD_AT_LOOP_START);

    companion object {
        fun fromNative(code: Int) = entries.firstOrNull { it.nativeCode == code }
            ?: error("Unknown native loop-cycle activation $code")
    }
}

enum class NativeRuntimeLoopDirectiveAction(val nativeCode: Int) {
    NONE(NativePacketSchema.ENUM_NATIVE_RUNTIME_LOOP_DIRECTIVE_ACTION_NONE),
    CLEAR(NativePacketSchema.ENUM_NATIVE_RUNTIME_LOOP_DIRECTIVE_ACTION_CLEAR),
    INSTALL_WARM_CACHE_ONLY(NativePacketSchema.ENUM_NATIVE_RUNTIME_LOOP_DIRECTIVE_ACTION_INSTALL_WARM_CACHE_ONLY),
    INSTALL_WARM_STREAMED(NativePacketSchema.ENUM_NATIVE_RUNTIME_LOOP_DIRECTIVE_ACTION_INSTALL_WARM_STREAMED),
    ACTIVATE_COLD_CACHE_ONLY(NativePacketSchema.ENUM_NATIVE_RUNTIME_LOOP_DIRECTIVE_ACTION_ACTIVATE_COLD_CACHE_ONLY),
    ACTIVATE_COLD_STREAMED(NativePacketSchema.ENUM_NATIVE_RUNTIME_LOOP_DIRECTIVE_ACTION_ACTIVATE_COLD_STREAMED),
    WRAP_CACHE_ONLY(NativePacketSchema.ENUM_NATIVE_RUNTIME_LOOP_DIRECTIVE_ACTION_WRAP_CACHE_ONLY),
    WRAP_STREAMED(NativePacketSchema.ENUM_NATIVE_RUNTIME_LOOP_DIRECTIVE_ACTION_WRAP_STREAMED),
    DEADLINE_FALLBACK(NativePacketSchema.ENUM_NATIVE_RUNTIME_LOOP_DIRECTIVE_ACTION_DEADLINE_FALLBACK);

    companion object {
        fun fromNative(code: Int) = entries.firstOrNull { it.nativeCode == code }
            ?: error("Unknown native loop directive action $code")
    }
}

enum class NativeRuntimeLoopHeadPreparationPhase(val nativeCode: Int) {
    IDLE(NativePacketSchema.ENUM_NATIVE_RUNTIME_LOOP_HEAD_PREPARATION_PHASE_IDLE), STAGING(NativePacketSchema.ENUM_NATIVE_RUNTIME_LOOP_HEAD_PREPARATION_PHASE_STAGING), LANE_CONFIGURED(NativePacketSchema.ENUM_NATIVE_RUNTIME_LOOP_HEAD_PREPARATION_PHASE_LANE_CONFIGURED), COMMITTED(NativePacketSchema.ENUM_NATIVE_RUNTIME_LOOP_HEAD_PREPARATION_PHASE_COMMITTED), FAILED(NativePacketSchema.ENUM_NATIVE_RUNTIME_LOOP_HEAD_PREPARATION_PHASE_FAILED), ABORTED(NativePacketSchema.ENUM_NATIVE_RUNTIME_LOOP_HEAD_PREPARATION_PHASE_ABORTED);

    companion object {
        fun fromNative(code: Int) = entries.firstOrNull { it.nativeCode == code }
            ?: error("Unknown native loop-head preparation phase $code")
    }
}

internal enum class NativeRuntimeLoopHeadBeginStatus {
    ACCEPTED,
    INVALID_KEY,
    INVALID_LANE_MASK,
    STALE_REVISION,
    READERS_ACTIVE,
    STOPPED,
    ALLOCATION_FAILURE,
    INSUFFICIENT_SPACE;

    companion object {
        fun fromNative(code: Int) = entries.getOrNull(code)
            ?: error("Unknown native loop-head begin status $code")
    }
}

internal enum class NativeRuntimeLoopHeadConfigureStatus {
    ACCEPTED,
    ALREADY_CONFIGURED,
    NO_PREPARATION,
    STALE_REVISION,
    INVALID_LANE,
    LANE_NOT_REQUESTED,
    LANE_ALREADY_COMPLETED,
    LANE_BUSY,
    INVALID_CONFIGURATION,
    ALLOCATION_FAILURE,
    STOPPED;

    companion object {
        fun fromNative(code: Int) = entries.getOrNull(code)
            ?: error("Unknown native loop-head configure status $code")
    }
}

internal enum class NativeRuntimeLoopHeadOfferStatus {
    ACCEPTED,
    PARTIAL,
    BACKPRESSURED,
    LANE_COMPLETED,
    CACHE_COMMITTED,
    NO_PREPARATION,
    STALE_REVISION,
    WRONG_LANE,
    STALE_GENERATION,
    AFTER_END_OF_STREAM,
    TIMELINE_DISCONTINUITY,
    CACHE_REJECTED,
    RESAMPLER_FAILURE,
    INVALID_ARGUMENT,
    STOPPED;

    companion object {
        fun fromNative(code: Int) = entries.getOrNull(code)
            ?: error("Unknown native loop-head offer status $code")
    }
}

internal data class NativeRuntimeLoopHeadOfferResult(
    val status: NativeRuntimeLoopHeadOfferStatus,
    val inputFramesAccepted: Int
) {
    init { require(inputFramesAccepted >= 0) }
}

internal enum class NativeRuntimeLoopHeadEndStatus {
    LANE_COMPLETED,
    CACHE_COMMITTED,
    ALREADY_COMPLETED,
    NO_PREPARATION,
    STALE_REVISION,
    WRONG_LANE,
    STALE_GENERATION,
    BOUNDARY_MISMATCH,
    TIMELINE_DISCONTINUITY,
    CACHE_REJECTED,
    RESAMPLER_FAILURE,
    INVALID_ARGUMENT,
    STOPPED,
    PADDING_PENDING;

    companion object {
        fun fromNative(code: Int) = entries.getOrNull(code)
            ?: error("Unknown native loop-head end status $code")
    }
}

internal enum class NativeRuntimeLoopHeadAbortStatus {
    ACCEPTED, NO_PREPARATION, STALE_REVISION, STOPPED;

    companion object {
        fun fromNative(code: Int) = entries.getOrNull(code)
            ?: error("Unknown native loop-head abort status $code")
    }
}

/** One atomic latest-wins command publication consumed at a native render boundary. */
internal data class NativeRuntimeCommandSnapshot(
    val revision: Long,
    val outputGeneration: Long,
    val banks: List<NativeRuntimeBankIntent>,
    val selectedBank: NativeRuntimeBank?,
    val playing: Boolean,
    val controls: NativeRuntimeControls,
    /**
     * Exact queue-policy authorization for one native drain handoff.
     *
     * This is deliberately distinct from a likely-next decoder hint. The outgoing owner remains
     * selected and publicly authoritative while the spare bank is rendered into the inactive
     * output ring. Native may expose the successor only after the callback consumes every outgoing
     * frame and presents successor PCM in that same callback boundary.
     */
    val automaticSuccessor: NativeRuntimeAutomaticSuccessorIntent? = null
) {
    val selectedOwnership: NativeRuntimeOwnership?
        get() = selectedBank?.let { banks[it.nativeCode].ownership }

    init {
        require(revision > 0L) { "A command revision must be positive" }
        require(outputGeneration > 0L) { "An output generation must be positive" }
        require(banks.size == BANK_COUNT) { "Exactly two resident bank intents are required" }
        val selection = selectedOwnership
        if (selectedBank == null) {
            require(!playing) { "Playback cannot continue without an assigned selection" }
            require(
                controls.sourceIntentRevision == 0L && controls.stemIntentRevision == 0L
            ) { "An unselected command must carry exact 0/0 source and stem fences" }
        } else {
            requireNotNull(selection) { "The selected bank must be assigned" }
            require(
                controls.sourceIntentRevision > 0L && controls.stemIntentRevision > 0L
            ) { "A selected command requires positive source and stem fences" }
            require(outputGeneration == selection.generation) {
                "Output and selected-owner generations must be identical"
            }
        }
        if (automaticSuccessor != null) {
            require(selection != null && automaticSuccessor.outgoingOwnership == selection) {
                "An automatic successor must be fenced to the selected outgoing owner"
            }
            require(playing) { "A paused transport cannot arm an automatic drain handoff" }
            require(automaticSuccessor.successorBank != selectedBank) {
                "The automatic successor must occupy the spare bank"
            }
            require(
                banks[automaticSuccessor.successorBank.nativeCode].ownership ==
                    automaticSuccessor.successorOwnership
            ) { "The automatic successor must match its exact assigned bank ownership" }
        }
    }

    companion object {
        const val BANK_COUNT = 2
    }
}

internal data class NativeRuntimeAutomaticSuccessorIntent(
    val revision: Long,
    val outgoingOwnership: NativeRuntimeOwnership,
    val successorBank: NativeRuntimeBank,
    val successorOwnership: NativeRuntimeOwnership,
    val inheritOutgoingControls: Boolean = false
) {
    init {
        require(revision > 0L) { "An automatic successor revision must be positive" }
        require(successorOwnership != outgoingOwnership)
        require(successorOwnership.generation > outgoingOwnership.generation) {
            "An automatic successor generation must be newer than the outgoing generation"
        }
    }
}

internal data class NativeRuntimeBeatGridUpdate(
    val revision: Long,
    val bank: NativeRuntimeBank,
    val ownership: NativeRuntimeOwnership,
    val beatFrames: LongArray,
    /** One explicit song-authored accent flag per base beat. */
    val beatAccents: BooleanArray
) {
    init {
        require(revision > 0L)
        require(beatFrames.isEmpty() || beatFrames.size >= 2)
        require(beatFrames.size <= MAX_NATIVE_BEAT_GRID_POINTS)
        require(beatAccents.size == beatFrames.size)
        beatFrames.forEachIndexed { index, frame ->
            require(frame >= 0L)
            if (index != 0) require(frame > beatFrames[index - 1])
        }
    }

    override fun equals(other: Any?): Boolean =
        other is NativeRuntimeBeatGridUpdate &&
            revision == other.revision && bank == other.bank && ownership == other.ownership &&
            beatFrames.contentEquals(other.beatFrames) && beatAccents.contentEquals(other.beatAccents)

    override fun hashCode(): Int {
        var result = revision.hashCode()
        result = 31 * result + bank.hashCode()
        result = 31 * result + ownership.hashCode()
        result = 31 * result + beatFrames.contentHashCode()
        return 31 * result + beatAccents.contentHashCode()
    }
}

internal enum class NativeRuntimePublishStatus {
    ACCEPTED,
    STALE_REVISION,
    INVALID_SNAPSHOT,
    STOPPED;

    companion object {
        fun fromNative(code: Int): NativeRuntimePublishStatus = entries.getOrNull(code)
            ?: error("Unknown native publish status $code")
    }
}

enum class NativeRuntimeStatus(val nativeCode: Int) {
    CONSTRUCTED(NativePacketSchema.ENUM_NATIVE_RUNTIME_STATUS_CONSTRUCTED),
    RUNNING(NativePacketSchema.ENUM_NATIVE_RUNTIME_STATUS_RUNNING),
    STOP_REQUESTED(NativePacketSchema.ENUM_NATIVE_RUNTIME_STATUS_STOP_REQUESTED),
    STOPPED(NativePacketSchema.ENUM_NATIVE_RUNTIME_STATUS_STOPPED);

    companion object {
        fun fromNative(code: Int): NativeRuntimeStatus = entries.firstOrNull { it.nativeCode == code }
            ?: error("Unknown native runtime status $code")
    }
}

data class NativeRuntimeConfiguration(
    val targetBursts: Int = 6,
    /** Time-based output reserve floor; the device burst-count reserve may still be larger. */
    val minimumOutputReserveMs: Int = 50,
    val fallbackFramesPerBurst: Int = 192,
    val ingressPullFrames: Int = 1_024,
    val maximumPullsPerLanePerCycle: Int = 4,
    val maximumOutputBlocksPerCycle: Int = 32,
    val idleSleepMicros: Int = 10_000,
    val currentSongCacheDirectory: String =
        System.getProperty("java.io.tmpdir").orEmpty()
) {
    init {
        require(targetBursts in 4..8)
        require(minimumOutputReserveMs in 4..64)
        require(fallbackFramesPerBurst > 0)
        require(ingressPullFrames in 1..4_096)
        require(maximumPullsPerLanePerCycle in 1..8)
        require(maximumOutputBlocksPerCycle > 0)
        require(idleSleepMicros > 0)
        require('\u0000' !in currentSongCacheDirectory)
    }
}

internal interface NativeRuntimeBankProgress {
    val appliedIntentRevision: Long
    val assigned: Boolean
}

internal data class NativeRuntimeBankControlProgress(
    override val appliedIntentRevision: Long,
    override val assigned: Boolean
) : NativeRuntimeBankProgress

data class NativeRuntimeBankDiagnostics(
    override val appliedIntentRevision: Long,
    override val assigned: Boolean,
    val beginSongCount: Long,
    val seekCount: Long,
    val rejectedIntentCount: Long,
    val beatGridUpdateCount: Long,
    val beatGridClearCount: Long
) : NativeRuntimeBankProgress {
    init {
        require(appliedIntentRevision >= 0L)
    }
}

data class NativeRuntimeRenderLaneReadinessDiagnostics(
    val laneIndex: Int,
    val generation: Long,
    val firstTimelineFrame: Long,
    val endTimelineFrameExclusive: Long,
    val availableFromRenderStart: Long,
    val reservoirGapRejectionCount: Long,
    val ingressFailureGeneration: Long,
    val ingressActiveGeneration: Long,
    val ingressProducerGeneration: Long,
    val ingressConsumerGeneration: Long,
    val ingressReadableSourceFrames: Long,
    val ingressPulls: Long,
    val ingressStaleAfterPull: Long,
    val ingressFutureGenerationDeferrals: Long
)

/** On-demand immutable native-worker view of the selected render cursor and lane reservoirs. */
enum class NativeCacheStorageStatus(val nativeCode: Int) {
    NOT_REQUESTED(NativePacketSchema.ENUM_NATIVE_CACHE_STORAGE_STATUS_NOT_REQUESTED), ADMITTED(NativePacketSchema.ENUM_NATIVE_CACHE_STORAGE_STATUS_ADMITTED), LIMIT_EXCEEDED(NativePacketSchema.ENUM_NATIVE_CACHE_STORAGE_STATUS_LIMIT_EXCEEDED), INSUFFICIENT_SPACE(NativePacketSchema.ENUM_NATIVE_CACHE_STORAGE_STATUS_INSUFFICIENT_SPACE), IO_FAILURE(NativePacketSchema.ENUM_NATIVE_CACHE_STORAGE_STATUS_IO_FAILURE);
    val failed: Boolean get() = this != NOT_REQUESTED && this != ADMITTED
}

enum class NativeRuntimeLoopPreparationFailureReason(val nativeCode: Int) { NONE(0), PIPELINE(1), LANES(2) }

data class NativeRuntimeLoopPreparationFailure(
    val ownership: NativeRuntimeOwnership,
    val revision: Long,
    val reason: NativeRuntimeLoopPreparationFailureReason
) {
    init { require(revision > 0L && reason != NativeRuntimeLoopPreparationFailureReason.NONE) }
}

data class NativeRuntimeRenderReadinessDiagnostics(
    val sequence: Long,
    val assigned: Boolean,
    val bank: NativeRuntimeBank,
    val ownership: NativeRuntimeOwnership?,
    val controlRevision: Long,
    val desiredSourceMode: NativeRuntimeSourceMode,
    val renderStartTimelineFrame: Long,
    val pendingControlRevision: Long,
    val pendingActualSourceMode: NativeRuntimeSourceMode,
    val pendingSourceStartTimelineFrame: Long,
    val pendingSourceEndTimelineFrameExclusive: Long,
    val pendingFrames: Long,
    val outputQueuedFrames: Long,
    val stemActivationDeferrals: Long,
    val stemSafetyFallbacks: Long,
    val needStemDataCount: Long,
    val lanes: List<NativeRuntimeRenderLaneReadinessDiagnostics>,
    val currentSongCacheBoundToSelectedOwner: Boolean = false,
    val currentSongCacheCompleteLaneMask: Int = 0,
    val currentSongCacheCanGrow: Boolean = false,
    val ingressBankConfiguredLaneCount: Long = 0L,
    val directAtomicPolicyGeneration: Long = 0L,
    val directAtomicPolicyPublishedRevision: Long = 0L,
    val directAtomicPolicyAdoptedRevision: Long = 0L,
    val directAtomicPolicyObservedRevision: Long = 0L,
    val fixedLoopHeadPrepared: Boolean = false,
    val ingressServicePolicyBreaks: Long = 0L,
    val ingressBankIdentity: Long = 0L,
    val requiredSongLaneMask: Int = 0,
    val cacheStorageStatus: NativeCacheStorageStatus = NativeCacheStorageStatus.NOT_REQUESTED,
    val loopPreparationFailure: NativeRuntimeLoopPreparationFailure? = null
) {
    val currentSongCacheReadyForSelectedOwner: Boolean
        get() = currentSongCacheBoundToSelectedOwner &&
            requiredSongLaneMask != 0 &&
            currentSongCacheCompleteLaneMask and requiredSongLaneMask == requiredSongLaneMask
}

data class NativeRuntimePresentationEvidence(
    val sequence: Long,
    val ownership: NativeRuntimeOwnership,
    val fingerprintSequence: Long,
    val firstTimelineFrame: Long,
    val firstOutputFrame: Long,
    val callbackSequence: Long,
    val pcmFrames: Long
) {
    init {
        require(sequence > 0L && fingerprintSequence > 0L && callbackSequence > 0L)
        require(firstTimelineFrame >= 0L && firstOutputFrame >= 0L && pcmFrames > 0L)
    }
}

data class NativeRuntimePresentedPosition(
    val sequence: Long,
    val ownership: NativeRuntimeOwnership,
    val fingerprintSequence: Long,
    val lastTimelineFrame: Long,
    val lastOutputFrame: Long,
    val callbackSequence: Long,
    val pcmFrames: Long,
    /** Exact loop policy stamped on the newest PCM physically emitted by the callback. */
    val loopPolicyRevision: Long = 0L,
    /** Exact canonical A/B revision first observed by the real output callback. */
    val loopPolicyAdoptedRevision: Long = 0L,
    /** Native policy-publication to callback-start latency; zero before any adoption. */
    val loopPolicyAdoptionLatencyMicros: Long = 0L,
    /** Android input uptime to native atomic publication; excludes callback and device/DAC time. */
    val loopPolicyInputToAtomicPublicationLatencyMicros: Long = 0L,
    /** Android input uptime to callback adoption; excludes downstream device/DAC time. */
    val loopPolicyInputToCallbackAdoptionLatencyMicros: Long = 0L
) {
    init {
        require(sequence > 0L && fingerprintSequence > 0L && callbackSequence > 0L)
        require(lastTimelineFrame >= 0L && lastOutputFrame >= 0L && pcmFrames > 0L)
        require(
            loopPolicyRevision >= 0L && loopPolicyAdoptedRevision >= 0L &&
                loopPolicyAdoptionLatencyMicros >= 0L &&
                loopPolicyInputToAtomicPublicationLatencyMicros >= 0L &&
                loopPolicyInputToCallbackAdoptionLatencyMicros >= 0L
        )
    }
}

data class NativeRuntimeAudibleEndEvent(
    val sequence: Long,
    val ownership: NativeRuntimeOwnership,
    val directLoopPolicyRevision: Long = 0L
) {
    init {
        require(sequence > 0L)
        require(directLoopPolicyRevision >= 0L)
    }
}

/** One whole output callback containing only a single settled native control/source tuple. */
data class NativeRuntimeAudibleControlEvidence(
    val sequence: Long,
    val ownership: NativeRuntimeOwnership,
    val controlRevision: Long,
    val actualSourceMode: NativeRuntimeSourceMode,
    val firstFingerprintSequence: Long,
    val lastFingerprintSequence: Long,
    val callbackSequence: Long,
    val pcmFrames: Long,
    /** Zero tempo with zero pitch is an unknown numerical transition/straddle. */
    val actualTempo: Float = 0f,
    val actualPitchSemitones: Float = 0f
) {
    init {
        require(sequence > 0L && controlRevision > 0L)
        require(firstFingerprintSequence > 0L && lastFingerprintSequence > 0L)
        require(callbackSequence > 0L && pcmFrames > 0L)
        require(actualTempo.isFinite() && actualPitchSemitones.isFinite())
        require((actualTempo == 0f && actualPitchSemitones == 0f) ||
            (actualTempo in app.stemwise.playback.MINIMUM_PLAYBACK_TEMPO..app.stemwise.playback.MAXIMUM_PLAYBACK_TEMPO && actualPitchSemitones in -12f..12f))
    }
}

/** Exact committed-owner gain overlay proven by one wholly coherent output callback. */
data class StemGainCallbackSettlementDiagnostics(
    val settlementSequence: Long,
    val ownerGeneration: Long,
    val ownerToken: Long,
    val offeredSequence: Long,
    val publishedSequence: Long,
    val activeMask: Int,
    val gainBits: IntArray,
    val callbackSequence: Long,
    val firstTaggedFrame: Long,
    val lastTaggedFrame: Long,
    val taggedFrameCount: Long,
    val nonZeroPcmFrameCount: Long,
    val callbackObservedAtNs: Long,
    val coherent: Boolean
) {
    init {
        require(gainBits.size == STEM_GAIN_CALLBACK_LANE_COUNT)
        require(activeMask and STEM_GAIN_CALLBACK_ALL_LANES_MASK.inv() == 0)
        require(taggedFrameCount >= 0L && nonZeroPcmFrameCount in 0L..taggedFrameCount)
        if (coherent) {
            require(settlementSequence > 0L && ownerGeneration > 0L && ownerToken > 0L)
            require(offeredSequence > 0L && publishedSequence > 0L && callbackSequence > 0L)
            require(firstTaggedFrame >= 0L && lastTaggedFrame >= firstTaggedFrame)
            require(taggedFrameCount > 0L)
            require(callbackObservedAtNs > 0L)
        }
    }
}

private const val STEM_GAIN_CALLBACK_LANE_COUNT = StemRoleContract.maximumStemCount
private const val STEM_GAIN_CALLBACK_ALL_LANES_MASK =
    (1 shl STEM_GAIN_CALLBACK_LANE_COUNT) - 1

private object StemGainCallbackSettlementDiagnosticsCodec {
    private const val SCHEMA_VERSION = NativePacketSchema.STEM_GAIN_CALLBACK_VERSION
    private const val FIELD_COUNT = NativePacketSchema.STEM_GAIN_CALLBACK_COUNT

    fun decode(fields: LongArray): StemGainCallbackSettlementDiagnostics? {
        if (fields.isEmpty()) return null
        require(fields.size == FIELD_COUNT && fields[NativePacketSchema.STEM_GAIN_CALLBACK_SCHEMA] == SCHEMA_VERSION) {
            "Unsupported native stem-gain callback diagnostics schema"
        }
        val coherent = fields[NativePacketSchema.STEM_GAIN_CALLBACK_VALID].nativePacketBoolean()
        return StemGainCallbackSettlementDiagnostics(
            settlementSequence = fields[NativePacketSchema.STEM_GAIN_CALLBACK_SEQUENCE],
            ownerGeneration = fields[NativePacketSchema.STEM_GAIN_CALLBACK_GENERATION],
            ownerToken = fields[NativePacketSchema.STEM_GAIN_CALLBACK_SELECTION_TOKEN],
            offeredSequence = fields[NativePacketSchema.STEM_GAIN_CALLBACK_OFFERED_SEQUENCE],
            publishedSequence = fields[NativePacketSchema.STEM_GAIN_CALLBACK_PUBLISHED_SEQUENCE],
            activeMask = fields[NativePacketSchema.STEM_GAIN_CALLBACK_ACTIVE_MASK].nativePacketInt(),
            gainBits = IntArray(STEM_GAIN_CALLBACK_LANE_COUNT) { lane ->
                fields[NativePacketSchema.STEM_GAIN_CALLBACK_GAIN_BITS_BASE + lane]
                    .nativePacketFloat().also { require(it in 0f..2f) }.toRawBits()
            },
            callbackSequence = fields[NativePacketSchema.STEM_GAIN_CALLBACK_CALLBACK_SEQUENCE],
            firstTaggedFrame = fields[NativePacketSchema.STEM_GAIN_CALLBACK_FIRST_TAGGED_FRAME],
            lastTaggedFrame = fields[NativePacketSchema.STEM_GAIN_CALLBACK_LAST_TAGGED_FRAME],
            taggedFrameCount = fields[NativePacketSchema.STEM_GAIN_CALLBACK_TAGGED_FRAME_COUNT],
            nonZeroPcmFrameCount = fields[NativePacketSchema.STEM_GAIN_CALLBACK_NON_ZERO_PCM_FRAME_COUNT],
            callbackObservedAtNs = fields[NativePacketSchema.STEM_GAIN_CALLBACK_CALLBACK_OBSERVED_AT_NS],
            coherent = coherent
        )
    }
}

data class NativeRuntimeCounters(
    val commandSnapshotsPublished: Long,
    val commandSnapshotsConsumed: Long,
    val commandSnapshotsSuperseded: Long,
    val rejectedCommandSnapshots: Long,
    val beatGridUpdatesPublished: Long,
    val rejectedBeatGridUpdates: Long,
    val renderCycles: Long,
    val idleSleeps: Long,
    val outputFlushes: Long,
    val outputFlushFailures: Long,
    val outputHandoffBegins: Long,
    val outputHandoffBeginRetries: Long,
    val outputHandoffCommits: Long,
    val outputHandoffCommitRetries: Long,
    val outputBlocksRendered: Long,
    val outputFramesRendered: Long,
    val outputFramesAccepted: Long,
    val outputPartialAccepts: Long,
    val outputBackpressureEvents: Long,
    val outputInvalidRenderResults: Long,
    val outputNeedDataEvents: Long,
    val outputEndOfStreamEvents: Long,
    val targetQueuedFrames: Long,
    val lastOutputFingerprintSequence: Long,
    /** Raw FNV-1a bit pattern; unlike counters it is intentionally allowed to be negative. */
    val lastRenderPayloadHash: Long,
    /** Expected empty-flush retries while the callback acknowledges an older role publication. */
    val outputFlushRetries: Long,
    val resourceOperationsCompleted: Long = 0L,
    val resourceLastOperationMicros: Long = 0L,
    val resourceMaximumOperationMicros: Long = 0L,
    val resourceMaximumQueueAgeMicros: Long = 0L,
    val refillDeadlineMisses: Long = 0L
)

data class NativeRuntimeOwnershipProgress(
    val requested: NativeRuntimeOwnership?,
    val accepted: NativeRuntimeOwnership?,
    val committed: NativeRuntimeOwnership?,
    val acceptedFirstFingerprintSequence: Long
)

enum class NativeRuntimeAutomaticSuccessorPhase(val nativeCode: Int) {
    IDLE(NativePacketSchema.ENUM_NATIVE_RUNTIME_AUTOMATIC_SUCCESSOR_PHASE_IDLE),
    STAGING(NativePacketSchema.ENUM_NATIVE_RUNTIME_AUTOMATIC_SUCCESSOR_PHASE_STAGING),
    DRAIN_COMMITTED(NativePacketSchema.ENUM_NATIVE_RUNTIME_AUTOMATIC_SUCCESSOR_PHASE_DRAIN_COMMITTED),
    PRESENTED_AWAITING_SELECTION(NativePacketSchema.ENUM_NATIVE_RUNTIME_AUTOMATIC_SUCCESSOR_PHASE_PRESENTED_AWAITING_SELECTION),
    APPLIED_PAUSED_AWAITING_SELECTION(NativePacketSchema.ENUM_NATIVE_RUNTIME_AUTOMATIC_SUCCESSOR_PHASE_APPLIED_PAUSED_AWAITING_SELECTION);

    companion object {
        fun fromNative(code: Int): NativeRuntimeAutomaticSuccessorPhase = entries.firstOrNull {
            it.nativeCode == code
        } ?: error("Unknown native automatic-successor phase $code")
    }
}

enum class NativeRuntimeDrainCancelStatus(val nativeCode: Int) {
    NOT_REQUESTED(NativePacketSchema.ENUM_NATIVE_RUNTIME_DRAIN_CANCEL_STATUS_NOT_REQUESTED),
    CANCELLED_OPEN_STAGE(NativePacketSchema.ENUM_NATIVE_RUNTIME_DRAIN_CANCEL_STATUS_CANCELLED_OPEN_STAGE),
    CANCELLATION_REQUESTED(NativePacketSchema.ENUM_NATIVE_RUNTIME_DRAIN_CANCEL_STATUS_CANCELLATION_REQUESTED),
    OUTGOING_ACTIVE(NativePacketSchema.ENUM_NATIVE_RUNTIME_DRAIN_CANCEL_STATUS_OUTGOING_ACTIVE),
    SUCCESSOR_ACTIVE(NativePacketSchema.ENUM_NATIVE_RUNTIME_DRAIN_CANCEL_STATUS_SUCCESSOR_ACTIVE),
    ADOPTION_IN_FLIGHT(NativePacketSchema.ENUM_NATIVE_RUNTIME_DRAIN_CANCEL_STATUS_ADOPTION_IN_FLIGHT),
    NO_MATCHING_DRAIN(NativePacketSchema.ENUM_NATIVE_RUNTIME_DRAIN_CANCEL_STATUS_NO_MATCHING_DRAIN);

    companion object {
        fun fromNative(code: Int): NativeRuntimeDrainCancelStatus = entries.firstOrNull {
            it.nativeCode == code
        } ?: error("Unknown native drain-cancellation status $code")
    }
}

data class NativeRuntimeAutomaticSuccessorDiagnostics(
    val phase: NativeRuntimeAutomaticSuccessorPhase,
    val cancellationStatus: NativeRuntimeDrainCancelStatus,
    val revision: Long,
    val transitionRevision: Long,
    val outgoingOwnership: NativeRuntimeOwnership?,
    val successorOwnership: NativeRuntimeOwnership?,
    val successorQueuedFrames: Long,
    val firstFingerprintSequence: Long,
    val cancellationDesired: Boolean,
    val successorEndObserved: Boolean
) {
    init {
        require(revision >= 0L && transitionRevision >= 0L)
        require(successorQueuedFrames >= 0L && firstFingerprintSequence >= 0L)
        if (phase != NativeRuntimeAutomaticSuccessorPhase.IDLE) {
            require(revision > 0L && transitionRevision > 0L)
            require(outgoingOwnership != null && successorOwnership != null)
        }
    }
}

data class NativeRuntimeCurrentOutputQueueLowWater(
    val sequence: Long,
    val generation: Long,
    val frames: Long,
    val monotonicMicros: Long
) {
    init {
        require(sequence > 0L && generation > 0L && frames >= 0L && monotonicMicros > 0L)
    }
}

data class NativeRuntimeOutputWaitReasonDiagnostics(
    val lowWaterEvidenceSequence: Long = 0L,
    val lastMonotonicMicros: Long = 0L,
    val lastGeneration: Long = 0L
) {
    init {
        require(
            lowWaterEvidenceSequence >= 0L &&
                lastMonotonicMicros >= 0L &&
                lastGeneration >= 0L
        )
        if (lowWaterEvidenceSequence == 0L) {
            require(lastMonotonicMicros == 0L && lastGeneration == 0L)
        } else {
            require(lastMonotonicMicros > 0L && lastGeneration > 0L)
        }
    }
}

internal interface NativeRuntimeControlObservation {
    val status: NativeRuntimeStatus
    val ownership: NativeRuntimeOwnershipProgress
    val presentation: NativeRuntimePresentationEvidence?
    val presentedPosition: NativeRuntimePresentedPosition?
    val audibleEnd: NativeRuntimeAudibleEndEvent?
    val banks: List<NativeRuntimeBankProgress>
    val audibleControl: NativeRuntimeAudibleControlEvidence?
    val automaticSuccessor: NativeRuntimeAutomaticSuccessorDiagnostics
    val readinessProgress: NativeRuntimeReadinessProgress? get() = null
}

internal data class NativeRuntimeReadinessProgress(
    val sequence: Long,
    val assigned: Boolean,
    val ownership: NativeRuntimeOwnership?,
    val controlRevision: Long,
    val cacheBound: Boolean,
    val cacheCanGrow: Boolean,
    val completeLaneMask: Int,
    val requiredLaneMask: Int,
    val storageStatus: NativeCacheStorageStatus,
    val fixedLoopHeadPrepared: Boolean,
    val directAtomicPolicyGeneration: Long,
    val failureLane: Int,
    val failureGeneration: Long,
    val loopPreparationFailure: NativeRuntimeLoopPreparationFailure? = null
) {
    val cacheReady: Boolean get() = cacheBound && requiredLaneMask != 0 &&
        completeLaneMask and requiredLaneMask == requiredLaneMask
}

internal data class NativeRuntimeControlProgress(
    override val status: NativeRuntimeStatus,
    override val ownership: NativeRuntimeOwnershipProgress,
    override val presentation: NativeRuntimePresentationEvidence?,
    override val presentedPosition: NativeRuntimePresentedPosition?,
    override val audibleEnd: NativeRuntimeAudibleEndEvent?,
    override val banks: List<NativeRuntimeBankProgress>,
    override val audibleControl: NativeRuntimeAudibleControlEvidence?,
    override val automaticSuccessor: NativeRuntimeAutomaticSuccessorDiagnostics,
    override val readinessProgress: NativeRuntimeReadinessProgress? = null
) : NativeRuntimeControlObservation

data class NativeRuntimeDiagnostics(
    override val status: NativeRuntimeStatus,
    val counters: NativeRuntimeCounters,
    override val ownership: NativeRuntimeOwnershipProgress,
    override val presentation: NativeRuntimePresentationEvidence?,
    override val presentedPosition: NativeRuntimePresentedPosition?,
    override val audibleEnd: NativeRuntimeAudibleEndEvent?,
    override val banks: List<NativeRuntimeBankDiagnostics>,
    override val audibleControl: NativeRuntimeAudibleControlEvidence? = null,
    /** Cumulative/current process-global native-runtime ownership evidence. */
    val processConstructionCount: Long = 0L,
    val processLiveInstanceCount: Long = 0L,
    /** Process-cumulative hard faults; runtime replacement cannot reset these totals. */
    val processOutputInvalidRenderResults: Long = 0L,
    val processOutputFlushFailures: Long = 0L,
    override val automaticSuccessor: NativeRuntimeAutomaticSuccessorDiagnostics =
        NativeRuntimeAutomaticSuccessorDiagnostics(
            phase = NativeRuntimeAutomaticSuccessorPhase.IDLE,
            cancellationStatus = NativeRuntimeDrainCancelStatus.NOT_REQUESTED,
            revision = 0L,
            transitionRevision = 0L,
            outgoingOwnership = null,
            successorOwnership = null,
            successorQueuedFrames = 0L,
            firstFingerprintSequence = 0L,
            cancellationDesired = false,
            successorEndObserved = false
        ),
    val renderWorkerStarted: Boolean = false,
    val renderWorkerAudioPriorityRequested: Boolean = false,
    val renderWorkerAudioPriorityApplied: Boolean = false,
    val renderWorkerNiceValue: Int = 0,
    val renderWorkerPriorityErrorCode: Int = 0,
    val currentOutputQueueLowWater: NativeRuntimeCurrentOutputQueueLowWater? = null,
    val needOriginalData: NativeRuntimeOutputWaitReasonDiagnostics =
        NativeRuntimeOutputWaitReasonDiagnostics(),
    val needStemData: NativeRuntimeOutputWaitReasonDiagnostics =
        NativeRuntimeOutputWaitReasonDiagnostics(),
    val loopBoundaryPending: NativeRuntimeOutputWaitReasonDiagnostics =
        NativeRuntimeOutputWaitReasonDiagnostics(),
    val canonicalPcmIo: NativeRuntimeCanonicalPcmIoDiagnostics? = null,
    val endOrdering: NativeRuntimeEndOrderingDiagnostics = NativeRuntimeEndOrderingDiagnostics(emptyList())
) : NativeRuntimeControlObservation

data class NativeRuntimeLoopCycleState(
    val mode: NativeRuntimeLoopCycleMode,
    val phase: NativeRuntimeLoopCyclePhase,
    val activation: NativeRuntimeLoopCycleActivation,
    val latestRevision: Long,
    val ownership: NativeRuntimeOwnership?,
    val renderCycle: Long,
    val activeIngressEpoch: Long,
    val preparedIngressEpoch: Long,
    /** Raw unsigned checksum bit pattern. */
    val cacheChecksum: Long,
    val cacheAccepted: Boolean,
    val renderInstalled: Boolean,
    val renderActive: Boolean,
    val audiblyProven: Boolean,
    val ingressRequestPending: Boolean,
    val renderDirectivePending: Boolean,
    val boundaryMode: NativeRuntimeManagedLoopBoundaryMode =
        NativeRuntimeManagedLoopBoundaryMode.UNRESOLVED,
    val boundaryResolved: Boolean = false,
    val resolvedLoopEndTimelineFrameExclusive: Long = 0L
)

data class NativeRuntimeLoopIngressRequest(
    val sequence: Long,
    val loopRevision: Long,
    val targetCycle: Long,
    val ownership: NativeRuntimeOwnership,
    val ingressEpoch: Long,
    val targetTimelineFrame: Long,
    val requiredLaneMask: Int,
    val activation: NativeRuntimeLoopCycleActivation,
    val requiredEndTimelineFrameExclusive: Long = 0L
)

data class NativeRuntimeLoopRenderDirective(
    val sequence: Long,
    val action: NativeRuntimeLoopDirectiveAction,
    val loopRevision: Long,
    val cycle: Long,
    val ownership: NativeRuntimeOwnership,
    val ingressEpoch: Long,
    val startTimelineFrame: Long,
    val endTimelineFrameExclusive: Long,
    val headEndTimelineFrameExclusive: Long,
    /** Raw unsigned checksum bit pattern. */
    val cacheChecksum: Long,
    val requiredLaneMask: Int,
    val declickFrames: Int,
    val boundaryMode: NativeRuntimeManagedLoopBoundaryMode =
        NativeRuntimeManagedLoopBoundaryMode.UNRESOLVED
)

data class NativeRuntimeLoopAudibleEvidence(
    val sequence: Long,
    val loopRevision: Long,
    val cycle: Long,
    val ownership: NativeRuntimeOwnership,
    val callbackSequence: Long
)

data class NativeRuntimeLoopHeadCacheSnapshot(
    val slotIndex: Int,
    val key: NativeRuntimeManagedLoopContentKey,
    val requiredLaneMask: Int,
    val completedLaneMask: Int,
    val frameCount: Int,
    val cacheOnly: Boolean,
    /** Raw unsigned checksum bit patterns. */
    val combinedChecksum: Long,
    val laneChecksums: LongArray,
    val boundaryMode: NativeRuntimeManagedLoopBoundaryMode =
        NativeRuntimeManagedLoopBoundaryMode.UNRESOLVED,
    val originalEndResolved: Boolean = false,
    val resolvedOriginalEndTimelineFrameExclusive: Long = 0L
) {
    override fun equals(other: Any?): Boolean =
        other is NativeRuntimeLoopHeadCacheSnapshot &&
            slotIndex == other.slotIndex && key == other.key &&
            requiredLaneMask == other.requiredLaneMask &&
            completedLaneMask == other.completedLaneMask && frameCount == other.frameCount &&
            cacheOnly == other.cacheOnly && combinedChecksum == other.combinedChecksum &&
            laneChecksums.contentEquals(other.laneChecksums) &&
            boundaryMode == other.boundaryMode && originalEndResolved == other.originalEndResolved &&
            resolvedOriginalEndTimelineFrameExclusive ==
                other.resolvedOriginalEndTimelineFrameExclusive

    override fun hashCode(): Int {
        var result = slotIndex
        result = 31 * result + key.hashCode()
        result = 31 * result + requiredLaneMask
        result = 31 * result + completedLaneMask
        result = 31 * result + frameCount
        result = 31 * result + cacheOnly.hashCode()
        result = 31 * result + combinedChecksum.hashCode()
        result = 31 * result + laneChecksums.contentHashCode()
        result = 31 * result + boundaryMode.hashCode()
        result = 31 * result + originalEndResolved.hashCode()
        return 31 * result + resolvedOriginalEndTimelineFrameExclusive.hashCode()
    }
}

data class NativeRuntimeLoopHeadPreparationSnapshot(
    val phase: NativeRuntimeLoopHeadPreparationPhase,
    val loopRevision: Long,
    val requiredLaneMask: Int,
    val completedLaneMask: Int,
    /** Seven means no lane is active. */
    val activeLane: Int,
    val activeSourceRateHz: Int,
    val activePreparationGeneration: Long,
    val nextExpectedTimelineFrame: Long,
    val beginCalls: Long,
    val configuredLanes: Long,
    val offerCalls: Long,
    val inputFramesAccepted: Long,
    val cacheFramesWritten: Long,
    val lanesCompleted: Long,
    val commits: Long,
    val failures: Long,
    val aborts: Long
)

data class NativeRuntimeLoopProtocolCounters(
    val acceptedIntents: Long,
    val unchangedIntents: Long,
    val ownershipRebinds: Long,
    val staleIntents: Long,
    val revisionCollisions: Long,
    val cachesAccepted: Long,
    val cacheRejections: Long,
    val ingressRequests: Long,
    val ingressAcks: Long,
    val ingressAckRejections: Long,
    val positionObservations: Long,
    val rejectedPositionObservations: Long,
    val boundaryEvents: Long,
    val rejectedBoundaryEvents: Long,
    val renderDirectives: Long,
    val renderCommits: Long,
    val cacheOnlyWraps: Long,
    val streamedWraps: Long,
    val deadlineFallbacks: Long,
    val callbackProofs: Long,
    val rejectedCallbackProofs: Long,
    val originalEosAccepted: Long = 0L,
    val originalEosRejected: Long = 0L
)

data class NativeRuntimeLoopProtocolSnapshot(
    val adopted: Boolean,
    val bank: NativeRuntimeBank,
    val state: NativeRuntimeLoopCycleState,
    val ingressRequest: NativeRuntimeLoopIngressRequest?,
    val renderDirective: NativeRuntimeLoopRenderDirective?,
    val audibleEvidence: NativeRuntimeLoopAudibleEvidence?,
    val activeCache: NativeRuntimeLoopHeadCacheSnapshot?,
    val preparation: NativeRuntimeLoopHeadPreparationSnapshot,
    val counters: NativeRuntimeLoopProtocolCounters,
    val lastSubmitStatus: Int,
    val lastCacheStatus: Int,
    val lastIngressStatus: Int,
    val lastPositionStatus: Int,
    val lastBoundaryStatus: Int,
    val lastRenderCommitStatus: Int,
    val lastCallbackStatus: Int,
    val positionEvidenceSequence: Long,
    val lastOutputLoopEvidenceSequence: Long,
    val lastOriginalEosStatus: Int = 2
)

internal interface NativePlaybackRuntimeBackend {
    fun create(
        outputHandle: Long,
        firstBankHandle: Long,
        secondBankHandle: Long,
        configuration: NativeRuntimeConfiguration
    ): Long

    fun inheritCompletedSongCache(handle: Long, previousHandle: Long): Boolean = true
    fun start(handle: Long): Boolean
    fun requestStop(handle: Long)
    fun join(handle: Long)
    fun publishCommand(handle: Long, longs: LongArray, floats: FloatArray): Int
    fun publishStemGainPreview(
        handle: Long,
        publicationSequence: Long,
        coversOfferSequence: Long,
        selectionToken: Long,
        generation: Long,
        sourceIntentRevision: Long,
        stemIntentRevision: Long,
        canonicalControlFloorRevision: Long,
        activeMask: Int,
        gains: FloatArray
    ): Int = NativeRuntimePublishStatus.INVALID_SNAPSHOT.ordinal
    fun publishBeatGrid(
        handle: Long,
        schemaVersion: Long,
        revision: Long,
        bank: Int,
        selectionToken: Long,
        generation: Long,
        beatFrames: LongArray,
        accentFlags: ByteArray
    ): Int
    fun publishLoopCycleIntent(handle: Long, encodedIntent: LongArray): Int
    fun publishDirectLoopPolicy(
        handle: Long,
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
    ): Int = NativeRuntimePublishStatus.INVALID_SNAPSHOT.ordinal
    fun publishLoopIngressReady(handle: Long, encodedEvidence: LongArray): Int
    fun beginLoopHeadPreparation(handle: Long, encodedIntent: LongArray): Int
    fun configureLoopHeadPreparationLane(
        handle: Long,
        schemaVersion: Long,
        loopRevision: Long,
        laneIndex: Int,
        sourceRateHz: Int,
        preparationGeneration: Long
    ): Int
    fun offerLoopHeadPreparationPcm(
        handle: Long,
        schemaVersion: Long,
        loopRevision: Long,
        laneIndex: Int,
        preparationGeneration: Long,
        presentationTimeUs: Long,
        sourceRateHz: Int,
        encodingCode: Int,
        channelCount: Int,
        directBuffer: ByteBuffer,
        byteOffset: Int,
        frameCount: Int
    ): Long
    fun markLoopHeadPreparationEndOfStream(
        handle: Long,
        schemaVersion: Long,
        loopRevision: Long,
        laneIndex: Int,
        preparationGeneration: Long,
        finalSourceFrameExclusive: Long
    ): Int
    fun abortLoopHeadPreparation(
        handle: Long,
        schemaVersion: Long,
        loopRevision: Long
    ): Int
    fun beginPcmWindowRead(handle: Long, owner: LongArray, lane: Int, first: Long, frames: Int): Long = 0L
    fun pollPcmWindowRead(handle: Long, ticket: Long, pcm: ByteBuffer, frames: Int): Int = -1
    fun cancelPcmWindowRead(handle: Long, ticket: Long) {}
    fun pcmCacheRangeReady(handle: Long, owner: LongArray, requiredLaneMask: Int,
        first: Long, end: Long): Boolean = false
    fun loopProtocolSnapshot(handle: Long): LongArray
    fun notifyIngressAvailable(handle: Long)
    fun awaitPcmCacheProgress(handle: Long, afterSequence: Long): Long = -1L
    fun awaitAudibleControlProgress(handle: Long, afterSequence: Long): Long = -1L
    fun stemGainCallbackSettlementDiagnostics(handle: Long): LongArray = LongArray(0)
    fun audibleControlEvidence(handle: Long): LongArray
    fun setDiagnosticPayloadFingerprints(handle: Long, enabled: Boolean) = Unit
    fun controlProgress(handle: Long): LongArray? = null
    fun diagnostics(handle: Long): LongArray
    fun renderReadinessDiagnostics(handle: Long): LongArray = LongArray(0)
    fun close(handle: Long)
}

/**
 * Owning control bridge for one native two-bank render runtime.
 *
 * This object borrows one output and two lane-bank allocations. [close] always requests stop and
 * joins the render worker before deleting the runtime, and must return before those three owners
 * are closed. Decoder workers must be cancelled/joined and the exact unassignment acknowledgement
 * observed before any individual lane is released. All JNI entry points run on setup/control or
 * polling threads; the output consumer never calls through this bridge.
 */
internal class NativePlaybackRuntime private constructor(
    outputHandle: Long,
    firstBankHandle: Long,
    secondBankHandle: Long,
    configuration: NativeRuntimeConfiguration,
    private val backend: NativePlaybackRuntimeBackend,
    @Suppress("unused") private val retainedOutput: NativeAudioOutput?,
    @Suppress("unused") private val retainedBanks: List<NativeSongLaneIngressBank>
) : AutoCloseable {
    private val handle = AtomicLong(0L)

    constructor(
        output: NativeAudioOutput,
        firstBank: NativeSongLaneIngressBank,
        secondBank: NativeSongLaneIngressBank,
        configuration: NativeRuntimeConfiguration = NativeRuntimeConfiguration()
    ) : this(
        output.borrowNativeHandleForRuntime(),
        firstBank.nativeHandleForRenderRuntime(),
        secondBank.nativeHandleForRenderRuntime(),
        configuration,
        NativePlaybackRuntimeJniBackend,
        output,
        listOf(firstBank, secondBank)
    )

    internal constructor(
        outputHandle: Long,
        firstBankHandle: Long,
        secondBankHandle: Long,
        configuration: NativeRuntimeConfiguration,
        backend: NativePlaybackRuntimeBackend
    ) : this(
        outputHandle,
        firstBankHandle,
        secondBankHandle,
        configuration,
        backend,
        null,
        emptyList()
    )

    init {
        // JNI handles are opaque pointer bit patterns. Android's tagged ARM64 allocator may set
        // the top byte, which makes a perfectly valid pointer negative when represented as Long.
        require(outputHandle != 0L)
        require(firstBankHandle != 0L && secondBankHandle != 0L)
        require(firstBankHandle != secondBankHandle) { "Runtime banks must be distinct" }
        handle.set(
            backend.create(outputHandle, firstBankHandle, secondBankHandle, configuration).also {
                check(it != 0L) { "Native playback runtime returned an invalid handle" }
            }
        )
    }

    @Synchronized
    fun inheritCompletedSongCache(previous: NativePlaybackRuntime): Boolean =
        backend.inheritCompletedSongCache(requireOpenHandle(), previous.requireOpenHandle())

    @Synchronized
    fun start(): Boolean = backend.start(requireOpenHandle())

    @Synchronized
    fun requestStop() {
        backend.requestStop(requireOpenHandle())
    }

    @Synchronized
    fun join() {
        backend.join(requireOpenHandle())
    }

    @Synchronized
    fun publish(snapshot: NativeRuntimeCommandSnapshot): NativeRuntimePublishStatus {
        val encoded = NativeRuntimeCommandCodec.encode(snapshot)
        return NativeRuntimePublishStatus.fromNative(
            backend.publishCommand(requireOpenHandle(), encoded.first, encoded.second)
        )
    }

    /**
     * Allocation-free sibling ingress for VSYNC-bounded mixer MOVE traffic. Unlike [publish], this
     * does not rebuild a whole transport snapshot or dirty source, tune, loop, or metronome state.
     */
    @Synchronized
    fun publishStemGainPreview(
        publicationSequence: Long,
        coversOfferSequence: Long,
        ownership: NativeRuntimeOwnership,
        sourceIntentRevision: Long,
        stemIntentRevision: Long,
        canonicalControlFloorRevision: Long,
        activeMask: Int,
        gains: FloatArray
    ): NativeRuntimePublishStatus = NativeRuntimePublishStatus.fromNative(
        backend.publishStemGainPreview(
            requireOpenHandle(),
            publicationSequence,
            coversOfferSequence,
            ownership.selectionToken,
            ownership.generation,
            sourceIntentRevision,
            stemIntentRevision,
            canonicalControlFloorRevision,
            activeMask,
            gains
        )
    )

    @Synchronized
    fun publishBeatGrid(update: NativeRuntimeBeatGridUpdate): NativeRuntimePublishStatus =
        NativeRuntimePublishStatus.fromNative(
            backend.publishBeatGrid(
                requireOpenHandle(),
                NativeRuntimeBeatGridCodec.SCHEMA_VERSION,
                update.revision,
                update.bank.nativeCode,
                update.ownership.selectionToken,
                update.ownership.generation,
                update.beatFrames,
                update.beatAccents.let { accents ->
                    ByteArray(accents.size) { index -> if (accents[index]) 1 else 0 }
                }
            )
        )

    @Synchronized
    fun publishManagedLoopIntent(
        intent: NativeRuntimeManagedLoopIntent
    ): NativeRuntimePublishStatus = NativeRuntimePublishStatus.fromNative(
        backend.publishLoopCycleIntent(
            requireOpenHandle(),
            NativeRuntimeManagedLoopIntentCodec.encode(intent)
        )
    )

    /** Primitive-only caller-thread path; native lifetime lookup and publication are bounded. */
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
    ): NativeRuntimePublishStatus {
        require(revision > 0L)
        require(selectionToken > 0L)
        require(generation > 0L)
        require(aTimelineFrame >= 0L && bTimelineFrameExclusive >= 0L &&
            capturedBTimelineFrameExclusive >= 0L)
        require(inputEventAtMonotonicMicros >= 0L)
        require(heldBGestureId >= 0L)
        require(!enabled || aTimelineFrame < bTimelineFrameExclusive)
        require(capturedBTimelineFrameExclusive == 0L ||
            (enabled && boundaryCrossedAtPublication &&
                aTimelineFrame < capturedBTimelineFrameExclusive)
        )
        require(captureReferenceTimelineFrame >= -1L)
        require(captureReferenceTimelineFrame < 0L ||
            (boundaryCrossedAtPublication && captureReferenceTimelineFrame >=
                (capturedBTimelineFrameExclusive.takeIf { it > 0L } ?: bTimelineFrameExclusive))
        )
        val openHandle = handle.get()
        if (openHandle == 0L) return NativeRuntimePublishStatus.STOPPED
        return NativeRuntimePublishStatus.fromNative(
            backend.publishDirectLoopPolicy(
                openHandle,
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
        )
    }

    @Synchronized
    fun publishManagedLoopIngressReady(
        evidence: NativeRuntimeManagedLoopIngressReady
    ): NativeRuntimePublishStatus = NativeRuntimePublishStatus.fromNative(
        backend.publishLoopIngressReady(
            requireOpenHandle(),
            NativeRuntimeManagedLoopIngressReadyCodec.encode(evidence)
        )
    )

    @Synchronized
    fun beginLoopHeadPreparation(
        intent: NativeRuntimeManagedLoopIntent
    ): NativeRuntimeLoopHeadBeginStatus {
        require(intent.enabled)
        return NativeRuntimeLoopHeadBeginStatus.fromNative(
            backend.beginLoopHeadPreparation(
                requireOpenHandle(),
                NativeRuntimeManagedLoopIntentCodec.encode(intent)
            )
        )
    }

    @Synchronized
    fun configureLoopHeadPreparationLane(
        loopRevision: Long,
        laneIndex: Int,
        sourceRateHz: Int,
        preparationGeneration: Long
    ): NativeRuntimeLoopHeadConfigureStatus = NativeRuntimeLoopHeadConfigureStatus.fromNative(
        backend.configureLoopHeadPreparationLane(
            requireOpenHandle(),
            NativeRuntimeManagedLoopIntentCodec.SCHEMA_VERSION,
            loopRevision,
            laneIndex,
            sourceRateHz,
            preparationGeneration
        )
    )

    @Synchronized
    fun offerLoopHeadPreparationPcm(
        loopRevision: Long,
        laneIndex: Int,
        preparationGeneration: Long,
        block: NativePcmBlock
    ): NativeRuntimeLoopHeadOfferResult {
        require(block.data.isDirect)
        val packed = backend.offerLoopHeadPreparationPcm(
            requireOpenHandle(),
            NativeRuntimeManagedLoopIntentCodec.SCHEMA_VERSION,
            loopRevision,
            laneIndex,
            preparationGeneration,
            block.presentationTimeUs,
            block.format.sampleRateHz,
            when (block.format.encoding) {
                NativePcmEncoding.PCM_16_BIT -> 1
                NativePcmEncoding.PCM_FLOAT -> 2
            },
            block.format.channelCount,
            block.data,
            block.data.position(),
            block.frameCount
        )
        val statusCode = (packed ushr NativePacketSchema.PACKED_RESULT_STATUS_SHIFT).nativePacketInt()
        val accepted = (packed and 0xffff_ffffL).also {
            require(it <= Int.MAX_VALUE.toLong())
        }.nativePacketInt()
        return NativeRuntimeLoopHeadOfferResult(
            NativeRuntimeLoopHeadOfferStatus.fromNative(statusCode),
            accepted
        )
    }

    @Synchronized
    fun markLoopHeadPreparationEndOfStream(
        loopRevision: Long,
        laneIndex: Int,
        preparationGeneration: Long,
        finalSourceFrameExclusive: Long
    ): NativeRuntimeLoopHeadEndStatus = NativeRuntimeLoopHeadEndStatus.fromNative(
        backend.markLoopHeadPreparationEndOfStream(
            requireOpenHandle(),
            NativeRuntimeManagedLoopIntentCodec.SCHEMA_VERSION,
            loopRevision,
            laneIndex,
            preparationGeneration,
            finalSourceFrameExclusive
        )
    )

    @Synchronized
    fun abortLoopHeadPreparation(loopRevision: Long): NativeRuntimeLoopHeadAbortStatus =
        NativeRuntimeLoopHeadAbortStatus.fromNative(
            backend.abortLoopHeadPreparation(
                requireOpenHandle(),
                NativeRuntimeManagedLoopIntentCodec.SCHEMA_VERSION,
                loopRevision
            )
        )

    suspend fun readPcmWindow(owner: LongArray, lane: Int, first: Long, frames: Int): SourcePcmWindowRead {
        require(owner.size == 5 && lane in 0..NativeRuntimeControls.STEM_COUNT && first >= 0L && frames in 1..480_000)
        val ticket = synchronized(this) {
            val openHandle = handle.get().takeIf { it != 0L } ?: return SourcePcmWindowRead.Stale
            backend.beginPcmWindowRead(openHandle, owner, lane, first, frames)
        }
        if (ticket == 0L) return SourcePcmWindowRead.Unsupported
        try {
            val pcm = ByteBuffer.allocateDirect(frames * 2 * Float.SIZE_BYTES).order(java.nio.ByteOrder.nativeOrder())
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(250L)
            while (true) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val status = synchronized(this) {
                    val openHandle = handle.get().takeIf { it != 0L } ?: return SourcePcmWindowRead.Stale
                    backend.pollPcmWindowRead(openHandle, ticket, pcm, frames)
                }
                if (status == -3) return SourcePcmWindowRead.Stale
                if (status == -1) return SourcePcmWindowRead.Unsupported
                if (status < -1) return SourcePcmWindowRead.Corrupt("SOURCE_WINDOW_CACHE_READ_FAILED")
                if (status > frames) return SourcePcmWindowRead.Corrupt("SOURCE_WINDOW_CACHE_RECEIPT_INVALID")
                if (status > 0) {
                    val samples = FloatArray(status * 2).also { pcm.asFloatBuffer().get(it) }
                    return if (samples.all(Float::isFinite)) SourcePcmWindowRead.Ready(samples)
                        else SourcePcmWindowRead.Corrupt("SOURCE_WINDOW_CACHE_NONFINITE")
                }
                if (System.nanoTime() >= deadline) return SourcePcmWindowRead.Delayed
                kotlinx.coroutines.delay(2L)
            }
        } finally {
            synchronized(this) { handle.get().takeIf { it != 0L }?.let { backend.cancelPcmWindowRead(it, ticket) } }
        }
    }

    @Synchronized
    fun pcmCacheRangeReady(intent: NativeRuntimeBankIntent, requiredLaneMask: Int,
        first: Long, end: Long): Boolean {
        val owner = requireNotNull(intent.ownership)
        require(requiredLaneMask != 0 && requiredLaneMask and NativeRuntimeManagedLoopIntent.ALL_LANES_MASK == requiredLaneMask)
        require(first >= 0L && end > first)
        return backend.pcmCacheRangeReady(requireOpenHandle(), longArrayOf(owner.selectionToken,
            owner.generation, intent.songIdentityHash, intent.originalContentHash, intent.stemPackageHash),
            requiredLaneMask, first, end)
    }

    @Synchronized
    fun loopProtocolSnapshot(): NativeRuntimeLoopProtocolSnapshot =
        NativeRuntimeLoopProtocolCodec.decode(
            backend.loopProtocolSnapshot(requireOpenHandle())
        )

    @Synchronized
    fun notifyIngressAvailable() {
        backend.notifyIngressAvailable(requireOpenHandle())
    }

    /** Actual canonical publication/read progress; stopped runtimes release every waiter. */
    fun awaitPcmCacheProgress(afterSequence: Long): Long {
        require(afterSequence >= 0L)
        val openHandle = handle.get()
        return if (openHandle == 0L) -1L else backend.awaitPcmCacheProgress(openHandle, afterSequence)
    }

    /** Blocking non-real-time progress wait; zero/negative means this runtime has stopped. */
    fun awaitAudibleControlProgress(afterSequence: Long): Long {
        require(afterSequence >= 0L)
        val openHandle = handle.get()
        if (openHandle == 0L) return -1L
        return backend.awaitAudibleControlProgress(openHandle, afterSequence)
    }

    @Synchronized
    fun stemGainCallbackSettlementDiagnostics(): StemGainCallbackSettlementDiagnostics? =
        StemGainCallbackSettlementDiagnosticsCodec.decode(
            backend.stemGainCallbackSettlementDiagnostics(requireOpenHandle())
        )

    @Synchronized
    fun setDiagnosticPayloadFingerprints(enabled: Boolean) =
        backend.setDiagnosticPayloadFingerprints(requireOpenHandle(), enabled)

    @Synchronized
    fun controlProgress(): NativeRuntimeControlObservation = backend.controlProgress(requireOpenHandle())
        ?.let(NativeRuntimeControlProgressCodec::decode) ?: diagnostics()

    @Synchronized
    fun diagnostics(): NativeRuntimeDiagnostics = NativeRuntimeDiagnosticsCodec.decode(
        backend.diagnostics(requireOpenHandle())
    )

    @Synchronized
    fun renderReadinessDiagnostics(): NativeRuntimeRenderReadinessDiagnostics? {
        val values = backend.renderReadinessDiagnostics(requireOpenHandle())
        return values.takeIf { it.isNotEmpty() }
            ?.let(NativeRuntimeRenderReadinessDiagnosticsCodec::decode)
    }

    /** Exact equality is required: a later reassignment is not a safe lane-release fence. */
    @Synchronized
    fun hasAppliedUnassignment(bank: NativeRuntimeBank, exactRevision: Long): Boolean {
        require(exactRevision > 0L)
        val acknowledgement = controlProgress().banks[bank.nativeCode]
        return acknowledgement.appliedIntentRevision == exactRevision && !acknowledgement.assigned
    }

    @Synchronized
    fun pollAudibleEnd(afterSequence: Long): NativeRuntimeAudibleEndEvent? {
        require(afterSequence >= 0L)
        return diagnostics().audibleEnd?.takeIf { it.sequence > afterSequence }
    }

    @Synchronized
    fun pollAudibleControl(afterSequence: Long): NativeRuntimeAudibleControlEvidence? {
        require(afterSequence >= 0L)
        return NativeRuntimeAudibleControlCodec.decode(backend.audibleControlEvidence(requireOpenHandle()))
            ?.takeIf { it.sequence > afterSequence }
    }

    fun isClosed(): Boolean = handle.get() == 0L

    /** Native close is the definitive request-stop -> join -> delete lifetime barrier. */
    @Synchronized
    override fun close() {
        val openHandle = handle.getAndSet(0L)
        if (openHandle == 0L) return
        backend.close(openHandle)
    }

    private fun requireOpenHandle(): Long = handle.get().also {
        check(it != 0L) { "Native playback runtime is closed" }
    }
}

internal object NativePlaybackRuntimeJniBackend : NativePlaybackRuntimeBackend {
    /* Create forwards the bounded render configuration and app-private PCM cache settings. */
    private external fun nativeCreate(
        outputHandle: Long,
        firstBankHandle: Long,
        secondBankHandle: Long,
        targetBursts: Int,
        minimumOutputReserveMs: Int,
        fallbackFramesPerBurst: Int,
        ingressPullFrames: Int,
        maximumPullsPerLanePerCycle: Int,
        maximumOutputBlocksPerCycle: Int,
        idleSleepMicros: Int,
        currentSongCacheDirectory: String
    ): Long

    override fun create(
        outputHandle: Long,
        firstBankHandle: Long,
        secondBankHandle: Long,
        configuration: NativeRuntimeConfiguration
    ): Long = nativeCreate(
        outputHandle,
        firstBankHandle,
        secondBankHandle,
        configuration.targetBursts,
        configuration.minimumOutputReserveMs,
        configuration.fallbackFramesPerBurst,
        configuration.ingressPullFrames,
        configuration.maximumPullsPerLanePerCycle,
        configuration.maximumOutputBlocksPerCycle,
        configuration.idleSleepMicros,
        configuration.currentSongCacheDirectory
    )

    override external fun inheritCompletedSongCache(handle: Long, previousHandle: Long): Boolean
    override external fun start(handle: Long): Boolean
    override external fun requestStop(handle: Long)
    override external fun join(handle: Long)
    override external fun publishCommand(handle: Long, longs: LongArray, floats: FloatArray): Int
    override external fun publishStemGainPreview(
        handle: Long,
        publicationSequence: Long,
        coversOfferSequence: Long,
        selectionToken: Long,
        generation: Long,
        sourceIntentRevision: Long,
        stemIntentRevision: Long,
        canonicalControlFloorRevision: Long,
        activeMask: Int,
        gains: FloatArray
    ): Int
    override external fun publishBeatGrid(
        handle: Long,
        schemaVersion: Long,
        revision: Long,
        bank: Int,
        selectionToken: Long,
        generation: Long,
        beatFrames: LongArray,
        accentFlags: ByteArray
    ): Int
    override external fun publishLoopCycleIntent(handle: Long, encodedIntent: LongArray): Int
    override external fun publishDirectLoopPolicy(
        handle: Long,
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
    ): Int
    override external fun publishLoopIngressReady(handle: Long, encodedEvidence: LongArray): Int
    override external fun beginLoopHeadPreparation(handle: Long, encodedIntent: LongArray): Int
    override external fun configureLoopHeadPreparationLane(
        handle: Long,
        schemaVersion: Long,
        loopRevision: Long,
        laneIndex: Int,
        sourceRateHz: Int,
        preparationGeneration: Long
    ): Int
    override external fun offerLoopHeadPreparationPcm(
        handle: Long,
        schemaVersion: Long,
        loopRevision: Long,
        laneIndex: Int,
        preparationGeneration: Long,
        presentationTimeUs: Long,
        sourceRateHz: Int,
        encodingCode: Int,
        channelCount: Int,
        directBuffer: ByteBuffer,
        byteOffset: Int,
        frameCount: Int
    ): Long
    override external fun markLoopHeadPreparationEndOfStream(
        handle: Long,
        schemaVersion: Long,
        loopRevision: Long,
        laneIndex: Int,
        preparationGeneration: Long,
        finalSourceFrameExclusive: Long
    ): Int
    override external fun abortLoopHeadPreparation(
        handle: Long,
        schemaVersion: Long,
        loopRevision: Long
    ): Int
    override external fun beginPcmWindowRead(handle: Long, owner: LongArray, lane: Int, first: Long, frames: Int): Long
    override external fun pollPcmWindowRead(handle: Long, ticket: Long, pcm: ByteBuffer, frames: Int): Int
    override external fun cancelPcmWindowRead(handle: Long, ticket: Long)
    override external fun pcmCacheRangeReady(handle: Long, owner: LongArray, requiredLaneMask: Int,
        first: Long, end: Long): Boolean
    override external fun loopProtocolSnapshot(handle: Long): LongArray
    override external fun notifyIngressAvailable(handle: Long)
    override external fun awaitPcmCacheProgress(handle: Long, afterSequence: Long): Long
    override external fun awaitAudibleControlProgress(handle: Long, afterSequence: Long): Long
    override external fun stemGainCallbackSettlementDiagnostics(handle: Long): LongArray
    override external fun audibleControlEvidence(handle: Long): LongArray
    override external fun setDiagnosticPayloadFingerprints(handle: Long, enabled: Boolean)
    override external fun controlProgress(handle: Long): LongArray
    override external fun diagnostics(handle: Long): LongArray
    override external fun renderReadinessDiagnostics(handle: Long): LongArray
    override external fun close(handle: Long)

    init {
        System.loadLibrary("stemwise_audio_output")
    }
}
