struct FakeOutputRecord final {
    std::uint64_t generation{0};
    std::uint64_t token{0};
    std::uint64_t fingerprint{0};
    std::uint64_t controlRevision{0};
    std::uint64_t sourceIntentRevision{0};
    std::uint64_t stemIntentRevision{0};
    std::uint32_t stemBusReadyMask{0};
    NativeAudioSourceMode sourceMode{NativeAudioSourceMode::Original};
    bool sourceSettled{false};
    std::uint64_t loopPolicyRevision{0};
    std::uint64_t loopRevision{0};
    std::uint64_t loopCycle{0};
    bool loopEnabled{false};
    bool endOfStreamAfterBlock{false};
    double sourceStart{0.0};
    double sourceEnd{0.0};
    std::size_t frames{0};
    float peak{0.0F};
    std::uint64_t originalPcmHash{0U};
};

class FakeOutput final {
    enum class DrainCancellationState : std::uint8_t {
        Idle = 0,
        Requested = 1,
        ClaimedByCallback = 2,
    };

public:
    std::atomic<bool> rejectPlay{false};
    std::atomic<bool> captureObservationAvailable{true};
    std::atomic<std::uint64_t> playAttempts{0};
    explicit FakeOutput(std::size_t capacityFrames = 4'096, std::int32_t burst = 64)
        : capacity(capacityFrames),
          framesPerBurstValue(burst),
          records(std::make_unique<FakeOutputRecord[]>(kMaximumFakeRecords)) {}

    [[nodiscard]] NativePlaybackOutputPort port() noexcept {
        NativePlaybackOutputPort result{};
        result.handle = static_cast<NativeAudioOutputHandle>(this);
        result.enqueue = &enqueueThunk;
        result.publishLateMixControl = &publishLateMixControlThunk;
        result.publishLoopDemand = &publishLoopDemandThunk;
        result.retireLoopDemand = [](NativeAudioOutputHandle handle, std::uint64_t revision,
            std::uint64_t generation, std::uint64_t token) noexcept {
            auto *output = self(handle);
            output->retiredLoopDemand.retire(revision, generation, token);
        };
        result.pendingLoopCapture = [](NativeAudioOutputHandle handle,
            const NativeAudioOutputLoopPolicy &demand) noexcept {
            auto *output = self(handle);
            if (!output->captureObservationAvailable.load(std::memory_order_acquire))
                return stemwise::audio::NativeAudioPendingLoopCapture{};
            NativeAudioOutputLoopPolicy captured{};
            if (output->loopCapturePublishes.load(std::memory_order_acquire) != 0U &&
                !output->loopCaptureMailbox.tryRead(captured)) return stemwise::audio::NativeAudioPendingLoopCapture{};
            const bool pending = demand.enabled && captured.revision > output->retiredCaptureRevision.load(std::memory_order_acquire) &&
                captured.generation == demand.generation && captured.selectionToken == demand.selectionToken &&
                captured.aTimelineFrame == demand.aTimelineFrame && captured.heldBGestureId == demand.heldBGestureId &&
                captured.revision <= demand.revision;
            return stemwise::audio::NativeAudioPendingLoopCapture{true, pending ? captured.revision : 0U};
        };
        result.publishLoopPolicy = &publishLoopPolicyThunk;
        result.beginLoopCutover = &beginLoopCutoverThunk;
        result.cancelLoopCutover = &cancelLoopCutoverThunk;
        result.enqueueLoopCutover = &enqueueLoopCutoverThunk;
        result.commitLoopCutover = &commitLoopCutoverThunk;
        result.releaseLoopCutoverWrites = &releaseLoopCutoverWritesThunk;
        result.loopBoundaryCoverage = &loopBoundaryCoverageThunk;
        result.beginHandoff = &beginHandoffThunk;
        result.commitHandoff = &commitHandoffThunk;
        result.flush = &flushThunk;
        result.setPlaying = &setPlayingThunk;
        result.setTerminalSilenceAuthorization = &setTerminalSilenceAuthorizationThunk;
        result.publishTerminalBoundary = &publishTerminalBoundaryThunk;
        result.queuedFrames = &queuedThunk;
        result.writableFrames = &writableThunk;
        result.framesPerBurst = &burstThunk;
        result.loopCutoverRequiredFrames = &loopCutoverRequiredFramesThunk;
        result.presentationEvidence = &evidenceThunk;
        result.presentedPosition = &positionThunk;
        result.appliedControlEvidence = &controlEvidenceThunk;
        result.stemGainCallbackEvidence = &stemGainEvidenceThunk;
        result.audibleLoopEvidence = &loopEvidenceThunk;
        result.beginDrainHandoff = &beginDrainHandoffThunk;
        result.commitDrainHandoff = &commitDrainHandoffThunk;
        result.cancelDrainHandoff = &cancelDrainHandoffThunk;
        result.queuedFramesForGeneration = &queuedForGenerationThunk;
        result.writableFramesForGeneration = &writableForGenerationThunk;
        return result;
    }

    void setMaximumAcceptance(std::size_t frames) noexcept {
        maximumAcceptance.store(frames, std::memory_order_release);
    }

    void setAudiblePosition(
        std::uint64_t generation,
        std::uint64_t selectionToken,
        std::int64_t timelineFrame) noexcept {
        audibleGeneration.store(generation, std::memory_order_relaxed);
        audibleToken.store(selectionToken, std::memory_order_relaxed);
        audibleTimeline.store(timelineFrame, std::memory_order_relaxed);
        audibleOverride.store(true, std::memory_order_release);
    }

    void clearAudiblePositionOverride() noexcept {
        audibleOverride.store(false, std::memory_order_release);
    }

    void setCommitAllowed(bool allowed) noexcept {
        commitAllowed.store(allowed, std::memory_order_release);
    }

    void setLoopBoundaryCoverageAllowed(bool allowed) noexcept {
        loopBoundaryCoverageAllowed.store(allowed, std::memory_order_release);
    }

    void setLoopCutoverRequiredFrames(std::size_t frames) noexcept {
        loopCutoverRequiredFrameCount.store(frames, std::memory_order_release);
    }

    void setLoopCutoverEnqueueBlocked(bool blocked) noexcept {
        if (blocked) {
            enteredLoopCutoverEnqueue.store(false, std::memory_order_release);
        }
        blockLoopCutoverEnqueue.store(blocked, std::memory_order_release);
    }

    void pauseNextLoopCutoverEnqueueAfterAcceptance() noexcept {
        loopCutoverEnqueuePauseEntered.store(false, std::memory_order_release);
        loopCutoverEnqueuePauseTimedOut.store(false, std::memory_order_release);
        pauseLoopCutoverEnqueueAfterAcceptance.store(true, std::memory_order_release);
    }

    void releaseLoopCutoverEnqueuePause() noexcept {
        pauseLoopCutoverEnqueueAfterAcceptance.store(false, std::memory_order_release);
    }

    [[nodiscard]] bool loopCutoverEnqueuePauseIsEntered() const noexcept {
        return loopCutoverEnqueuePauseEntered.load(std::memory_order_acquire);
    }

    [[nodiscard]] bool loopCutoverEnqueuePauseExpired() const noexcept {
        return loopCutoverEnqueuePauseTimedOut.load(std::memory_order_acquire);
    }

    void setLoopCutoverCancellationInFlight(bool inFlight) noexcept {
        if (inFlight) loopCutoverCancellationObserved.store(false, std::memory_order_release);
        loopCutoverCancellationInFlight.store(inFlight, std::memory_order_release);
    }

    [[nodiscard]] bool loopCancellationWasObserved() const noexcept {
        return loopCutoverCancellationObserved.load(std::memory_order_acquire);
    }

    void adoptCommittedLoopAtNextCancellation() noexcept {
        adoptLoopOnCancellation.store(true, std::memory_order_release);
    }

    void setLoopCutoverWriteReleaseBlocked(bool blocked) noexcept {
        blockLoopCutoverWriteRelease.store(blocked, std::memory_order_release);
    }

    void retireNextLoopCutoverCommit() noexcept {
        retireNextLoopCutoverCommitAttempt.store(true, std::memory_order_release);
    }

    [[nodiscard]] bool loopCutoverEnqueueEntered() const noexcept {
        return enteredLoopCutoverEnqueue.load(std::memory_order_acquire);
    }

    [[nodiscard]] double blockedLoopCutoverSourceStart() const noexcept {
        return blockedLoopCutoverStart.load(std::memory_order_acquire);
    }

    [[nodiscard]] std::size_t lastCancelledLoopCutoverQueuedFrames() const noexcept {
        return cancelledLoopCutoverQueuedFrames.load(std::memory_order_acquire);
    }

    void rejectNextLoopPolicyPublication() noexcept {
        rejectNextLoopPolicy.store(true, std::memory_order_release);
    }

    void rejectNextLoopDemandPublication() noexcept {
        rejectNextLoopDemand.store(true, std::memory_order_release);
    }

    [[nodiscard]] std::uint64_t loopDemandPublishCount() const noexcept {
        return loopDemandPublishes.load(std::memory_order_acquire);
    }

    [[nodiscard]] std::uint64_t rejectedLoopDemandPublishCount() const noexcept {
        return rejectedLoopDemandPublishes.load(std::memory_order_acquire);
    }

    [[nodiscard]] std::uint64_t rejectedLoopPolicyPublishCount() const noexcept {
        return rejectedLoopPolicyPublishes.load(std::memory_order_acquire);
    }

    void forceNextFlushStatus(NativeAudioFlushStatus status) noexcept {
        forcedFlushStatus.store(
            static_cast<std::uint8_t>(status),
            std::memory_order_release);
    }

    void setAutoAcknowledgePausedCommit(bool enabled) noexcept {
        autoAcknowledgePausedCommit.store(enabled, std::memory_order_release);
    }

    /** Holds the worker inside the output commit so ordering can be observed deterministically. */
    void setDrainCommitBlocked(bool blocked) noexcept {
        blockDrainCommit.store(blocked, std::memory_order_release);
    }

    [[nodiscard]] bool drainCommitEntered() const noexcept {
        return enteredDrainCommit.load(std::memory_order_acquire);
    }

    [[nodiscard]] std::size_t recordCount() const noexcept {
        return publishedRecordCount.load(std::memory_order_acquire);
    }

    [[nodiscard]] std::size_t currentGenerationFirstRecord() const noexcept {
        return generationFirstRecord.load(std::memory_order_acquire);
    }

    [[nodiscard]] std::uint64_t publishedPositionSequence() const noexcept {
        return positionSequence.load(std::memory_order_acquire);
    }

    [[nodiscard]] std::uint64_t lastPositionSequenceRead() const noexcept {
        return lastPositionSequenceReadValue.load(std::memory_order_acquire);
    }

    [[nodiscard]] FakeOutputRecord record(std::size_t index) const noexcept {
        return index < recordCount() ? records[index] : FakeOutputRecord{};
    }

    [[nodiscard]] std::uint64_t flushCount() const noexcept {
        return flushCalls.load(std::memory_order_acquire);
    }

    [[nodiscard]] std::uint64_t beginCount() const noexcept {
        return beginCalls.load(std::memory_order_acquire);
    }

    [[nodiscard]] std::uint64_t commitCount() const noexcept {
        return commitCalls.load(std::memory_order_acquire);
    }

    [[nodiscard]] std::uint64_t loopCutoverCommitCount() const noexcept {
        return loopCutoverCommitCalls.load(std::memory_order_acquire);
    }

    [[nodiscard]] std::size_t loopCutoverFramesAtCommit() const noexcept {
        return loopCutoverQueuedFramesAtCommit.load(std::memory_order_acquire);
    }

    [[nodiscard]] std::uint64_t lastLoopCutoverBeginMicros() const noexcept {
        return loopCutoverBeginMicros.load(std::memory_order_acquire);
    }

    [[nodiscard]] std::uint64_t lastLoopCutoverCommitMicros() const noexcept {
        return loopCutoverCommitMicros.load(std::memory_order_acquire);
    }

    [[nodiscard]] std::uint64_t loopCutoverBeginCount() const noexcept {
        return loopCutoverBeginCalls.load(std::memory_order_acquire);
    }

    [[nodiscard]] std::uint64_t loopCutoverCommitAttemptCount() const noexcept {
        return loopCutoverCommitAttempts.load(std::memory_order_acquire);
    }

    [[nodiscard]] std::uint64_t loopCutoverWriteReleaseCount() const noexcept {
        return loopCutoverWriteReleases.load(std::memory_order_acquire);
    }

    [[nodiscard]] std::uint64_t activeEnqueueBackpressureCount() const noexcept {
        return activeEnqueueBackpressure.load(std::memory_order_acquire);
    }

    [[nodiscard]] bool loopCutoverIsCommitted() const noexcept {
        return loopCutoverCommitted.load(std::memory_order_acquire);
    }

    [[nodiscard]] bool loopCutoverIsOpen() const noexcept {
        return loopCutoverStageOpen.load(std::memory_order_acquire);
    }

    [[nodiscard]] std::uint64_t loopPolicyPublishCount() const noexcept {
        return loopPolicyPublishCalls.load(std::memory_order_acquire);
    }

    [[nodiscard]] NativeAudioOutputLoopPolicy lastPublishedLoopPolicy() const noexcept {
        static_cast<void>(loopPolicyPublishCalls.load(std::memory_order_acquire));
        return {
            .revision = lastLoopPolicyRevision.load(std::memory_order_relaxed),
            .generation = lastLoopPolicyGeneration.load(std::memory_order_relaxed),
            .selectionToken = lastLoopPolicyToken.load(std::memory_order_relaxed),
            .enabled = lastLoopPolicyEnabled.load(std::memory_order_relaxed),
            .physicalReady = lastLoopPolicyPhysicalReady.load(std::memory_order_relaxed),
            .aTimelineFrame = lastLoopPolicyA.load(std::memory_order_relaxed),
            .bTimelineFrameExclusive = lastLoopPolicyB.load(std::memory_order_relaxed),
            .cutoverTimelineFrameExclusive =
                lastLoopPolicyCutover.load(std::memory_order_relaxed),
            .continuationTimelineFrame =
                lastLoopPolicyContinuationFrame.load(std::memory_order_relaxed),
            .cutoverSourceRevision =
                lastLoopPolicyCutoverSourceRevision.load(std::memory_order_relaxed),
            .continuationRevision =
                lastLoopPolicyContinuationRevision.load(std::memory_order_relaxed),
            .nextRevisionOnly =
                lastLoopPolicyNextRevisionOnly.load(std::memory_order_relaxed),
        };
    }

    [[nodiscard]] NativeAudioOutputLoopPolicy acceptedLoopPolicy() const noexcept {
        NativeAudioOutputLoopPolicy policy{};
        static_cast<void>(loopPolicyMailbox.tryRead(policy));
        return policy;
    }

    [[nodiscard]] NativeAudioOutputLoopPolicy lastPublishedLoopDemand() const noexcept {
        static_cast<void>(loopDemandPublishes.load(std::memory_order_acquire));
        return lastLoopDemand;
    }

    [[nodiscard]] NativeAudioOutputLoopPolicy lastCapturedLoopDemand() const noexcept {
        static_cast<void>(loopCapturePublishes.load(std::memory_order_acquire));
        return lastLoopCapture;
    }

    [[nodiscard]] std::size_t loopCutoverStagedFrames() const noexcept {
        return loopCutoverQueuedFrames.load(std::memory_order_acquire);
    }

    [[nodiscard]] bool observeCommittedLoopCutover(
        std::uint64_t revision) const noexcept {
        return loopCutoverCommitted.load(std::memory_order_acquire) &&
            loopCutoverRevision.load(std::memory_order_relaxed) == revision;
    }

    [[nodiscard]] FakeOutputRecord committedLoopCutoverHead() const noexcept {
        return record(loopCutoverFirstRecord.load(std::memory_order_acquire));
    }

    [[nodiscard]] NativeAudioLoopBoundaryCoverage loopBoundaryCoverageForTest(
        std::uint64_t generation,
        std::uint64_t token,
        std::uint64_t cutover,
        std::uint64_t expectedLoopPolicyRevision) const noexcept {
        return loopBoundaryCoverage(
            generation,
            token,
            cutover,
            expectedLoopPolicyRevision);
    }

    [[nodiscard]] bool presentCommittedLoopCutover(bool publishPosition = true) noexcept {
        if (!loopCutoverLateControlBound.load(std::memory_order_acquire)) return false;
        if (!loopBoundaryCoverageAllowed.load(std::memory_order_acquire)) return false;
        if (!loopCutoverCommitted.exchange(false, std::memory_order_acq_rel)) return false;
        loopCutoverWritesAwaitingRelease.store(true, std::memory_order_release);
        queuedFrameCount.store(
            loopCutoverQueuedFrames.exchange(0U, std::memory_order_acq_rel),
            std::memory_order_release);
        generationFirstRecord.store(
            loopCutoverFirstRecord.load(std::memory_order_acquire),
            std::memory_order_release);
        // A real callback records the logical demand it bound to this prepared role.
        callbackLoopAdoptedRevision.store(lastLoopDemand.revision, std::memory_order_release);
        NativeAudioOutputLoopPolicy captured{};
        const auto head = committedLoopCutoverHead();
        if (loopCaptureMailbox.tryRead(captured) && captured.generation == head.generation &&
            captured.selectionToken == head.token && static_cast<double>(captured.aTimelineFrame) == head.sourceStart &&
            captured.revision <= lastLoopDemand.revision && captured.heldBGestureId == lastLoopDemand.heldBGestureId) {
            retiredCaptureRevision.store(captured.revision, std::memory_order_release);
        }
        return !publishPosition || presentFirstCurrentRecord();
    }

    [[nodiscard]] std::uint64_t drainBeginCount() const noexcept {
        return drainBeginCalls.load(std::memory_order_acquire);
    }

    [[nodiscard]] std::uint64_t drainCommitCount() const noexcept {
        return drainCommitCalls.load(std::memory_order_acquire);
    }

    [[nodiscard]] std::uint64_t drainCancelAttemptCount() const noexcept {
        return drainCancelAttempts.load(std::memory_order_acquire);
    }

    [[nodiscard]] std::uint64_t terminalSilenceAuthorization() const noexcept {
        return terminalSilenceAuthorizedGeneration.load(std::memory_order_acquire);
    }

    [[nodiscard]] std::uint64_t lateControlPublicationCount() const noexcept {
        return lateControlSuccessfulPublications.load(std::memory_order_acquire);
    }

    [[nodiscard]] NativeAudioLateMixControl lastLateControl() const noexcept {
        std::scoped_lock lock(lateControlObservationMutex);
        return lastPublishedLateControlForTest;
    }

    [[nodiscard]] bool lateControlForOwner(
        SongRenderOwnership ownership,
        NativeAudioLateMixControl &result) const noexcept {
        std::scoped_lock lock(lateControlObservationMutex);
        const NativeAudioLateMixControl *control = lateControlFor(
            ownership.generation,
            ownership.selectionToken);
        if (control == nullptr) return false;
        result = *control;
        return true;
    }

    [[nodiscard]] bool sawLateControlForOwner(
        SongRenderOwnership ownership,
        std::uint64_t canonicalRevision,
        NativeAudioSourceMode sourceMode) const noexcept {
        const std::size_t historyCount =
            lateControlHistoryCount.load(std::memory_order_acquire);
        for (std::size_t index = 0U; index < historyCount; ++index) {
            const NativeAudioLateMixControl &control = lateControlHistory[index];
            if (control.generation == ownership.generation &&
                control.selectionToken == ownership.selectionToken &&
                control.canonicalControlRevision == canonicalRevision &&
                control.sourceMode == sourceMode) {
                return true;
            }
        }
        return false;
    }

    /** Callback test seam: claim cancellation while leaving acknowledgement deliberately pending. */
    [[nodiscard]] bool claimRequestedDrainCancellationWithoutAcknowledgement() noexcept {
        DrainCancellationState expected = DrainCancellationState::Requested;
        return drainCancellationState.compare_exchange_strong(
            expected,
            DrainCancellationState::ClaimedByCallback,
            std::memory_order_acq_rel,
            std::memory_order_acquire);
    }

    /** Callback test seam: retire the exact published drain after a successful cancellation claim. */
    [[nodiscard]] bool acknowledgeClaimedDrainCancellation() noexcept {
        if (drainCancellationState.load(std::memory_order_acquire) !=
            DrainCancellationState::ClaimedByCallback) {
            return false;
        }
        drainStageOpen.store(false, std::memory_order_release);
        drainCommitted.store(false, std::memory_order_release);
        stagedQueuedFrameCount.store(0U, std::memory_order_release);
        // Publish Idle only after the retired role is fully observable. A producer retry while the
        // callback owns the claim must see ClaimedByCallback, never manufacture a second request.
        drainCancellationState.store(DrainCancellationState::Idle, std::memory_order_release);
        return true;
    }

    /** Callback test seam: claim a committed drain swap, but do not read successor PCM yet. */
    [[nodiscard]] bool claimCommittedDrainWithoutReading() noexcept {
        if (!drainCommitted.load(std::memory_order_acquire) ||
            queuedFrameCount.load(std::memory_order_acquire) != 0U) {
            return false;
        }
        bool expected = false;
        return drainSwapClaimed.compare_exchange_strong(
            expected,
            true,
            std::memory_order_acq_rel,
            std::memory_order_acquire);
    }

    /** Completes the callback role acknowledgement without manufacturing presentation evidence. */
    [[nodiscard]] bool acknowledgeClaimedDrainWithoutPresentation() noexcept {
        if (!drainSwapClaimed.load(std::memory_order_acquire) ||
            !drainCommitted.load(std::memory_order_acquire)) {
            return false;
        }
        const std::uint64_t successor =
            drainSuccessorGeneration.load(std::memory_order_acquire);
        lastDrainSuccessorGeneration.store(successor, std::memory_order_release);
        producerGeneration.store(successor, std::memory_order_release);
        callbackGeneration.store(successor, std::memory_order_release);
        generationFirstRecord.store(
            drainSuccessorFirstRecord.load(std::memory_order_acquire),
            std::memory_order_release);
        queuedFrameCount.store(
            stagedQueuedFrameCount.exchange(0U, std::memory_order_acq_rel),
            std::memory_order_release);
        drainStageOpen.store(false, std::memory_order_release);
        drainCommitted.store(false, std::memory_order_release);
        // Publish the successor completely before permitting producer cancellation again.
        drainSwapClaimed.store(false, std::memory_order_release);
        return true;
    }

    struct CallbackResult final {
        std::size_t outgoingFrames{0};
        std::size_t successorFrames{0};
        std::size_t zeroFrames{0};
    };

    [[nodiscard]] CallbackResult consumeCallback(std::size_t requestedFrames) noexcept {
        CallbackResult result{};
        acknowledgeRequestedDrainCancellation();
        if (requestedFrames == 0U || !isPlaying.load(std::memory_order_acquire)) return result;

        const auto adoptDrain = [&]() noexcept {
            if (!drainCommitted.load(std::memory_order_acquire) ||
                queuedFrameCount.load(std::memory_order_acquire) != 0U) {
                return false;
            }
            const std::uint64_t successor = drainSuccessorGeneration.load(
                std::memory_order_acquire);
            lastDrainSuccessorGeneration.store(successor, std::memory_order_release);
            producerGeneration.store(successor, std::memory_order_release);
            callbackGeneration.store(successor, std::memory_order_release);
            generationFirstRecord.store(
                drainSuccessorFirstRecord.load(std::memory_order_acquire),
                std::memory_order_release);
            queuedFrameCount.store(
                stagedQueuedFrameCount.exchange(0U, std::memory_order_acq_rel),
                std::memory_order_release);
            drainStageOpen.store(false, std::memory_order_release);
            drainCommitted.store(false, std::memory_order_release);
            return true;
        };

        static_cast<void>(adoptDrain());
        const bool beganOnSuccessor = callbackGeneration.load(std::memory_order_acquire) ==
            producerGeneration.load(std::memory_order_acquire) &&
            producerGeneration.load(std::memory_order_acquire) ==
                lastDrainSuccessorGeneration.load(std::memory_order_acquire);
        std::size_t queued = queuedFrameCount.load(std::memory_order_acquire);
        const std::size_t firstRead = std::min(queued, requestedFrames);
        queuedFrameCount.fetch_sub(firstRead, std::memory_order_acq_rel);
        std::size_t remaining = requestedFrames - firstRead;
        if (beganOnSuccessor) {
            result.successorFrames = firstRead;
        } else {
            result.outgoingFrames = firstRead;
        }

        result.successorFrames += stemwise::audio::callbackReadDrainRemainder(
            remaining, adoptDrain, [&](std::size_t maximum) noexcept {
                const auto frames = std::min(queuedFrameCount.load(std::memory_order_acquire), maximum);
                queuedFrameCount.fetch_sub(frames, std::memory_order_acq_rel);
                remaining -= frames;
                return frames;
            });
        result.zeroFrames = remaining;

        if (result.successorFrames != 0U) {
            const std::uint64_t generation = callbackGeneration.load(std::memory_order_acquire);
            const std::size_t begin = generationFirstRecord.load(std::memory_order_acquire);
            const std::size_t end = publishedRecordCount.load(std::memory_order_acquire);
            for (std::size_t index = begin; index < end; ++index) {
                FakeOutputRecord candidate = records[index];
                if (candidate.generation != generation || candidate.frames == 0U) continue;
                candidate.frames = result.successorFrames;
                candidate.sourceEnd = candidate.sourceStart +
                    static_cast<double>(result.successorFrames);
                if (result.outgoingFrames != 0U) candidate.sourceSettled = false;
                publishEvidence(candidate);
                break;
            }
        }
        return result;
    }

    [[nodiscard]] std::size_t queued() const noexcept {
        return queuedFrameCount.load(std::memory_order_acquire);
    }

    [[nodiscard]] bool playing() const noexcept {
        return isPlaying.load(std::memory_order_acquire);
    }

    void setCallbackPressureReserve(std::size_t frames) noexcept {
        callbackPressureReserve.store(frames, std::memory_order_release);
        if (frames != 0U && queued() > frames) static_cast<void>(consumeFrames(queued() - frames));
    }

    void consumeAll() noexcept {
        queuedFrameCount.store(0U, std::memory_order_release);
        publishTerminalPositionIfCurrentDrained();
    }

    [[nodiscard]] std::size_t consumeFrames(std::size_t requestedFrames) noexcept {
        std::size_t queued = queuedFrameCount.load(std::memory_order_acquire);
        for (;;) {
            const std::size_t consumed = std::min(queued, requestedFrames);
            if (queuedFrameCount.compare_exchange_weak(
                    queued,
                    queued - consumed,
                    std::memory_order_acq_rel,
                    std::memory_order_acquire)) {
                if (queued == consumed) publishTerminalPositionIfCurrentDrained();
                return consumed;
            }
        }
    }

    void advanceAudiblePosition(std::uint64_t frames = 64U) noexcept {
        if (positionSequenceValue == 0U) return;
        stemwise::audio::beginGuardedPublication(positionVersion);
        positionSequenceValue += 1U;
        positionLastTimelineValue += static_cast<std::int64_t>(frames);
        positionLastOutputValue += frames;
        positionSequence.store(positionSequenceValue, std::memory_order_relaxed);
        positionLastTimeline.store(positionLastTimelineValue, std::memory_order_relaxed);
        positionLastOutput.store(positionLastOutputValue, std::memory_order_relaxed);
        positionCallback.store(positionSequenceValue, std::memory_order_relaxed);
        positionFrames.store(frames, std::memory_order_relaxed);
        positionTerminalAfterFrame.store(0U, std::memory_order_relaxed);
        positionVersion.fetch_add(1U, std::memory_order_release);
    }

    [[nodiscard]] bool firstCurrentRecord(FakeOutputRecord &result) noexcept {
        const std::size_t begin = generationFirstRecord.load(std::memory_order_acquire);
        const std::size_t end = publishedRecordCount.load(std::memory_order_acquire);
        const bool awaiting = commitAwaitingAcknowledgement.load(std::memory_order_acquire);
        const std::uint64_t generation = awaiting
            ? publishedGeneration.load(std::memory_order_acquire)
            : callbackGeneration.load(std::memory_order_acquire);
        if (awaiting) {
            callbackGeneration.store(generation, std::memory_order_release);
            commitAwaitingAcknowledgement.store(false, std::memory_order_release);
        }
        for (std::size_t index = begin; index < end; ++index) {
            const FakeOutputRecord candidate = records[index];
            if (candidate.generation != generation || candidate.frames == 0U) continue;
            result = candidate;
            return true;
        }
        return false;
    }

    [[nodiscard]] bool presentFirstCurrentRecord() noexcept {
        FakeOutputRecord candidate{};
        if (!firstCurrentRecord(candidate)) return false;
        publishEvidence(candidate);
        return true;
    }

    /** Test seam: publish the callback's position tuple before its ownership tuple. */
    [[nodiscard]] bool presentFirstCurrentPositionOnly() noexcept {
        FakeOutputRecord candidate{};
        if (!firstCurrentRecord(candidate)) return false;
        publishPositionOnly(candidate);
        return true;
    }

    /** Publishes ownership without manufacturing another position or control tuple. */
    [[nodiscard]] bool presentFirstCurrentPresentationOnly() noexcept {
        FakeOutputRecord candidate{};
        if (!firstCurrentRecord(candidate)) return false;
        publishPresentationOnly(candidate);
        return true;
    }

    [[nodiscard]] bool presentRecord(std::size_t index) noexcept {
        if (index >= publishedRecordCount.load(std::memory_order_acquire)) return false;
        const FakeOutputRecord candidate = records[index];
        if (candidate.frames == 0U ||
            candidate.generation != callbackGeneration.load(std::memory_order_acquire)) {
            return false;
        }
        publishEvidence(candidate);
        return true;
    }

    /** Simulates one callback spanning several producer records with the production coherence rule. */
    [[nodiscard]] bool presentControlSpan(std::size_t begin, std::size_t count) noexcept {
        const std::size_t end = begin + count;
        if (count == 0U || end < begin ||
            end > publishedRecordCount.load(std::memory_order_acquire)) {
            return false;
        }
        const FakeOutputRecord first = records[begin];
        std::size_t frames = 0U;
        bool coherent = first.sourceSettled && first.controlRevision != 0U;
        for (std::size_t index = begin; index < end; ++index) {
            const FakeOutputRecord candidate = records[index];
            frames += candidate.frames;
            coherent = coherent && candidate.sourceSettled &&
                candidate.generation == first.generation &&
                candidate.token == first.token &&
                candidate.controlRevision == first.controlRevision &&
                candidate.sourceMode == first.sourceMode;
        }
        if (!coherent) return false;
        publishControlEvidence(first, records[end - 1U], frames);
        return true;
    }

    /** Test-only stale callback injection; the runtime must filter it to the committed owner. */
    void injectControlEvidence(std::size_t index) noexcept {
        if (index >= publishedRecordCount.load(std::memory_order_acquire)) return;
        publishControlEvidence(records[index], records[index], records[index].frames);
    }

private:
    static FakeOutput *self(NativeAudioOutputHandle handle) noexcept {
        return static_cast<FakeOutput *>(handle);
    }

    static bool publishLateMixControlThunk(
        NativeAudioOutputHandle handle,
        const NativeAudioLateMixControl &control) noexcept {
        return self(handle)->publishLateMixControl(control);
    }

    static bool publishLoopPolicyThunk(
        NativeAudioOutputHandle handle,
        const NativeAudioOutputLoopPolicy &policy) noexcept {
        return self(handle)->publishLoopPolicy(policy);
    }

    static bool publishLoopDemandThunk(
        NativeAudioOutputHandle handle,
        const NativeAudioOutputLoopPolicy &policy,
        bool boundaryCrossedAtPublication,
        std::uint64_t capturedBTimelineFrameExclusive) noexcept {
        if (!policy.valid() || policy.physicalReady) return false;
        FakeOutput *output = self(handle);
        if (output->rejectNextLoopDemand.exchange(false, std::memory_order_acq_rel)) {
            output->rejectedLoopDemandPublishes.fetch_add(1U, std::memory_order_release);
            return false;
        }
        output->lastLoopDemand = policy;
        output->loopDemandPublishes.fetch_add(1U, std::memory_order_release);
        if (boundaryCrossedAtPublication) {
            output->lastLoopCapture = policy;
            output->lastLoopCapture.bTimelineFrameExclusive =
                capturedBTimelineFrameExclusive;
            if (!output->loopCaptureMailbox.publish(output->lastLoopCapture)) return false;
            output->loopCapturePublishes.fetch_add(1U, std::memory_order_release);
        }
        return true;
    }

    static bool beginLoopCutoverThunk(
        NativeAudioOutputHandle handle,
        std::uint64_t generation,
        std::uint64_t revision) noexcept {
        return self(handle)->beginLoopCutover(generation, revision);
    }

    static NativeAudioDrainCancelStatus cancelLoopCutoverThunk(
        NativeAudioOutputHandle handle,
        std::uint64_t generation,
        std::uint64_t revision) noexcept {
        return self(handle)->cancelLoopCutover(generation, revision);
    }

    static NativeAudioLoopCutoverCommitStatus commitLoopCutoverThunk(
        NativeAudioOutputHandle handle,
        const NativeAudioOutputLoopPolicy &policy) noexcept {
        return self(handle)->commitLoopCutover(policy);
    }

    static bool releaseLoopCutoverWritesThunk(
        NativeAudioOutputHandle handle,
        std::uint64_t generation,
        std::uint64_t revision) noexcept {
        return self(handle)->releaseLoopCutoverWrites(generation, revision);
    }

    static NativeAudioLoopBoundaryCoverage loopBoundaryCoverageThunk(
        NativeAudioOutputHandle handle,
        std::uint64_t generation,
        std::uint64_t token,
        std::uint64_t cutover,
        std::uint64_t expectedLoopPolicyRevision) noexcept {
        return self(handle)->loopBoundaryCoverage(
            generation,
            token,
            cutover,
            expectedLoopPolicyRevision);
    }

    static std::size_t enqueueThunk(NativeAudioOutputHandle handle, const NativeRenderBusSubmission &submission) noexcept {
        const auto generation = submission.generation;
        const auto token = submission.selectionToken;
        const auto fingerprint = submission.payloadSequence;
        const auto payloadHash = submission.payloadHash;
        const auto controlRevision = submission.canonicalControlRevision;
        const auto sourceIntentRevision = submission.sourceIntentRevision;
        const auto stemIntentRevision = submission.stemIntentRevision;
        const auto stemBusReadyMask = submission.stemBusReadyMask;
        const auto loopPolicyRevision = submission.loopPolicyRevision;
        const auto loopRevision = submission.loopRevision;
        const auto loopCycle = submission.loopCycle;
        const auto loopEnabled = submission.loopEnabled;
        const auto endOfStreamAfterBlock = submission.endOfStreamAfterInput;
        const auto sourceStart = submission.sourceStartFrame;
        const auto sourceEnd = submission.sourceEndFrameExclusive;
        const auto samples = submission.samples;
        const auto frames = submission.frameCount;

        return self(handle)->enqueue(
            generation,
            token,
            fingerprint,
            payloadHash,
            controlRevision,
            sourceIntentRevision,
            stemIntentRevision,
            stemBusReadyMask,
            loopPolicyRevision,
            loopRevision,
            loopCycle,
            loopEnabled,
            endOfStreamAfterBlock,
            sourceStart,
            sourceEnd,
            samples,
            frames,
            false);
    }

    static std::size_t enqueueLoopCutoverThunk(NativeAudioOutputHandle handle, const NativeRenderBusSubmission &submission) noexcept {
        const auto generation = submission.generation;
        const auto token = submission.selectionToken;
        const auto fingerprint = submission.payloadSequence;
        const auto payloadHash = submission.payloadHash;
        const auto controlRevision = submission.canonicalControlRevision;
        const auto sourceIntentRevision = submission.sourceIntentRevision;
        const auto stemIntentRevision = submission.stemIntentRevision;
        const auto stemBusReadyMask = submission.stemBusReadyMask;
        const auto loopPolicyRevision = submission.loopPolicyRevision;
        const auto loopRevision = submission.loopRevision;
        const auto loopCycle = submission.loopCycle;
        const auto loopEnabled = submission.loopEnabled;
        const auto endOfStreamAfterBlock = submission.endOfStreamAfterInput;
        const auto sourceStart = submission.sourceStartFrame;
        const auto sourceEnd = submission.sourceEndFrameExclusive;
        const auto samples = submission.samples;
        const auto frames = submission.frameCount;

        return self(handle)->enqueue(
            generation,
            token,
            fingerprint,
            payloadHash,
            controlRevision,
            sourceIntentRevision,
            stemIntentRevision,
            stemBusReadyMask,
            loopPolicyRevision,
            loopRevision,
            loopCycle,
            loopEnabled,
            endOfStreamAfterBlock,
            sourceStart,
            sourceEnd,
            samples,
            frames,
            true);
    }

    static NativeAudioFlushStatus flushThunk(
        NativeAudioOutputHandle handle,
        std::uint64_t generation) noexcept {
        return self(handle)->flush(generation);
    }

    static bool beginHandoffThunk(
        NativeAudioOutputHandle handle,
        std::uint64_t generation) noexcept {
        return self(handle)->beginHandoff(generation);
    }

    static bool commitHandoffThunk(
        NativeAudioOutputHandle handle,
        std::uint64_t generation) noexcept {
        return self(handle)->commitHandoff(generation);
    }

    static bool beginDrainHandoffThunk(
        NativeAudioOutputHandle handle,
        std::uint64_t expectedActiveGeneration,
        std::uint64_t stagedGeneration,
        std::uint64_t transitionRevision) noexcept {
        return self(handle)->beginDrainHandoff(
            expectedActiveGeneration,
            stagedGeneration,
            transitionRevision);
    }

    static bool commitDrainHandoffThunk(
        NativeAudioOutputHandle handle,
        std::uint64_t expectedActiveGeneration,
        std::uint64_t stagedGeneration,
        std::uint64_t transitionRevision) noexcept {
        return self(handle)->commitDrainHandoff(
            expectedActiveGeneration,
            stagedGeneration,
            transitionRevision);
    }

    static NativeAudioDrainCancelStatus cancelDrainHandoffThunk(
        NativeAudioOutputHandle handle,
        std::uint64_t expectedActiveGeneration,
        std::uint64_t stagedGeneration,
        std::uint64_t transitionRevision) noexcept {
        return self(handle)->cancelDrainHandoff(
            expectedActiveGeneration,
            stagedGeneration,
            transitionRevision);
    }

    static bool setPlayingThunk(NativeAudioOutputHandle handle, bool playing) noexcept {
        if (playing) {
            self(handle)->playAttempts.fetch_add(1U, std::memory_order_relaxed);
            if (self(handle)->rejectPlay.load(std::memory_order_acquire)) return false;
        }
        self(handle)->isPlaying.store(playing, std::memory_order_release);
        return true;
    }

    static void setTerminalSilenceAuthorizationThunk(
        NativeAudioOutputHandle handle,
        std::uint64_t generation,
        bool authorized) noexcept {
        self(handle)->terminalSilenceAuthorizedGeneration.store(
            authorized ? generation : 0U,
            std::memory_order_release);
    }

    static bool publishTerminalBoundaryThunk(NativeAudioOutputHandle handle, std::uint64_t generation,
        std::uint64_t token, std::uint64_t physicalRevision, double sourceEnd) noexcept {
        auto &output = *self(handle);
        for (auto index = output.recordCount(); index != 0U; --index) {
            const auto &record = output.records[index - 1U];
            if (record.generation == generation && record.loopPolicyRevision == physicalRevision)
                return record.token == token && record.sourceEnd == sourceEnd;
        }
        return false;
    }

    static std::size_t queuedThunk(NativeAudioOutputHandle handle) noexcept {
        return self(handle)->queuedFrameCount.load(std::memory_order_acquire);
    }

    static std::size_t writableThunk(NativeAudioOutputHandle handle) noexcept {
        FakeOutput *output = self(handle);
        const std::size_t queued = output->queuedFrameCount.load(std::memory_order_acquire);
        return queued >= output->capacity ? 0U : output->capacity - queued;
    }

    static std::size_t queuedForGenerationThunk(
        NativeAudioOutputHandle handle,
        std::uint64_t generation) noexcept {
        return self(handle)->queuedForGeneration(generation);
    }

    static std::size_t writableForGenerationThunk(
        NativeAudioOutputHandle handle,
        std::uint64_t generation) noexcept {
        return self(handle)->writableForGeneration(generation);
    }

    static std::int32_t burstThunk(NativeAudioOutputHandle handle) noexcept {
        return self(handle)->framesPerBurstValue;
    }

    static std::size_t loopCutoverRequiredFramesThunk(
        NativeAudioOutputHandle handle) noexcept {
        const FakeOutput *output = self(handle);
        return std::max(
            static_cast<std::size_t>(std::max(output->framesPerBurstValue, 1)),
            output->loopCutoverRequiredFrameCount.load(std::memory_order_acquire));
    }

    static NativeAudioPresentationEvidence evidenceThunk(
        NativeAudioOutputHandle handle) noexcept {
        return self(handle)->evidence();
    }

    static NativeAudioPresentedPosition positionThunk(
        NativeAudioOutputHandle handle) noexcept {
        return self(handle)->position();
    }

    static NativeAudioAppliedControlEvidence controlEvidenceThunk(
        NativeAudioOutputHandle handle) noexcept {
        return self(handle)->controlEvidence();
    }

    static NativeAudioStemGainCallbackEvidence stemGainEvidenceThunk(
        NativeAudioOutputHandle handle) noexcept {
        return self(handle)->stemGainEvidence();
    }

    static NativeAudioAudibleLoopEvidence loopEvidenceThunk(
        NativeAudioOutputHandle handle) noexcept {
        return self(handle)->loopEvidence();
    }

    [[nodiscard]] bool publishLateMixControl(
        const NativeAudioLateMixControl &control) noexcept {
        if (control.publicationSequence == 0U ||
            control.generation == 0U || control.selectionToken == 0U ||
            control.canonicalControlRevision == 0U ||
            control.sourceIntentRevision == 0U || control.stemIntentRevision == 0U ||
            lastLateControlPublication == std::numeric_limits<std::uint64_t>::max() ||
            !std::isfinite(control.musicGain) || !std::isfinite(control.mixHeadroom)) {
            return false;
        }
        const bool activeTarget =
            control.generation == producerGeneration.load(std::memory_order_acquire);
        const bool stagedTarget =
            (drainStageOpen.load(std::memory_order_acquire) ||
             drainCommitted.load(std::memory_order_acquire)) &&
            control.generation ==
                drainSuccessorGeneration.load(std::memory_order_acquire);
        if (!activeTarget && !stagedTarget) return false;

        std::scoped_lock observationLock(lateControlObservationMutex);
        for (std::size_t index = 0U; index < lateControls.size(); ++index)
            if (lateControlReadable[index] && lateControls[index].generation == control.generation &&
                lateControls[index].selectionToken != control.selectionToken) return false;
        std::size_t slot = lateControls.size();
        for (std::size_t index = 0U; index < lateControls.size(); ++index) {
            if (lateControlReadable[index] &&
                lateControls[index].generation == control.generation &&
                lateControls[index].selectionToken == control.selectionToken) {
                slot = index;
                break;
            }
        }
        if (slot == lateControls.size()) {
            for (std::size_t index = 0U; index < lateControls.size(); ++index) {
                if (!lateControlReadable[index]) {
                    slot = index;
                    break;
                }
            }
        }
        if (slot == lateControls.size()) {
            slot = lateControls[0].generation <= lateControls[1].generation ? 0U : 1U;
        }
        const bool sameOwner = lateControlReadable[slot] &&
            lateControls[slot].generation == control.generation &&
            lateControls[slot].selectionToken == control.selectionToken;
        if (!sameOwner && !lateControlMailbox.resetRole(
                static_cast<std::uint32_t>(slot), control.generation, control.selectionToken,
                true)) {
            return false;
        }
        const stemwise::audio::LateStemMixControlSnapshot actualControl{
            .publicationSequence = ++lastLateControlPublication,
            .generation = control.generation,
            .selectionToken = control.selectionToken,
            .canonicalControlRevision = control.canonicalControlRevision,
            .sourceIntentRevision = control.sourceIntentRevision,
            .stemIntentRevision = control.stemIntentRevision,
            .stemGainPreviewPublicationSequence = control.stemGainPreviewPublicationSequence,
            .stemGainPreviewCoversOfferSequence = control.stemGainPreviewCoversOfferSequence,
            .stemGainPreviewActiveMask = control.stemGainPreviewActiveMask,
            .stemGainPreviewGainBits = control.stemGainPreviewGainBits,
            .sourceMode = control.sourceMode == NativeAudioSourceMode::Stems
                ? stemwise::audio::LateStemSourceMode::Stems
                : stemwise::audio::LateStemSourceMode::Original,
            .requiredStemMask = control.requiredStemMask,
            .effectiveStemGains = control.effectiveStemGains,
            .musicGain = control.musicGain,
            .mixHeadroom = control.mixHeadroom,
            .sourceRampFrames = control.sourceRampFrames,
            .laneRampFrames = control.laneRampFrames,
            .masterRampFrames = control.masterRampFrames,
        };
        if (!lateControlMailbox.publish(static_cast<std::uint32_t>(slot), actualControl)) return false;
        const bool sourceChanged = sameOwner &&
            lateControls[slot].sourceMode != control.sourceMode;
        lateSourceTransitionRemaining[slot] = sourceChanged
            ? control.sourceRampFrames
            : (sameOwner ? lateSourceTransitionRemaining[slot] : 0U);
        lateControls[slot] = control;
        lateControlReadable[slot] = true;
        lastPublishedLateControlForTest = control;
        const std::size_t historyCount =
            lateControlHistoryCount.load(std::memory_order_relaxed);
        if (historyCount < lateControlHistory.size()) {
            lateControlHistory[historyCount] = control;
            lateControlHistoryCount.store(historyCount + 1U, std::memory_order_release);
        }
        lateControlSuccessfulPublications.fetch_add(1U, std::memory_order_release);
        if (loopCutoverStageOpen.load(std::memory_order_acquire) &&
            control.generation == loopCutoverGeneration.load(std::memory_order_acquire)) {
            loopCutoverLateControlBound.store(true, std::memory_order_release);
        }
        return true;
    }

    [[nodiscard]] bool publishLoopPolicy(
        const NativeAudioOutputLoopPolicy &policy) noexcept {
        if (!policy.valid()) return false;
        if (rejectNextLoopPolicy.exchange(false, std::memory_order_acq_rel)) {
            rejectedLoopPolicyPublishes.fetch_add(1U, std::memory_order_release);
            return false;
        }
        if (!loopPolicyMailbox.publish(policy)) return false;
        recordLoopPolicy(policy);
        return true;
    }

    void recordLoopPolicy(const NativeAudioOutputLoopPolicy &policy) noexcept {
        lastLoopPolicyGeneration.store(policy.generation, std::memory_order_relaxed);
        lastLoopPolicyToken.store(policy.selectionToken, std::memory_order_relaxed);
        lastLoopPolicyA.store(policy.aTimelineFrame, std::memory_order_relaxed);
        lastLoopPolicyB.store(policy.bTimelineFrameExclusive, std::memory_order_relaxed);
        lastLoopPolicyEnabled.store(policy.enabled, std::memory_order_relaxed);
        lastLoopPolicyPhysicalReady.store(policy.physicalReady, std::memory_order_relaxed);
        lastLoopPolicyCutover.store(
            policy.cutoverTimelineFrameExclusive,
            std::memory_order_relaxed);
        lastLoopPolicyContinuationFrame.store(
            policy.continuationTimelineFrame,
            std::memory_order_relaxed);
        lastLoopPolicyCutoverSourceRevision.store(
            policy.cutoverSourceRevision,
            std::memory_order_relaxed);
        lastLoopPolicyContinuationRevision.store(
            policy.physicalRevision(),
            std::memory_order_relaxed);
        lastLoopPolicyNextRevisionOnly.store(
            policy.nextRevisionOnly,
            std::memory_order_relaxed);
        lastLoopPolicyRevision.store(policy.revision, std::memory_order_relaxed);
        loopPolicyPublishCalls.fetch_add(1U, std::memory_order_release);
    }

    [[nodiscard]] NativeAudioLoopBoundaryCoverage loopBoundaryCoverage(
        std::uint64_t generation,
        std::uint64_t token,
        std::uint64_t cutover,
        std::uint64_t expectedLoopPolicyRevision) const noexcept {
        if (generation == 0U || token == 0U || cutover == 0U ||
            !loopBoundaryCoverageAllowed.load(std::memory_order_acquire)) return {};
        const bool exactPresented =
            positionGeneration.load(std::memory_order_acquire) == generation &&
            positionToken.load(std::memory_order_relaxed) == token;
        const std::int64_t rawPresented = exactPresented
            ? positionLastTimeline.load(std::memory_order_relaxed)
            : -1;
        std::int64_t previous = rawPresented;
        bool unreadStarted = !exactPresented;
        std::size_t prefix = 0U;
        const std::size_t end = publishedRecordCount.load(std::memory_order_acquire);
        for (std::size_t index = generationFirstRecord.load(std::memory_order_acquire);
             index < end;
             ++index) {
            const FakeOutputRecord candidate = records[index];
            if (candidate.generation != generation || candidate.token != token ||
                (expectedLoopPolicyRevision !=
                     std::numeric_limits<std::uint64_t>::max() &&
                 candidate.loopPolicyRevision != expectedLoopPolicyRevision) ||
                candidate.frames == 0U) {
                continue;
            }
            const std::int64_t first = static_cast<std::int64_t>(
                std::floor(candidate.sourceStart));
            const std::int64_t last = static_cast<std::int64_t>(
                std::ceil(candidate.sourceEnd)) - 1;
            if (!unreadStarted) {
                if (first <= rawPresented && rawPresented <= last) {
                    unreadStarted = true;
                    continue;
                }
                if (last <= rawPresented) continue;
                unreadStarted = true;
            }
            const std::int64_t unreadFirst = first;
            if (previous >= 0 && unreadFirst < previous) {
                return {
                    .boundaryQueued = true,
                    .unreadPrefixFrames = prefix,
                    .queuedFrames = queuedFrameCount.load(std::memory_order_relaxed),
                    .boundaryLoopPolicyRevision = candidate.loopPolicyRevision,
                };
            }
            if (unreadFirst >= static_cast<std::int64_t>(cutover) ||
                last >= static_cast<std::int64_t>(cutover)) {
                return {
                    .boundaryQueued = true,
                    .unreadPrefixFrames = prefix + static_cast<std::size_t>(
                        std::max<std::int64_t>(
                            0,
                            static_cast<std::int64_t>(cutover) - unreadFirst)),
                    .queuedFrames = queuedFrameCount.load(std::memory_order_relaxed),
                    .boundaryLoopPolicyRevision = candidate.loopPolicyRevision,
                };
            }
            prefix += static_cast<std::size_t>(last - unreadFirst + 1);
            previous = last;
        }
        return {
            .boundaryQueued = false,
            .unreadPrefixFrames = prefix,
            .queuedFrames = queuedFrameCount.load(std::memory_order_relaxed),
        };
    }

    [[nodiscard]] const NativeAudioLateMixControl *lateControlFor(
        std::uint64_t generation,
        std::uint64_t token) const noexcept {
        for (std::size_t index = 0U; index < lateControls.size(); ++index) {
            if (lateControlReadable[index] && lateControls[index].generation == generation &&
                lateControls[index].selectionToken == token) {
                return &lateControls[index];
            }
        }
        return nullptr;
    }

    [[nodiscard]] std::size_t enqueue(
        std::uint64_t generation,
        std::uint64_t token,
        std::uint64_t fingerprint,
        std::uint64_t, // Optional diagnostic hash; this sink validates owner and sequence.
        std::uint64_t controlRevision,
        std::uint64_t sourceIntentRevision,
        std::uint64_t stemIntentRevision,
        std::uint32_t stemBusReadyMask,
        std::uint64_t loopPolicyRevision,
        std::uint64_t loopRevision,
        std::uint64_t loopCycle,
        bool loopEnabled,
        bool endOfStreamAfterBlock,
        double sourceStart,
        double sourceEnd,
        const float *samples,
        std::size_t frames,
        bool explicitLoopCutover) noexcept {
        if (samples == nullptr || frames == 0U || token == 0U ||
            fingerprint == 0U) {
            return 0U;
        }
        const NativeAudioLateMixControl *control = lateControlFor(generation, token);
        if (control == nullptr || control->canonicalControlRevision < controlRevision ||
            control->sourceIntentRevision < sourceIntentRevision ||
            control->stemIntentRevision < stemIntentRevision) {
            return 0U;
        }
        const bool loopCutoverTarget = explicitLoopCutover &&
            (loopCutoverStageOpen.load(std::memory_order_acquire) ||
             loopCutoverCommitted.load(std::memory_order_acquire)) &&
            generation == loopCutoverGeneration.load(std::memory_order_acquire);
        if (loopCutoverTarget) {
            enteredLoopCutoverEnqueue.store(true, std::memory_order_release);
            if (blockLoopCutoverEnqueue.load(std::memory_order_acquire)) {
                blockedLoopCutoverStart.store(sourceStart, std::memory_order_release);
                return 0U;
            }
        }
        const bool stagedTarget =
            (drainStageOpen.load(std::memory_order_acquire) ||
             drainCommitted.load(std::memory_order_acquire)) &&
            generation == drainSuccessorGeneration.load(std::memory_order_acquire);
        const bool activeTarget =
            generation == producerGeneration.load(std::memory_order_acquire) &&
            !loopCutoverWritesAwaitingRelease.load(std::memory_order_acquire);
        if (!loopCutoverTarget && !stagedTarget && !activeTarget) return 0U;
        std::atomic<std::size_t> &targetQueue = loopCutoverTarget
            ? loopCutoverQueuedFrames
            : (stagedTarget ? stagedQueuedFrameCount : queuedFrameCount);
        const std::size_t queued = targetQueue.load(std::memory_order_acquire);
        const std::size_t writable = queued >= capacity ? 0U : capacity - queued;
        const std::size_t acceptanceLimit = maximumAcceptance.load(std::memory_order_acquire);
        const std::size_t accepted = std::min({frames, writable, acceptanceLimit});
        if (accepted == 0U) {
            if (activeTarget && !loopCutoverTarget && !stagedTarget) {
                activeEnqueueBackpressure.fetch_add(1U, std::memory_order_release);
            }
            return 0U;
        }
        std::size_t lateControlSlot = lateControls.size();
        for (std::size_t slot = 0U; slot < lateControls.size(); ++slot) {
            if (lateControlReadable[slot] &&
                lateControls[slot].generation == generation &&
                lateControls[slot].selectionToken == token) {
                lateControlSlot = slot;
                break;
            }
        }
        if (lateControlSlot == lateControls.size()) return 0U;
        const bool sourceSettled =
            lateSourceTransitionRemaining[lateControlSlot] == 0U;
        if (!sourceSettled) {
            const auto acceptedFrames = static_cast<std::uint32_t>(
                std::min<std::size_t>(accepted, std::numeric_limits<std::uint32_t>::max()));
            lateSourceTransitionRemaining[lateControlSlot] = acceptedFrames >=
                    lateSourceTransitionRemaining[lateControlSlot]
                ? 0U
                : lateSourceTransitionRemaining[lateControlSlot] - acceptedFrames;
        }
        const std::size_t index = nextRecordIndex;
        if (index >= kMaximumFakeRecords) return 0U;
        const double fraction = static_cast<double>(accepted) / static_cast<double>(frames);
        float peak = 0.0F;
        std::uint64_t originalPcmHash = 14695981039346656037ULL;
        const bool stemsReady = control->requiredStemMask != 0U &&
            (stemBusReadyMask & control->requiredStemMask) == control->requiredStemMask;
        const bool useStems =
            control->sourceMode == NativeAudioSourceMode::Stems && stemsReady;
        for (std::size_t frame = 0U; frame < accepted; ++frame) {
            const float *payload = samples + frame * kStemwiseMultibusStride;
            for (std::size_t channel = 0U; channel < 2U; ++channel) {
                originalPcmHash ^= std::bit_cast<std::uint32_t>(payload[stemwiseOriginalChannel(channel)]);
                originalPcmHash *= 1099511628211ULL;
            }
            float left = payload[stemwiseOriginalChannel(0U)];
            float right = payload[stemwiseOriginalChannel(1U)];
            if (useStems) {
                left = 0.0F;
                right = 0.0F;
                for (std::size_t lane = 0U; lane < control->effectiveStemGains.size(); ++lane) {
                    left += payload[stemwiseStemChannel(lane, 0U)] *
                        control->effectiveStemGains[lane];
                    right += payload[stemwiseStemChannel(lane, 1U)] *
                        control->effectiveStemGains[lane];
                }
            }
            const float envelope = std::clamp(
                payload[stemwise::audio::kStemwiseMultibusEnvelopeChannel],
                0.0F,
                1.0F);
            const float click = payload[stemwise::audio::kStemwiseMultibusClickChannel];
            left = (left * control->musicGain * control->mixHeadroom + click) * envelope;
            right = (right * control->musicGain * control->mixHeadroom + click) * envelope;
            peak = std::max({peak, std::abs(left), std::abs(right)});
        }
        records[index] = {
            generation,
            token,
            fingerprint,
            controlRevision,
            sourceIntentRevision,
            stemIntentRevision,
            stemBusReadyMask,
            control->sourceMode,
            sourceSettled,
            loopPolicyRevision,
            loopRevision,
            loopCycle,
            loopEnabled,
            endOfStreamAfterBlock && accepted == frames,
            sourceStart,
            sourceStart + (sourceEnd - sourceStart) * fraction,
            accepted,
            peak,
            originalPcmHash,
        };
        nextRecordIndex = index + 1U;
        if (loopCutoverTarget && queued == 0U) {
            loopCutoverFirstRecord.store(index, std::memory_order_release);
        }
        if (loopCutoverTarget) loopCutoverLastRecord.store(index, std::memory_order_release);
        targetQueue.fetch_add(accepted, std::memory_order_release);
        const std::size_t pressureReserve = callbackPressureReserve.load(std::memory_order_acquire);
        if (!loopCutoverTarget && !stagedTarget && pressureReserve != 0U &&
            queuedFrameCount.load(std::memory_order_acquire) > pressureReserve) {
            static_cast<void>(consumeFrames(queuedFrameCount.load(std::memory_order_acquire) - pressureReserve));
        }
        publishedRecordCount.store(nextRecordIndex, std::memory_order_release);
        if (loopCutoverTarget &&
            pauseLoopCutoverEnqueueAfterAcceptance.load(std::memory_order_acquire)) {
            loopCutoverEnqueuePauseEntered.store(true, std::memory_order_release);
            const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(3);
            while (pauseLoopCutoverEnqueueAfterAcceptance.load(std::memory_order_acquire)) {
                if (std::chrono::steady_clock::now() >= deadline) {
                    loopCutoverEnqueuePauseTimedOut.store(true, std::memory_order_release);
                    pauseLoopCutoverEnqueueAfterAcceptance.store(false, std::memory_order_release);
                    break;
                }
                std::this_thread::yield();
            }
        }
        return accepted;
    }

    [[nodiscard]] NativeAudioFlushStatus flush(std::uint64_t generation) noexcept {
        const auto forced = static_cast<NativeAudioFlushStatus>(
            forcedFlushStatus.exchange(
                static_cast<std::uint8_t>(NativeAudioFlushStatus::Accepted),
                std::memory_order_acq_rel));
        if (forced != NativeAudioFlushStatus::Accepted) return forced;
        if (generation <= generationHighWater.load(std::memory_order_acquire)) {
            return NativeAudioFlushStatus::StaleGeneration;
        }
        if (commitAwaitingAcknowledgement.load(std::memory_order_acquire) ||
            drainCancellationState.load(std::memory_order_acquire) !=
                DrainCancellationState::Idle) {
            return NativeAudioFlushStatus::RetryAfterCallbackAcknowledgement;
        }
        queuedFrameCount.store(0U, std::memory_order_release);
        generationFirstRecord.store(nextRecordIndex, std::memory_order_release);
        producerGeneration.store(generation, std::memory_order_release);
        generationHighWater.store(generation, std::memory_order_release);
        callbackGeneration.store(generation, std::memory_order_release);
        stageOpen.store(false, std::memory_order_release);
        flushCalls.fetch_add(1U, std::memory_order_release);
        return NativeAudioFlushStatus::Accepted;
    }

    [[nodiscard]] bool beginLoopCutover(
        std::uint64_t generation,
        std::uint64_t revision) noexcept {
        if (revision == 0U || generation != producerGeneration.load(std::memory_order_acquire) ||
            generation != callbackGeneration.load(std::memory_order_acquire) ||
            loopCutoverStageOpen.load(std::memory_order_acquire) ||
            loopCutoverCommitted.load(std::memory_order_acquire) ||
            drainStageOpen.load(std::memory_order_acquire) ||
            drainCommitted.load(std::memory_order_acquire)) {
            return false;
        }
        loopCutoverQueuedFrames.store(0U, std::memory_order_release);
        loopCutoverGeneration.store(generation, std::memory_order_release);
        loopCutoverRevision.store(revision, std::memory_order_release);
        loopCutoverFirstRecord.store(kMaximumFakeRecords, std::memory_order_release);
        loopCutoverLateControlBound.store(false, std::memory_order_release);
        loopCutoverStageOpen.store(true, std::memory_order_release);
        loopCutoverBeginMicros.store(testMonotonicMicros(), std::memory_order_release);
        loopCutoverBeginCalls.fetch_add(1U, std::memory_order_release);
        return true;
    }

    [[nodiscard]] NativeAudioDrainCancelStatus cancelLoopCutover(
        std::uint64_t generation,
        std::uint64_t revision) noexcept {
        if (adoptLoopOnCancellation.exchange(false, std::memory_order_acq_rel)) {
            loopBoundaryCoverageAllowed.store(true, std::memory_order_release);
            if (!presentCommittedLoopCutover()) std::terminate();
            loopBoundaryCoverageAllowed.store(false, std::memory_order_release);
        }
        if (generation != loopCutoverGeneration.load(std::memory_order_acquire) ||
            revision != loopCutoverRevision.load(std::memory_order_acquire)) {
            return NativeAudioDrainCancelStatus::NoMatchingDrain;
        }
        if (loopCutoverStageOpen.exchange(false, std::memory_order_acq_rel)) {
            cancelledLoopCutoverQueuedFrames.store(
                loopCutoverQueuedFrames.load(std::memory_order_relaxed),
                std::memory_order_release);
            loopCutoverQueuedFrames.store(0U, std::memory_order_release);
            return NativeAudioDrainCancelStatus::CancelledOpenStage;
        }
        if (loopCutoverCommitted.load(std::memory_order_acquire) &&
            loopCutoverCancellationInFlight.load(std::memory_order_acquire)) {
            loopCutoverCancellationObserved.store(true, std::memory_order_release);
            return NativeAudioDrainCancelStatus::AdoptionInFlight;
        }
        if (loopCutoverCommitted.exchange(false, std::memory_order_acq_rel)) {
            cancelledLoopCutoverQueuedFrames.store(
                loopCutoverQueuedFrames.load(std::memory_order_relaxed),
                std::memory_order_release);
            loopCutoverQueuedFrames.store(0U, std::memory_order_release);
            return NativeAudioDrainCancelStatus::CancelledOpenStage;
        }
        return NativeAudioDrainCancelStatus::OutgoingActive;
    }

    [[nodiscard]] NativeAudioLoopCutoverCommitStatus commitLoopCutover(
        const NativeAudioOutputLoopPolicy &policy) noexcept {
        loopCutoverCommitAttempts.fetch_add(1U, std::memory_order_release);
        if (loopCutoverCommitted.load(std::memory_order_acquire)) {
            return NativeAudioLoopCutoverCommitStatus::Retry;
        }
        if (!policy.valid() || !loopCutoverStageOpen.load(std::memory_order_acquire) ||
            !policy.hasPhysicalCutover() ||
            policy.generation != loopCutoverGeneration.load(std::memory_order_acquire) ||
            policy.physicalRevision() != loopCutoverRevision.load(std::memory_order_acquire) ||
            loopCutoverQueuedFrames.load(std::memory_order_acquire) == 0U) {
            return NativeAudioLoopCutoverCommitStatus::Retry;
        }
        const std::size_t headIndex = loopCutoverFirstRecord.load(std::memory_order_acquire);
        if (headIndex >= publishedRecordCount.load(std::memory_order_acquire)) {
            return NativeAudioLoopCutoverCommitStatus::Retired;
        }
        const FakeOutputRecord head = records[headIndex];
        if (head.generation != policy.generation || head.token != policy.selectionToken ||
            head.loopPolicyRevision != policy.physicalRevision() ||
            static_cast<std::uint64_t>(std::floor(head.sourceStart)) !=
                policy.continuationTimelineFrame) {
            return NativeAudioLoopCutoverCommitStatus::Retired;
        }
        const bool continuationLoopEnabled = policy.enabled &&
            policy.continuationTimelineFrame >= policy.aTimelineFrame &&
            policy.continuationTimelineFrame < policy.bTimelineFrameExclusive;
        if (head.loopEnabled != continuationLoopEnabled ||
            (continuationLoopEnabled
                 ? head.loopRevision != policy.physicalRevision()
                 : head.loopRevision != 0U)) {
            return NativeAudioLoopCutoverCommitStatus::Retired;
        }
        if (loopCutoverQueuedFrames.load(std::memory_order_acquire) <
                loopCutoverRequiredFramesThunk(static_cast<NativeAudioOutputHandle>(this))) {
            const auto tailIndex = loopCutoverLastRecord.load(std::memory_order_acquire);
            if (tailIndex >= publishedRecordCount.load(std::memory_order_acquire))
                return NativeAudioLoopCutoverCommitStatus::Retry;
            const auto &tail = records[tailIndex];
            if (continuationLoopEnabled || tail.generation != policy.generation ||
                tail.token != policy.selectionToken || tail.loopPolicyRevision != policy.physicalRevision() ||
                tail.loopEnabled || !tail.endOfStreamAfterBlock)
                return NativeAudioLoopCutoverCommitStatus::Retry;
        }
        if (retireNextLoopCutoverCommitAttempt.exchange(false, std::memory_order_acq_rel)) {
            loopCutoverStageOpen.store(false, std::memory_order_release);
            loopCutoverQueuedFrames.store(0U, std::memory_order_release);
            return NativeAudioLoopCutoverCommitStatus::Retired;
        }
        if (!committedLoopPolicyMailbox.publish(policy)) {
            return NativeAudioLoopCutoverCommitStatus::Retired;
        }
        if (!policy.nextRevisionOnly) {
            static_cast<void>(loopPolicyMailbox.publish(policy));
        }
        loopCutoverStageOpen.store(false, std::memory_order_release);
        loopCutoverCommitted.store(true, std::memory_order_release);
        loopCutoverQueuedFramesAtCommit.store(
            loopCutoverQueuedFrames.load(std::memory_order_relaxed),
            std::memory_order_release);
        recordLoopPolicy(policy);
        loopCutoverCommitMicros.store(testMonotonicMicros(), std::memory_order_release);
        loopCutoverCommitCalls.fetch_add(1U, std::memory_order_release);
        return NativeAudioLoopCutoverCommitStatus::Committed;
    }

    [[nodiscard]] bool releaseLoopCutoverWrites(
        std::uint64_t generation,
        std::uint64_t revision) noexcept {
        if (blockLoopCutoverWriteRelease.load(std::memory_order_acquire)) return false;
        if (generation != loopCutoverGeneration.load(std::memory_order_acquire) ||
            revision != loopCutoverRevision.load(std::memory_order_relaxed) ||
            !loopCutoverWritesAwaitingRelease.exchange(false, std::memory_order_acq_rel)) {
            return false;
        }
        loopCutoverWriteReleases.fetch_add(1U, std::memory_order_release);
        return true;
    }

    [[nodiscard]] bool beginHandoff(std::uint64_t generation) noexcept {
        if (generation <= generationHighWater.load(std::memory_order_acquire) ||
            commitAwaitingAcknowledgement.load(std::memory_order_acquire)) return false;
        queuedFrameCount.store(0U, std::memory_order_release);
        generationFirstRecord.store(nextRecordIndex, std::memory_order_release);
        producerGeneration.store(generation, std::memory_order_release);
        generationHighWater.store(generation, std::memory_order_release);
        stageOpen.store(true, std::memory_order_release);
        beginCalls.fetch_add(1U, std::memory_order_release);
        return true;
    }

    [[nodiscard]] bool commitHandoff(std::uint64_t generation) noexcept {
        if (!stageOpen.load(std::memory_order_acquire) ||
            !commitAllowed.load(std::memory_order_acquire) ||
            commitAwaitingAcknowledgement.load(std::memory_order_acquire) ||
            generation != producerGeneration.load(std::memory_order_acquire) ||
            queuedFrameCount.load(std::memory_order_acquire) == 0U) {
            return false;
        }
        publishedGeneration.store(generation, std::memory_order_release);
        stageOpen.store(false, std::memory_order_release);
        commitAwaitingAcknowledgement.store(true, std::memory_order_release);
        commitCalls.fetch_add(1U, std::memory_order_release);
        if (autoAcknowledgePausedCommit.load(std::memory_order_acquire)) {
            callbackGeneration.store(generation, std::memory_order_release);
            commitAwaitingAcknowledgement.store(false, std::memory_order_release);
        }
        return true;
    }

    [[nodiscard]] bool beginDrainHandoff(
        std::uint64_t expectedActiveGeneration,
        std::uint64_t stagedGeneration,
        std::uint64_t transitionRevision) noexcept {
        if (transitionRevision == 0U ||
            expectedActiveGeneration != producerGeneration.load(std::memory_order_acquire) ||
            expectedActiveGeneration != callbackGeneration.load(std::memory_order_acquire) ||
            stagedGeneration <= generationHighWater.load(std::memory_order_acquire) ||
            drainStageOpen.load(std::memory_order_acquire) ||
            drainCommitted.load(std::memory_order_acquire) ||
            commitAwaitingAcknowledgement.load(std::memory_order_acquire)) {
            return false;
        }
        stagedQueuedFrameCount.store(0U, std::memory_order_release);
        drainOutgoingGeneration.store(expectedActiveGeneration, std::memory_order_release);
        drainSuccessorGeneration.store(stagedGeneration, std::memory_order_release);
        drainTransitionRevision.store(transitionRevision, std::memory_order_release);
        drainSuccessorFirstRecord.store(nextRecordIndex, std::memory_order_release);
        generationHighWater.store(stagedGeneration, std::memory_order_release);
        drainStageOpen.store(true, std::memory_order_release);
        drainBeginCalls.fetch_add(1U, std::memory_order_release);
        return true;
    }

    [[nodiscard]] bool commitDrainHandoff(
        std::uint64_t expectedActiveGeneration,
        std::uint64_t stagedGeneration,
        std::uint64_t transitionRevision) noexcept {
        if (!drainStageOpen.load(std::memory_order_acquire) ||
            expectedActiveGeneration != drainOutgoingGeneration.load(std::memory_order_acquire) ||
            stagedGeneration != drainSuccessorGeneration.load(std::memory_order_acquire) ||
            transitionRevision != drainTransitionRevision.load(std::memory_order_acquire) ||
            stagedQueuedFrameCount.load(std::memory_order_acquire) == 0U) {
            return false;
        }
        enteredDrainCommit.store(true, std::memory_order_release);
        while (blockDrainCommit.load(std::memory_order_acquire)) {
            std::this_thread::yield();
        }
        drainStageOpen.store(false, std::memory_order_release);
        drainCommitted.store(true, std::memory_order_release);
        drainCommitCalls.fetch_add(1U, std::memory_order_release);
        return true;
    }

    [[nodiscard]] NativeAudioDrainCancelStatus cancelDrainHandoff(
        std::uint64_t expectedActiveGeneration,
        std::uint64_t stagedGeneration,
        std::uint64_t transitionRevision) noexcept {
        if (expectedActiveGeneration != drainOutgoingGeneration.load(std::memory_order_acquire) ||
            stagedGeneration != drainSuccessorGeneration.load(std::memory_order_acquire) ||
            transitionRevision != drainTransitionRevision.load(std::memory_order_acquire)) {
            return NativeAudioDrainCancelStatus::NoMatchingDrain;
        }
        drainCancelAttempts.fetch_add(1U, std::memory_order_release);
        if (drainSwapClaimed.load(std::memory_order_acquire)) {
            return NativeAudioDrainCancelStatus::AdoptionInFlight;
        }
        if (drainCancellationState.load(std::memory_order_acquire) !=
            DrainCancellationState::Idle) {
            return NativeAudioDrainCancelStatus::CancellationRequested;
        }
        if (!drainStageOpen.load(std::memory_order_acquire) &&
            !drainCommitted.load(std::memory_order_acquire)) {
            const std::uint64_t active = callbackGeneration.load(std::memory_order_acquire);
            if (active == stagedGeneration) {
                return NativeAudioDrainCancelStatus::SuccessorActive;
            }
            if (active == expectedActiveGeneration) {
                return NativeAudioDrainCancelStatus::OutgoingActive;
            }
            return NativeAudioDrainCancelStatus::NoMatchingDrain;
        }
        const bool wasCommitted = drainCommitted.load(std::memory_order_acquire);
        drainCancelCalls.fetch_add(1U, std::memory_order_release);
        if (wasCommitted) {
            DrainCancellationState expected = DrainCancellationState::Idle;
            if (!drainCancellationState.compare_exchange_strong(
                    expected,
                    DrainCancellationState::Requested,
                    std::memory_order_acq_rel,
                    std::memory_order_acquire)) {
                return NativeAudioDrainCancelStatus::CancellationRequested;
            }
            return NativeAudioDrainCancelStatus::CancellationRequested;
        }
        drainStageOpen.store(false, std::memory_order_release);
        stagedQueuedFrameCount.store(0U, std::memory_order_release);
        return NativeAudioDrainCancelStatus::CancelledOpenStage;
    }

    void acknowledgeRequestedDrainCancellation() noexcept {
        if (!claimRequestedDrainCancellationWithoutAcknowledgement()) return;
        static_cast<void>(acknowledgeClaimedDrainCancellation());
    }

    [[nodiscard]] std::size_t queuedForGeneration(std::uint64_t generation) const noexcept {
        if ((drainStageOpen.load(std::memory_order_acquire) ||
             drainCommitted.load(std::memory_order_acquire)) &&
            generation == drainSuccessorGeneration.load(std::memory_order_acquire)) {
            return stagedQueuedFrameCount.load(std::memory_order_acquire);
        }
        return generation == producerGeneration.load(std::memory_order_acquire)
            ? queuedFrameCount.load(std::memory_order_acquire)
            : 0U;
    }

    [[nodiscard]] std::size_t writableForGeneration(std::uint64_t generation) const noexcept {
        const std::size_t queued = queuedForGeneration(generation);
        const bool valid = generation == producerGeneration.load(std::memory_order_acquire) ||
            ((drainStageOpen.load(std::memory_order_acquire) ||
              drainCommitted.load(std::memory_order_acquire)) &&
             generation == drainSuccessorGeneration.load(std::memory_order_acquire));
        const bool loopCutoverFenced =
            generation == producerGeneration.load(std::memory_order_relaxed) &&
            loopCutoverWritesAwaitingRelease.load(std::memory_order_acquire);
        return !valid || loopCutoverFenced || queued >= capacity ? 0U : capacity - queued;
    }

    void publishTerminalPositionIfCurrentDrained() noexcept {
        if (queuedFrameCount.load(std::memory_order_acquire) != 0U) return;
        const std::uint64_t generation = callbackGeneration.load(std::memory_order_acquire);
        const std::size_t begin = generationFirstRecord.load(std::memory_order_acquire);
        const std::size_t end = publishedRecordCount.load(std::memory_order_acquire);
        for (std::size_t index = end; index > begin; --index) {
            const FakeOutputRecord candidate = records[index - 1U];
            if (candidate.generation != generation || !candidate.endOfStreamAfterBlock ||
                candidate.frames == 0U) {
                continue;
            }
            if (positionTerminalAfterFrame.load(std::memory_order_acquire) != 0U &&
                positionGeneration.load(std::memory_order_acquire) == candidate.generation &&
                positionToken.load(std::memory_order_acquire) == candidate.token) {
                return;
            }
            publishPositionOnly(candidate);
            return;
        }
    }

    void publishPositionOnly(const FakeOutputRecord &recordValue) noexcept {
        stemwise::audio::beginGuardedPublication(positionVersion);
        positionSequenceValue += 1U;
        positionLastTimelineValue =
            static_cast<std::int64_t>(std::floor(recordValue.sourceEnd)) - 1;
        positionLastOutputValue = totalPresentedFrames + recordValue.frames - 1U;
        positionSequence.store(positionSequenceValue, std::memory_order_relaxed);
        positionGeneration.store(recordValue.generation, std::memory_order_relaxed);
        positionToken.store(recordValue.token, std::memory_order_relaxed);
        positionFingerprint.store(recordValue.fingerprint, std::memory_order_relaxed);
        positionLoopPolicyRevision.store(
            recordValue.loopPolicyRevision,
            std::memory_order_relaxed);
        positionLastTimeline.store(positionLastTimelineValue, std::memory_order_relaxed);
        positionLastOutput.store(positionLastOutputValue, std::memory_order_relaxed);
        positionCallback.store(positionSequenceValue, std::memory_order_relaxed);
        positionFrames.store(recordValue.frames, std::memory_order_relaxed);
        positionTerminalAfterFrame.store(
            recordValue.endOfStreamAfterBlock ? 1U : 0U,
            std::memory_order_relaxed);
        positionVersion.fetch_add(1U, std::memory_order_release);
    }

    void publishPresentationOnly(const FakeOutputRecord &recordValue) noexcept {
        const std::uint64_t next = evidenceSequenceValue + 1U;
        stemwise::audio::beginGuardedPublication(evidenceVersion);
        evidenceGeneration.store(recordValue.generation, std::memory_order_relaxed);
        evidenceToken.store(recordValue.token, std::memory_order_relaxed);
        evidenceFingerprint.store(recordValue.fingerprint, std::memory_order_relaxed);
        evidenceTimeline.store(
            static_cast<std::int64_t>(std::floor(recordValue.sourceStart)),
            std::memory_order_relaxed);
        evidenceOutputFrame.store(totalPresentedFrames, std::memory_order_relaxed);
        evidenceCallback.store(next, std::memory_order_relaxed);
        evidenceFrames.store(recordValue.frames, std::memory_order_relaxed);
        evidenceSequenceValue = next;
        totalPresentedFrames += recordValue.frames;
        evidenceSequence.store(next, std::memory_order_relaxed);
        evidenceVersion.fetch_add(1U, std::memory_order_release);

        if (recordValue.loopEnabled && recordValue.loopRevision != 0U) {
            publishLoopEvidence(recordValue, recordValue, recordValue.frames);
        }
    }

    void publishEvidence(const FakeOutputRecord &recordValue) noexcept {
        const std::uint64_t next = evidenceSequenceValue + 1U;
        stemwise::audio::beginGuardedPublication(evidenceVersion);
        evidenceGeneration.store(recordValue.generation, std::memory_order_relaxed);
        evidenceToken.store(recordValue.token, std::memory_order_relaxed);
        evidenceFingerprint.store(recordValue.fingerprint, std::memory_order_relaxed);
        evidenceTimeline.store(
            static_cast<std::int64_t>(std::floor(recordValue.sourceStart)),
            std::memory_order_relaxed);
        evidenceOutputFrame.store(totalPresentedFrames, std::memory_order_relaxed);
        evidenceCallback.store(next, std::memory_order_relaxed);
        evidenceFrames.store(recordValue.frames, std::memory_order_relaxed);
        evidenceSequenceValue = next;
        totalPresentedFrames += recordValue.frames;
        evidenceSequence.store(next, std::memory_order_relaxed);
        evidenceVersion.fetch_add(1U, std::memory_order_release);

        stemwise::audio::beginGuardedPublication(positionVersion);
        positionSequenceValue += 1U;
        positionLastTimelineValue =
            static_cast<std::int64_t>(std::floor(recordValue.sourceEnd)) - 1;
        positionLastOutputValue = totalPresentedFrames - 1U;
        positionSequence.store(positionSequenceValue, std::memory_order_relaxed);
        positionGeneration.store(recordValue.generation, std::memory_order_relaxed);
        positionToken.store(recordValue.token, std::memory_order_relaxed);
        positionFingerprint.store(recordValue.fingerprint, std::memory_order_relaxed);
        positionLoopPolicyRevision.store(
            recordValue.loopPolicyRevision,
            std::memory_order_relaxed);
        positionLastTimeline.store(positionLastTimelineValue, std::memory_order_relaxed);
        positionLastOutput.store(positionLastOutputValue, std::memory_order_relaxed);
        positionCallback.store(positionSequenceValue, std::memory_order_relaxed);
        positionFrames.store(recordValue.frames, std::memory_order_relaxed);
        positionTerminalAfterFrame.store(
            recordValue.endOfStreamAfterBlock ? 1U : 0U,
            std::memory_order_relaxed);
        positionVersion.fetch_add(1U, std::memory_order_release);

        if (recordValue.sourceSettled && recordValue.controlRevision != 0U) {
            publishControlEvidence(recordValue, recordValue, recordValue.frames);
        }
        if (recordValue.loopEnabled && recordValue.loopRevision != 0U) {
            publishLoopEvidence(recordValue, recordValue, recordValue.frames);
        }
    }

    void publishControlEvidence(
        const FakeOutputRecord &firstRecord,
        const FakeOutputRecord &lastRecord,
        std::size_t frames) noexcept {
        stemwise::audio::beginGuardedPublication(controlEvidenceVersion);
        controlEvidenceSequenceValue += 1U;
        controlEvidenceSequence.store(controlEvidenceSequenceValue, std::memory_order_relaxed);
        controlEvidenceGeneration.store(firstRecord.generation, std::memory_order_relaxed);
        controlEvidenceToken.store(firstRecord.token, std::memory_order_relaxed);
        controlEvidenceRevision.store(firstRecord.controlRevision, std::memory_order_relaxed);
        controlEvidenceSourceMode.store(
            static_cast<std::uint8_t>(firstRecord.sourceMode),
            std::memory_order_relaxed);
        controlEvidenceFirstFingerprint.store(firstRecord.fingerprint, std::memory_order_relaxed);
        controlEvidenceLastFingerprint.store(lastRecord.fingerprint, std::memory_order_relaxed);
        controlEvidenceCallback.store(evidenceSequenceValue, std::memory_order_relaxed);
        controlEvidenceFrames.store(frames, std::memory_order_relaxed);
        controlEvidenceVersion.fetch_add(1U, std::memory_order_release);
    }

    void publishLoopEvidence(
        const FakeOutputRecord &firstRecord,
        const FakeOutputRecord &lastRecord,
        std::size_t frames) noexcept {
        stemwise::audio::beginGuardedPublication(loopEvidenceVersion);
        loopEvidenceSequenceValue += 1U;
        loopEvidenceSequence.store(loopEvidenceSequenceValue, std::memory_order_relaxed);
        loopEvidenceGeneration.store(firstRecord.generation, std::memory_order_relaxed);
        loopEvidenceToken.store(firstRecord.token, std::memory_order_relaxed);
        loopEvidenceRevision.store(firstRecord.loopRevision, std::memory_order_relaxed);
        loopEvidenceCycle.store(firstRecord.loopCycle, std::memory_order_relaxed);
        loopEvidenceFirstFingerprint.store(
            firstRecord.fingerprint,
            std::memory_order_relaxed);
        loopEvidenceLastFingerprint.store(
            lastRecord.fingerprint,
            std::memory_order_relaxed);
        loopEvidenceCallback.store(evidenceSequenceValue, std::memory_order_relaxed);
        loopEvidenceFrames.store(frames, std::memory_order_relaxed);
        loopEvidenceVersion.fetch_add(1U, std::memory_order_release);
    }

    [[nodiscard]] NativeAudioPresentationEvidence evidence() const noexcept {
        for (unsigned attempt = 0U; attempt != 8U; ++attempt) {
            const std::uint64_t before = evidenceVersion.load(std::memory_order_acquire);
            if ((before & 1U) != 0U) continue;
            NativeAudioPresentationEvidence result{
                evidenceSequence.load(std::memory_order_relaxed),
                evidenceGeneration.load(std::memory_order_relaxed),
                evidenceToken.load(std::memory_order_relaxed),
                evidenceFingerprint.load(std::memory_order_relaxed),
                evidenceTimeline.load(std::memory_order_relaxed),
                evidenceOutputFrame.load(std::memory_order_relaxed),
                evidenceCallback.load(std::memory_order_relaxed),
                evidenceFrames.load(std::memory_order_relaxed),
            };
            std::atomic_thread_fence(std::memory_order_acquire);
            const std::uint64_t after = evidenceVersion.load(std::memory_order_acquire);
            if (before == after) return result;
        }
        return {};
    }

    [[nodiscard]] NativeAudioPresentedPosition position() const noexcept {
        for (unsigned attempt = 0U; attempt != 8U; ++attempt) {
            const std::uint64_t before = positionVersion.load(std::memory_order_acquire);
            if ((before & 1U) != 0U) continue;
            NativeAudioPresentedPosition result{
                .sequence = positionSequence.load(std::memory_order_relaxed),
                .generation = positionGeneration.load(std::memory_order_relaxed),
                .selectionToken = positionToken.load(std::memory_order_relaxed),
                .fingerprintSequence = positionFingerprint.load(std::memory_order_relaxed),
                .loopPolicyRevision =
                    positionLoopPolicyRevision.load(std::memory_order_relaxed),
                .lastTimelineFrame = positionLastTimeline.load(std::memory_order_relaxed),
                .lastOutputFrame = positionLastOutput.load(std::memory_order_relaxed),
                .callbackSequence = positionCallback.load(std::memory_order_relaxed),
                .pcmFrames = positionFrames.load(std::memory_order_relaxed),
                .terminalAfterFrame =
                    positionTerminalAfterFrame.load(std::memory_order_relaxed) != 0U,
                .loopPolicyAdoptedRevision =
                    callbackLoopAdoptedRevision.load(std::memory_order_acquire),
            };
            std::atomic_thread_fence(std::memory_order_acquire);
            const std::uint64_t after = positionVersion.load(std::memory_order_acquire);
            if (before == after) {
                lastPositionSequenceReadValue.store(result.sequence, std::memory_order_release);
                return result;
            }
        }
        return {};
    }

    [[nodiscard]] NativeAudioAudiblePosition audiblePosition() const noexcept {
        if (audibleOverride.load(std::memory_order_acquire)) {
            return {
                .generation = audibleGeneration.load(std::memory_order_relaxed),
                .selectionToken = audibleToken.load(std::memory_order_relaxed),
                .timelineFrame = audibleTimeline.load(std::memory_order_relaxed),
            };
        }
        const NativeAudioPresentedPosition presented = position();
        return presented.valid()
            ? NativeAudioAudiblePosition{
                .generation = presented.generation,
                .selectionToken = presented.selectionToken,
                .timelineFrame = presented.lastTimelineFrame,
                .outputFrame = presented.lastOutputFrame,
            }
            : NativeAudioAudiblePosition{};
    }

    [[nodiscard]] NativeAudioAppliedControlEvidence controlEvidence() const noexcept {
        for (unsigned attempt = 0U; attempt != 8U; ++attempt) {
            const std::uint64_t before =
                controlEvidenceVersion.load(std::memory_order_acquire);
            if ((before & 1U) != 0U) continue;
            NativeAudioAppliedControlEvidence result{
                controlEvidenceSequence.load(std::memory_order_relaxed),
                controlEvidenceGeneration.load(std::memory_order_relaxed),
                controlEvidenceToken.load(std::memory_order_relaxed),
                controlEvidenceRevision.load(std::memory_order_relaxed),
                static_cast<NativeAudioSourceMode>(
                    controlEvidenceSourceMode.load(std::memory_order_relaxed)),
                // This output double proves owner/source acknowledgement, not numerical PCM.
                0.0F, 0.0F,
                controlEvidenceFirstFingerprint.load(std::memory_order_relaxed),
                controlEvidenceLastFingerprint.load(std::memory_order_relaxed),
                controlEvidenceCallback.load(std::memory_order_relaxed),
                controlEvidenceFrames.load(std::memory_order_relaxed),
            };
            std::atomic_thread_fence(std::memory_order_acquire);
            const std::uint64_t after =
                controlEvidenceVersion.load(std::memory_order_acquire);
            if (before == after) return result;
        }
        return {};
    }

    [[nodiscard]] NativeAudioStemGainCallbackEvidence stemGainEvidence() const noexcept {
        for (unsigned attempt = 0U; attempt != 8U; ++attempt) {
            const std::uint64_t before =
                stemGainEvidenceVersion.load(std::memory_order_acquire);
            if ((before & 1U) != 0U) continue;
            NativeAudioStemGainCallbackEvidence result{};
            result.sequence = stemGainEvidenceSequence.load(std::memory_order_relaxed);
            result.generation = stemGainEvidenceGeneration.load(std::memory_order_relaxed);
            result.selectionToken = stemGainEvidenceToken.load(std::memory_order_relaxed);
            result.publishedSequence =
                stemGainEvidencePublishedSequence.load(std::memory_order_relaxed);
            result.offeredSequence =
                stemGainEvidenceOfferedSequence.load(std::memory_order_relaxed);
            result.activeMask = stemGainEvidenceActiveMask.load(std::memory_order_relaxed);
            for (std::size_t lane = 0U; lane < result.gainBits.size(); ++lane) {
                result.gainBits[lane] =
                    stemGainEvidenceGainBits[lane].load(std::memory_order_relaxed);
            }
            result.callbackSequence =
                stemGainEvidenceCallbackSequence.load(std::memory_order_relaxed);
            result.firstTaggedFrame =
                stemGainEvidenceFirstTaggedFrame.load(std::memory_order_relaxed);
            result.lastTaggedFrame =
                stemGainEvidenceLastTaggedFrame.load(std::memory_order_relaxed);
            result.taggedFrameCount =
                stemGainEvidenceTaggedFrameCount.load(std::memory_order_relaxed);
            result.nonZeroPcmFrameCount =
                stemGainEvidenceNonZeroFrameCount.load(std::memory_order_relaxed);
            result.callbackObservedAtNs =
                stemGainEvidenceObservedAtNs.load(std::memory_order_relaxed);
            std::atomic_thread_fence(std::memory_order_acquire);
            const std::uint64_t after =
                stemGainEvidenceVersion.load(std::memory_order_acquire);
            if (before == after) return result;
        }
        return {};
    }

public:
    void publishStemGainEvidence(
        const NativeAudioStemGainCallbackEvidence &evidence) noexcept {
        stemwise::audio::beginGuardedPublication(stemGainEvidenceVersion);
        stemGainEvidenceSequence.store(evidence.sequence, std::memory_order_relaxed);
        stemGainEvidenceGeneration.store(evidence.generation, std::memory_order_relaxed);
        stemGainEvidenceToken.store(evidence.selectionToken, std::memory_order_relaxed);
        stemGainEvidencePublishedSequence.store(
            evidence.publishedSequence,
            std::memory_order_relaxed);
        stemGainEvidenceOfferedSequence.store(
            evidence.offeredSequence,
            std::memory_order_relaxed);
        stemGainEvidenceActiveMask.store(evidence.activeMask, std::memory_order_relaxed);
        for (std::size_t lane = 0U; lane < evidence.gainBits.size(); ++lane) {
            stemGainEvidenceGainBits[lane].store(
                evidence.gainBits[lane],
                std::memory_order_relaxed);
        }
        stemGainEvidenceCallbackSequence.store(
            evidence.callbackSequence,
            std::memory_order_relaxed);
        stemGainEvidenceFirstTaggedFrame.store(
            evidence.firstTaggedFrame,
            std::memory_order_relaxed);
        stemGainEvidenceLastTaggedFrame.store(
            evidence.lastTaggedFrame,
            std::memory_order_relaxed);
        stemGainEvidenceTaggedFrameCount.store(
            evidence.taggedFrameCount,
            std::memory_order_relaxed);
        stemGainEvidenceNonZeroFrameCount.store(
            evidence.nonZeroPcmFrameCount,
            std::memory_order_relaxed);
        stemGainEvidenceObservedAtNs.store(
            evidence.callbackObservedAtNs,
            std::memory_order_relaxed);
        stemGainEvidenceVersion.fetch_add(1U, std::memory_order_release);
    }

private:
    NativeAudioOutputLoopPolicy lastLoopDemand{};
public:
    stemwise::audio::NativeAudioOutputLoopDemandRetirement retiredLoopDemand{};
private:
    NativeAudioOutputLoopPolicy lastLoopCapture{};
    std::atomic<bool> rejectNextLoopDemand{false};
    std::atomic<std::uint64_t> loopDemandPublishes{0U};
    std::atomic<std::uint64_t> loopCapturePublishes{0U};
    std::atomic<std::uint64_t> rejectedLoopDemandPublishes{0U};

    [[nodiscard]] NativeAudioAudibleLoopEvidence loopEvidence() const noexcept {
        for (unsigned attempt = 0U; attempt != 8U; ++attempt) {
            const std::uint64_t before = loopEvidenceVersion.load(std::memory_order_acquire);
            if ((before & 1U) != 0U) continue;
            NativeAudioAudibleLoopEvidence result{
                loopEvidenceSequence.load(std::memory_order_relaxed),
                loopEvidenceGeneration.load(std::memory_order_relaxed),
                loopEvidenceToken.load(std::memory_order_relaxed),
                loopEvidenceRevision.load(std::memory_order_relaxed),
                loopEvidenceCycle.load(std::memory_order_relaxed),
                loopEvidenceFirstFingerprint.load(std::memory_order_relaxed),
                loopEvidenceLastFingerprint.load(std::memory_order_relaxed),
                loopEvidenceCallback.load(std::memory_order_relaxed),
                loopEvidenceFrames.load(std::memory_order_relaxed),
            };
            std::atomic_thread_fence(std::memory_order_acquire);
            const std::uint64_t after = loopEvidenceVersion.load(std::memory_order_acquire);
            if (before == after) return result;
        }
        return {};
    }

    const std::size_t capacity;
    const std::int32_t framesPerBurstValue;
    mutable std::mutex lateControlObservationMutex;
    std::array<NativeAudioLateMixControl, 2U> lateControls{};
    stemwise::audio::LateStemMixControlMailbox lateControlMailbox;
    std::array<NativeAudioLateMixControl, 16U> lateControlHistory{};
    std::atomic<std::size_t> lateControlHistoryCount{0U};
    std::array<bool, 2U> lateControlReadable{};
    std::array<std::uint32_t, 2U> lateSourceTransitionRemaining{};
    std::uint64_t lastLateControlPublication{0U};
    NativeAudioLateMixControl lastPublishedLateControlForTest{};
    std::atomic<std::uint64_t> lateControlSuccessfulPublications{0U};
    std::unique_ptr<FakeOutputRecord[]> records;
    std::size_t nextRecordIndex{0};
    std::uint64_t evidenceSequenceValue{0};
    std::uint64_t totalPresentedFrames{0};
    std::uint64_t positionSequenceValue{0};
    std::uint64_t controlEvidenceSequenceValue{0};
    std::uint64_t loopEvidenceSequenceValue{0};
    std::int64_t positionLastTimelineValue{0};
    std::uint64_t positionLastOutputValue{0};
    std::atomic<std::size_t> maximumAcceptance{std::numeric_limits<std::size_t>::max()};
    std::atomic<bool> audibleOverride{false};
    std::atomic<std::uint64_t> audibleGeneration{0U};
    std::atomic<std::uint64_t> audibleToken{0U};
    std::atomic<std::int64_t> audibleTimeline{-1};
    std::atomic<std::size_t> queuedFrameCount{0};
    std::atomic<std::size_t> callbackPressureReserve{0};
    std::atomic<std::size_t> stagedQueuedFrameCount{0};
    std::atomic<std::size_t> publishedRecordCount{0};
    std::atomic<std::size_t> generationFirstRecord{0};
    std::atomic<std::uint64_t> producerGeneration{1};
    std::atomic<std::uint64_t> generationHighWater{1};
    std::atomic<std::uint64_t> callbackGeneration{1};
    std::atomic<std::uint64_t> publishedGeneration{1};
    std::atomic<bool> stageOpen{false};
    std::atomic<bool> commitAwaitingAcknowledgement{false};
    std::atomic<bool> commitAllowed{true};
    std::atomic<bool> autoAcknowledgePausedCommit{false};
    std::atomic<std::uint8_t> forcedFlushStatus{
        static_cast<std::uint8_t>(NativeAudioFlushStatus::Accepted)};
    std::atomic<std::uint64_t> flushCalls{0};
    std::atomic<std::uint64_t> beginCalls{0};
    std::atomic<std::uint64_t> commitCalls{0};
    std::atomic<std::uint64_t> loopCutoverCommitCalls{0};
    std::atomic<std::uint64_t> loopCutoverBeginCalls{0};
    std::atomic<std::uint64_t> loopCutoverCommitAttempts{0};
    std::atomic<std::uint64_t> loopPolicyPublishCalls{0};
    AtomicNativeAudioOutputLoopPolicy loopPolicyMailbox{};
    AtomicNativeAudioOutputLoopPolicy committedLoopPolicyMailbox{};
    AtomicNativeAudioOutputLoopPolicy loopCaptureMailbox{};
    std::atomic<std::uint64_t> retiredCaptureRevision{0U};
    std::atomic<bool> loopBoundaryCoverageAllowed{true};
    std::atomic<bool> adoptLoopOnCancellation{false};
    std::atomic<bool> rejectNextLoopPolicy{false};
    std::atomic<std::uint64_t> rejectedLoopPolicyPublishes{0};
    std::atomic<std::uint64_t> lastLoopPolicyRevision{0};
    std::atomic<std::uint64_t> lastLoopPolicyGeneration{0};
    std::atomic<std::uint64_t> lastLoopPolicyToken{0};
    std::atomic<std::uint64_t> lastLoopPolicyA{0};
    std::atomic<std::uint64_t> lastLoopPolicyB{0};
    std::atomic<bool> lastLoopPolicyEnabled{false};
    std::atomic<bool> lastLoopPolicyPhysicalReady{true};
    std::atomic<std::uint64_t> lastLoopPolicyCutover{0};
    std::atomic<std::uint64_t> lastLoopPolicyContinuationFrame{0};
    std::atomic<std::uint64_t> lastLoopPolicyCutoverSourceRevision{0};
    std::atomic<std::uint64_t> lastLoopPolicyContinuationRevision{0};
    std::atomic<bool> lastLoopPolicyNextRevisionOnly{false};
    std::atomic<std::uint64_t> loopCutoverGeneration{0};
    std::atomic<double> blockedLoopCutoverStart{-1.0};
    std::atomic<std::uint64_t> loopCutoverRevision{0};
    std::atomic<std::size_t> loopCutoverFirstRecord{0};
    std::atomic<std::size_t> loopCutoverLastRecord{0};
    std::atomic<std::size_t> loopCutoverQueuedFrames{0};
    std::atomic<std::size_t> loopCutoverQueuedFramesAtCommit{0};
    std::atomic<std::size_t> loopCutoverRequiredFrameCount{0};
    std::atomic<bool> loopCutoverLateControlBound{false};
    std::atomic<bool> loopCutoverStageOpen{false};
    std::atomic<bool> loopCutoverCommitted{false};
    std::atomic<bool> loopCutoverWritesAwaitingRelease{false};
    std::atomic<std::uint64_t> loopCutoverWriteReleases{0U};
    std::atomic<std::uint64_t> loopCutoverBeginMicros{0U};
    std::atomic<std::uint64_t> loopCutoverCommitMicros{0U};
    std::atomic<std::uint64_t> activeEnqueueBackpressure{0U};
    std::atomic<bool> loopCutoverCancellationInFlight{false};
    std::atomic<bool> loopCutoverCancellationObserved{false};
    std::atomic<bool> blockLoopCutoverWriteRelease{false};
    std::atomic<bool> retireNextLoopCutoverCommitAttempt{false};
    std::atomic<bool> blockLoopCutoverEnqueue{false};
    std::atomic<bool> enteredLoopCutoverEnqueue{false};
    std::atomic<bool> pauseLoopCutoverEnqueueAfterAcceptance{false};
    std::atomic<bool> loopCutoverEnqueuePauseEntered{false};
    std::atomic<bool> loopCutoverEnqueuePauseTimedOut{false};
    std::atomic<std::size_t> cancelledLoopCutoverQueuedFrames{0U};
    std::atomic<std::uint64_t> drainBeginCalls{0};
    std::atomic<std::uint64_t> drainCommitCalls{0};
    std::atomic<bool> blockDrainCommit{false};
    std::atomic<bool> enteredDrainCommit{false};
    std::atomic<std::uint64_t> drainCancelCalls{0};
    std::atomic<std::uint64_t> drainCancelAttempts{0};
    std::atomic<std::uint64_t> drainOutgoingGeneration{0};
    std::atomic<std::uint64_t> drainSuccessorGeneration{0};
    std::atomic<std::uint64_t> lastDrainSuccessorGeneration{0};
    std::atomic<std::uint64_t> drainTransitionRevision{0};
    std::atomic<std::size_t> drainSuccessorFirstRecord{0};
    std::atomic<bool> drainStageOpen{false};
    std::atomic<bool> drainCommitted{false};
    std::atomic<DrainCancellationState> drainCancellationState{
        DrainCancellationState::Idle};
    std::atomic<bool> drainSwapClaimed{false};
    std::atomic<bool> isPlaying{false};
    std::atomic<std::uint64_t> terminalSilenceAuthorizedGeneration{0U};

    mutable std::atomic<std::uint64_t> evidenceVersion{0};
    std::atomic<std::uint64_t> evidenceSequence{0};
    std::atomic<std::uint64_t> evidenceGeneration{0};
    std::atomic<std::uint64_t> evidenceToken{0};
    std::atomic<std::uint64_t> evidenceFingerprint{0};
    std::atomic<std::int64_t> evidenceTimeline{0};
    std::atomic<std::uint64_t> evidenceOutputFrame{0};
    std::atomic<std::uint64_t> evidenceCallback{0};
    std::atomic<std::uint64_t> evidenceFrames{0};

    mutable std::atomic<std::uint64_t> positionVersion{0};
    mutable std::atomic<std::uint64_t> lastPositionSequenceReadValue{0};
    std::atomic<std::uint64_t> positionSequence{0};
    std::atomic<std::uint64_t> positionGeneration{0};
    std::atomic<std::uint64_t> positionToken{0};
    std::atomic<std::uint64_t> positionFingerprint{0};
    std::atomic<std::uint64_t> positionLoopPolicyRevision{0};
    std::atomic<std::uint64_t> callbackLoopAdoptedRevision{0};
    std::atomic<std::int64_t> positionLastTimeline{0};
    std::atomic<std::uint64_t> positionLastOutput{0};
    std::atomic<std::uint64_t> positionCallback{0};
    std::atomic<std::uint64_t> positionFrames{0};
    std::atomic<std::uint8_t> positionTerminalAfterFrame{0};

    mutable std::atomic<std::uint64_t> controlEvidenceVersion{0};
    std::atomic<std::uint64_t> controlEvidenceSequence{0};
    std::atomic<std::uint64_t> controlEvidenceGeneration{0};
    std::atomic<std::uint64_t> controlEvidenceToken{0};
    std::atomic<std::uint64_t> controlEvidenceRevision{0};
    std::atomic<std::uint8_t> controlEvidenceSourceMode{0};
    std::atomic<std::uint64_t> controlEvidenceFirstFingerprint{0};
    std::atomic<std::uint64_t> controlEvidenceLastFingerprint{0};
    std::atomic<std::uint64_t> controlEvidenceCallback{0};
    std::atomic<std::uint64_t> controlEvidenceFrames{0};

    mutable std::atomic<std::uint64_t> stemGainEvidenceVersion{0};
    std::atomic<std::uint64_t> stemGainEvidenceSequence{0};
    std::atomic<std::uint64_t> stemGainEvidenceGeneration{0};
    std::atomic<std::uint64_t> stemGainEvidenceToken{0};
    std::atomic<std::uint64_t> stemGainEvidencePublishedSequence{0};
    std::atomic<std::uint64_t> stemGainEvidenceOfferedSequence{0};
    std::atomic<std::uint32_t> stemGainEvidenceActiveMask{0};
    std::array<std::atomic<std::uint32_t>, stemwise::audio::kNativeAudioStemGainLaneCount>
        stemGainEvidenceGainBits{};
    std::atomic<std::uint64_t> stemGainEvidenceCallbackSequence{0};
    std::atomic<std::int64_t> stemGainEvidenceFirstTaggedFrame{0};
    std::atomic<std::int64_t> stemGainEvidenceLastTaggedFrame{0};
    std::atomic<std::uint64_t> stemGainEvidenceTaggedFrameCount{0};
    std::atomic<std::uint64_t> stemGainEvidenceNonZeroFrameCount{0};
    std::atomic<std::uint64_t> stemGainEvidenceObservedAtNs{0};

    mutable std::atomic<std::uint64_t> loopEvidenceVersion{0};
    std::atomic<std::uint64_t> loopEvidenceSequence{0};
    std::atomic<std::uint64_t> loopEvidenceGeneration{0};
    std::atomic<std::uint64_t> loopEvidenceToken{0};
    std::atomic<std::uint64_t> loopEvidenceRevision{0};
    std::atomic<std::uint64_t> loopEvidenceCycle{0};
    std::atomic<std::uint64_t> loopEvidenceFirstFingerprint{0};
    std::atomic<std::uint64_t> loopEvidenceLastFingerprint{0};
    std::atomic<std::uint64_t> loopEvidenceCallback{0};
    std::atomic<std::uint64_t> loopEvidenceFrames{0};
};
