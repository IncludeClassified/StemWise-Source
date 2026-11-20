// Private render-worker publications. Included once inside the runtime implementation namespace.
struct AtomicLaneDiagnostics final {
    std::atomic<std::uint64_t> failureGeneration{0};
    std::atomic<std::uint64_t> pulls{0};
    std::atomic<std::uint64_t> pulledFrames{0};
    std::atomic<std::uint64_t> appendedFrames{0};
    std::atomic<std::uint64_t> retainedSuffixFrames{0};
    std::atomic<std::uint64_t> partialAppends{0};
    std::atomic<std::uint64_t> staleAfterPull{0};
    std::atomic<std::uint64_t> futureGenerationDeferrals{0};
    std::atomic<std::uint64_t> discontinuityRejects{0};
    std::atomic<std::uint64_t> endOfStreamMarks{0};
    std::atomic<std::uint64_t> endOfStreamFailures{0};

    [[nodiscard]] NativePlaybackLaneDiagnostics snapshot() const noexcept {
        return {
            pulls.load(std::memory_order_relaxed),
            pulledFrames.load(std::memory_order_relaxed),
            appendedFrames.load(std::memory_order_relaxed),
            retainedSuffixFrames.load(std::memory_order_relaxed),
            partialAppends.load(std::memory_order_relaxed),
            staleAfterPull.load(std::memory_order_relaxed),
            futureGenerationDeferrals.load(std::memory_order_relaxed),
            discontinuityRejects.load(std::memory_order_relaxed),
            endOfStreamMarks.load(std::memory_order_relaxed),
            endOfStreamFailures.load(std::memory_order_relaxed),
        };
    }
};

struct AtomicBankDiagnostics final {
    std::atomic<std::uint64_t> appliedIntentGuard{0};
    std::atomic<std::uint64_t> appliedIntentRevision{0};
    std::atomic<bool> assigned{false};
    std::atomic<std::uint64_t> beginSongCount{0};
    std::atomic<std::uint64_t> seekCount{0};
    std::atomic<std::uint64_t> rejectedBankIntents{0};
    std::atomic<std::uint64_t> beatGridUpdates{0};
    std::atomic<std::uint64_t> beatGridClears{0};
    std::array<AtomicLaneDiagnostics, kSongRenderLaneCount> lanes{};

    void publishAppliedIntent(bool isAssigned, std::uint64_t revision) noexcept {
        beginGuardedPublication(appliedIntentGuard);
        assigned.store(isAssigned, std::memory_order_relaxed);
        appliedIntentRevision.store(revision, std::memory_order_relaxed);
        appliedIntentGuard.fetch_add(1U, std::memory_order_release);
    }

    [[nodiscard]] NativePlaybackBankControlProgress controlProgress() const noexcept {
        NativePlaybackBankControlProgress result{};
        if (!readStableDiagnosticsGroup(appliedIntentGuard, [&] {
            result.appliedIntentRevision = appliedIntentRevision.load(std::memory_order_relaxed);
            result.assigned = assigned.load(std::memory_order_relaxed);
        })) return {};
        return result;
    }

    [[nodiscard]] NativePlaybackBankDiagnostics snapshot() const noexcept {
        NativePlaybackBankDiagnostics result{};
        const auto progress = controlProgress();
        result.appliedIntentRevision = progress.appliedIntentRevision;
        result.assigned = progress.assigned;
        result.beginSongCount = beginSongCount.load(std::memory_order_relaxed);
        result.seekCount = seekCount.load(std::memory_order_relaxed);
        result.rejectedBankIntents = rejectedBankIntents.load(std::memory_order_relaxed);
        result.beatGridUpdates = beatGridUpdates.load(std::memory_order_relaxed);
        result.beatGridClears = beatGridClears.load(std::memory_order_relaxed);
        for (std::size_t lane = 0; lane < kSongRenderLaneCount; ++lane) {
            result.lanes[lane] = lanes[lane].snapshot();
        }
        return result;
    }
};

struct AtomicEndOrderingEvent final {
    std::atomic<std::uint64_t> guard{0};
    std::atomic<std::uint64_t> sequence{0};
    std::atomic<std::uint64_t> workerStateSequence{0};
    std::atomic<std::uint64_t> commandRevision{0};
    std::atomic<std::uint64_t> selectionToken{0};
    std::atomic<std::uint64_t> generation{0};
    std::atomic<bool> desiredPlaying{false};
    std::atomic<bool> outputPlaying{false};
    std::atomic<bool> pipelineEndObserved{false};
    std::atomic<bool> commandUnread{false};
    std::atomic<std::uint64_t> pendingOutputFrames{0};
    std::atomic<std::uint64_t> queuedOutputFrames{0};
    std::atomic<bool> terminalPositionValid{false};
    std::atomic<bool> terminalAfterFrame{false};

    void publish(const NativePlaybackEndOrderingEvent &value) noexcept {
        beginGuardedPublication(guard);
        sequence.store(value.sequence, std::memory_order_relaxed);
        workerStateSequence.store(value.workerStateSequence, std::memory_order_relaxed);
        commandRevision.store(value.commandRevision, std::memory_order_relaxed);
        selectionToken.store(value.ownership.selectionToken, std::memory_order_relaxed);
        generation.store(value.ownership.generation, std::memory_order_relaxed);
        desiredPlaying.store(value.desiredPlaying, std::memory_order_relaxed);
        outputPlaying.store(value.outputPlaying, std::memory_order_relaxed);
        pipelineEndObserved.store(value.pipelineEndObserved, std::memory_order_relaxed);
        commandUnread.store(value.commandUnread, std::memory_order_relaxed);
        pendingOutputFrames.store(value.pendingOutputFrames, std::memory_order_relaxed);
        queuedOutputFrames.store(value.queuedOutputFrames, std::memory_order_relaxed);
        terminalPositionValid.store(value.terminalPositionValid, std::memory_order_relaxed);
        terminalAfterFrame.store(value.terminalAfterFrame, std::memory_order_relaxed);
        guard.fetch_add(1U, std::memory_order_release);
    }

    [[nodiscard]] NativePlaybackEndOrderingEvent snapshot() const noexcept {
        NativePlaybackEndOrderingEvent result{};
        for (unsigned attempt = 0U; attempt < 8U; ++attempt) {
            const std::uint64_t before = guard.load(std::memory_order_acquire);
            if ((before & 1U) != 0U) continue;
            result.sequence = sequence.load(std::memory_order_relaxed);
            result.workerStateSequence =
                workerStateSequence.load(std::memory_order_relaxed);
            result.commandRevision = commandRevision.load(std::memory_order_relaxed);
            result.ownership.selectionToken = selectionToken.load(std::memory_order_relaxed);
            result.ownership.generation = generation.load(std::memory_order_relaxed);
            result.desiredPlaying = desiredPlaying.load(std::memory_order_relaxed);
            result.outputPlaying = outputPlaying.load(std::memory_order_relaxed);
            result.pipelineEndObserved = pipelineEndObserved.load(std::memory_order_relaxed);
            result.commandUnread = commandUnread.load(std::memory_order_relaxed);
            result.pendingOutputFrames = pendingOutputFrames.load(std::memory_order_relaxed);
            result.queuedOutputFrames = queuedOutputFrames.load(std::memory_order_relaxed);
            result.terminalPositionValid = terminalPositionValid.load(std::memory_order_relaxed);
            result.terminalAfterFrame = terminalAfterFrame.load(std::memory_order_relaxed);
            std::atomic_thread_fence(std::memory_order_acquire);
            const std::uint64_t after = guard.load(std::memory_order_acquire);
            if (before == after) return result;
        }
        return {};
    }
};

struct DiagnosticLowWaterPublication final {
    bool published{false};
    std::uint64_t monotonicMicros{0};
};

struct AtomicOutputWaitReasonDiagnostics final {
    // Only the render worker touches the current starvation episode.
    std::uint64_t producerGeneration{0};
    std::uint64_t producerLowWaterFrames{0};
    bool episodeActive{false};
    std::atomic<std::uint64_t> guard{0};
    std::atomic<std::uint64_t> lowWaterEvidenceSequence{0};
    std::atomic<std::uint64_t> lastMonotonicMicros{0};
    std::atomic<std::uint64_t> lastGeneration{0};

    void record(
        std::uint64_t generation,
        std::size_t queuedFrames,
        std::size_t targetFrames,
        bool committedCurrentOutput,
        DiagnosticLowWaterPublication currentOutputLowWater) noexcept {
        // A healthy repeated Need* result at the same queue level must remain worker-local: no
        // clock read, atomic operation, or seqlock publication. The target comparison also keeps
        // this evidence specific to an actual current-output deficit.
        if (!committedCurrentOutput || targetFrames == 0U || queuedFrames >= targetFrames) {
            return;
        }
        const std::uint64_t boundedFrames = static_cast<std::uint64_t>(queuedFrames);
        if (episodeActive && producerGeneration == generation &&
            boundedFrames >= producerLowWaterFrames) {
            return;
        }
        episodeActive = true;
        producerGeneration = generation;
        producerLowWaterFrames = boundedFrames;
        // fillOutput already sampled CLOCK_MONOTONIC when this exact queue observation also
        // advanced the global current-output low-water. Reuse it instead of taking a second clock
        // sample. A reason first seen at an already-known queue takes one independent sample.
        const std::uint64_t timestamp = currentOutputLowWater.published
            ? currentOutputLowWater.monotonicMicros
            : diagnosticMonotonicMicros();
        beginGuardedPublication(guard);
        lowWaterEvidenceSequence.store(
            saturatingIncremented(
                lowWaterEvidenceSequence.load(std::memory_order_relaxed)),
            std::memory_order_relaxed);
        lastMonotonicMicros.store(timestamp, std::memory_order_relaxed);
        lastGeneration.store(generation, std::memory_order_relaxed);
        guard.fetch_add(1U, std::memory_order_release);
    }

    void recovered() noexcept { episodeActive = false; }

    [[nodiscard]] NativePlaybackOutputWaitReasonDiagnostics snapshot() const noexcept {
        NativePlaybackOutputWaitReasonDiagnostics result{};
        for (unsigned attempt = 0U; attempt < 8U; ++attempt) {
            const std::uint64_t before = guard.load(std::memory_order_acquire);
            if ((before & 1U) != 0U) continue;
            result.lowWaterEvidenceSequence =
                lowWaterEvidenceSequence.load(std::memory_order_relaxed);
            result.lastMonotonicMicros =
                lastMonotonicMicros.load(std::memory_order_relaxed);
            result.lastGeneration = lastGeneration.load(std::memory_order_relaxed);
            std::atomic_thread_fence(std::memory_order_acquire);
            const std::uint64_t after = guard.load(std::memory_order_acquire);
            if (before == after) return result;
        }
        return {};
    }
};

struct AtomicCurrentOutputQueueLowWater final {
    // Only the render worker touches producerGeneration/producerLowWaterFrames.
    std::uint64_t producerGeneration{0};
    std::uint64_t producerLowWaterFrames{0};
    std::atomic<std::uint64_t> guard{0};
    std::atomic<std::uint64_t> generation{0};
    std::atomic<std::uint64_t> frames{0};
    std::atomic<std::uint64_t> monotonicMicros{0};

    [[nodiscard]] DiagnosticLowWaterPublication observe(
        std::uint64_t observedGeneration,
        std::size_t observedFrames) noexcept {
        const std::uint64_t boundedFrames = static_cast<std::uint64_t>(observedFrames);
        if (producerGeneration == observedGeneration &&
            boundedFrames >= producerLowWaterFrames) {
            return {};
        }
        producerGeneration = observedGeneration;
        producerLowWaterFrames = boundedFrames;
        const std::uint64_t timestamp = diagnosticMonotonicMicros();
        beginGuardedPublication(guard);
        generation.store(observedGeneration, std::memory_order_relaxed);
        frames.store(boundedFrames, std::memory_order_relaxed);
        monotonicMicros.store(timestamp, std::memory_order_relaxed);
        guard.fetch_add(1U, std::memory_order_release);
        return {true, timestamp};
    }

    [[nodiscard]] NativePlaybackCurrentOutputQueueLowWater snapshot() const noexcept {
        NativePlaybackCurrentOutputQueueLowWater result{};
        for (unsigned attempt = 0U; attempt < 8U; ++attempt) {
            const std::uint64_t before = guard.load(std::memory_order_acquire);
            if ((before & 1U) != 0U) continue;
            result.sequence = before / 2U;
            result.generation = generation.load(std::memory_order_relaxed);
            result.frames = frames.load(std::memory_order_relaxed);
            result.monotonicMicros = monotonicMicros.load(std::memory_order_relaxed);
            std::atomic_thread_fence(std::memory_order_acquire);
            const std::uint64_t after = guard.load(std::memory_order_acquire);
            if (before == after) return result;
        }
        return {};
    }
};

struct AtomicRenderReadinessDiagnostics final {
    std::atomic<std::uint64_t> guard{0};
    std::atomic<std::uint64_t> sequence{0};
    std::atomic<std::uint64_t> ingressEpoch{0};
    std::atomic<bool> assigned{false};
    std::atomic<std::uint8_t> bank{0};
    std::atomic<std::uint64_t> selectionToken{0};
    std::atomic<std::uint64_t> generation{0};
    std::atomic<std::uint64_t> controlRevision{0};
    std::atomic<std::uint8_t> desiredSourceMode{0};
    std::atomic<std::uint64_t> renderStartTimelineFrame{0};
    std::atomic<std::uint64_t> pendingControlRevision{0};
    std::atomic<std::uint8_t> pendingActualSourceMode{0};
    std::atomic<std::uint64_t> pendingSourceStartTimelineFrame{0};
    std::atomic<std::uint64_t> pendingSourceEndTimelineFrameExclusive{0};
    std::atomic<std::uint64_t> pendingFrames{0};
    std::atomic<std::uint64_t> outputQueuedFrames{0};
    std::atomic<std::uint64_t> stemActivationDeferrals{0};
    std::atomic<std::uint64_t> stemSafetyFallbacks{0};
    std::atomic<std::uint64_t> needStemDataCount{0};
    std::atomic<bool> currentSongCacheBoundToSelectedOwner{false};
    std::atomic<std::uint32_t> currentSongCacheCompleteLaneMask{0U};
    std::atomic<bool> currentSongCacheCanGrow{false};
    std::atomic<std::uint64_t> ingressBankConfiguredLaneCount{0};
    std::atomic<std::uint64_t> directAtomicPolicyGeneration{0};
    std::atomic<std::uint64_t> directAtomicPolicyPublishedRevision{0};
    std::atomic<std::uint64_t> directAtomicPolicyAdoptedRevision{0};
    std::atomic<std::uint64_t> directAtomicPolicyObservedRevision{0};
    std::atomic<bool> fixedLoopHeadPrepared{false};
    std::atomic<std::uint64_t> loopFailureToken{}, loopFailureGeneration{}, loopFailureRevision{};
    std::atomic<NativePlaybackLoopPreparationFailureReason> loopFailureReason{NativePlaybackLoopPreparationFailureReason::None};

    NativePlaybackLoopPreparationFailure loopFailure() const noexcept {
        return {{loopFailureToken.load(std::memory_order_relaxed), loopFailureGeneration.load(std::memory_order_relaxed)},
            loopFailureRevision.load(std::memory_order_relaxed), loopFailureReason.load(std::memory_order_relaxed)};
    }
    std::atomic<std::uint64_t> ingressServicePolicyBreaks{0};
    std::atomic<std::uint64_t> ingressBankIdentity{0};
    std::atomic<std::uint32_t> requiredSongLaneMask{0U};
    std::atomic<std::uint8_t> cacheStorageStatus{0U};
    struct AtomicLane final {
        std::atomic<std::uint64_t> generation{0};
        std::atomic<std::uint64_t> firstTimelineFrame{0};
        std::atomic<std::uint64_t> endTimelineFrameExclusive{0};
        std::atomic<std::uint64_t> availableFromRenderStart{0};
        std::atomic<std::uint64_t> reservoirGapRejectionCount{0};
        std::atomic<std::uint64_t> ingressFailureGeneration{0};
        std::atomic<std::uint64_t> ingressActiveGeneration{0};
        std::atomic<std::uint64_t> ingressProducerGeneration{0};
        std::atomic<std::uint64_t> ingressConsumerGeneration{0};
        std::atomic<std::uint64_t> ingressReadableSourceFrames{0};
        std::atomic<std::uint64_t> ingressPulls{0};
        std::atomic<std::uint64_t> ingressStaleAfterPull{0};
        std::atomic<std::uint64_t> ingressFutureGenerationDeferrals{0};
    };
    std::array<AtomicLane, kSongRenderLaneCount> lanes{};

    [[nodiscard]] NativePlaybackReadinessProgress controlProgress() const noexcept {
        NativePlaybackReadinessProgress result{};
        if (!readStableDiagnosticsGroup(guard, [&] {
            result = {};
            result.sequence = sequence.load(std::memory_order_relaxed);
            result.assigned = assigned.load(std::memory_order_relaxed);
            result.ownership = {selectionToken.load(std::memory_order_relaxed), generation.load(std::memory_order_relaxed)};
            result.controlRevision = controlRevision.load(std::memory_order_relaxed);
            result.cacheBound = currentSongCacheBoundToSelectedOwner.load(std::memory_order_relaxed);
            result.cacheCanGrow = currentSongCacheCanGrow.load(std::memory_order_relaxed);
            result.completeLaneMask = currentSongCacheCompleteLaneMask.load(std::memory_order_relaxed);
            result.requiredLaneMask = requiredSongLaneMask.load(std::memory_order_relaxed);
            result.storageStatus = static_cast<CurrentSongCacheStorageStatus>(cacheStorageStatus.load(std::memory_order_relaxed));
            result.fixedLoopHeadPrepared = fixedLoopHeadPrepared.load(std::memory_order_relaxed);
            result.loopPreparationFailure = loopFailure();
            result.directAtomicPolicyGeneration = directAtomicPolicyGeneration.load(std::memory_order_relaxed);
            for (std::size_t lane = 0U; lane < lanes.size(); ++lane) {
                const auto failure = lanes[lane].ingressFailureGeneration.load(std::memory_order_relaxed);
                const auto current = ingressEpoch.load(std::memory_order_relaxed);
                if (failure != 0U && failure == current) {
                    result.failureLane = static_cast<std::int32_t>(lane);
                    result.failureGeneration = failure;
                    break;
                }
            }
        })) return {};
        return result;
    }

    void publish(const NativePlaybackRenderReadinessDiagnostics &value, std::uint64_t epoch = 0U) noexcept {
        // The same publication owns both detail and compact failure attribution.
        beginGuardedPublication(guard);
        ingressEpoch.store(epoch, std::memory_order_relaxed);
        sequence.store(value.sequence, std::memory_order_relaxed);
        requiredSongLaneMask.store(value.requiredSongLaneMask, std::memory_order_relaxed);
        cacheStorageStatus.store(static_cast<std::uint8_t>(value.cacheStorageStatus), std::memory_order_relaxed);
        assigned.store(value.assigned, std::memory_order_relaxed);
        bank.store(static_cast<std::uint8_t>(value.bank), std::memory_order_relaxed);
        selectionToken.store(value.ownership.selectionToken, std::memory_order_relaxed);
        generation.store(value.ownership.generation, std::memory_order_relaxed);
        controlRevision.store(value.controlRevision, std::memory_order_relaxed);
        desiredSourceMode.store(
            static_cast<std::uint8_t>(value.desiredSourceMode),
            std::memory_order_relaxed);
        renderStartTimelineFrame.store(value.renderStartTimelineFrame, std::memory_order_relaxed);
        pendingControlRevision.store(value.pendingControlRevision, std::memory_order_relaxed);
        pendingActualSourceMode.store(
            static_cast<std::uint8_t>(value.pendingActualSourceMode),
            std::memory_order_relaxed);
        pendingSourceStartTimelineFrame.store(
            value.pendingSourceStartTimelineFrame,
            std::memory_order_relaxed);
        pendingSourceEndTimelineFrameExclusive.store(
            value.pendingSourceEndTimelineFrameExclusive,
            std::memory_order_relaxed);
        pendingFrames.store(value.pendingFrames, std::memory_order_relaxed);
        outputQueuedFrames.store(value.outputQueuedFrames, std::memory_order_relaxed);
        stemActivationDeferrals.store(value.stemActivationDeferrals, std::memory_order_relaxed);
        stemSafetyFallbacks.store(value.stemSafetyFallbacks, std::memory_order_relaxed);
        needStemDataCount.store(value.needStemDataCount, std::memory_order_relaxed);
        currentSongCacheBoundToSelectedOwner.store(
            value.currentSongCacheBoundToSelectedOwner,
            std::memory_order_relaxed);
        currentSongCacheCompleteLaneMask.store(
            value.currentSongCacheCompleteLaneMask,
            std::memory_order_relaxed);
        currentSongCacheCanGrow.store(value.currentSongCacheCanGrow, std::memory_order_relaxed);
        ingressBankConfiguredLaneCount.store(
            value.ingressBankConfiguredLaneCount,
            std::memory_order_relaxed);
        directAtomicPolicyGeneration.store(
            value.directAtomicPolicyGeneration,
            std::memory_order_relaxed);
        directAtomicPolicyPublishedRevision.store(
            value.directAtomicPolicyPublishedRevision,
            std::memory_order_relaxed);
        directAtomicPolicyAdoptedRevision.store(
            value.directAtomicPolicyAdoptedRevision,
            std::memory_order_relaxed);
        directAtomicPolicyObservedRevision.store(
            value.directAtomicPolicyObservedRevision,
            std::memory_order_relaxed);
        fixedLoopHeadPrepared.store(value.fixedLoopHeadPrepared, std::memory_order_relaxed);
        loopFailureToken.store(value.loopPreparationFailure.ownership.selectionToken, std::memory_order_relaxed);
        loopFailureGeneration.store(value.loopPreparationFailure.ownership.generation, std::memory_order_relaxed);
        loopFailureRevision.store(value.loopPreparationFailure.revision, std::memory_order_relaxed);
        loopFailureReason.store(value.loopPreparationFailure.reason, std::memory_order_relaxed);
        ingressServicePolicyBreaks.store(
            value.ingressServicePolicyBreaks,
            std::memory_order_relaxed);
        ingressBankIdentity.store(value.ingressBankIdentity, std::memory_order_relaxed);
        for (std::size_t lane = 0; lane < lanes.size(); ++lane) {
            lanes[lane].generation.store(value.lanes[lane].generation, std::memory_order_relaxed);
            lanes[lane].firstTimelineFrame.store(
                value.lanes[lane].firstTimelineFrame,
                std::memory_order_relaxed);
            lanes[lane].endTimelineFrameExclusive.store(
                value.lanes[lane].endTimelineFrameExclusive,
                std::memory_order_relaxed);
            lanes[lane].availableFromRenderStart.store(
                value.lanes[lane].availableFromRenderStart,
                std::memory_order_relaxed);
            lanes[lane].reservoirGapRejectionCount.store(
                value.lanes[lane].reservoirGapRejectionCount,
                std::memory_order_relaxed);
            lanes[lane].ingressFailureGeneration.store(
                value.lanes[lane].ingressFailureGeneration,
                std::memory_order_relaxed);
            lanes[lane].ingressActiveGeneration.store(
                value.lanes[lane].ingressActiveGeneration,
                std::memory_order_relaxed);
            lanes[lane].ingressProducerGeneration.store(
                value.lanes[lane].ingressProducerGeneration,
                std::memory_order_relaxed);
            lanes[lane].ingressConsumerGeneration.store(
                value.lanes[lane].ingressConsumerGeneration,
                std::memory_order_relaxed);
            lanes[lane].ingressReadableSourceFrames.store(
                value.lanes[lane].ingressReadableSourceFrames,
                std::memory_order_relaxed);
            lanes[lane].ingressPulls.store(
                value.lanes[lane].ingressPulls,
                std::memory_order_relaxed);
            lanes[lane].ingressStaleAfterPull.store(
                value.lanes[lane].ingressStaleAfterPull,
                std::memory_order_relaxed);
            lanes[lane].ingressFutureGenerationDeferrals.store(
                value.lanes[lane].ingressFutureGenerationDeferrals,
                std::memory_order_relaxed);
        }
        guard.fetch_add(1U, std::memory_order_release);
    }

    [[nodiscard]] NativePlaybackRenderReadinessDiagnostics snapshot() const noexcept {
        NativePlaybackRenderReadinessDiagnostics result{};
        for (unsigned attempt = 0U; attempt < 8U; ++attempt) {
            const std::uint64_t before = guard.load(std::memory_order_acquire);
            if ((before & 1U) != 0U) continue;
            result.sequence = sequence.load(std::memory_order_relaxed);
            result.assigned = assigned.load(std::memory_order_relaxed);
            result.bank = static_cast<NativePlaybackBank>(bank.load(std::memory_order_relaxed));
            result.ownership.selectionToken = selectionToken.load(std::memory_order_relaxed);
            result.ownership.generation = generation.load(std::memory_order_relaxed);
            result.controlRevision = controlRevision.load(std::memory_order_relaxed);
            result.desiredSourceMode = static_cast<MixerSourceMode>(
                desiredSourceMode.load(std::memory_order_relaxed));
            result.renderStartTimelineFrame =
                renderStartTimelineFrame.load(std::memory_order_relaxed);
            result.pendingControlRevision =
                pendingControlRevision.load(std::memory_order_relaxed);
            result.pendingActualSourceMode = static_cast<NativeAudioSourceMode>(
                pendingActualSourceMode.load(std::memory_order_relaxed));
            result.pendingSourceStartTimelineFrame =
                pendingSourceStartTimelineFrame.load(std::memory_order_relaxed);
            result.pendingSourceEndTimelineFrameExclusive =
                pendingSourceEndTimelineFrameExclusive.load(std::memory_order_relaxed);
            result.pendingFrames = pendingFrames.load(std::memory_order_relaxed);
            result.outputQueuedFrames = outputQueuedFrames.load(std::memory_order_relaxed);
            result.stemActivationDeferrals =
                stemActivationDeferrals.load(std::memory_order_relaxed);
            result.stemSafetyFallbacks = stemSafetyFallbacks.load(std::memory_order_relaxed);
            result.needStemDataCount = needStemDataCount.load(std::memory_order_relaxed);
            result.currentSongCacheBoundToSelectedOwner =
                currentSongCacheBoundToSelectedOwner.load(std::memory_order_relaxed);
            result.currentSongCacheCompleteLaneMask =
                currentSongCacheCompleteLaneMask.load(std::memory_order_relaxed);
            result.requiredSongLaneMask = requiredSongLaneMask.load(std::memory_order_relaxed);
            result.cacheStorageStatus = static_cast<CurrentSongCacheStorageStatus>(cacheStorageStatus.load(std::memory_order_relaxed));
            result.currentSongCacheCanGrow = currentSongCacheCanGrow.load(std::memory_order_relaxed);
            result.ingressBankConfiguredLaneCount =
                ingressBankConfiguredLaneCount.load(std::memory_order_relaxed);
            result.directAtomicPolicyGeneration =
                directAtomicPolicyGeneration.load(std::memory_order_relaxed);
            result.directAtomicPolicyPublishedRevision =
                directAtomicPolicyPublishedRevision.load(std::memory_order_relaxed);
            result.directAtomicPolicyAdoptedRevision =
                directAtomicPolicyAdoptedRevision.load(std::memory_order_relaxed);
            result.directAtomicPolicyObservedRevision =
                directAtomicPolicyObservedRevision.load(std::memory_order_relaxed);
            result.fixedLoopHeadPrepared = fixedLoopHeadPrepared.load(std::memory_order_relaxed);
            result.loopPreparationFailure = loopFailure();
            result.ingressServicePolicyBreaks =
                ingressServicePolicyBreaks.load(std::memory_order_relaxed);
            result.ingressBankIdentity = ingressBankIdentity.load(std::memory_order_relaxed);
            for (std::size_t lane = 0; lane < lanes.size(); ++lane) {
                result.lanes[lane] = {
                    lanes[lane].generation.load(std::memory_order_relaxed),
                    lanes[lane].firstTimelineFrame.load(std::memory_order_relaxed),
                    lanes[lane].endTimelineFrameExclusive.load(std::memory_order_relaxed),
                    lanes[lane].availableFromRenderStart.load(std::memory_order_relaxed),
                    lanes[lane].reservoirGapRejectionCount.load(std::memory_order_relaxed),
                    lanes[lane].ingressFailureGeneration.load(std::memory_order_relaxed),
                    lanes[lane].ingressActiveGeneration.load(std::memory_order_relaxed),
                    lanes[lane].ingressProducerGeneration.load(std::memory_order_relaxed),
                    lanes[lane].ingressConsumerGeneration.load(std::memory_order_relaxed),
                    lanes[lane].ingressReadableSourceFrames.load(std::memory_order_relaxed),
                    lanes[lane].ingressPulls.load(std::memory_order_relaxed),
                    lanes[lane].ingressStaleAfterPull.load(std::memory_order_relaxed),
                    lanes[lane].ingressFutureGenerationDeferrals.load(std::memory_order_relaxed),
                };
            }
            std::atomic_thread_fence(std::memory_order_acquire);
            const std::uint64_t after = guard.load(std::memory_order_acquire);
            if (before == after) return result;
        }
        return {};
    }
};
