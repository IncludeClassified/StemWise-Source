package app.stemwise.playback.engine

import app.stemwise.playback.playbackStableDigest
import app.stemwise.playback.playbackControlPositionMs
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Millisecond transport commands retain at least one renderable frame instead of empty EOS. */
internal fun nativeRenderablePositionMs(
    valueMs: Double, durationMs: Long, playbackStartMs: Long = 0L,
    playbackEndMs: Long = durationMs
): Long = playbackControlPositionMs(valueMs, durationMs).coerceIn(playbackStartMs, playbackEndMs - 1L)

/**
 * Stable boundary between StemWise's queue/session model and its native audio runtime.
 *
 * A token represents one request to make a song the audible owner. Song identifiers are not
 * sufficient because the same row can be selected repeatedly while older decoder work is still
 * completing. Tokens are process-local, strictly increasing, and never reused.
 */
@JvmInline
value class EngineSelectionToken(val value: Long) {
    init {
        require(value > 0L) { "An engine selection token must be positive" }
    }
}

/** Stable on-disk audio identity; unlike a selection token it may be prepared speculatively. */
data class EngineAudioIdentity(
    val songId: String,
    val audioRevision: String,
    val originalLocation: String,
    val durationMs: Long,
    val originalContentSha256: String? = null,
    val playbackStartMs: Long = 0L,
    val playbackEndMs: Long = durationMs
) {
    init {
        require(songId.isNotBlank())
        require(audioRevision.isNotBlank())
        require(originalLocation.isNotBlank())
        require(durationMs > 0L)
        require(playbackStartMs >= 0L && playbackStartMs < playbackEndMs && playbackEndMs <= durationMs)
    }
}

internal fun EngineAudioIdentity.stableIdentity(): String = playbackStableDigest(
    "engine-audio-identity-v1|${songId.length}:$songId|" +
        "${audioRevision.length}:$audioRevision|" +
        "${originalLocation.length}:$originalLocation|$durationMs|$originalContentSha256|$playbackStartMs|$playbackEndMs"
)

data class EngineSelection(
    val token: EngineSelectionToken,
    val songId: String,
    val audioRevision: String,
    val originalLocation: String,
    val durationMs: Long,
    val startPositionMs: Long,
    val autoplay: Boolean,
    val originalContentSha256: String? = null,
    val playbackStartMs: Long = 0L,
    val playbackEndMs: Long = durationMs
) {
    init {
        require(songId.isNotBlank())
        require(audioRevision.isNotBlank())
        require(originalLocation.isNotBlank())
        require(durationMs > 0L)
        require(playbackStartMs >= 0L && playbackStartMs < playbackEndMs && playbackEndMs <= durationMs)
        require(startPositionMs in 0L..durationMs)
    }
}

fun EngineSelection.audioIdentity(): EngineAudioIdentity = EngineAudioIdentity(
    songId = songId,
    audioRevision = audioRevision,
    originalLocation = originalLocation,
    durationMs = durationMs,
    originalContentSha256 = originalContentSha256,
    playbackStartMs = playbackStartMs,
    playbackEndMs = playbackEndMs
)

data class EngineStemLane(
    val id: String,
    val normalizedRole: String,
    val location: String,
    val contentSha256: String
) {
    init {
        require(id.isNotBlank())
        require(normalizedRole.isNotBlank())
        require(location.isNotBlank())
        require(contentSha256.isNotBlank())
    }
}

/**
 * Evidence written only after the one device callback consumes final PCM for [token].
 *
 * Decoder-open, input-buffered, render-ready, and queued-to-output are deliberately not owner
 * evidence. The callback sequence and non-empty final-PCM block make the audible boundary
 * explicit and independently testable.
 */
internal data class EngineAudibleOwnerEvidence(
    val token: EngineSelectionToken,
    val songId: String,
    val callbackSequence: Long,
    val firstOutputFrame: Long,
    val finalPcmFrames: Int,
    val outputFingerprintSequence: Long
) {
    init {
        require(songId.isNotBlank())
        require(callbackSequence > 0L)
        require(firstOutputFrame >= 0L)
        require(finalPcmFrames > 0)
        require(outputFingerprintSequence > 0L)
    }
}

internal data class EngineOwnerCommit(
    val selection: EngineSelection,
    val evidence: EngineAudibleOwnerEvidence
)

/**
 * A process-local owner token reserved for one speculative Original decoder.
 *
 * A reservation is deliberately not an [EngineSelection]: it has no transport intent and is not
 * installed as the latest public request. Consequently decoded/queued PCM for a likely-next song
 * cannot pass [EngineOwnerCommitGate.accept]. Claiming preserves the token so the already-filled
 * candidate bank can become the requested owner without reopening or copying its PCM.
 */
internal data class EngineSelectionReservation(
    val token: EngineSelectionToken,
    val identity: EngineAudioIdentity
)

enum class EngineSourceMode { ORIGINAL, STEMS }

data class EngineStemControl(
    val gain: Float = 1f,
    val muted: Boolean = false,
    val solo: Boolean = false
) {
    init {
        require(gain in 0f..1f)
    }
}

data class EngineLoopConfig(
    val startMs: Long,
    val endMs: Long,
    /**
     * Stable command provenance captured when an already-complete range is edited around an
     * outside playhead. Native keeps that new range dormant until real transport enters it instead
     * of interpreting asynchronous cache readiness as permission to jump back to A.
     */
    val parkUntilRangeEntry: Boolean = false
) {
    init {
        require(app.stemwise.playback.PlaybackLoopValidation.accepts(startMs, endMs))
    }
}

/** Loop-only authority. It is intentionally not part of [EngineDesiredState]. */
internal data class EngineExplicitLoopState(
    val token: EngineSelectionToken,
    val revision: Long,
    val config: EngineLoopConfig?,
    /** Exact callback-committed owner; null is reserved for actor-owned reset state. */
    val ownership: NativeRuntimeOwnership? = null,
    /** Android input uptime in the native CLOCK_MONOTONIC domain; zero for non-pointer commands. */
    val inputEventAtMonotonicMicros: Long = 0L,
    /** Non-zero only while one physical B-handle gesture publishes MOVE samples. */
    val heldBGestureId: Long = 0L,
    /** Historical B crossing folded into the newest visible MOVE; negative means none. */
    val capturedBMs: Long = -1L,
    /** Cursor that proved this capture crossed B, retained across a concurrent native wrap. */
    val captureReferencePositionMs: Long = -1L,
    /** Native policy revision accepted for this logical state; zero before first publication. */
    val nativePolicyRevision: Long = 0L
) {
    init {
        require(revision >= 0L)
        require(ownership == null || ownership.selectionToken == token.value)
        require(inputEventAtMonotonicMicros >= 0L)
        require(heldBGestureId >= 0L)
        require(capturedBMs >= -1L)
        require(captureReferencePositionMs >= -1L)
        require(captureReferencePositionMs < 0L ||
            (capturedBMs >= 0L && captureReferencePositionMs >= capturedBMs))
        require(capturedBMs < 0L || heldBGestureId > 0L)
        require(capturedBMs < 0L ||
            (config != null && capturedBMs - config.startMs >= 100L)
        )
        require(nativePolicyRevision >= 0L)
    }
}

data class EngineMeterSegment(
    val startMs: Long,
    val endMs: Long,
    val numerator: Int,
    val denominator: Int
) {
    init {
        require(startMs >= 0L && endMs > startMs)
        require(numerator in 1..32)
        require(denominator in setOf(1, 2, 4, 8, 16, 32))
    }
}

data class EngineMetronomeConfig(
    val enabled: Boolean = false,
    val songSynced: Boolean = false,
    val bpm: Float = 120f,
    val volume: Float = 1f,
    val subdivision: Int = 1,
    val numerator: Int = 4,
    val denominator: Int = 4,
    val beatGridMs: LongArray = longArrayOf(),
    val downbeats: BooleanArray = booleanArrayOf(),
    val meterSegments: List<EngineMeterSegment> = emptyList()
) {
    /** Owned timing arrays change only at the defensive import boundary. */
    fun sameTimingAs(other: EngineMetronomeConfig): Boolean =
        beatGridMs === other.beatGridMs && downbeats === other.downbeats &&
            meterSegments === other.meterSegments && numerator == other.numerator &&
            denominator == other.denominator

    init {
        require(bpm in 20f..400f)
        require(volume in 0f..1f)
        require(subdivision in setOf(1, 2, 4))
        require(numerator in 1..32)
        require(denominator in setOf(1, 2, 4, 8, 16, 32))
        require(beatGridMs.size == downbeats.size)
        for (index in 1 until beatGridMs.size) {
            require(beatGridMs[index] > beatGridMs[index - 1])
        }
        meterSegments.forEachIndexed { index, segment ->
            if (index != 0) require(segment.startMs >= meterSegments[index - 1].endMs)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is EngineMetronomeConfig &&
            enabled == other.enabled &&
            songSynced == other.songSynced &&
            bpm == other.bpm &&
            volume == other.volume &&
            subdivision == other.subdivision &&
            numerator == other.numerator &&
            denominator == other.denominator &&
            beatGridMs.contentEquals(other.beatGridMs) &&
            downbeats.contentEquals(other.downbeats) &&
            meterSegments == other.meterSegments

    override fun hashCode(): Int {
        var result = enabled.hashCode()
        result = 31 * result + songSynced.hashCode()
        result = 31 * result + bpm.hashCode()
        result = 31 * result + volume.hashCode()
        result = 31 * result + subdivision
        result = 31 * result + numerator
        result = 31 * result + denominator
        result = 31 * result + beatGridMs.contentHashCode()
        result = 31 * result + downbeats.contentHashCode()
        return 31 * result + meterSegments.hashCode()
    }
}

/** Initial song controls are admitted with selection, before any preparation snapshot is queued. */
data class NativeEngineInitialControls(
    val tempo: Float = 1f,
    val semitoneShift: Int = 0,
    val metronome: EngineMetronomeConfig = EngineMetronomeConfig()
) {
    init {
        require(tempo in app.stemwise.playback.MINIMUM_PLAYBACK_TEMPO..app.stemwise.playback.MAXIMUM_PLAYBACK_TEMPO)
        require(semitoneShift in -12..12)
    }
}

internal fun EngineMetronomeConfig.detachedCopy() = EngineMetronomeConfig(
    enabled = enabled,
    songSynced = songSynced,
    bpm = bpm,
    volume = volume,
    subdivision = subdivision,
    numerator = numerator,
    denominator = denominator,
    beatGridMs = beatGridMs.copyOf(),
    downbeats = downbeats.copyOf(),
    meterSegments = meterSegments.toList()
)

internal fun NativeEngineInitialControls.detachedCopy() = copy(metronome = metronome.detachedCopy())

/**
 * Complete desired engine state for one selection token.
 *
 * Mutable controls are snapshots, not queued imperative work. The engine consumes only the
 * newest revision at a render boundary, which keeps seek, mixer, source, tuning, and
 * metronome response bounded even if the UI produces hundreds of intermediate values.
 */
internal data class EngineDesiredState(
    val selection: EngineSelection,
    val revision: Long,
    /** Exact revision of the latest source-mode edge, retained through mailbox conflation. */
    val sourceIntentRevision: Long = revision,
    /** Exact revision of the latest physical stem-package identity edge. */
    val stemIntentRevision: Long = revision,
    /** Explicit retry intent; unrelated controls cannot reopen a failed physical package. */
    val stemRetryRevision: Long = 0L,
    val playWhenReady: Boolean = selection.autoplay,
    val requestedPositionMs: Long = nativeRenderablePositionMs(selection.startPositionMs.toDouble(), selection.durationMs, selection.playbackStartMs, selection.playbackEndMs),
    val seekRevision: Long = 0L,
    val sourceMode: EngineSourceMode = EngineSourceMode.ORIGINAL,
    val stemLanes: List<EngineStemLane> = emptyList(),
    /** Opaque canonical-package revision from the library row/enrichment import. */
    val stemRevision: String = "",
    val stemControls: Map<String, EngineStemControl> = emptyMap(),
    val tempo: Float = 1f,
    val semitoneShift: Int = 0,
    val metronome: EngineMetronomeConfig = EngineMetronomeConfig()
) {
    init {
        require(revision > 0L)
        require(sourceIntentRevision in 1L..revision)
        require(stemIntentRevision in 1L..revision)
        require(stemRetryRevision >= 0L)
        require(requestedPositionMs in 0L..selection.durationMs)
        require(seekRevision >= 0L)
        require(tempo in app.stemwise.playback.MINIMUM_PLAYBACK_TEMPO..app.stemwise.playback.MAXIMUM_PLAYBACK_TEMPO)
        require(semitoneShift in -12..12)
        require(stemLanes.size <= app.stemwise.model.StemSourceCatalogue.MAX_TARGETS + 1)
        require(stemLanes.map(EngineStemLane::id).toSet().size == stemLanes.size)
        require(stemControls.keys.all { id -> stemLanes.any { it.id == id } })
        require(sourceMode != EngineSourceMode.STEMS ||
            app.stemwise.model.StemRoleContract.isPlayable(stemLanes.map(EngineStemLane::normalizedRole))) {
            "Song parts cannot become desired until their complete declared package is attached"
        }
    }
}

/**
 * Builds the first desired-state snapshot for a callback-promoted queue successor.
 *
 * Every new owner starts Original with neutral controls. Repeat One is a transport restart of the
 * current owner, not a successor, so no song-scoped control state crosses this boundary.
 */
internal fun automaticSuccessorDesiredState(
    selection: EngineSelection
): EngineDesiredState = EngineDesiredState(selection = selection, revision = 1L)

internal fun initialEngineDesiredState(
    selection: EngineSelection,
    controls: NativeEngineInitialControls
): EngineDesiredState = EngineDesiredState(
    selection = selection,
    revision = 1L,
    tempo = controls.tempo,
    semitoneShift = controls.semitoneShift,
    metronome = controls.metronome.detachedCopy()
)

internal fun acceptedEngineDesiredState(
    previous: EngineDesiredState,
    transformed: EngineDesiredState
): EngineDesiredState {
    check(previous.revision < Long.MAX_VALUE) { "Engine control revision space exhausted" }
    require(transformed.selection == previous.selection) {
        "A control update cannot replace the selected owner"
    }
    require(transformed.revision == previous.revision) {
        "Control revisions are assigned only by EngineDesiredStateStore"
    }
    require(transformed.sourceIntentRevision == previous.sourceIntentRevision) {
        "Source intent revisions are assigned only by EngineDesiredStateStore"
    }
    require(transformed.stemIntentRevision == previous.stemIntentRevision) {
        "Stem intent revisions are assigned only by EngineDesiredStateStore"
    }
    require(transformed.stemRetryRevision >= previous.stemRetryRevision &&
        transformed.stemRetryRevision - previous.stemRetryRevision <= 1L) {
        "A package retry must advance its explicit request once"
    }
    val nextRevision = previous.revision + 1L
    val sourceIntentRevision = if (transformed.sourceMode != previous.sourceMode) {
        nextRevision
    } else {
        previous.sourceIntentRevision
    }
    val stemIntentRevision = if (
        transformed.stemLanes != previous.stemLanes ||
        transformed.stemRevision != previous.stemRevision ||
        transformed.stemRetryRevision != previous.stemRetryRevision
    ) {
        nextRevision
    } else {
        previous.stemIntentRevision
    }
    return transformed.copy(
        requestedPositionMs = nativeRenderablePositionMs(transformed.requestedPositionMs.toDouble(), previous.selection.durationMs, previous.selection.playbackStartMs, previous.selection.playbackEndMs),
        revision = nextRevision,
        sourceIntentRevision = sourceIntentRevision,
        stemIntentRevision = stemIntentRevision
    )
}

/** Conflated control store: stale-token UI work is rejected before it reaches native code. */
internal class EngineDesiredStateStore {
    private val latest = AtomicReference<EngineDesiredState?>(null)

    @Synchronized
    fun install(
        selection: EngineSelection,
        initialControls: NativeEngineInitialControls = NativeEngineInitialControls()
    ): EngineDesiredState = initialEngineDesiredState(selection, initialControls).also(latest::set)

    @Synchronized
    fun installAutomaticSuccessor(
        outgoingToken: EngineSelectionToken,
        appliedSeekRevision: Long,
        select: (playWhenReady: Boolean) -> EngineSelection?
    ): EngineDesiredState? {
        val outgoing = latest.get()?.takeIf { it.selection.token == outgoingToken } ?: return null
        if (outgoing.seekRevision > appliedSeekRevision) return null
        val selection = select(outgoing.playWhenReady) ?: return null
        return automaticSuccessorDesiredState(selection).copy(playWhenReady = outgoing.playWhenReady).also(latest::set)
    }

    fun latest(): EngineDesiredState? = latest.get()

    @Synchronized
    fun update(
        token: EngineSelectionToken,
        transform: (EngineDesiredState) -> EngineDesiredState
    ): EngineDesiredState? = updateLocked(token, expectedRevision = null, transform)

    /**
     * Applies one adapter-owned control mutation only while its exact desired-state baseline is
     * still current. A stale callback or lifecycle command is ordinary command rejection, not a
     * process-fatal invariant failure.
     */
    @Synchronized
    fun updateIfRevision(
        token: EngineSelectionToken,
        expectedRevision: Long,
        transform: (EngineDesiredState) -> EngineDesiredState
    ): EngineDesiredState? = updateLocked(token, expectedRevision, transform)

    /** A newer caller cannot publish an accepted revision ahead of its owner's admission fence. */
    @Synchronized
    fun updateAndPublishIfRevision(
        token: EngineSelectionToken,
        expectedRevision: Long,
        transform: (EngineDesiredState) -> EngineDesiredState,
        publish: (EngineDesiredState) -> Unit
    ): EngineDesiredState? = updateLocked(token, expectedRevision, transform)?.also(publish)

    private fun updateLocked(
        token: EngineSelectionToken,
        expectedRevision: Long?,
        transform: (EngineDesiredState) -> EngineDesiredState
    ): EngineDesiredState? {
        val previous = latest.get()?.takeIf { it.selection.token == token } ?: return null
        if (expectedRevision != null && previous.revision != expectedRevision) return null
        return acceptedEngineDesiredState(previous, transform(previous)).also(latest::set)
    }

    @Synchronized
    fun clear() {
        latest.set(null)
    }

    /** Restores the still-audible committed state only if no newer request replaced the failure. */
    @Synchronized
    fun restoreAfterFailedSelection(
        failedToken: EngineSelectionToken,
        committed: EngineDesiredState?
    ): Boolean {
        val failed = latest.get()?.takeIf { it.selection.token == failedToken } ?: return false
        require(committed == null || committed.selection.token.value < failed.selection.token.value)
        latest.set(committed)
        return true
    }
}

/**
 * Latest-request-wins ownership gate shared by the native engine and AudioPlayback adapter.
 *
 * This class is intentionally independent of coroutines and Android so the most important
 * metadata/audio invariant can be pressure-tested on the host. All methods are thread-safe.
 */
internal class EngineOwnerCommitGate {
    private val nextToken = AtomicLong(0L)
    private val latestSelection = AtomicReference<EngineSelection?>(null)
    private val committedToken = AtomicLong(0L)
    private val reservation = AtomicReference<EngineSelectionReservation?>(null)

    /** Keeps at most one likely-next token. Repeating the same request is allocation-free. */
    @Synchronized
    fun reserve(identity: EngineAudioIdentity): EngineSelectionReservation {
        reservation.get()?.takeIf { it.identity == identity }?.let { return it }
        val reserved = EngineSelectionReservation(allocateToken(), identity)
        reservation.set(reserved)
        return reserved
    }

    fun reservation(): EngineSelectionReservation? = reservation.get()

    /**
     * Turns the exact live reservation into the latest request. A stale/cancelled reservation is
     * rejected instead of being assigned a replacement token behind the caller's back.
     */
    @Synchronized
    fun claim(
        expected: EngineSelectionReservation,
        startPositionMs: Long,
        autoplay: Boolean
    ): EngineSelection? {
        if (!reservation.compareAndSet(expected, null)) return null
        val identity = expected.identity
        return EngineSelection(
            token = expected.token,
            songId = identity.songId,
            audioRevision = identity.audioRevision,
            originalLocation = identity.originalLocation,
            durationMs = identity.durationMs,
            originalContentSha256 = identity.originalContentSha256,
            playbackStartMs = identity.playbackStartMs,
            playbackEndMs = identity.playbackEndMs,
            startPositionMs = nativeRenderablePositionMs(startPositionMs.toDouble(), identity.durationMs, identity.playbackStartMs, identity.playbackEndMs),
            autoplay = autoplay
        ).also(latestSelection::set)
    }

    @Synchronized
    fun cancelReservation(expected: EngineSelectionReservation): Boolean =
        reservation.compareAndSet(expected, null)

    @Synchronized
    fun request(
        songId: String,
        audioRevision: String,
        originalLocation: String,
        durationMs: Long,
        startPositionMs: Long,
        autoplay: Boolean,
        originalContentSha256: String? = null,
        playbackStartMs: Long = 0L,
        playbackEndMs: Long = durationMs
    ): EngineSelection {
        // Any explicit non-claiming selection makes an older speculative owner irrelevant.
        reservation.set(null)
        return EngineSelection(
            token = allocateToken(),
            songId = songId,
            audioRevision = audioRevision,
            originalLocation = originalLocation,
            durationMs = durationMs,
            startPositionMs = nativeRenderablePositionMs(startPositionMs.toDouble(), durationMs, playbackStartMs, playbackEndMs),
            autoplay = autoplay,
            originalContentSha256 = originalContentSha256,
            playbackStartMs = playbackStartMs,
            playbackEndMs = playbackEndMs
        ).also(latestSelection::set)
    }

    fun latest(): EngineSelection? = latestSelection.get()

    fun isLatest(token: EngineSelectionToken): Boolean =
        latestSelection.get()?.token == token

    /** Rolls a failed uncommitted request back without reusing its monotonically allocated id. */
    @Synchronized
    fun restoreAfterFailedSelection(
        failedToken: EngineSelectionToken,
        committed: EngineSelection?
    ): Boolean {
        val failed = latestSelection.get()?.takeIf { it.token == failedToken } ?: return false
        require(committed == null || committed.token.value == committedToken.get()) {
            "Only the exact committed owner may replace a failed latest request"
        }
        require(committed == null || committed.token.value < failed.token.value)
        latestSelection.set(committed)
        return true
    }

    /**
     * Returns a public-owner commit only for fresh final PCM belonging to the exact latest
     * selection. Stale, mismatched, duplicate, or pre-invalidation evidence is ignored.
     */
    @Synchronized
    fun accept(evidence: EngineAudibleOwnerEvidence): EngineOwnerCommit? {
        while (true) {
            val selection = latestSelection.get() ?: return null
            if (selection.token != evidence.token || selection.songId != evidence.songId) return null
            val previousCommit = committedToken.get()
            if (evidence.token.value <= previousCommit) return null
            if (committedToken.compareAndSet(previousCommit, evidence.token.value)) {
                return EngineOwnerCommit(selection, evidence)
            }
        }
    }

    /**
     * Atomically claims and commits one dormant automatic successor at its first audible frame.
     *
     * Unlike [claim], this leaves the outgoing owner as the latest request while native prepares
     * the spare bank. A manual selection, queue-policy cancellation, unload, or invalidation clears
     * the reservation and therefore wins this race. The successor becomes latest/public only when
     * callback evidence proves that every outgoing frame has already been consumed.
     */
    @Synchronized
    fun acceptReservedAtAudibleBoundary(
        expected: EngineSelectionReservation,
        expectedOutgoingToken: EngineSelectionToken,
        evidence: EngineAudibleOwnerEvidence,
        startPositionMs: Long = 0L,
        autoplay: Boolean = true
    ): EngineOwnerCommit? {
        val outgoing = latestSelection.get()?.takeIf { it.token == expectedOutgoingToken }
            ?: return null
        if (committedToken.get() != outgoing.token.value || reservation.get() != expected ||
            evidence.token != expected.token || evidence.songId != expected.identity.songId ||
            evidence.token.value <= outgoing.token.value
        ) return null
        if (!reservation.compareAndSet(expected, null)) return null

        val identity = expected.identity
        val successor = EngineSelection(
            token = expected.token,
            songId = identity.songId,
            audioRevision = identity.audioRevision,
            originalLocation = identity.originalLocation,
            durationMs = identity.durationMs,
            originalContentSha256 = identity.originalContentSha256,
            playbackStartMs = identity.playbackStartMs,
            playbackEndMs = identity.playbackEndMs,
            startPositionMs = nativeRenderablePositionMs(startPositionMs.toDouble(), identity.durationMs, identity.playbackStartMs, identity.playbackEndMs),
            autoplay = autoplay
        )
        latestSelection.set(successor)
        committedToken.set(successor.token.value)
        return EngineOwnerCommit(successor, evidence)
    }

    /**
     * Claims a reserved successor after the callback has physically adopted its ring while the
     * transport is paused, so no successor PCM exists from which to fabricate audible evidence.
     * The caller must independently prove exact native requested/accepted/committed ownership.
     */
    @Synchronized
    fun acceptReservedPausedInstalled(
        expected: EngineSelectionReservation,
        expectedOutgoingToken: EngineSelectionToken,
        startPositionMs: Long = 0L
    ): EngineSelection? {
        val outgoing = latestSelection.get()?.takeIf { it.token == expectedOutgoingToken }
            ?: return null
        if (committedToken.get() != outgoing.token.value || reservation.get() != expected ||
            expected.token.value <= outgoing.token.value
        ) return null
        if (!reservation.compareAndSet(expected, null)) return null

        val identity = expected.identity
        val successor = EngineSelection(
            token = expected.token,
            songId = identity.songId,
            audioRevision = identity.audioRevision,
            originalLocation = identity.originalLocation,
            durationMs = identity.durationMs,
            originalContentSha256 = identity.originalContentSha256,
            playbackStartMs = identity.playbackStartMs,
            playbackEndMs = identity.playbackEndMs,
            startPositionMs = nativeRenderablePositionMs(startPositionMs.toDouble(), identity.durationMs, identity.playbackStartMs, identity.playbackEndMs),
            autoplay = false
        )
        latestSelection.set(successor)
        committedToken.set(successor.token.value)
        return successor
    }

    /**
     * Commits metadata without fabricating callback evidence only after the controller has
     * independently observed a non-empty accepted fingerprint installed as the paused owner.
     */
    @Synchronized
    fun acceptPausedInstalled(
        token: EngineSelectionToken,
        songId: String
    ): EngineSelection? {
        val selection = latestSelection.get() ?: return null
        if (selection.token != token || selection.songId != songId) return null
        while (true) {
            val previousCommit = committedToken.get()
            if (token.value <= previousCommit) return null
            if (committedToken.compareAndSet(previousCommit, token.value)) return selection
        }
    }

    /** Invalidates all in-flight decoder/output work without reusing an earlier token. */
    @Synchronized
    fun invalidate() {
        allocateToken()
        reservation.set(null)
        latestSelection.set(null)
    }

    private fun allocateToken(): EngineSelectionToken = EngineSelectionToken(
        nextToken.updateAndGet { current ->
            check(current < Long.MAX_VALUE) { "Engine selection token space exhausted" }
            current + 1L
        }
    )
}
