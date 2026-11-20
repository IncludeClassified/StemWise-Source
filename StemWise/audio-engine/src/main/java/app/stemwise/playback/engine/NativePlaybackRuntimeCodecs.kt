package app.stemwise.playback.engine

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import app.stemwise.model.StemRoleContract

private fun decodeLoopPreparationFailure(token: Long, generation: Long, revision: Long, reasonCode: Long): NativeRuntimeLoopPreparationFailure? {
    val reason = NativeRuntimeLoopPreparationFailureReason.entries.firstOrNull { it.nativeCode.toLong() == reasonCode }
        ?: error("Unknown loop preparation failure reason")
    if (reason == NativeRuntimeLoopPreparationFailureReason.NONE) {
        require(token == 0L && generation == 0L && revision == 0L)
        return null
    }
    return NativeRuntimeLoopPreparationFailure(NativeRuntimeOwnership(token, generation), revision, reason)
}

internal object NativeRuntimeCommandCodec {
    // Current command layout: version; command revision/output generation; two 6-field bank intents;
    // 5-field selection; control revision/source/ramp fields; the current-capacity muted/solo pairs; metronome;
    // beat-unit denominator; and an 8-field exact automatic
    // successor intent; exact source/stem intent revisions; three stable content hashes and an exact declared stem count per bank.
    // Float schema v1: the current-capacity stem gains;
    // music/headroom; metronome BPM/gain; tempo/pitch.
    const val SCHEMA_VERSION = NativePacketSchema.COMMAND_VERSION
    const val LONG_COUNT = NativePacketSchema.COMMAND_LONG_COUNT
    const val FLOAT_COUNT = NativePacketSchema.COMMAND_FLOAT_COUNT

    fun encode(snapshot: NativeRuntimeCommandSnapshot): Pair<LongArray, FloatArray> {
        val longs = LongArray(LONG_COUNT)
        val floats = FloatArray(FLOAT_COUNT)
        longs[NativePacketSchema.COMMAND_LONG_SCHEMA] = SCHEMA_VERSION
        longs[NativePacketSchema.COMMAND_LONG_REVISION] = snapshot.revision
        longs[NativePacketSchema.COMMAND_LONG_OUTPUT_GENERATION] = snapshot.outputGeneration
        snapshot.banks.forEachIndexed { index, bank ->
            val base = NativePacketSchema.COMMAND_LONG_BANK_0_REVISION + index * 6
            longs[base] = bank.revision
            longs[base + 1] = bank.assigned.code
            longs[base + 2] = bank.ownership?.selectionToken ?: 0L
            longs[base + 3] = bank.ownership?.generation ?: 0L
            longs[base + 4] = bank.firstTimelineFrame
            longs[base + 5] = bank.outputClockStartFrame
        }
        val selected = snapshot.selectedOwnership
        longs[NativePacketSchema.COMMAND_LONG_SELECTED] = (snapshot.selectedBank != null).code
        longs[NativePacketSchema.COMMAND_LONG_SELECTED_BANK] = snapshot.selectedBank?.nativeCode?.toLong() ?: 0L
        longs[NativePacketSchema.COMMAND_LONG_SELECTED_TOKEN] = selected?.selectionToken ?: 0L
        longs[NativePacketSchema.COMMAND_LONG_SELECTED_GENERATION] = selected?.generation ?: 0L
        longs[NativePacketSchema.COMMAND_LONG_PLAYING] = snapshot.playing.code
        longs[NativePacketSchema.COMMAND_LONG_CONTROL_REVISION] = snapshot.controls.revision
        longs[NativePacketSchema.COMMAND_LONG_SOURCE_MODE] = snapshot.controls.sourceMode.nativeCode.toLong()
        longs[NativePacketSchema.COMMAND_LONG_SOURCE_RAMP] = snapshot.controls.sourceTransitionFrames.toLong()
        longs[NativePacketSchema.COMMAND_LONG_LANE_RAMP] = snapshot.controls.laneRampFrames.toLong()
        longs[NativePacketSchema.COMMAND_LONG_MASTER_RAMP] = snapshot.controls.masterRampFrames.toLong()
        longs[NativePacketSchema.COMMAND_LONG_DECLICK] = snapshot.controls.defaultDeclickFrames.toLong()
        snapshot.controls.stems.forEachIndexed { index, stem ->
            val base = NativePacketSchema.COMMAND_LONG_LANE_0_MUTED + index * 2
            longs[base] = stem.muted.code
            longs[base + 1] = stem.soloed.code
            floats[index] = stem.gain
        }
        longs[NativePacketSchema.COMMAND_LONG_METRONOME_MODE] = snapshot.controls.metronome.mode.nativeCode.toLong()
        longs[NativePacketSchema.COMMAND_LONG_METRONOME_SUBDIVISION] = snapshot.controls.metronome.subdivision.toLong()
        longs[NativePacketSchema.COMMAND_LONG_METRONOME_METER] = snapshot.controls.metronome.beatsPerBar.toLong()
        longs[NativePacketSchema.COMMAND_LONG_METRONOME_UNIT] = snapshot.controls.metronome.beatUnitDenominator.toLong()
        snapshot.automaticSuccessor?.let { successor ->
            longs[NativePacketSchema.COMMAND_LONG_SUCCESSOR_PRESENT] = 1L
            longs[NativePacketSchema.COMMAND_LONG_SUCCESSOR_REVISION] = successor.revision
            longs[NativePacketSchema.COMMAND_LONG_OUTGOING_TOKEN] = successor.outgoingOwnership.selectionToken
            longs[NativePacketSchema.COMMAND_LONG_OUTGOING_GENERATION] = successor.outgoingOwnership.generation
            longs[NativePacketSchema.COMMAND_LONG_SUCCESSOR_BANK] = successor.successorBank.nativeCode.toLong()
            longs[NativePacketSchema.COMMAND_LONG_SUCCESSOR_TOKEN] = successor.successorOwnership.selectionToken
            longs[NativePacketSchema.COMMAND_LONG_SUCCESSOR_GENERATION] = successor.successorOwnership.generation
            longs[NativePacketSchema.COMMAND_LONG_SUCCESSOR_INHERIT] = successor.inheritOutgoingControls.code
        }
        longs[NativePacketSchema.COMMAND_LONG_SOURCE_INTENT] = snapshot.controls.sourceIntentRevision
        longs[NativePacketSchema.COMMAND_LONG_STEM_INTENT] = snapshot.controls.stemIntentRevision
        snapshot.banks.forEachIndexed { index, bank ->
            val base = NativePacketSchema.COMMAND_LONG_BANK_0_SONG_HASH + index * 3
            longs[base] = bank.songIdentityHash
            longs[base + 1] = bank.originalContentHash
            longs[base + 2] = bank.stemPackageHash
            longs[NativePacketSchema.COMMAND_LONG_BANK_0_STEM_COUNT + index] = bank.stemLaneCount.toLong()
            longs[NativePacketSchema.COMMAND_LONG_BANK_0_CACHE_BOUND + index] = bank.cacheFrameUpperBound
            longs[NativePacketSchema.COMMAND_LONG_BANK_0_PLAYBACK_END + index] = bank.playbackEndFrameExclusive
        }
        floats[NativePacketSchema.COMMAND_FLOAT_MUSIC_GAIN] = snapshot.controls.musicGain
        floats[NativePacketSchema.COMMAND_FLOAT_HEADROOM] = snapshot.controls.mixHeadroom
        floats[NativePacketSchema.COMMAND_FLOAT_METRONOME_BPM] = snapshot.controls.metronome.bpm
        floats[NativePacketSchema.COMMAND_FLOAT_METRONOME_GAIN] = snapshot.controls.metronome.gain
        floats[NativePacketSchema.COMMAND_FLOAT_TEMPO] = snapshot.controls.tempo
        floats[NativePacketSchema.COMMAND_FLOAT_PITCH] = snapshot.controls.pitchSemitones
        return longs to floats
    }

    private val Boolean.code: Long get() = if (this) 1L else 0L
}

internal object NativeRuntimeBeatGridCodec {
    /** JNI beat-grid schema v3 requires one explicit 0/1 accent flag per base beat. */
    const val SCHEMA_VERSION = 3L
}

internal object NativeRuntimeManagedLoopIntentCodec {
    /** Managed Repeat One uses decoded Original EOS; direct A/B has a separate atomic ABI. */
    const val SCHEMA_VERSION = NativePacketSchema.LOOP_INTENT_VERSION
    const val VALUE_COUNT = NativePacketSchema.LOOP_INTENT_COUNT

    fun encode(intent: NativeRuntimeManagedLoopIntent): LongArray = LongArray(VALUE_COUNT).also {
        values ->
        values[NativePacketSchema.LOOP_INTENT_SCHEMA] = SCHEMA_VERSION
        values[NativePacketSchema.LOOP_INTENT_REVISION] = intent.revision
        values[NativePacketSchema.LOOP_INTENT_ENABLED] = if (intent.enabled) 1L else 0L
        values[NativePacketSchema.LOOP_INTENT_TOKEN] = intent.ownership.selectionToken
        values[NativePacketSchema.LOOP_INTENT_GENERATION] = intent.ownership.generation
        if (intent.enabled) {
            val key = requireNotNull(intent.cacheKey)
            values[NativePacketSchema.LOOP_INTENT_SONG_HASH] = key.songIdentityHash
            values[NativePacketSchema.LOOP_INTENT_ORIGINAL_HASH] = key.originalContentHash
            values[NativePacketSchema.LOOP_INTENT_STEM_HASH] = key.stemPackageHash
            values[NativePacketSchema.LOOP_INTENT_CONTENT_REVISION] = key.contentRevision
            values[NativePacketSchema.LOOP_INTENT_LOOP_REVISION] = key.loopRevision
            values[NativePacketSchema.LOOP_INTENT_START_FRAME] = key.startTimelineFrame
            values[NativePacketSchema.LOOP_INTENT_END_FRAME] = key.endTimelineFrameExclusive
            values[NativePacketSchema.LOOP_INTENT_REQUIRED_LANE_MASK] = intent.requiredLaneMask.toLong()
            values[NativePacketSchema.LOOP_INTENT_DECLICK_FRAMES] = intent.declickFrames.toLong()
            values[NativePacketSchema.LOOP_INTENT_BOUNDARY_MODE] = intent.boundaryMode.nativeCode.toLong()
        }
    }
}

internal object NativeRuntimeManagedLoopIngressReadyCodec {
    const val SCHEMA_VERSION = NativePacketSchema.LOOP_INGRESS_READY_VERSION
    const val VALUE_COUNT = NativePacketSchema.LOOP_INGRESS_READY_COUNT

    fun encode(evidence: NativeRuntimeManagedLoopIngressReady) = LongArray(VALUE_COUNT).apply {
        this[NativePacketSchema.LOOP_INGRESS_READY_SCHEMA] = SCHEMA_VERSION
        this[NativePacketSchema.LOOP_INGRESS_READY_REQUEST_SEQUENCE] = evidence.requestSequence
        this[NativePacketSchema.LOOP_INGRESS_READY_LOOP_REVISION] = evidence.loopRevision
        this[NativePacketSchema.LOOP_INGRESS_READY_TARGET_CYCLE] = evidence.targetCycle
        this[NativePacketSchema.LOOP_INGRESS_READY_TOKEN] = evidence.ownership.selectionToken
        this[NativePacketSchema.LOOP_INGRESS_READY_GENERATION] = evidence.ownership.generation
        this[NativePacketSchema.LOOP_INGRESS_READY_INGRESS_EPOCH] = evidence.ingressEpoch
        this[NativePacketSchema.LOOP_INGRESS_READY_READY_LANE_MASK] = evidence.readyLaneMask.toLong()
        this[NativePacketSchema.LOOP_INGRESS_READY_EOS_PADDED_LANE_MASK] = evidence.eosPaddedLaneMask.toLong()
        this[NativePacketSchema.LOOP_INGRESS_READY_FIRST_FRAME] = evidence.firstTimelineFrame
        this[NativePacketSchema.LOOP_INGRESS_READY_END_FRAME] = evidence.endTimelineFrameExclusive
    }
}

internal object NativeRuntimeLoopProtocolCodec {
    const val SCHEMA_VERSION = NativePacketSchema.LOOP_PROTOCOL_VERSION
    const val VALUE_COUNT = NativePacketSchema.LOOP_PROTOCOL_COUNT

    fun decode(values: LongArray): NativeRuntimeLoopProtocolSnapshot {
        require(values.size == VALUE_COUNT) {
            "Native loop protocol snapshot must contain $VALUE_COUNT values"
        }
        require(values[NativePacketSchema.LOOP_PROTOCOL_SCHEMA] == SCHEMA_VERSION) {
            "Unsupported native loop protocol schema ${values[NativePacketSchema.LOOP_PROTOCOL_SCHEMA]}"
        }
        fun nonNegative(index: Int, label: String): Long = values[index].also {
            require(it >= 0L) { "$label cannot be negative" }
        }
        fun positive(index: Int, label: String): Long = values[index].also {
            require(it > 0L) { "$label must be positive" }
        }
        fun boolean(index: Int, label: String): Boolean = when (values[index]) {
            0L -> false
            1L -> true
            else -> error("$label must be encoded as zero or one")
        }
        fun laneMask(index: Int): Int = nonNegative(index, "lane mask").also {
            require(it <= NativeRuntimeManagedLoopIntent.ALL_LANES_MASK.toLong())
        }.nativePacketInt()
        fun ownership(tokenIndex: Int, generationIndex: Int): NativeRuntimeOwnership? {
            val token = nonNegative(tokenIndex, "loop owner token")
            val generation = nonNegative(generationIndex, "loop owner generation")
            if (token == 0L && generation == 0L) return null
            return NativeRuntimeOwnership(token, generation)
        }
        fun requiredOwnership(tokenIndex: Int, generationIndex: Int) =
            requireNotNull(ownership(tokenIndex, generationIndex))

        val state = NativeRuntimeLoopCycleState(
            mode = NativeRuntimeLoopCycleMode.fromNative(values[NativePacketSchema.LOOP_PROTOCOL_STATE_MODE].nativePacketInt()),
            phase = NativeRuntimeLoopCyclePhase.fromNative(values[NativePacketSchema.LOOP_PROTOCOL_STATE_PHASE].nativePacketInt()),
            activation = NativeRuntimeLoopCycleActivation.fromNative(values[NativePacketSchema.LOOP_PROTOCOL_STATE_ACTIVATION].nativePacketInt()),
            latestRevision = nonNegative(NativePacketSchema.LOOP_PROTOCOL_STATE_LATEST_REVISION, "latest loop revision"),
            ownership = ownership(NativePacketSchema.LOOP_PROTOCOL_STATE_OWNER_SELECTION_TOKEN, NativePacketSchema.LOOP_PROTOCOL_STATE_OWNER_RENDER_GENERATION),
            renderCycle = nonNegative(NativePacketSchema.LOOP_PROTOCOL_STATE_RENDER_CYCLE, "render cycle"),
            activeIngressEpoch = nonNegative(NativePacketSchema.LOOP_PROTOCOL_STATE_ACTIVE_INGRESS_EPOCH, "active ingress epoch"),
            preparedIngressEpoch = nonNegative(NativePacketSchema.LOOP_PROTOCOL_STATE_PREPARED_INGRESS_EPOCH, "prepared ingress epoch"),
            cacheChecksum = values[NativePacketSchema.LOOP_PROTOCOL_STATE_CACHE_CHECKSUM],
            cacheAccepted = boolean(NativePacketSchema.LOOP_PROTOCOL_STATE_CACHE_ACCEPTED, "cache accepted"),
            renderInstalled = boolean(NativePacketSchema.LOOP_PROTOCOL_STATE_RENDER_INSTALLED, "render installed"),
            renderActive = boolean(NativePacketSchema.LOOP_PROTOCOL_STATE_RENDER_ACTIVE, "render active"),
            audiblyProven = boolean(NativePacketSchema.LOOP_PROTOCOL_STATE_AUDIBLY_PROVEN, "audibly proven"),
            ingressRequestPending = boolean(NativePacketSchema.LOOP_PROTOCOL_STATE_INGRESS_REQUEST_PENDING, "ingress request pending"),
            renderDirectivePending = boolean(NativePacketSchema.LOOP_PROTOCOL_STATE_RENDER_DIRECTIVE_PENDING, "render directive pending"),
            boundaryMode = NativeRuntimeManagedLoopBoundaryMode.fromNative(values[NativePacketSchema.LOOP_PROTOCOL_STATE_BOUNDARY_MODE].nativePacketInt()),
            boundaryResolved = boolean(NativePacketSchema.LOOP_PROTOCOL_STATE_BOUNDARY_RESOLVED, "loop boundary resolved"),
            resolvedLoopEndTimelineFrameExclusive = nonNegative(NativePacketSchema.LOOP_PROTOCOL_STATE_RESOLVED_LOOP_END_TIMELINE_FRAME_EXCLUSIVE, "resolved loop end")
        )
        val request = if (boolean(NativePacketSchema.LOOP_PROTOCOL_REQUEST_VALID, "ingress request valid")) {
            NativeRuntimeLoopIngressRequest(
                sequence = positive(NativePacketSchema.LOOP_PROTOCOL_REQUEST_SEQUENCE, "ingress request sequence"),
                loopRevision = positive(NativePacketSchema.LOOP_PROTOCOL_REQUEST_LOOP_REVISION, "ingress request revision"),
                targetCycle = positive(NativePacketSchema.LOOP_PROTOCOL_REQUEST_TARGET_CYCLE, "ingress request target cycle"),
                ownership = requiredOwnership(NativePacketSchema.LOOP_PROTOCOL_REQUEST_OWNER_SELECTION_TOKEN, NativePacketSchema.LOOP_PROTOCOL_REQUEST_OWNER_RENDER_GENERATION),
                ingressEpoch = positive(NativePacketSchema.LOOP_PROTOCOL_REQUEST_INGRESS_EPOCH, "ingress request epoch"),
                targetTimelineFrame = nonNegative(NativePacketSchema.LOOP_PROTOCOL_REQUEST_TARGET_TIMELINE_FRAME, "ingress request timeline"),
                requiredLaneMask = laneMask(NativePacketSchema.LOOP_PROTOCOL_REQUEST_REQUIRED_LANE_MASK),
                activation = NativeRuntimeLoopCycleActivation.fromNative(values[NativePacketSchema.LOOP_PROTOCOL_REQUEST_ACTIVATION].nativePacketInt()),
                requiredEndTimelineFrameExclusive =
                    nonNegative(NativePacketSchema.LOOP_PROTOCOL_REQUEST_REQUIRED_END_TIMELINE_FRAME_EXCLUSIVE, "ingress request required end")
            )
        } else {
            null
        }
        val directive = if (boolean(NativePacketSchema.LOOP_PROTOCOL_DIRECTIVE_VALID, "render directive valid")) {
            val action = NativeRuntimeLoopDirectiveAction.fromNative(values[NativePacketSchema.LOOP_PROTOCOL_DIRECTIVE_ACTION].nativePacketInt())
            NativeRuntimeLoopRenderDirective(
                sequence = positive(NativePacketSchema.LOOP_PROTOCOL_DIRECTIVE_SEQUENCE, "render directive sequence"),
                action = action,
                loopRevision = positive(NativePacketSchema.LOOP_PROTOCOL_DIRECTIVE_LOOP_REVISION, "render directive revision"),
                cycle = nonNegative(NativePacketSchema.LOOP_PROTOCOL_DIRECTIVE_CYCLE, "render directive cycle"),
                ownership = requiredOwnership(NativePacketSchema.LOOP_PROTOCOL_DIRECTIVE_OWNER_SELECTION_TOKEN, NativePacketSchema.LOOP_PROTOCOL_DIRECTIVE_OWNER_RENDER_GENERATION),
                // Clear retires physical render policy before a rebound owner has adopted an
                // ingress epoch. Every other directive remains strictly positive.
                ingressEpoch = if (action == NativeRuntimeLoopDirectiveAction.CLEAR) {
                    nonNegative(NativePacketSchema.LOOP_PROTOCOL_DIRECTIVE_INGRESS_EPOCH, "clear directive epoch")
                } else {
                    positive(NativePacketSchema.LOOP_PROTOCOL_DIRECTIVE_INGRESS_EPOCH, "render directive epoch")
                },
                startTimelineFrame = nonNegative(NativePacketSchema.LOOP_PROTOCOL_DIRECTIVE_START_TIMELINE_FRAME, "render directive start"),
                endTimelineFrameExclusive = nonNegative(NativePacketSchema.LOOP_PROTOCOL_DIRECTIVE_END_TIMELINE_FRAME_EXCLUSIVE, "render directive end"),
                headEndTimelineFrameExclusive = nonNegative(NativePacketSchema.LOOP_PROTOCOL_DIRECTIVE_HEAD_END_TIMELINE_FRAME_EXCLUSIVE, "render directive head end"),
                cacheChecksum = values[NativePacketSchema.LOOP_PROTOCOL_DIRECTIVE_CACHE_CHECKSUM],
                requiredLaneMask = laneMask(NativePacketSchema.LOOP_PROTOCOL_DIRECTIVE_REQUIRED_LANE_MASK),
                declickFrames = nonNegative(NativePacketSchema.LOOP_PROTOCOL_DIRECTIVE_DECLICK_FRAMES, "render directive declick").nativePacketInt(),
                boundaryMode =
                    NativeRuntimeManagedLoopBoundaryMode.fromNative(values[NativePacketSchema.LOOP_PROTOCOL_DIRECTIVE_BOUNDARY_MODE].nativePacketInt())
            )
        } else {
            null
        }
        val audible = if (boolean(NativePacketSchema.LOOP_PROTOCOL_AUDIBLE_VALID, "audible loop evidence valid")) {
            NativeRuntimeLoopAudibleEvidence(
                sequence = positive(NativePacketSchema.LOOP_PROTOCOL_AUDIBLE_SEQUENCE, "audible loop evidence sequence"),
                loopRevision = positive(NativePacketSchema.LOOP_PROTOCOL_AUDIBLE_LOOP_REVISION, "audible loop evidence revision"),
                cycle = nonNegative(NativePacketSchema.LOOP_PROTOCOL_AUDIBLE_CYCLE, "audible loop cycle"),
                ownership = requiredOwnership(NativePacketSchema.LOOP_PROTOCOL_AUDIBLE_OWNER_SELECTION_TOKEN, NativePacketSchema.LOOP_PROTOCOL_AUDIBLE_OWNER_RENDER_GENERATION),
                callbackSequence = positive(NativePacketSchema.LOOP_PROTOCOL_AUDIBLE_CALLBACK_SEQUENCE, "audible loop callback")
            )
        } else {
            null
        }
        val cache = if (boolean(NativePacketSchema.LOOP_PROTOCOL_CACHE_VALID, "active loop cache valid")) {
            val key = NativeRuntimeManagedLoopContentKey(
                songIdentityHash = values[NativePacketSchema.LOOP_PROTOCOL_CACHE_KEY_SONG_IDENTITY_HASH],
                originalContentHash = values[NativePacketSchema.LOOP_PROTOCOL_CACHE_KEY_ORIGINAL_CONTENT_HASH],
                stemPackageHash = values[NativePacketSchema.LOOP_PROTOCOL_CACHE_KEY_STEM_PACKAGE_HASH],
                contentRevision = positive(NativePacketSchema.LOOP_PROTOCOL_CACHE_KEY_CONTENT_REVISION, "cache content revision"),
                loopRevision = positive(NativePacketSchema.LOOP_PROTOCOL_CACHE_KEY_LOOP_REVISION, "cache loop revision"),
                startTimelineFrame = nonNegative(NativePacketSchema.LOOP_PROTOCOL_CACHE_KEY_START_TIMELINE_FRAME, "cache start"),
                endTimelineFrameExclusive = nonNegative(NativePacketSchema.LOOP_PROTOCOL_CACHE_KEY_END_TIMELINE_FRAME_EXCLUSIVE, "cache end")
            )
            NativeRuntimeLoopHeadCacheSnapshot(
                slotIndex = nonNegative(NativePacketSchema.LOOP_PROTOCOL_CACHE_SLOT_INDEX, "cache slot").nativePacketInt(),
                key = key,
                requiredLaneMask = laneMask(NativePacketSchema.LOOP_PROTOCOL_CACHE_REQUIRED_LANE_MASK),
                completedLaneMask = laneMask(NativePacketSchema.LOOP_PROTOCOL_CACHE_COMPLETED_LANE_MASK),
                frameCount = nonNegative(NativePacketSchema.LOOP_PROTOCOL_CACHE_FRAME_COUNT, "cache frames").nativePacketInt(),
                cacheOnly = boolean(NativePacketSchema.LOOP_PROTOCOL_CACHE_CACHE_ONLY, "cache-only"),
                combinedChecksum = values[NativePacketSchema.LOOP_PROTOCOL_CACHE_COMBINED_CHECKSUM],
                laneChecksums = LongArray(NativeSongLaneIngressBank.LANE_COUNT) { lane -> values[NativePacketSchema.LOOP_PROTOCOL_LANE_CHECKSUM_BASE + lane] },
                boundaryMode =
                    NativeRuntimeManagedLoopBoundaryMode.fromNative(values[NativePacketSchema.LOOP_PROTOCOL_CACHE_BOUNDARY_MODE].nativePacketInt()),
                originalEndResolved = boolean(NativePacketSchema.LOOP_PROTOCOL_CACHE_ORIGINAL_END_RESOLVED, "cache Original end resolved"),
                resolvedOriginalEndTimelineFrameExclusive =
                    nonNegative(NativePacketSchema.LOOP_PROTOCOL_CACHE_RESOLVED_ORIGINAL_END_TIMELINE_FRAME_EXCLUSIVE, "cache resolved Original end")
            )
        } else {
            null
        }
        val preparation = NativeRuntimeLoopHeadPreparationSnapshot(
            phase = NativeRuntimeLoopHeadPreparationPhase.fromNative(values[NativePacketSchema.LOOP_PROTOCOL_PREPARATION_PHASE].nativePacketInt()),
            loopRevision = nonNegative(NativePacketSchema.LOOP_PROTOCOL_PREPARATION_KEY_LOOP_REVISION, "preparation loop revision"),
            requiredLaneMask = laneMask(NativePacketSchema.LOOP_PROTOCOL_PREPARATION_REQUIRED_LANE_MASK),
            completedLaneMask = laneMask(NativePacketSchema.LOOP_PROTOCOL_PREPARATION_COMPLETED_LANE_MASK),
            activeLane = nonNegative(NativePacketSchema.LOOP_PROTOCOL_PREPARATION_ACTIVE_LANE, "preparation active lane").also {
                require(it <= NativeSongLaneIngressBank.LANE_COUNT.toLong()) {
                    "preparation active lane exceeds native lane capacity"
                }
            }.nativePacketInt(),
            activeSourceRateHz = nonNegative(NativePacketSchema.LOOP_PROTOCOL_PREPARATION_ACTIVE_SOURCE_RATE_HZ, "preparation source rate").nativePacketInt(),
            activePreparationGeneration = nonNegative(NativePacketSchema.LOOP_PROTOCOL_PREPARATION_ACTIVE_PREPARATION_GENERATION, "preparation generation"),
            nextExpectedTimelineFrame = nonNegative(NativePacketSchema.LOOP_PROTOCOL_PREPARATION_NEXT_EXPECTED_TIMELINE_FRAME, "preparation timeline"),
            beginCalls = nonNegative(NativePacketSchema.LOOP_PROTOCOL_PREPARATION_BEGIN_CALLS, "preparation begin calls"),
            configuredLanes = nonNegative(NativePacketSchema.LOOP_PROTOCOL_PREPARATION_CONFIGURED_LANES, "preparation configured lanes"),
            offerCalls = nonNegative(NativePacketSchema.LOOP_PROTOCOL_PREPARATION_OFFER_CALLS, "preparation offer calls"),
            inputFramesAccepted = nonNegative(NativePacketSchema.LOOP_PROTOCOL_PREPARATION_INPUT_FRAMES_ACCEPTED, "preparation accepted frames"),
            cacheFramesWritten = nonNegative(NativePacketSchema.LOOP_PROTOCOL_PREPARATION_CACHE_FRAMES_WRITTEN, "preparation cache frames"),
            lanesCompleted = nonNegative(NativePacketSchema.LOOP_PROTOCOL_PREPARATION_LANES_COMPLETED, "preparation completed lanes"),
            commits = nonNegative(NativePacketSchema.LOOP_PROTOCOL_PREPARATION_COMMITS, "preparation commits"),
            failures = nonNegative(NativePacketSchema.LOOP_PROTOCOL_PREPARATION_FAILURES, "preparation failures"),
            aborts = nonNegative(NativePacketSchema.LOOP_PROTOCOL_PREPARATION_ABORTS, "preparation aborts")
        )
        val counters = NativeRuntimeLoopProtocolCounters(
            // BEGIN GENERATED loop-counters
            acceptedIntents = nonNegative(NativePacketSchema.LOOP_PROTOCOL_ACCEPTED_INTENTS, "accepted loop intents"),
            unchangedIntents = nonNegative(NativePacketSchema.LOOP_PROTOCOL_UNCHANGED_INTENTS, "unchanged loop intents"),
            ownershipRebinds = nonNegative(NativePacketSchema.LOOP_PROTOCOL_OWNERSHIP_REBINDS, "loop ownership rebinds"),
            staleIntents = nonNegative(NativePacketSchema.LOOP_PROTOCOL_STALE_INTENTS, "stale loop intents"),
            revisionCollisions = nonNegative(NativePacketSchema.LOOP_PROTOCOL_REVISION_COLLISIONS, "loop revision collisions"),
            cachesAccepted = nonNegative(NativePacketSchema.LOOP_PROTOCOL_CACHES_ACCEPTED, "accepted loop caches"),
            cacheRejections = nonNegative(NativePacketSchema.LOOP_PROTOCOL_CACHE_REJECTIONS, "loop cache rejections"),
            ingressRequests = nonNegative(NativePacketSchema.LOOP_PROTOCOL_INGRESS_REQUESTS, "loop ingress requests"),
            ingressAcks = nonNegative(NativePacketSchema.LOOP_PROTOCOL_INGRESS_ACKS, "loop ingress acknowledgements"),
            ingressAckRejections = nonNegative(NativePacketSchema.LOOP_PROTOCOL_INGRESS_ACK_REJECTIONS, "loop ingress acknowledgement rejections"),
            positionObservations = nonNegative(NativePacketSchema.LOOP_PROTOCOL_POSITION_OBSERVATIONS, "loop position observations"),
            rejectedPositionObservations = nonNegative(NativePacketSchema.LOOP_PROTOCOL_REJECTED_POSITION_OBSERVATIONS, "rejected loop positions"),
            boundaryEvents = nonNegative(NativePacketSchema.LOOP_PROTOCOL_BOUNDARY_EVENTS, "loop boundary events"),
            rejectedBoundaryEvents = nonNegative(NativePacketSchema.LOOP_PROTOCOL_REJECTED_BOUNDARY_EVENTS, "rejected loop boundaries"),
            renderDirectives = nonNegative(NativePacketSchema.LOOP_PROTOCOL_RENDER_DIRECTIVES, "loop render directives"),
            renderCommits = nonNegative(NativePacketSchema.LOOP_PROTOCOL_RENDER_COMMITS, "loop render commits"),
            cacheOnlyWraps = nonNegative(NativePacketSchema.LOOP_PROTOCOL_CACHE_ONLY_WRAPS, "cache-only wraps"),
            streamedWraps = nonNegative(NativePacketSchema.LOOP_PROTOCOL_STREAMED_WRAPS, "streamed wraps"),
            deadlineFallbacks = nonNegative(NativePacketSchema.LOOP_PROTOCOL_DEADLINE_FALLBACKS, "loop deadline fallbacks"),
            callbackProofs = nonNegative(NativePacketSchema.LOOP_PROTOCOL_CALLBACK_PROOFS, "loop callback proofs"),
            rejectedCallbackProofs = nonNegative(NativePacketSchema.LOOP_PROTOCOL_REJECTED_CALLBACK_PROOFS, "rejected loop callback proofs"),
            originalEosAccepted = nonNegative(NativePacketSchema.LOOP_PROTOCOL_ORIGINAL_EOS_ACCEPTED, "accepted Original EOS boundaries"),
            originalEosRejected = nonNegative(NativePacketSchema.LOOP_PROTOCOL_ORIGINAL_EOS_REJECTED, "rejected Original EOS boundaries"),
            // END GENERATED loop-counters
        )
        return NativeRuntimeLoopProtocolSnapshot(
            adopted = boolean(NativePacketSchema.LOOP_PROTOCOL_ADOPTED, "managed loop adopted"),
            bank = NativeRuntimeBank.fromNative(values[NativePacketSchema.LOOP_PROTOCOL_BANK].nativePacketInt()),
            state = state,
            ingressRequest = request,
            renderDirective = directive,
            audibleEvidence = audible,
            activeCache = cache,
            preparation = preparation,
            counters = counters,
            lastSubmitStatus = nonNegative(NativePacketSchema.LOOP_PROTOCOL_LAST_SUBMIT_STATUS, "last loop submit status").nativePacketInt().also { require(NativePacketSchema.isLoopSubmitStatus(it.toLong())) },
            lastCacheStatus = nonNegative(NativePacketSchema.LOOP_PROTOCOL_LAST_CACHE_STATUS, "last loop cache status").nativePacketInt().also { require(NativePacketSchema.isLoopCacheStatus(it.toLong())) },
            lastIngressStatus = nonNegative(NativePacketSchema.LOOP_PROTOCOL_LAST_INGRESS_STATUS, "last loop ingress status").nativePacketInt().also { require(NativePacketSchema.isLoopIngressStatus(it.toLong())) },
            lastPositionStatus = nonNegative(NativePacketSchema.LOOP_PROTOCOL_LAST_POSITION_STATUS, "last loop position status").nativePacketInt().also { require(NativePacketSchema.isLoopPositionStatus(it.toLong())) },
            lastBoundaryStatus = nonNegative(NativePacketSchema.LOOP_PROTOCOL_LAST_BOUNDARY_STATUS, "last loop boundary status").nativePacketInt().also { require(NativePacketSchema.isLoopBoundaryStatus(it.toLong())) },
            lastRenderCommitStatus = nonNegative(NativePacketSchema.LOOP_PROTOCOL_LAST_RENDER_COMMIT_STATUS, "last loop render status").nativePacketInt().also { require(NativePacketSchema.isLoopRenderCommitStatus(it.toLong())) },
            lastCallbackStatus = nonNegative(NativePacketSchema.LOOP_PROTOCOL_LAST_CALLBACK_STATUS, "last loop callback status").nativePacketInt().also { require(NativePacketSchema.isLoopCallbackStatus(it.toLong())) },
            positionEvidenceSequence = nonNegative(NativePacketSchema.LOOP_PROTOCOL_POSITION_EVIDENCE_SEQUENCE, "loop position sequence"),
            lastOutputLoopEvidenceSequence = nonNegative(NativePacketSchema.LOOP_PROTOCOL_LAST_OUTPUT_LOOP_EVIDENCE_SEQUENCE, "output loop sequence"),
            lastOriginalEosStatus = nonNegative(NativePacketSchema.LOOP_PROTOCOL_LAST_ORIGINAL_EOS_STATUS, "last Original EOS status").nativePacketInt().also { require(NativePacketSchema.isLoopOriginalEosStatus(it.toLong())) }
        )
    }
}

internal object NativeRuntimeAudibleControlCodec {
    const val SCHEMA_VERSION = NativePacketSchema.AUDIBLE_CONTROL_VERSION
    const val VALUE_COUNT = NativePacketSchema.AUDIBLE_CONTROL_COUNT

    fun decode(values: LongArray): NativeRuntimeAudibleControlEvidence? {
        require(values.size == VALUE_COUNT && values[NativePacketSchema.AUDIBLE_CONTROL_SCHEMA] == SCHEMA_VERSION) {
            "Unsupported audible-control evidence schema"
        }
        require(values[NativePacketSchema.AUDIBLE_CONTROL_SEQUENCE] >= 0L)
        if (values[NativePacketSchema.AUDIBLE_CONTROL_SEQUENCE] == 0L) return null
        require(values[NativePacketSchema.AUDIBLE_CONTROL_ACTUAL_SOURCE_MODE] == 0L || values[NativePacketSchema.AUDIBLE_CONTROL_ACTUAL_SOURCE_MODE] == 1L) { "Invalid audible source mode" }
        require(values[NativePacketSchema.AUDIBLE_CONTROL_CONTROL_REVISION] > 0L)
        for (index in NativePacketSchema.AUDIBLE_CONTROL_FIRST_FINGERPRINT..NativePacketSchema.AUDIBLE_CONTROL_PCM_FRAMES) require(values[index] > 0L)
        return NativeRuntimeAudibleControlEvidence(
            sequence = values[NativePacketSchema.AUDIBLE_CONTROL_SEQUENCE],
            ownership = NativeRuntimeOwnership(values[NativePacketSchema.AUDIBLE_CONTROL_TOKEN], values[NativePacketSchema.AUDIBLE_CONTROL_GENERATION]),
            controlRevision = values[NativePacketSchema.AUDIBLE_CONTROL_CONTROL_REVISION],
            actualSourceMode = NativeRuntimeSourceMode.fromNative(values[NativePacketSchema.AUDIBLE_CONTROL_ACTUAL_SOURCE_MODE].nativePacketInt()),
            firstFingerprintSequence = values[NativePacketSchema.AUDIBLE_CONTROL_FIRST_FINGERPRINT],
            lastFingerprintSequence = values[NativePacketSchema.AUDIBLE_CONTROL_LAST_FINGERPRINT],
            callbackSequence = values[NativePacketSchema.AUDIBLE_CONTROL_CALLBACK_SEQUENCE],
            pcmFrames = values[NativePacketSchema.AUDIBLE_CONTROL_PCM_FRAMES],
            actualTempo = values[NativePacketSchema.AUDIBLE_CONTROL_ACTUAL_TEMPO_BITS].nativePacketFloat(),
            actualPitchSemitones = values[NativePacketSchema.AUDIBLE_CONTROL_ACTUAL_PITCH_SEMITONES_BITS].nativePacketFloat()
        )
    }
}

/** Reads shared control fields in either generated layout without expanding a compact packet. */
internal class RuntimeControlPacketReader(
    private val values: LongArray,
    private val indices: IntArray? = null
) {
    operator fun get(field: Int): Long {
        val index = indices?.get(field) ?: field
        require(index >= 0) { "Field is not present in compact control progress" }
        return values[index]
    }
}

internal object NativeRuntimeControlProgressCodec {
    fun decode(values: LongArray): NativeRuntimeControlProgress {
        require(values.size == NativePacketSchema.CONTROL_PROGRESS_COUNT &&
            values[NativePacketSchema.CONTROL_PROGRESS_SCHEMA] == NativePacketSchema.CONTROL_PROGRESS_VERSION) {
            "Unsupported native control progress packet"
        }
        val reader = RuntimeControlPacketReader(values, NativePacketSchema.CONTROL_PROGRESS_RUNTIME_INDEX)
        val banks = List(2) { bank ->
            val base = NativePacketSchema.CONTROL_PROGRESS_BANK_BASE + bank * NativePacketSchema.CONTROL_PROGRESS_BANK_STRIDE
            NativeRuntimeBankControlProgress(values[base].nonNegative("bank revision"),
                values[base + NativePacketSchema.CONTROL_PROGRESS_BANK_FIELD_ASSIGNED].nativePacketBoolean())
        }
        fun number(field: Int) = values[field].nonNegative("readiness progress")
        fun flag(field: Int) = values[field].nativePacketBoolean()
        val token = number(NativePacketSchema.CONTROL_PROGRESS_READINESS_TOKEN)
        val generation = number(NativePacketSchema.CONTROL_PROGRESS_READINESS_GENERATION)
        val assigned = flag(NativePacketSchema.CONTROL_PROGRESS_READINESS_ASSIGNED)
        val ownership = if (token == 0L && generation == 0L) null else NativeRuntimeOwnership(token, generation)
        require(!assigned || ownership != null)
        val mask = number(NativePacketSchema.CONTROL_PROGRESS_READINESS_COMPLETE_LANE_MASK).also {
            require(it <= NativeRuntimeManagedLoopIntent.ALL_LANES_MASK)
        }.nativePacketInt()
        val required = number(NativePacketSchema.CONTROL_PROGRESS_READINESS_REQUIRED_LANE_MASK).also {
            require(it <= NativeRuntimeManagedLoopIntent.ALL_LANES_MASK)
        }.nativePacketInt()
        val failureLane = values[NativePacketSchema.CONTROL_PROGRESS_READINESS_FAILURE_LANE].also { require(it in -1L..app.stemwise.model.StemRoleContract.maximumStemCount.toLong()) }.nativePacketInt()
        val failureGeneration = number(NativePacketSchema.CONTROL_PROGRESS_READINESS_FAILURE_GENERATION)
        require((failureLane == -1) == (failureGeneration == 0L))
        val readiness = NativeRuntimeReadinessProgress(
            number(NativePacketSchema.CONTROL_PROGRESS_READINESS_SEQUENCE), assigned, ownership,
            number(NativePacketSchema.CONTROL_PROGRESS_READINESS_CONTROL_REVISION),
            flag(NativePacketSchema.CONTROL_PROGRESS_READINESS_CACHE_BOUND), flag(NativePacketSchema.CONTROL_PROGRESS_READINESS_CACHE_CAN_GROW),
            mask, required,
            NativeCacheStorageStatus.entries.firstOrNull {
                it.nativeCode.toLong() == number(NativePacketSchema.CONTROL_PROGRESS_READINESS_STORAGE_STATUS)
            } ?: error("Unknown compact cache storage status"),
            flag(NativePacketSchema.CONTROL_PROGRESS_READINESS_FIXED_LOOP_HEAD_PREPARED),
            number(NativePacketSchema.CONTROL_PROGRESS_READINESS_DIRECT_ATOMIC_POLICY_GENERATION), failureLane, failureGeneration,
            decodeLoopPreparationFailure(values[NativePacketSchema.CONTROL_PROGRESS_READINESS_LOOP_FAILURE_TOKEN],
                values[NativePacketSchema.CONTROL_PROGRESS_READINESS_LOOP_FAILURE_GENERATION],
                values[NativePacketSchema.CONTROL_PROGRESS_READINESS_LOOP_FAILURE_REVISION],
                values[NativePacketSchema.CONTROL_PROGRESS_READINESS_LOOP_FAILURE_REASON]))
        return decodeFields(reader, banks, readiness)
    }

    fun decodeFields(values: RuntimeControlPacketReader, banks: List<NativeRuntimeBankProgress>,
        readiness: NativeRuntimeReadinessProgress? = null): NativeRuntimeControlProgress {
        fun counter(index: Int): Long = values[index].also { require(it >= 0L) }
        fun ownership(tokenIndex: Int, generationIndex: Int): NativeRuntimeOwnership? {
            val token = values[tokenIndex]
            val generation = values[generationIndex]
            if (token == 0L && generation == 0L) return null
            return NativeRuntimeOwnership(token, generation)
        }
        fun requiredOwnership(tokenIndex: Int, generationIndex: Int) =
            requireNotNull(ownership(tokenIndex, generationIndex))
        val presentation = values[NativePacketSchema.RUNTIME_DIAGNOSTICS_PRESENTATION_SEQUENCE].takeIf { it != 0L }?.let { sequence ->
            NativeRuntimePresentationEvidence(
                sequence = sequence,
                ownership = requiredOwnership(NativePacketSchema.RUNTIME_DIAGNOSTICS_PRESENTATION_SELECTION_TOKEN, NativePacketSchema.RUNTIME_DIAGNOSTICS_PRESENTATION_GENERATION),
                fingerprintSequence = values[NativePacketSchema.RUNTIME_DIAGNOSTICS_PRESENTATION_FINGERPRINT_SEQUENCE].positive("presentation fingerprint"),
                firstTimelineFrame = values[NativePacketSchema.RUNTIME_DIAGNOSTICS_PRESENTATION_FIRST_TIMELINE_FRAME].nonNegative("presentation timeline"),
                firstOutputFrame = values[NativePacketSchema.RUNTIME_DIAGNOSTICS_PRESENTATION_FIRST_OUTPUT_FRAME].nonNegative("presentation output frame"),
                callbackSequence = values[NativePacketSchema.RUNTIME_DIAGNOSTICS_PRESENTATION_CALLBACK_SEQUENCE].positive("presentation callback"),
                pcmFrames = values[NativePacketSchema.RUNTIME_DIAGNOSTICS_PRESENTATION_PCM_FRAMES].positive("presentation PCM frames")
            )
        }
        val position = values[NativePacketSchema.RUNTIME_DIAGNOSTICS_POSITION_SEQUENCE].takeIf { it != 0L }?.let { sequence ->
            NativeRuntimePresentedPosition(
                sequence = sequence,
                ownership = requiredOwnership(NativePacketSchema.RUNTIME_DIAGNOSTICS_POSITION_SELECTION_TOKEN, NativePacketSchema.RUNTIME_DIAGNOSTICS_POSITION_GENERATION),
                fingerprintSequence = values[NativePacketSchema.RUNTIME_DIAGNOSTICS_POSITION_FINGERPRINT_SEQUENCE].positive("position fingerprint"),
                lastTimelineFrame = values[NativePacketSchema.RUNTIME_DIAGNOSTICS_POSITION_LAST_TIMELINE_FRAME].nonNegative("presented timeline"),
                lastOutputFrame = values[NativePacketSchema.RUNTIME_DIAGNOSTICS_POSITION_LAST_OUTPUT_FRAME].nonNegative("presented output frame"),
                callbackSequence = values[NativePacketSchema.RUNTIME_DIAGNOSTICS_POSITION_CALLBACK_SEQUENCE].positive("position callback"),
                pcmFrames = values[NativePacketSchema.RUNTIME_DIAGNOSTICS_POSITION_PCM_FRAMES].positive("position PCM frames"),
                loopPolicyRevision =
                    values[NativePacketSchema.RUNTIME_DIAGNOSTICS_POSITION_LOOP_POLICY_REVISION].nonNegative("presented PCM loop-policy revision"),
                loopPolicyAdoptedRevision =
                    values[NativePacketSchema.RUNTIME_DIAGNOSTICS_POSITION_LOOP_POLICY_ADOPTED_REVISION].nonNegative("adopted loop-policy revision"),
                loopPolicyAdoptionLatencyMicros =
                    values[NativePacketSchema.RUNTIME_DIAGNOSTICS_POSITION_LOOP_POLICY_ADOPTION_LATENCY_MICROS].nonNegative("loop-policy adoption latency"),
                loopPolicyInputToAtomicPublicationLatencyMicros =
                    values[NativePacketSchema.RUNTIME_DIAGNOSTICS_POSITION_LOOP_POLICY_INPUT_TO_ATOMIC_PUBLICATION_LATENCY_MICROS].nonNegative("input-to-atomic-publication latency"),
                loopPolicyInputToCallbackAdoptionLatencyMicros =
                    values[NativePacketSchema.RUNTIME_DIAGNOSTICS_POSITION_LOOP_POLICY_INPUT_TO_CALLBACK_ADOPTION_LATENCY_MICROS].nonNegative("input-to-callback-adoption latency")
            )
        }
        val audibleEnd = values[NativePacketSchema.RUNTIME_DIAGNOSTICS_AUDIBLE_END_SEQUENCE].takeIf { it != 0L }?.let { sequence ->
            NativeRuntimeAudibleEndEvent(
                sequence,
                requiredOwnership(NativePacketSchema.RUNTIME_DIAGNOSTICS_AUDIBLE_END_TOKEN, NativePacketSchema.RUNTIME_DIAGNOSTICS_AUDIBLE_END_GENERATION),
                values[NativePacketSchema.RUNTIME_DIAGNOSTICS_AUDIBLE_END_DIRECT_LOOP_POLICY_REVISION].nonNegative("audible-end direct-loop policy revision")
            )
        }
        val audibleControl = values[NativePacketSchema.RUNTIME_DIAGNOSTICS_AUDIBLE_CONTROL_SEQUENCE].takeIf { it != 0L }?.let { sequence ->
            NativeRuntimeAudibleControlEvidence(
                sequence = sequence,
                ownership = requiredOwnership(NativePacketSchema.RUNTIME_DIAGNOSTICS_AUDIBLE_CONTROL_TOKEN, NativePacketSchema.RUNTIME_DIAGNOSTICS_AUDIBLE_CONTROL_GENERATION),
                controlRevision = values[NativePacketSchema.RUNTIME_DIAGNOSTICS_AUDIBLE_CONTROL_CONTROL_REVISION].positive("audible control revision"),
                actualSourceMode = NativeRuntimeSourceMode.fromNative(values[NativePacketSchema.RUNTIME_DIAGNOSTICS_AUDIBLE_CONTROL_ACTUAL_SOURCE_MODE].nativePacketInt()),
                firstFingerprintSequence =
                    values[NativePacketSchema.RUNTIME_DIAGNOSTICS_AUDIBLE_CONTROL_FIRST_FINGERPRINT_SEQUENCE].positive("audible control first fingerprint"),
                lastFingerprintSequence =
                    values[NativePacketSchema.RUNTIME_DIAGNOSTICS_AUDIBLE_CONTROL_LAST_FINGERPRINT_SEQUENCE].positive("audible control last fingerprint"),
                callbackSequence = values[NativePacketSchema.RUNTIME_DIAGNOSTICS_AUDIBLE_CONTROL_CALLBACK_SEQUENCE].positive("audible control callback"),
                pcmFrames = values[NativePacketSchema.RUNTIME_DIAGNOSTICS_AUDIBLE_CONTROL_PCM_FRAMES].positive("audible control PCM frames"),
                actualTempo = values[NativePacketSchema.RUNTIME_DIAGNOSTICS_AUDIBLE_CONTROL_ACTUAL_TEMPO_BITS].nativePacketFloat(),
                actualPitchSemitones = values[NativePacketSchema.RUNTIME_DIAGNOSTICS_AUDIBLE_CONTROL_ACTUAL_PITCH_SEMITONES_BITS].nativePacketFloat()
            )
        }
        val automaticSuccessor = NativeRuntimeAutomaticSuccessorDiagnostics(
            phase = NativeRuntimeAutomaticSuccessorPhase.fromNative(
                counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_AUTOMATIC_PHASE).nativePacketInt()
            ),
            cancellationStatus = NativeRuntimeDrainCancelStatus.fromNative(
                counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_AUTOMATIC_CANCELLATION_STATUS).nativePacketInt()
            ),
            revision = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_AUTOMATIC_REVISION),
            transitionRevision = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_AUTOMATIC_TRANSITION_REVISION),
            outgoingOwnership = ownership(NativePacketSchema.RUNTIME_DIAGNOSTICS_AUTOMATIC_OUTGOING_TOKEN, NativePacketSchema.RUNTIME_DIAGNOSTICS_AUTOMATIC_OUTGOING_GENERATION),
            successorOwnership = ownership(NativePacketSchema.RUNTIME_DIAGNOSTICS_AUTOMATIC_SUCCESSOR_TOKEN, NativePacketSchema.RUNTIME_DIAGNOSTICS_AUTOMATIC_SUCCESSOR_GENERATION),
            successorQueuedFrames = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_AUTOMATIC_SUCCESSOR_QUEUED_FRAMES),
            firstFingerprintSequence = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_AUTOMATIC_FIRST_FINGERPRINT_SEQUENCE),
            cancellationDesired = values[NativePacketSchema.RUNTIME_DIAGNOSTICS_AUTOMATIC_CANCELLATION_DESIRED].strictBoolean(
                "automatic successor cancellation desired"
            ),
            successorEndObserved = values[NativePacketSchema.RUNTIME_DIAGNOSTICS_AUTOMATIC_SUCCESSOR_END_OBSERVED].strictBoolean(
                "automatic successor end observed"
            )
        )
        return NativeRuntimeControlProgress(
            status = NativeRuntimeStatus.fromNative(values[NativePacketSchema.RUNTIME_DIAGNOSTICS_STATUS].nativePacketInt()),
            ownership = NativeRuntimeOwnershipProgress(
                requested = ownership(NativePacketSchema.RUNTIME_DIAGNOSTICS_REQUESTED_TOKEN, NativePacketSchema.RUNTIME_DIAGNOSTICS_REQUESTED_GENERATION),
                accepted = ownership(NativePacketSchema.RUNTIME_DIAGNOSTICS_ACCEPTED_TOKEN, NativePacketSchema.RUNTIME_DIAGNOSTICS_ACCEPTED_GENERATION),
                committed = ownership(NativePacketSchema.RUNTIME_DIAGNOSTICS_COMMITTED_TOKEN, NativePacketSchema.RUNTIME_DIAGNOSTICS_COMMITTED_GENERATION),
                acceptedFirstFingerprintSequence = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_ACCEPTED_FIRST_FINGERPRINT_SEQUENCE)
            ),
            presentation = presentation, presentedPosition = position, audibleEnd = audibleEnd,
            banks = banks, audibleControl = audibleControl, automaticSuccessor = automaticSuccessor,
            readinessProgress = readiness)
    }
}

internal object NativeRuntimeDiagnosticsCodec {
    // Generated layout includes bounded exact-request refill history. Counter fields are
    // nonnegative; content hashes preserve every bit. Software latencies exclude device/DAC time.
    const val SCHEMA_VERSION = NativePacketSchema.RUNTIME_DIAGNOSTICS_VERSION
    const val VALUE_COUNT = NativePacketSchema.RUNTIME_DIAGNOSTICS_COUNT

    fun decode(values: LongArray): NativeRuntimeDiagnostics {
        require(values.size == VALUE_COUNT) {
            "Native runtime diagnostics must contain $VALUE_COUNT values"
        }
        require(values[NativePacketSchema.RUNTIME_DIAGNOSTICS_SCHEMA] == SCHEMA_VERSION) {
            "Unsupported native runtime diagnostics schema ${values[NativePacketSchema.RUNTIME_DIAGNOSTICS_SCHEMA]}"
        }
        fun counter(index: Int): Long = values[index].also { require(it >= 0L) }
        fun int32(index: Int, label: String): Int = values[index].also {
            require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                "$label must fit Int"
            }
        }.nativePacketInt()
        fun ownership(tokenIndex: Int, generationIndex: Int): NativeRuntimeOwnership? {
            val token = values[tokenIndex]
            val generation = values[generationIndex]
            if (token == 0L && generation == 0L) return null
            return NativeRuntimeOwnership(token, generation)
        }
        fun requiredOwnership(tokenIndex: Int, generationIndex: Int) =
            requireNotNull(ownership(tokenIndex, generationIndex))

        val banks = List(2) { bank ->
            val base = NativePacketSchema.RUNTIME_DIAGNOSTICS_BANK_BASE + bank * NativePacketSchema.RUNTIME_DIAGNOSTICS_BANK_STRIDE
            NativeRuntimeBankDiagnostics(
                appliedIntentRevision = counter(base),
                assigned = values[base + NativePacketSchema.RUNTIME_DIAGNOSTICS_BANK_FIELD_ASSIGNED].strictBoolean("bank assigned"),
                beginSongCount = counter(base + NativePacketSchema.RUNTIME_DIAGNOSTICS_BANK_FIELD_BEGIN_SONG_COUNT),
                seekCount = counter(base + NativePacketSchema.RUNTIME_DIAGNOSTICS_BANK_FIELD_SEEK_COUNT),
                rejectedIntentCount = counter(base + NativePacketSchema.RUNTIME_DIAGNOSTICS_BANK_FIELD_REJECTED_BANK_INTENTS),
                beatGridUpdateCount = counter(base + NativePacketSchema.RUNTIME_DIAGNOSTICS_BANK_FIELD_BEAT_GRID_UPDATES),
                beatGridClearCount = counter(base + NativePacketSchema.RUNTIME_DIAGNOSTICS_BANK_FIELD_BEAT_GRID_CLEARS)
            )
        }
        val control = NativeRuntimeControlProgressCodec.decodeFields(RuntimeControlPacketReader(values), banks)
        val renderWorkerStarted = values[NativePacketSchema.RUNTIME_DIAGNOSTICS_RENDER_WORKER_STARTED].strictBoolean("render worker started")
        val renderWorkerAudioPriorityRequested =
            values[NativePacketSchema.RUNTIME_DIAGNOSTICS_RENDER_WORKER_AUDIO_PRIORITY_REQUESTED].strictBoolean("render worker audio priority requested")
        val renderWorkerAudioPriorityApplied =
            values[NativePacketSchema.RUNTIME_DIAGNOSTICS_RENDER_WORKER_AUDIO_PRIORITY_APPLIED].strictBoolean("render worker audio priority applied")
        val renderWorkerNiceValue = int32(NativePacketSchema.RUNTIME_DIAGNOSTICS_RENDER_WORKER_NICE_VALUE, "render worker nice value")
        val renderWorkerPriorityErrorCode = int32(NativePacketSchema.RUNTIME_DIAGNOSTICS_RENDER_WORKER_PRIORITY_ERROR_CODE, "render worker priority error code")
            .also { require(it >= 0) { "render worker priority error code cannot be negative" } }
        require(!renderWorkerAudioPriorityRequested || renderWorkerStarted) {
            "render worker cannot request priority before it starts"
        }
        require(!renderWorkerAudioPriorityApplied || renderWorkerAudioPriorityRequested) {
            "render worker cannot apply unrequested audio priority"
        }
        val currentOutputQueueLowWater = when (counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_LOW_WATER_SEQUENCE)) {
            0L -> {
                require(counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_LOW_WATER_GENERATION) == 0L && counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_LOW_WATER_FRAMES) == 0L && counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_LOW_WATER_MONOTONIC_MICROS) == 0L)
                null
            }
            else -> NativeRuntimeCurrentOutputQueueLowWater(
                sequence = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_LOW_WATER_SEQUENCE),
                generation = values[NativePacketSchema.RUNTIME_DIAGNOSTICS_LOW_WATER_GENERATION].positive("current output low-water generation"),
                frames = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_LOW_WATER_FRAMES),
                monotonicMicros = values[NativePacketSchema.RUNTIME_DIAGNOSTICS_LOW_WATER_MONOTONIC_MICROS].positive("current output low-water timestamp")
            )
        }
        fun waitReason(
            sequenceIndex: Int,
            timestampIndex: Int,
            generationIndex: Int
        ) = NativeRuntimeOutputWaitReasonDiagnostics(
            lowWaterEvidenceSequence = counter(sequenceIndex),
            lastMonotonicMicros = counter(timestampIndex),
            lastGeneration = counter(generationIndex)
        )
        return NativeRuntimeDiagnostics(
            status = control.status,
            counters = NativeRuntimeCounters(
                // BEGIN GENERATED runtime-counters
                commandSnapshotsPublished = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_COMMAND_SNAPSHOTS_PUBLISHED),
                commandSnapshotsConsumed = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_COMMAND_SNAPSHOTS_CONSUMED),
                commandSnapshotsSuperseded = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_COMMAND_SNAPSHOTS_SUPERSEDED),
                rejectedCommandSnapshots = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_REJECTED_COMMAND_SNAPSHOTS),
                beatGridUpdatesPublished = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_BEAT_GRID_UPDATES_PUBLISHED),
                rejectedBeatGridUpdates = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_REJECTED_BEAT_GRID_UPDATES),
                renderCycles = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_RENDER_CYCLES),
                idleSleeps = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_IDLE_SLEEPS),
                outputFlushes = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_OUTPUT_FLUSHES),
                outputFlushFailures = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_OUTPUT_FLUSH_FAILURES),
                outputHandoffBegins = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_OUTPUT_HANDOFF_BEGINS),
                outputHandoffBeginRetries = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_OUTPUT_HANDOFF_BEGIN_RETRIES),
                outputHandoffCommits = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_OUTPUT_HANDOFF_COMMITS),
                outputHandoffCommitRetries = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_OUTPUT_HANDOFF_COMMIT_RETRIES),
                outputBlocksRendered = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_OUTPUT_BLOCKS_RENDERED),
                outputFramesRendered = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_OUTPUT_FRAMES_RENDERED),
                outputFramesAccepted = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_OUTPUT_FRAMES_ACCEPTED),
                outputPartialAccepts = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_OUTPUT_PARTIAL_ACCEPTS),
                outputBackpressureEvents = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_OUTPUT_BACKPRESSURE_EVENTS),
                outputInvalidRenderResults = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_OUTPUT_INVALID_RENDER_RESULTS),
                outputNeedDataEvents = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_OUTPUT_NEED_DATA_EVENTS),
                outputEndOfStreamEvents = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_OUTPUT_END_OF_STREAM_EVENTS),
                targetQueuedFrames = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_TARGET_QUEUED_FRAMES),
                lastOutputFingerprintSequence = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_LAST_OUTPUT_FINGERPRINT_SEQUENCE),
                lastRenderPayloadHash = values[NativePacketSchema.RUNTIME_DIAGNOSTICS_LAST_RENDER_PAYLOAD_HASH],
                outputFlushRetries = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_OUTPUT_FLUSH_RETRIES),
                resourceOperationsCompleted = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_RESOURCE_OPERATIONS_COMPLETED),
                resourceLastOperationMicros = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_RESOURCE_LAST_OPERATION_MICROS),
                resourceMaximumOperationMicros = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_RESOURCE_MAXIMUM_OPERATION_MICROS),
                resourceMaximumQueueAgeMicros = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_RESOURCE_MAXIMUM_QUEUE_AGE_MICROS),
                refillDeadlineMisses = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_REFILL_DEADLINE_MISSES),
                // END GENERATED runtime-counters
            ),
            ownership = control.ownership,
            presentation = control.presentation,
            presentedPosition = control.presentedPosition,
            audibleEnd = control.audibleEnd,
            banks = banks,
            audibleControl = control.audibleControl,
            processConstructionCount = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_PROCESS_CONSTRUCTION_COUNT),
            processLiveInstanceCount = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_PROCESS_LIVE_INSTANCE_COUNT),
            processOutputInvalidRenderResults = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_PROCESS_OUTPUT_INVALID_RENDER_RESULTS),
            processOutputFlushFailures = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_PROCESS_OUTPUT_FLUSH_FAILURES),
            automaticSuccessor = control.automaticSuccessor,
            renderWorkerStarted = renderWorkerStarted,
            renderWorkerAudioPriorityRequested = renderWorkerAudioPriorityRequested,
            renderWorkerAudioPriorityApplied = renderWorkerAudioPriorityApplied,
            renderWorkerNiceValue = renderWorkerNiceValue,
            renderWorkerPriorityErrorCode = renderWorkerPriorityErrorCode,
            currentOutputQueueLowWater = currentOutputQueueLowWater,
            needOriginalData = waitReason(NativePacketSchema.RUNTIME_DIAGNOSTICS_NEED_ORIGINAL_LOW_WATER_EVIDENCE_SEQUENCE, NativePacketSchema.RUNTIME_DIAGNOSTICS_NEED_ORIGINAL_LAST_MONOTONIC_MICROS, NativePacketSchema.RUNTIME_DIAGNOSTICS_NEED_ORIGINAL_LAST_GENERATION),
            needStemData = waitReason(NativePacketSchema.RUNTIME_DIAGNOSTICS_NEED_STEM_LOW_WATER_EVIDENCE_SEQUENCE, NativePacketSchema.RUNTIME_DIAGNOSTICS_NEED_STEM_LAST_MONOTONIC_MICROS, NativePacketSchema.RUNTIME_DIAGNOSTICS_NEED_STEM_LAST_GENERATION),
            loopBoundaryPending = waitReason(NativePacketSchema.RUNTIME_DIAGNOSTICS_LOOP_BOUNDARY_LOW_WATER_EVIDENCE_SEQUENCE, NativePacketSchema.RUNTIME_DIAGNOSTICS_LOOP_BOUNDARY_LAST_MONOTONIC_MICROS, NativePacketSchema.RUNTIME_DIAGNOSTICS_LOOP_BOUNDARY_LAST_GENERATION),
            canonicalPcmIo = if (counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_CANONICAL_CACHE_TOKEN) == 0L) null else {
                NativeRuntimeCanonicalPcmIoDiagnostics(
                    requiredOwnership(NativePacketSchema.RUNTIME_DIAGNOSTICS_CANONICAL_CACHE_TOKEN,
                        NativePacketSchema.RUNTIME_DIAGNOSTICS_CANONICAL_CACHE_GENERATION),
                    values[NativePacketSchema.RUNTIME_DIAGNOSTICS_CANONICAL_CACHE_SONG_HASH],
                    values[NativePacketSchema.RUNTIME_DIAGNOSTICS_CANONICAL_CACHE_ORIGINAL_HASH],
                    values[NativePacketSchema.RUNTIME_DIAGNOSTICS_CANONICAL_CACHE_STEM_HASH],
                    counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_CANONICAL_READ_OPERATIONS),
                    counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_CANONICAL_READ_BYTES),
                    counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_CANONICAL_LAST_READ_BYTE_OFFSET),
                    counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_CANONICAL_LAST_READ_BYTES),
                    counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_CANONICAL_FILE_BYTES),
                    counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_CANONICAL_RESERVED_BYTES),
                    counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_CANONICAL_PEAK_FILE_BYTES),
                    counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_CANONICAL_ADMITTED_LANES).also { require(it <= NativeSongLaneIngressBank.LANE_COUNT.toLong()) },
                    counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_CANONICAL_ADMITTED_FRAME_BOUND),
                    NativeCacheStorageStatus.entries.firstOrNull {
                        it.nativeCode.toLong() == counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_CANONICAL_LAST_ADMISSION_STATUS)
                    } ?: throw IllegalArgumentException("Unknown cache admission status"))
            },
            endOrdering = NativeRuntimeEndOrderingDiagnostics(NativeRuntimeEndOrderingKind.entries.mapNotNull { kind ->
                val base = NativePacketSchema.RUNTIME_DIAGNOSTICS_END_ORDERING_BASE +
                    kind.ordinal * NativePacketSchema.RUNTIME_DIAGNOSTICS_END_ORDERING_STRIDE
                fun number(field: Int) = counter(base + field)
                fun flag(field: Int) = values[base + field].nativePacketBoolean()
                val sequence = number(NativePacketSchema.RUNTIME_DIAGNOSTICS_END_ORDERING_FIELD_SEQUENCE)
                if (sequence == 0L) null else NativeRuntimeEndOrderingEvent(
                    kind, sequence,
                    number(NativePacketSchema.RUNTIME_DIAGNOSTICS_END_ORDERING_FIELD_WORKER_STATE_SEQUENCE),
                    number(NativePacketSchema.RUNTIME_DIAGNOSTICS_END_ORDERING_FIELD_COMMAND_REVISION),
                    ownership(base + NativePacketSchema.RUNTIME_DIAGNOSTICS_END_ORDERING_FIELD_TOKEN,
                        base + NativePacketSchema.RUNTIME_DIAGNOSTICS_END_ORDERING_FIELD_GENERATION),
                    flag(NativePacketSchema.RUNTIME_DIAGNOSTICS_END_ORDERING_FIELD_DESIRED_PLAYING),
                    flag(NativePacketSchema.RUNTIME_DIAGNOSTICS_END_ORDERING_FIELD_OUTPUT_PLAYING),
                    flag(NativePacketSchema.RUNTIME_DIAGNOSTICS_END_ORDERING_FIELD_PIPELINE_END_OBSERVED),
                    flag(NativePacketSchema.RUNTIME_DIAGNOSTICS_END_ORDERING_FIELD_COMMAND_UNREAD),
                    number(NativePacketSchema.RUNTIME_DIAGNOSTICS_END_ORDERING_FIELD_PENDING_OUTPUT_FRAMES),
                    number(NativePacketSchema.RUNTIME_DIAGNOSTICS_END_ORDERING_FIELD_QUEUED_OUTPUT_FRAMES),
                    flag(NativePacketSchema.RUNTIME_DIAGNOSTICS_END_ORDERING_FIELD_TERMINAL_POSITION_VALID),
                    flag(NativePacketSchema.RUNTIME_DIAGNOSTICS_END_ORDERING_FIELD_TERMINAL_AFTER_FRAME))
            }.sortedBy { it.sequence })
        )
    }

}

private fun Long.positive(label: String): Long = also {
    require(it > 0L) { "$label must be positive" }
}

private fun Long.nonNegative(label: String): Long = also {
    require(it >= 0L) { "$label cannot be negative" }
}

private fun Long.strictBoolean(label: String): Boolean = when (this) {
    0L -> false
    1L -> true
    else -> error("$label must be encoded as zero or one")
}

internal object NativeRuntimeRenderReadinessDiagnosticsCodec {
    const val SCHEMA_VERSION = NativePacketSchema.RENDER_READINESS_VERSION
    const val VALUE_COUNT = NativePacketSchema.RENDER_READINESS_COUNT

    fun decode(values: LongArray): NativeRuntimeRenderReadinessDiagnostics? {
        require(values.size == VALUE_COUNT) {
            "Native render-readiness diagnostics must contain $VALUE_COUNT values"
        }
        require(values[NativePacketSchema.RENDER_READINESS_SCHEMA] == SCHEMA_VERSION) {
            "Unsupported native render-readiness diagnostics schema ${values[NativePacketSchema.RENDER_READINESS_SCHEMA]}"
        }
        fun nonNegative(index: Int): Long = values[index].also { require(it >= 0L) }
        val sequence = nonNegative(NativePacketSchema.RENDER_READINESS_SEQUENCE)
        if (sequence == 0L) return null
        val assigned = values[NativePacketSchema.RENDER_READINESS_ASSIGNED].strictDiagnosticBoolean("render assigned")
        val ownership = if (!assigned && values[NativePacketSchema.RENDER_READINESS_OWNERSHIP_SELECTION_TOKEN] == 0L && values[NativePacketSchema.RENDER_READINESS_OWNERSHIP_GENERATION] == 0L) {
            null
        } else {
            NativeRuntimeOwnership(nonNegative(NativePacketSchema.RENDER_READINESS_OWNERSHIP_SELECTION_TOKEN), nonNegative(NativePacketSchema.RENDER_READINESS_OWNERSHIP_GENERATION))
        }
        val lanes = List(NativeSongLaneIngressBank.LANE_COUNT) { laneIndex ->
            val base = NativePacketSchema.RENDER_READINESS_LANE_BASE + laneIndex * NativePacketSchema.RENDER_READINESS_LANE_STRIDE
            NativeRuntimeRenderLaneReadinessDiagnostics(
                laneIndex = laneIndex,
                generation = nonNegative(base),
                firstTimelineFrame = nonNegative(base + NativePacketSchema.RENDER_READINESS_LANE_FIELD_FIRST_TIMELINE_FRAME),
                endTimelineFrameExclusive = nonNegative(base + NativePacketSchema.RENDER_READINESS_LANE_FIELD_END_TIMELINE_FRAME_EXCLUSIVE),
                availableFromRenderStart = nonNegative(base + NativePacketSchema.RENDER_READINESS_LANE_FIELD_AVAILABLE_FROM_RENDER_START),
                reservoirGapRejectionCount = nonNegative(base + NativePacketSchema.RENDER_READINESS_LANE_FIELD_RESERVOIR_GAP_REJECTION_COUNT),
                ingressFailureGeneration = nonNegative(base + NativePacketSchema.RENDER_READINESS_LANE_FIELD_INGRESS_FAILURE_GENERATION),
                ingressActiveGeneration = nonNegative(base + NativePacketSchema.RENDER_READINESS_LANE_FIELD_INGRESS_ACTIVE_GENERATION),
                ingressProducerGeneration = nonNegative(base + NativePacketSchema.RENDER_READINESS_LANE_FIELD_INGRESS_PRODUCER_GENERATION),
                ingressConsumerGeneration = nonNegative(base + NativePacketSchema.RENDER_READINESS_LANE_FIELD_INGRESS_CONSUMER_GENERATION),
                ingressReadableSourceFrames = nonNegative(base + NativePacketSchema.RENDER_READINESS_LANE_FIELD_INGRESS_READABLE_SOURCE_FRAMES),
                ingressPulls = nonNegative(base + NativePacketSchema.RENDER_READINESS_LANE_FIELD_INGRESS_PULLS),
                ingressStaleAfterPull = nonNegative(base + NativePacketSchema.RENDER_READINESS_LANE_FIELD_INGRESS_STALE_AFTER_PULL),
                ingressFutureGenerationDeferrals = nonNegative(base + NativePacketSchema.RENDER_READINESS_LANE_FIELD_INGRESS_FUTURE_GENERATION_DEFERRALS)
            )
        }
        val cacheBoundToSelectedOwner =
            values[NativePacketSchema.RENDER_READINESS_CURRENT_SONG_CACHE_BOUND_TO_SELECTED_OWNER].strictDiagnosticBoolean("current-song cache selected-owner binding")
        val cacheCompleteLaneMask = nonNegative(NativePacketSchema.RENDER_READINESS_CURRENT_SONG_CACHE_COMPLETE_LANE_MASK).also {
            require(it <= NativeRuntimeManagedLoopIntent.ALL_LANES_MASK.toLong()) { "Current-song cache lane mask contains unknown lanes" }
        }.nativePacketInt()
        return NativeRuntimeRenderReadinessDiagnostics(
            sequence = sequence,
            assigned = assigned,
            bank = NativeRuntimeBank.fromNative(nonNegative(NativePacketSchema.RENDER_READINESS_BANK).nativePacketInt()),
            ownership = ownership,
            controlRevision = nonNegative(NativePacketSchema.RENDER_READINESS_CONTROL_REVISION),
            desiredSourceMode = NativeRuntimeSourceMode.fromNative(nonNegative(NativePacketSchema.RENDER_READINESS_DESIRED_SOURCE_MODE).nativePacketInt()),
            renderStartTimelineFrame = nonNegative(NativePacketSchema.RENDER_READINESS_RENDER_START_TIMELINE_FRAME),
            pendingControlRevision = nonNegative(NativePacketSchema.RENDER_READINESS_PENDING_CONTROL_REVISION),
            pendingActualSourceMode = NativeRuntimeSourceMode.fromNative(nonNegative(NativePacketSchema.RENDER_READINESS_PENDING_ACTUAL_SOURCE_MODE).nativePacketInt()),
            pendingSourceStartTimelineFrame = nonNegative(NativePacketSchema.RENDER_READINESS_PENDING_SOURCE_START_TIMELINE_FRAME),
            pendingSourceEndTimelineFrameExclusive = nonNegative(NativePacketSchema.RENDER_READINESS_PENDING_SOURCE_END_TIMELINE_FRAME_EXCLUSIVE),
            pendingFrames = nonNegative(NativePacketSchema.RENDER_READINESS_PENDING_FRAMES),
            outputQueuedFrames = nonNegative(NativePacketSchema.RENDER_READINESS_OUTPUT_QUEUED_FRAMES),
            stemActivationDeferrals = nonNegative(NativePacketSchema.RENDER_READINESS_STEM_ACTIVATION_DEFERRALS),
            stemSafetyFallbacks = nonNegative(NativePacketSchema.RENDER_READINESS_STEM_SAFETY_FALLBACKS),
            needStemDataCount = nonNegative(NativePacketSchema.RENDER_READINESS_NEED_STEM_DATA_COUNT),
            lanes = lanes,
            currentSongCacheBoundToSelectedOwner = cacheBoundToSelectedOwner,
            currentSongCacheCompleteLaneMask = cacheCompleteLaneMask,
            currentSongCacheCanGrow = values[NativePacketSchema.RENDER_READINESS_CURRENT_SONG_CACHE_CAN_GROW].strictDiagnosticBoolean("current-song cache can grow"),
            ingressBankConfiguredLaneCount = nonNegative(NativePacketSchema.RENDER_READINESS_INGRESS_BANK_CONFIGURED_LANE_COUNT),
            directAtomicPolicyGeneration = nonNegative(NativePacketSchema.RENDER_READINESS_DIRECT_ATOMIC_POLICY_GENERATION),
            directAtomicPolicyPublishedRevision = nonNegative(NativePacketSchema.RENDER_READINESS_DIRECT_ATOMIC_POLICY_PUBLISHED_REVISION),
            directAtomicPolicyAdoptedRevision = nonNegative(NativePacketSchema.RENDER_READINESS_DIRECT_ATOMIC_POLICY_ADOPTED_REVISION),
            directAtomicPolicyObservedRevision = nonNegative(NativePacketSchema.RENDER_READINESS_DIRECT_ATOMIC_POLICY_OBSERVED_REVISION),
            fixedLoopHeadPrepared = values[NativePacketSchema.RENDER_READINESS_FIXED_LOOP_HEAD_PREPARED].strictDiagnosticBoolean("fixed loop head prepared"),
            ingressServicePolicyBreaks = nonNegative(NativePacketSchema.RENDER_READINESS_INGRESS_SERVICE_POLICY_BREAKS),
            ingressBankIdentity = nonNegative(NativePacketSchema.RENDER_READINESS_INGRESS_BANK_IDENTITY),
            requiredSongLaneMask = nonNegative(NativePacketSchema.RENDER_READINESS_REQUIRED_SONG_LANE_MASK).also { require(it <= NativeRuntimeManagedLoopIntent.ALL_LANES_MASK.toLong()) }.nativePacketInt(),
            cacheStorageStatus = NativeCacheStorageStatus.entries.firstOrNull {
                it.nativeCode == nonNegative(NativePacketSchema.RENDER_READINESS_CACHE_STORAGE_STATUS).nativePacketInt()
            }
                ?: error("Unknown native cache storage status"),
            loopPreparationFailure = decodeLoopPreparationFailure(values[NativePacketSchema.RENDER_READINESS_LOOP_FAILURE_TOKEN],
                values[NativePacketSchema.RENDER_READINESS_LOOP_FAILURE_GENERATION],
                values[NativePacketSchema.RENDER_READINESS_LOOP_FAILURE_REVISION],
                values[NativePacketSchema.RENDER_READINESS_LOOP_FAILURE_REASON])
        )
    }

    private fun Long.strictDiagnosticBoolean(label: String): Boolean = when (this) {
        0L -> false
        1L -> true
        else -> error("$label must be encoded as zero or one")
    }
}
