package app.stemwise.playback.engine

/** Current native evidence decides whether the selected owner needs a separately decoded head. */
internal class NativeLoopCacheAdmission(
    private val environment: NativeEngineEnvironment,
    private val minimumRunwayFrames: Long,
    private val declickFrames: Int
) {
    sealed interface Decision {
        data object Wait : Decision
        data object FullSong : Decision
        data class Prepare(val startMs: Long, val endFrameExclusive: Long,
            val boundaryMode: NativeRuntimeManagedLoopBoundaryMode) : Decision
    }

    fun preservationMask(record: BankRecord, intent: NativeRuntimeBankIntent, intended: Int): Int {
        val admitted = environment.diagnostics().canonicalPcmIo
        // Rejected optional growth preserves admitted Original; stale capacity cannot waive a builder.
        if (admitted == null || admitted.ownership != record.ownership || intent.ownership != record.ownership ||
            admitted.songIdentityHash != intent.songIdentityHash || admitted.originalContentHash != intent.originalContentHash ||
            admitted.stemPackageHash != intent.stemPackageHash ||
            admitted.admittedLaneCount !in 1L..NativeSongLaneIngressBank.LANE_COUNT.toLong()) return intended
        val capacityMask = (1 shl admitted.admittedLaneCount.toInt()) - 1
        return if (capacityMask and intended == intended) intended else NativeRuntimeManagedLoopIntent.ORIGINAL_LANE_MASK
    }

    fun rangeReady(record: BankRecord, intent: NativeRuntimeBankIntent, requiredMask: Int,
        currentFrame: Long, config: EngineLoopConfig?): Boolean {
        val end = record.decoders[0]?.head()?.endOfStreamTimelineFrameExclusive48k ?: msToFrame(record.identity.durationMs)
        if (end <= 0L) return false
        val first = config?.let { msToFrame(it.startMs) } ?: minOf(currentFrame, end - 1L)
        if (first >= end) return false
        val queryEnd = minOf(config?.let { msToFrame(it.endMs) } ?: end,
            first + minOf(minimumRunwayFrames, end - first))
        return queryEnd > first && environment.isCurrentSongCacheRangeReady(intent, requiredMask, first, queryEnd)
    }

    fun decide(record: BankRecord, publicOwner: NativeRuntimeOwnership?, requiredMask: Int,
        readiness: NativeRuntimeRenderReadinessDiagnostics?, bankIntents: List<NativeRuntimeBankIntent>,
        producers: List<BankRecord>, currentFrame: Long, config: EngineLoopConfig?,
        existing: NativeRuntimeManagedLoopIntent?): Decision {
        if (readiness == null || readiness.ownership != record.ownership || publicOwner != record.ownership) return Decision.Wait
        val selectedIntent = bankIntents[record.bank.nativeCode]
        val hasFrameZeroProducer = producers.any { producer ->
            val intent = bankIntents[producer.bank.nativeCode]
            producer.assigned && producer.selection?.token == record.selection?.token && producer.identity == record.identity &&
                intent.ownership == producer.ownership && intent.firstTimelineFrame == 0L &&
                (requiredMask == NativeRuntimeManagedLoopIntent.ORIGINAL_LANE_MASK || intent.stemPackageHash == selectedIntent.stemPackageHash) &&
                (0 until NativeSongLaneIngressBank.LANE_COUNT).all { lane ->
                    if (requiredMask and (1 shl lane) == 0) true else producer.decoders[lane]?.head()?.let { head ->
                        head.nativeGeneration == producer.ingressEpoch && head.firstTimelineFrame48k == 0L
                    } == true
                }
        }
        val sealed = readiness.currentSongCacheBoundToSelectedOwner &&
            readiness.currentSongCacheCompleteLaneMask and requiredMask == requiredMask
        if (readiness.cacheStorageStatus == NativeCacheStorageStatus.ADMITTED &&
            (sealed || readiness.currentSongCacheCanGrow && hasFrameZeroProducer ||
                rangeReady(record, selectedIntent, requiredMask, currentFrame, config))) return Decision.FullSong
        if (readiness.cacheStorageStatus == NativeCacheStorageStatus.NOT_REQUESTED && hasFrameZeroProducer)
            return Decision.FullSong
        val startMs = config?.startMs ?: existing?.cacheKey?.takeIf {
            currentFrame < it.endTimelineFrameExclusive
        }?.startTimelineFrame?.let(::frameToMs) ?: frameToMs(currentFrame)
        val headEnd = Math.addExact(msToFrame(startMs), NativeRuntimeManagedLoopIntent.HEAD_CACHE_CAPACITY_FRAMES)
        return if (config != null && msToFrame(config.endMs) > headEnd)
            Decision.Prepare(startMs, Math.addExact(msToFrame(config.endMs), NativeRuntimeManagedLoopIntent.HEAD_CACHE_CAPACITY_FRAMES),
                NativeRuntimeManagedLoopBoundaryMode.RETAINED_WINDOW)
        else Decision.Prepare(startMs, headEnd, NativeRuntimeManagedLoopBoundaryMode.ORIGINAL_EOS)
    }

    fun identity(record: BankRecord, loop: EffectiveManagedLoop, physicalStems: StemPackageRequest?): ManagedLoopStableIdentity {
        val start = msToFrame(loop.startMs)
        val signature = physicalStems?.signature.orEmpty()
        return ManagedLoopStableIdentity(record.selection?.token ?: EngineSelectionToken(record.ownership.selectionToken),
            enabled = true, songIdentityHash = stableRawHash("song-v1", record.identity.songId),
            originalContentHash = stableRawHash("original-v1", record.identity.audioRevision, record.identity.originalLocation,
                record.identity.originalContentSha256 ?: record.identity.audioRevision),
            stemPackageHash = physicalStems?.let { stableRawHash("stems-v1", it.signature) } ?: 0L,
            contentRevision = stablePositiveHash("content-revision-v1", record.identity.audioRevision,
                record.identity.originalContentSha256 ?: record.identity.audioRevision, signature),
            startTimelineFrame = start, endTimelineFrameExclusive = Math.addExact(start, NativeRuntimeManagedLoopIntent.HEAD_CACHE_CAPACITY_FRAMES),
            boundaryMode = loop.boundaryMode, requiredLaneMask = musicLaneMaskForStemCount(physicalStems?.canonicalLanes?.size ?: 0),
            declickFrames = declickFrames)
    }
}

internal fun stableRawHash(vararg parts: String): Long {
    var hash = -3_750_763_034_362_895_579L // FNV-1a offset as raw signed bits.
    parts.forEach { part ->
        part.toByteArray(Charsets.UTF_8).forEach { byte -> hash = (hash xor (byte.toLong() and 0xffL)) * 1_099_511_628_211L }
        hash = (hash xor 0xffL) * 1_099_511_628_211L
    }
    return if (hash == 0L) 1L else hash
}

internal fun stablePositiveHash(vararg parts: String): Long =
    (stableRawHash(*parts) and Long.MAX_VALUE).takeIf { it > 0L } ?: 1L

internal fun msToFrame(ms: Long): Long = try {
    Math.multiplyExact(ms, NATIVE_ENGINE_SAMPLE_RATE_HZ.toLong()) / 1_000L
} catch (_: ArithmeticException) { throw IllegalArgumentException("Timeline milliseconds exceed native frame space") }

internal fun frameToMs(frame: Long): Long = try {
    Math.multiplyExact(frame, 1_000L) / NATIVE_ENGINE_SAMPLE_RATE_HZ
} catch (_: ArithmeticException) { Long.MAX_VALUE }

internal fun msToUs(ms: Long): Long = try {
    Math.multiplyExact(ms, 1_000L)
} catch (_: ArithmeticException) { throw IllegalArgumentException("Timeline milliseconds exceed microsecond space") }
