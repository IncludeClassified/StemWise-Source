#include "LoopCycleStateMachine.h"

#include <algorithm>
#include <limits>

namespace stemwise::audio::engine {
namespace {

void saturatingIncrement(std::uint64_t &value) noexcept {
    if (value != std::numeric_limits<std::uint64_t>::max()) ++value;
}

[[nodiscard]] bool advanceSequence(std::uint64_t &value) noexcept {
    if (value == std::numeric_limits<std::uint64_t>::max()) return false;
    ++value;
    return true;
}

[[nodiscard]] constexpr bool validLaneMask(std::uint32_t mask) noexcept {
    return mask != 0U && (mask & 1U) != 0U &&
        (mask & kLoopHeadAllLanesMask) == mask;
}

}  // namespace

LoopCycleStateMachine::LoopCycleStateMachine(
    std::uint32_t minimumBodyPrimeFrames) noexcept
    : mMinimumBodyPrimeFrames(minimumBodyPrimeFrames) {}

bool LoopCycleStateMachine::validIntent(const LoopCycleIntent &intent) const noexcept {
    if (intent.revision == 0U || !intent.owner.valid()) return false;
    if (!intent.enabled) return intent.requiredLaneMask == 0U && !intent.cacheKey.valid();
    return intent.boundaryMode == LoopHeadBoundaryMode::OriginalEos &&
        intent.cacheKey.valid() && validLaneMask(intent.requiredLaneMask) &&
        ((intent.requiredLaneMask & ~1U) == 0U || intent.cacheKey.stemPackageHash != 0U) &&
        intent.cacheKey.endTimelineFrameExclusive - intent.cacheKey.startTimelineFrame ==
            LoopHeadCache::kCapacityFrames;
}

bool LoopCycleStateMachine::sameStableIdentity(
    const LoopCycleIntent &intent) const noexcept {
    if (intent.revision != mIntent.revision || intent.enabled != mIntent.enabled ||
        intent.owner.selectionToken != mIntent.owner.selectionToken) {
        return false;
    }
    if (!intent.enabled) return true;
    return intent.cacheKey == mIntent.cacheKey &&
        intent.requiredLaneMask == mIntent.requiredLaneMask &&
        intent.declickFrames == mIntent.declickFrames &&
        intent.boundaryMode == mIntent.boundaryMode;
}

std::uint64_t LoopCycleStateMachine::loopStart() const noexcept {
    return mIntent.cacheKey.startTimelineFrame;
}

std::uint64_t LoopCycleStateMachine::loopEnd() const noexcept {
    return mBoundaryResolved ? mResolvedLoopEnd : 0U;
}

std::uint64_t LoopCycleStateMachine::headEnd() const noexcept {
    if (!mIntent.enabled || !mBoundaryResolved) return 0U;
    const std::uint64_t length = loopEnd() - loopStart();
    return loopStart() + std::min<std::uint64_t>(
        length,
        LoopHeadCache::kCapacityFrames);
}

std::uint64_t LoopCycleStateMachine::deadlineFrame() const noexcept {
    return mIntent.enabled ? loopEnd() : 0U;
}

std::uint64_t LoopCycleStateMachine::requiredBodyEnd() const noexcept {
    if (!mIntent.enabled) return 0U;
    const std::uint64_t bodyStart = headEnd();
    const std::uint64_t remaining = loopEnd() - bodyStart;
    return bodyStart + std::min<std::uint64_t>(
        remaining,
        mMinimumBodyPrimeFrames);
}

bool LoopCycleStateMachine::nextRenderCycle(std::uint64_t &cycle) const noexcept {
    if (mRenderCycle == std::numeric_limits<std::uint64_t>::max()) return false;
    cycle = mRenderCycle + 1U;
    return true;
}

bool LoopCycleStateMachine::currentCycleAudiblyProven() const noexcept {
    return mRenderInstalled && mAudibleEvidence.valid &&
        mAudibleEvidence.loopRevision == mIntent.revision &&
        mAudibleEvidence.cycle == mRenderCycle &&
        mAudibleEvidence.owner == mIntent.owner;
}

LoopCyclePhase LoopCycleStateMachine::installedSteadyPhase() const noexcept {
    return currentCycleAudiblyProven()
        ? LoopCyclePhase::Active
        : LoopCyclePhase::AwaitingCallbackProof;
}

void LoopCycleStateMachine::rememberPhysicalCallbackForRetirement() noexcept {
    if (mRenderInstalled) {
        mSupersededPhysicalCallback = {
            .valid = true,
            .loopRevision = mIntent.revision,
            .cycle = mRenderCycle,
            .owner = mIntent.owner,
        };
    } else {
        // There is no physical predecessor behind the next policy. Do not let an ancient exact
        // tuple remain exempt from the normal stale-evidence diagnostics indefinitely.
        mSupersededPhysicalCallback = {};
    }
}

void LoopCycleStateMachine::clearTransientProtocol() noexcept {
    mActivation = LoopCycleActivation::None;
    mRenderCycle = 0U;
    mFirstInstalledCycle = 0U;
    mActiveIngressEpoch = 0U;
    mPreparedIngressEpoch = 0U;
    mPreparedTargetCycle = 0U;
    mLastPositionSequence = 0U;
    mLastBoundarySequence = 0U;
    mLastCallbackSequence = 0U;
    mRenderInstalled = false;
    mIngressRequest = {};
    mDirective = {};
    mAudibleEvidence = {};
}

void LoopCycleStateMachine::resetForIntent(const LoopCycleIntent &intent) noexcept {
    mIntent = intent;
    mBoundaryResolved = false;
    mResolvedLoopEnd = 0U;
    mMode = intent.enabled ? LoopCycleMode::Streamed : LoopCycleMode::Disabled;
    mCacheChecksum = 0U;
    mCacheAccepted = false;
    mPhaseAfterClear = intent.enabled
        ? LoopCyclePhase::WaitingForCache
        : LoopCyclePhase::Disabled;
    clearTransientProtocol();
    mPhase = mPhaseAfterClear;
}

LoopCycleSubmitStatus LoopCycleStateMachine::submit(
    const LoopCycleIntent &intent) noexcept {
    if (!validIntent(intent)) return LoopCycleSubmitStatus::InvalidIntent;
    if (intent.revision < mLatestRevision) {
        saturatingIncrement(mDiagnostics.staleIntents);
        return LoopCycleSubmitStatus::StaleRevision;
    }
    if (intent.revision == mLatestRevision && mLatestRevision != 0U) {
        if (!sameStableIdentity(intent)) {
            saturatingIncrement(mDiagnostics.revisionCollisions);
            return LoopCycleSubmitStatus::RevisionCollision;
        }
        if (intent.owner.renderGeneration < mIntent.owner.renderGeneration) {
            saturatingIncrement(mDiagnostics.staleIntents);
            return LoopCycleSubmitStatus::StaleRevision;
        }
        if (intent.owner.renderGeneration == mIntent.owner.renderGeneration) {
            saturatingIncrement(mDiagnostics.unchangedIntents);
            return LoopCycleSubmitStatus::Unchanged;
        }

        const bool mustClearRender = mRenderInstalled ||
            mDirective.valid;
        const bool retainedCache = mCacheAccepted;
        const std::uint64_t retainedChecksum = mCacheChecksum;
        rememberPhysicalCallbackForRetirement();
        mIntent.owner = intent.owner;
        clearTransientProtocol();
        mCacheAccepted = retainedCache;
        mCacheChecksum = retainedChecksum;
        mPhaseAfterClear = retainedCache
            ? (mBoundaryResolved
                ? LoopCyclePhase::WaitingForSafeWindow
                : LoopCyclePhase::WaitingForBoundary)
            : (intent.enabled ? LoopCyclePhase::WaitingForCache : LoopCyclePhase::Disabled);
        mPhase = mPhaseAfterClear;
        if (mustClearRender) {
            static_cast<void>(publishDirective(
                LoopCycleDirectiveAction::Clear,
                0U,
                0U));
        }
        saturatingIncrement(mDiagnostics.ownershipRebinds);
        return LoopCycleSubmitStatus::OwnershipRebound;
    }

    const bool mustClearRender = mRenderInstalled ||
        mDirective.valid;
    rememberPhysicalCallbackForRetirement();
    mLatestRevision = intent.revision;
    resetForIntent(intent);
    if (mustClearRender) {
        static_cast<void>(publishDirective(
            LoopCycleDirectiveAction::Clear,
            0U,
            0U));
    }
    saturatingIncrement(mDiagnostics.acceptedIntents);
    return intent.enabled
        ? LoopCycleSubmitStatus::Accepted
        : LoopCycleSubmitStatus::AcceptedDisabled;
}

bool LoopCycleStateMachine::retireRenderer(std::uint64_t loopRevision, LoopCycleOwner owner) noexcept {
    if (loopRevision != mIntent.revision || owner != mIntent.owner ||
        (!mRenderInstalled && !mDirective.valid)) return false;
    clearTransientProtocol();
    // The caller has the physical consumer retirement acknowledgement, so no old renderer remains queued.
    mSupersededPhysicalCallback = {};
    mPhaseAfterClear = !mIntent.enabled ? LoopCyclePhase::Disabled :
        !mCacheAccepted ? LoopCyclePhase::WaitingForCache :
        mBoundaryResolved ? LoopCyclePhase::WaitingForSafeWindow : LoopCyclePhase::WaitingForBoundary;
    mPhase = mPhaseAfterClear;
    return true;
}

LoopCycleCacheStatus LoopCycleStateMachine::acceptCache(
    const LoopHeadCacheSnapshot &snapshot,
    std::uint64_t playbackEndFrameExclusive) noexcept {
    if (!mIntent.enabled ||
        snapshot.key.loopRevision < mIntent.cacheKey.loopRevision) {
        saturatingIncrement(mDiagnostics.cacheRejections);
        return LoopCycleCacheStatus::StaleRevision;
    }
    if (mCacheAccepted && snapshot.valid && snapshot.key == mIntent.cacheKey &&
        snapshot.combinedChecksum == mCacheChecksum) {
        return LoopCycleCacheStatus::AlreadyAccepted;
    }
    if (mPhase != LoopCyclePhase::WaitingForCache) {
        saturatingIncrement(mDiagnostics.cacheRejections);
        return LoopCycleCacheStatus::NotWaiting;
    }
    if (!snapshot.valid || snapshot.combinedChecksum == 0U ||
        snapshot.frameCount == 0U) {
        saturatingIncrement(mDiagnostics.cacheRejections);
        return LoopCycleCacheStatus::InvalidSnapshot;
    }
    if (snapshot.key != mIntent.cacheKey ||
        (snapshot.requiredLaneMask & mIntent.requiredLaneMask) !=
            mIntent.requiredLaneMask) {
        saturatingIncrement(mDiagnostics.cacheRejections);
        return LoopCycleCacheStatus::KeyMismatch;
    }
    if ((playbackEndFrameExclusive != 0U && playbackEndFrameExclusive <= loopStart()) ||
        snapshot.boundaryMode != mIntent.boundaryMode ||
        (snapshot.completedLaneMask & mIntent.requiredLaneMask) !=
            mIntent.requiredLaneMask) {
        saturatingIncrement(mDiagnostics.cacheRejections);
        return LoopCycleCacheStatus::InvalidSnapshot;
    }

    if (snapshot.originalEndResolved) {
        if (!snapshot.cacheOnly || snapshot.frameCount == 0U ||
            snapshot.frameCount > LoopHeadCache::kCapacityFrames ||
            snapshot.resolvedOriginalEndTimelineFrameExclusive !=
                loopStart() + snapshot.frameCount ||
            snapshot.resolvedOriginalEndTimelineFrameExclusive >
                mIntent.cacheKey.endTimelineFrameExclusive) {
            saturatingIncrement(mDiagnostics.cacheRejections);
            return LoopCycleCacheStatus::InvalidSnapshot;
        }
    } else if (snapshot.cacheOnly ||
               snapshot.frameCount != LoopHeadCache::kCapacityFrames ||
               snapshot.resolvedOriginalEndTimelineFrameExclusive != 0U ||
               mIntent.cacheKey.endTimelineFrameExclusive !=
                   loopStart() + LoopHeadCache::kCapacityFrames) {
        saturatingIncrement(mDiagnostics.cacheRejections);
        return LoopCycleCacheStatus::InvalidSnapshot;
    }
    for (std::size_t lane = 0; lane < kLoopHeadLaneCount; ++lane) {
        const std::uint32_t bit = 1U << static_cast<std::uint32_t>(lane);
        if ((mIntent.requiredLaneMask & bit) != 0U &&
            snapshot.laneChecksums[lane] == 0U) {
            saturatingIncrement(mDiagnostics.cacheRejections);
            return LoopCycleCacheStatus::InvalidSnapshot;
        }
    }

    // Commit semantic endpoint state only after every snapshot field and lane checksum has passed.
    // A rejected cache is observationally inert and cannot partially resolve Repeat One.
    auto knownEnd = snapshot.originalEndResolved ? snapshot.resolvedOriginalEndTimelineFrameExclusive : 0U;
    // The playback view may end within real retained PCM; its physical EOF metadata stays intact.
    if (playbackEndFrameExclusive != 0U && (knownEnd != 0U ||
            playbackEndFrameExclusive <= loopStart() + snapshot.frameCount)) {
        knownEnd = knownEnd == 0U ? playbackEndFrameExclusive : std::min(knownEnd, playbackEndFrameExclusive);
    }
    if (mIntent.boundaryMode == LoopHeadBoundaryMode::OriginalEos && knownEnd != 0U) {
        mBoundaryResolved = true;
        mResolvedLoopEnd = knownEnd;
        mMode = LoopCycleMode::CacheOnly;
    }

    mCacheAccepted = true;
    mCacheChecksum = snapshot.combinedChecksum;
    mPhase = mBoundaryResolved
        ? LoopCyclePhase::WaitingForSafeWindow
        : LoopCyclePhase::WaitingForBoundary;
    saturatingIncrement(mDiagnostics.cachesAccepted);
    return LoopCycleCacheStatus::Accepted;
}

LoopCycleOriginalEosStatus LoopCycleStateMachine::resolveOriginalEos(
    const LoopCycleOriginalEosEvidence &evidence) noexcept {
    const auto reject = [this](LoopCycleOriginalEosStatus status) noexcept {
        saturatingIncrement(mDiagnostics.originalEosRejected);
        return status;
    };
    if (!mIntent.enabled || mIntent.boundaryMode != LoopHeadBoundaryMode::OriginalEos) {
        return reject(LoopCycleOriginalEosStatus::NotRequired);
    }
    if (!mCacheAccepted) return reject(LoopCycleOriginalEosStatus::NotReady);
    if (evidence.loopRevision != mIntent.revision) {
        return reject(LoopCycleOriginalEosStatus::StaleRevision);
    }
    if (evidence.owner != mIntent.owner) {
        return reject(LoopCycleOriginalEosStatus::StaleOwner);
    }
    const std::uint64_t expectedIngressEpoch = mActiveIngressEpoch != 0U
        ? mActiveIngressEpoch
        : mIntent.owner.renderGeneration;
    if (evidence.ingressEpoch == 0U || evidence.ingressEpoch != expectedIngressEpoch) {
        return reject(LoopCycleOriginalEosStatus::UnexpectedIngressEpoch);
    }
    if (evidence.endTimelineFrameExclusive <= loopStart()) {
        return reject(LoopCycleOriginalEosStatus::InvalidBoundary);
    }
    if (mBoundaryResolved) {
        return evidence.endTimelineFrameExclusive == mResolvedLoopEnd
            ? LoopCycleOriginalEosStatus::AlreadyResolved
            : reject(LoopCycleOriginalEosStatus::ConflictingBoundary);
    }
    const std::uint64_t preparedHeadEnd =
        loopStart() + LoopHeadCache::kCapacityFrames;
    if (evidence.endTimelineFrameExclusive < preparedHeadEnd) {
        // A short Original must have resolved and shrunk the preparation cache before commit.
        return reject(LoopCycleOriginalEosStatus::InvalidBoundary);
    }
    mBoundaryResolved = true;
    mResolvedLoopEnd = evidence.endTimelineFrameExclusive;
    mMode = mResolvedLoopEnd - loopStart() <= LoopHeadCache::kCapacityFrames
        ? LoopCycleMode::CacheOnly
        : LoopCycleMode::Streamed;
    mPhase = LoopCyclePhase::WaitingForSafeWindow;
    saturatingIncrement(mDiagnostics.originalEosAccepted);
    return LoopCycleOriginalEosStatus::Accepted;
}

bool LoopCycleStateMachine::publishIngressRequest(
    std::uint64_t currentIngressEpoch,
    LoopCycleActivation activation) noexcept {
    if (mMode != LoopCycleMode::Streamed || mIngressRequest.valid ||
        currentIngressEpoch == 0U) {
        return false;
    }
    std::uint64_t targetCycle = 0U;
    if (!nextRenderCycle(targetCycle)) return false;
    const std::uint64_t base = std::max(currentIngressEpoch, mActiveIngressEpoch);
    if (base == std::numeric_limits<std::uint64_t>::max() ||
        !advanceSequence(mIngressRequestSequence)) {
        return false;
    }
    if (!mRenderInstalled) mActiveIngressEpoch = currentIngressEpoch;
    mActivation = activation;
    mIngressRequest = {
        .valid = true,
        .sequence = mIngressRequestSequence,
        .loopRevision = mIntent.revision,
        .targetCycle = targetCycle,
        .owner = mIntent.owner,
        .ingressEpoch = base + 1U,
        .targetTimelineFrame = headEnd(),
        .requiredEndTimelineFrameExclusive = requiredBodyEnd(),
        .requiredLaneMask = mIntent.requiredLaneMask,
        .activation = activation,
    };
    mPhase = LoopCyclePhase::WaitingForIngress;
    saturatingIncrement(mDiagnostics.ingressRequests);
    return true;
}

bool LoopCycleStateMachine::publishDirective(
    LoopCycleDirectiveAction action,
    std::uint64_t cycle,
    std::uint64_t ingressEpoch) noexcept {
    if (mDirective.valid || !advanceSequence(mDirectiveSequence)) return false;
    mDirective = {
        .valid = true,
        .sequence = mDirectiveSequence,
        .action = action,
        .loopRevision = mIntent.revision,
        .cacheRevision = mIntent.enabled ? mIntent.cacheKey.loopRevision : 0U,
        .cycle = cycle,
        .owner = mIntent.owner,
        .ingressEpoch = ingressEpoch,
        .startTimelineFrame = mIntent.enabled ? loopStart() : 0U,
        .endTimelineFrameExclusive = mIntent.enabled ? loopEnd() : 0U,
        .headEndTimelineFrameExclusive = mIntent.enabled ? headEnd() : 0U,
        .cacheChecksum = mCacheChecksum,
        .requiredLaneMask = mIntent.requiredLaneMask,
        .declickFrames = mIntent.declickFrames,
        .boundaryMode = mIntent.boundaryMode,
    };
    mPhase = LoopCyclePhase::AwaitingRenderCommit;
    saturatingIncrement(mDiagnostics.renderDirectives);
    return true;
}

bool LoopCycleStateMachine::publishDeadlineFallback() noexcept {
    if (mDirective.valid) return false;
    if (!publishDirective(
            LoopCycleDirectiveAction::DeadlineFallback,
            mRenderCycle,
            mActiveIngressEpoch)) {
        return false;
    }
    saturatingIncrement(mDiagnostics.deadlineFallbacks);
    return true;
}

LoopCyclePositionStatus LoopCycleStateMachine::observePosition(
    const LoopCyclePositionEvidence &evidence) noexcept {
    const auto reject = [this](LoopCyclePositionStatus status) noexcept {
        saturatingIncrement(mDiagnostics.rejectedPositionObservations);
        return status;
    };
    if (!mIntent.enabled || !mCacheAccepted) {
        return reject(LoopCyclePositionStatus::Disabled);
    }
    if (evidence.loopRevision != mIntent.revision) {
        return reject(LoopCyclePositionStatus::StaleRevision);
    }
    if (evidence.owner != mIntent.owner) {
        return reject(LoopCyclePositionStatus::StaleOwner);
    }
    if (evidence.sequence == 0U || evidence.sequence <= mLastPositionSequence) {
        return reject(LoopCyclePositionStatus::StaleObservation);
    }
    mLastPositionSequence = evidence.sequence;
    if (evidence.cycle != mRenderCycle) {
        return reject(LoopCyclePositionStatus::StaleCycle);
    }
    if (evidence.currentIngressEpoch == 0U ||
        (mActiveIngressEpoch != 0U &&
         evidence.currentIngressEpoch != mActiveIngressEpoch)) {
        return reject(LoopCyclePositionStatus::UnexpectedIngressEpoch);
    }

    saturatingIncrement(mDiagnostics.positionObservations);
    if (mDirective.valid) return LoopCyclePositionStatus::Observed;
    if (!mBoundaryResolved) return LoopCyclePositionStatus::Observed;

    // A warm decoder ACK never installs from a stale position. The next exact render observation
    // decides whether the current pass is still safe or must become one cold activation.
    if (!mRenderInstalled && mPreparedIngressEpoch != 0U &&
        mActivation == LoopCycleActivation::WarmCurrentPass) {
        if (evidence.sourceTimelineFrame < deadlineFrame()) {
            return publishDirective(
                LoopCycleDirectiveAction::InstallWarmStreamed,
                mRenderCycle,
                mActiveIngressEpoch)
                ? LoopCyclePositionStatus::DirectivePublished
                : LoopCyclePositionStatus::Observed;
        }
        mActivation = LoopCycleActivation::ColdAtLoopStart;
        return publishDirective(
            LoopCycleDirectiveAction::ActivateColdStreamed,
            mPreparedTargetCycle,
            mPreparedIngressEpoch)
            ? LoopCyclePositionStatus::DirectivePublished
            : LoopCyclePositionStatus::Observed;
    }

    if (mPhase == LoopCyclePhase::WaitingForSafeWindow) {
        if (mMode == LoopCycleMode::CacheOnly) {
            // Installing before A is warm only when the live decoder reservoirs really cover the
            // approach to A. A seek can rebind ownership while the retired physical loop is still
            // audible, leaving the new pipeline cursor before A but its reservoirs beginning at A.
            // Treating that unretained gap as warm installs a valid cache without repositioning;
            // the renderer then requests PCM that can never arrive and starves at the old cursor.
            const bool insideCachedRange = evidence.sourceTimelineFrame >= loopStart() &&
                evidence.sourceTimelineFrame < loopEnd();
            const bool retainedApproach = evidence.sourceTimelineFrame < loopStart() &&
                evidence.tailRetainedThroughEnd;
            mActivation = insideCachedRange || retainedApproach
                ? LoopCycleActivation::WarmCurrentPass
                : LoopCycleActivation::ColdAtLoopStart;
            std::uint64_t cycle = mRenderCycle;
            if (mActivation == LoopCycleActivation::ColdAtLoopStart &&
                !nextRenderCycle(cycle)) {
                return LoopCyclePositionStatus::CycleOverflow;
            }
            return publishDirective(
                mActivation == LoopCycleActivation::WarmCurrentPass
                    ? LoopCycleDirectiveAction::InstallWarmCacheOnly
                    : LoopCycleDirectiveAction::ActivateColdCacheOnly,
                cycle,
                evidence.currentIngressEpoch)
                ? LoopCyclePositionStatus::DirectivePublished
                : LoopCyclePositionStatus::Observed;
        }

        if (evidence.sourceTimelineFrame < loopStart()) {
            return LoopCyclePositionStatus::Observed;
        }
        // Moving live decoders to the next pass without retaining this pass would restart it.
        // Wait for retained tail coverage (or actual EOS), even when Repeat was enabled late.
        if (mIntent.boundaryMode == LoopHeadBoundaryMode::OriginalEos &&
            evidence.sourceTimelineFrame < loopEnd() && !evidence.tailRetainedThroughEnd) {
            return LoopCyclePositionStatus::Observed;
        }
        return publishIngressRequest(
            evidence.currentIngressEpoch,
            evidence.sourceTimelineFrame < deadlineFrame() &&
                    evidence.tailRetainedThroughEnd
                ? LoopCycleActivation::WarmCurrentPass
                : LoopCycleActivation::ColdAtLoopStart)
            ? LoopCyclePositionStatus::IngressRequested
            : LoopCyclePositionStatus::Observed;
    }

    if (mPhase == LoopCyclePhase::WaitingForIngress) {
        if (mActivation == LoopCycleActivation::WarmCurrentPass &&
            evidence.sourceTimelineFrame >= deadlineFrame()) {
            return publishDeadlineFallback()
                ? LoopCyclePositionStatus::DirectivePublished
                : LoopCyclePositionStatus::Observed;
        }
        return LoopCyclePositionStatus::Observed;
    }

    if (mRenderInstalled && mMode == LoopCycleMode::Streamed) {
        if (mPreparedIngressEpoch != 0U) return LoopCyclePositionStatus::Observed;
        if (mIngressRequest.valid) {
            return evidence.sourceTimelineFrame >= deadlineFrame() &&
                    publishDeadlineFallback()
                ? LoopCyclePositionStatus::DirectivePublished
                : LoopCyclePositionStatus::Observed;
        }
        if (evidence.sourceTimelineFrame < deadlineFrame() &&
            evidence.tailRetainedThroughEnd) {
            return publishIngressRequest(
                evidence.currentIngressEpoch,
                LoopCycleActivation::WarmCurrentPass)
                ? LoopCyclePositionStatus::IngressRequested
                : LoopCyclePositionStatus::Observed;
        }
        if (evidence.sourceTimelineFrame >= deadlineFrame()) {
            return publishDeadlineFallback()
                ? LoopCyclePositionStatus::DirectivePublished
                : LoopCyclePositionStatus::Observed;
        }
    }
    return LoopCyclePositionStatus::Observed;
}

LoopCycleIngressStatus LoopCycleStateMachine::acknowledgeIngressReady(
    const LoopCycleIngressReadyEvidence &evidence) noexcept {
    if (evidence.loopRevision != mLatestRevision) {
        saturatingIncrement(mDiagnostics.ingressAckRejections);
        return LoopCycleIngressStatus::StaleRevision;
    }
    if (!mIngressRequest.valid) {
        saturatingIncrement(mDiagnostics.ingressAckRejections);
        return LoopCycleIngressStatus::NoRequest;
    }
    if (evidence.requestSequence != mIngressRequest.sequence) {
        saturatingIncrement(mDiagnostics.ingressAckRejections);
        return LoopCycleIngressStatus::UnexpectedRequest;
    }
    if (evidence.loopRevision != mIngressRequest.loopRevision) {
        saturatingIncrement(mDiagnostics.ingressAckRejections);
        return LoopCycleIngressStatus::StaleRevision;
    }
    if (evidence.owner != mIngressRequest.owner) {
        saturatingIncrement(mDiagnostics.ingressAckRejections);
        return LoopCycleIngressStatus::StaleOwner;
    }
    if (evidence.targetCycle != mIngressRequest.targetCycle) {
        saturatingIncrement(mDiagnostics.ingressAckRejections);
        return LoopCycleIngressStatus::StaleCycle;
    }
    if (evidence.ingressEpoch != mIngressRequest.ingressEpoch) {
        saturatingIncrement(mDiagnostics.ingressAckRejections);
        return LoopCycleIngressStatus::UnexpectedEpoch;
    }
    if ((evidence.readyLaneMask & mIntent.requiredLaneMask) !=
        mIntent.requiredLaneMask) {
        saturatingIncrement(mDiagnostics.ingressAckRejections);
        return LoopCycleIngressStatus::MissingRequiredLanes;
    }
    const std::uint32_t eosPadded = evidence.eosPaddedLaneMask;
    if ((eosPadded & ~evidence.readyLaneMask) != 0U ||
        (eosPadded & ~mIntent.requiredLaneMask) != 0U ||
        (eosPadded & 1U) != 0U) {
        saturatingIncrement(mDiagnostics.ingressAckRejections);
        return LoopCycleIngressStatus::InvalidCoverage;
    }
    if (evidence.firstTimelineFrame > headEnd() ||
        evidence.endTimelineFrameExclusive < requiredBodyEnd() ||
        evidence.endTimelineFrameExclusive <= evidence.firstTimelineFrame) {
        saturatingIncrement(mDiagnostics.ingressAckRejections);
        return LoopCycleIngressStatus::InvalidCoverage;
    }

    const std::uint64_t targetCycle = mIngressRequest.targetCycle;
    const LoopCycleActivation requestActivation = mIngressRequest.activation;
    mPreparedIngressEpoch = evidence.ingressEpoch;
    mPreparedTargetCycle = targetCycle;
    mIngressRequest = {};
    saturatingIncrement(mDiagnostics.ingressAcks);

    if (mDirective.valid) {
        // A deadline directive already owns the next render transition. Its commit will convert the
        // now-ready epoch into one cold activation instead of overwriting the pending clear.
        return LoopCycleIngressStatus::Accepted;
    }
    if (!mRenderInstalled) {
        if (requestActivation == LoopCycleActivation::WarmCurrentPass) {
            // Wait for one newer render-side position instead of trusting the potentially stale
            // observation that originally launched asynchronous preparation.
            mPhase = LoopCyclePhase::WaitingForSafeWindow;
        } else {
            mActivation = LoopCycleActivation::ColdAtLoopStart;
            static_cast<void>(publishDirective(
                LoopCycleDirectiveAction::ActivateColdStreamed,
                targetCycle,
                mPreparedIngressEpoch));
        }
    } else {
        mPhase = installedSteadyPhase();
    }
    return LoopCycleIngressStatus::Accepted;
}

LoopCycleBoundaryStatus LoopCycleStateMachine::onLoopBoundary(
    const LoopCycleBoundaryEvidence &evidence) noexcept {
    const auto reject = [this](LoopCycleBoundaryStatus status) noexcept {
        saturatingIncrement(mDiagnostics.rejectedBoundaryEvents);
        return status;
    };
    if (!mRenderInstalled) return reject(LoopCycleBoundaryStatus::NotActive);
    if (evidence.loopRevision != mIntent.revision) {
        return reject(LoopCycleBoundaryStatus::StaleRevision);
    }
    if (evidence.owner != mIntent.owner) {
        return reject(LoopCycleBoundaryStatus::StaleOwner);
    }
    if (evidence.sequence == 0U || evidence.sequence <= mLastBoundarySequence) {
        return reject(LoopCycleBoundaryStatus::StaleBoundary);
    }
    mLastBoundarySequence = evidence.sequence;
    if (evidence.cycle != mRenderCycle) {
        return reject(LoopCycleBoundaryStatus::StaleCycle);
    }
    if (evidence.ingressEpoch != mActiveIngressEpoch) {
        return reject(LoopCycleBoundaryStatus::UnexpectedIngressEpoch);
    }
    if (evidence.sourceTimelineFrame != loopEnd()) {
        return reject(LoopCycleBoundaryStatus::InvalidBoundary);
    }
    if (mDirective.valid) {
        return reject(LoopCycleBoundaryStatus::DirectiveAlreadyPending);
    }
    std::uint64_t nextCycle = 0U;
    if (!nextRenderCycle(nextCycle)) {
        return reject(LoopCycleBoundaryStatus::CycleOverflow);
    }
    saturatingIncrement(mDiagnostics.boundaryEvents);
    if (mMode == LoopCycleMode::CacheOnly) {
        return publishDirective(
            LoopCycleDirectiveAction::WrapCacheOnly,
            nextCycle,
            mActiveIngressEpoch)
            ? LoopCycleBoundaryStatus::DirectivePublished
            : LoopCycleBoundaryStatus::DirectiveAlreadyPending;
    }
    if (mPreparedIngressEpoch != 0U) {
        return publishDirective(
            LoopCycleDirectiveAction::WrapStreamed,
            nextCycle,
            mPreparedIngressEpoch)
            ? LoopCycleBoundaryStatus::DirectivePublished
            : LoopCycleBoundaryStatus::DirectiveAlreadyPending;
    }
    return publishDeadlineFallback()
        ? LoopCycleBoundaryStatus::DirectivePublished
        : LoopCycleBoundaryStatus::DirectiveAlreadyPending;
}

LoopCycleRenderCommitStatus LoopCycleStateMachine::commitRenderDirective(
    std::uint64_t directiveSequence) noexcept {
    if (!mDirective.valid) return LoopCycleRenderCommitStatus::NoDirective;
    if (directiveSequence != mDirective.sequence) {
        return LoopCycleRenderCommitStatus::StaleSequence;
    }
    const LoopCycleDirective committed = mDirective;
    mDirective = {};
    saturatingIncrement(mDiagnostics.renderCommits);

    switch (committed.action) {
    case LoopCycleDirectiveAction::Clear:
        mRenderInstalled = false;
        mRenderCycle = 0U;
        mFirstInstalledCycle = 0U;
        mActiveIngressEpoch = 0U;
        mPreparedIngressEpoch = 0U;
        mPreparedTargetCycle = 0U;
        mIngressRequest = {};
        mActivation = LoopCycleActivation::None;
        mAudibleEvidence = {};
        mPhase = mPhaseAfterClear;
        break;
    case LoopCycleDirectiveAction::InstallWarmCacheOnly:
    case LoopCycleDirectiveAction::InstallWarmStreamed:
        mRenderInstalled = true;
        mRenderCycle = 0U;
        mFirstInstalledCycle = 0U;
        mActiveIngressEpoch = committed.ingressEpoch;
        mActivation = LoopCycleActivation::None;
        mAudibleEvidence = {};
        mPhase = LoopCyclePhase::AwaitingCallbackProof;
        break;
    case LoopCycleDirectiveAction::ActivateColdCacheOnly:
    case LoopCycleDirectiveAction::ActivateColdStreamed:
        mRenderInstalled = true;
        mRenderCycle = committed.cycle;
        mFirstInstalledCycle = committed.cycle;
        mActiveIngressEpoch = committed.ingressEpoch;
        mPreparedIngressEpoch = 0U;
        mPreparedTargetCycle = 0U;
        mIngressRequest = {};
        mActivation = LoopCycleActivation::None;
        mAudibleEvidence = {};
        mPhase = LoopCyclePhase::AwaitingCallbackProof;
        break;
    case LoopCycleDirectiveAction::WrapCacheOnly:
        mRenderCycle = committed.cycle;
        mActivation = LoopCycleActivation::None;
        mPhase = LoopCyclePhase::AwaitingCallbackProof;
        saturatingIncrement(mDiagnostics.cacheOnlyWraps);
        break;
    case LoopCycleDirectiveAction::WrapStreamed:
        mRenderCycle = committed.cycle;
        mActiveIngressEpoch = committed.ingressEpoch;
        mPreparedIngressEpoch = 0U;
        mPreparedTargetCycle = 0U;
        mIngressRequest = {};
        mActivation = LoopCycleActivation::None;
        mPhase = LoopCyclePhase::AwaitingCallbackProof;
        saturatingIncrement(mDiagnostics.streamedWraps);
        break;
    case LoopCycleDirectiveAction::DeadlineFallback: {
        mRenderInstalled = false;
        mAudibleEvidence = {};
        mActivation = LoopCycleActivation::ColdAtLoopStart;
        mPhase = LoopCyclePhase::DeadlineFallback;
        if (mIngressRequest.valid) {
            mIngressRequest.activation = LoopCycleActivation::ColdAtLoopStart;
            mPhase = LoopCyclePhase::WaitingForIngress;
        } else if (mPreparedIngressEpoch != 0U) {
            static_cast<void>(publishDirective(
                LoopCycleDirectiveAction::ActivateColdStreamed,
                mPreparedTargetCycle,
                mPreparedIngressEpoch));
        } else {
            static_cast<void>(publishIngressRequest(
                mActiveIngressEpoch,
                LoopCycleActivation::ColdAtLoopStart));
        }
        break;
    }
    case LoopCycleDirectiveAction::None:
        break;
    }
    return LoopCycleRenderCommitStatus::Accepted;
}

LoopCycleCallbackStatus LoopCycleStateMachine::observeCallback(
    std::uint64_t loopRevision,
    std::uint64_t cycle,
    LoopCycleOwner owner,
    std::uint64_t callbackSequence) noexcept {
    if (callbackSequence != 0U && mSupersededPhysicalCallback.valid &&
        loopRevision == mSupersededPhysicalCallback.loopRevision &&
        cycle <= mSupersededPhysicalCallback.cycle &&
        owner == mSupersededPhysicalCallback.owner) {
        // A fast short loop can leave more than one cycle from the immediately retired physical
        // policy in the output reserve. They cannot prove or reject the replacement policy.
        return LoopCycleCallbackStatus::SupersededPhysicalPolicy;
    }
    if (!mRenderInstalled) {
        saturatingIncrement(mDiagnostics.rejectedCallbackProofs);
        return LoopCycleCallbackStatus::NoActiveLoop;
    }
    if (loopRevision != mIntent.revision) {
        saturatingIncrement(mDiagnostics.rejectedCallbackProofs);
        return LoopCycleCallbackStatus::StaleRevision;
    }
    if (owner != mIntent.owner) {
        saturatingIncrement(mDiagnostics.rejectedCallbackProofs);
        return LoopCycleCallbackStatus::StaleOwner;
    }
    if (callbackSequence == 0U || callbackSequence <= mLastCallbackSequence) {
        saturatingIncrement(mDiagnostics.rejectedCallbackProofs);
        return LoopCycleCallbackStatus::StaleCallback;
    }
    mLastCallbackSequence = callbackSequence;
    if (cycle < mRenderCycle && cycle >= mFirstInstalledCycle) {
        // Render cycle is the producer head while callback evidence is the device-consumer head.
        // A coherent older cycle from this exact revision and owner was already committed and can
        // remain in the output reserve; it cannot prove or reject the current producer cycle.
        return LoopCycleCallbackStatus::SupersededPhysicalPolicy;
    }
    if (cycle != mRenderCycle) {
        saturatingIncrement(mDiagnostics.rejectedCallbackProofs);
        return LoopCycleCallbackStatus::StaleCycle;
    }
    if (mAudibleEvidence.valid && mAudibleEvidence.loopRevision == loopRevision &&
        mAudibleEvidence.cycle == cycle && mAudibleEvidence.owner == owner) {
        return LoopCycleCallbackStatus::AlreadyProven;
    }
    if (!advanceSequence(mAudibleEvidenceSequence)) {
        saturatingIncrement(mDiagnostics.rejectedCallbackProofs);
        return LoopCycleCallbackStatus::StaleCallback;
    }
    mAudibleEvidence = {
        .valid = true,
        .sequence = mAudibleEvidenceSequence,
        .loopRevision = loopRevision,
        .cycle = cycle,
        .owner = owner,
        .callbackSequence = callbackSequence,
    };
    mSupersededPhysicalCallback = {};
    if (mPhase == LoopCyclePhase::AwaitingCallbackProof) {
        mPhase = LoopCyclePhase::Active;
    }
    saturatingIncrement(mDiagnostics.callbackProofs);
    return LoopCycleCallbackStatus::Accepted;
}

LoopCycleIngressRequest LoopCycleStateMachine::ingressRequest() const noexcept {
    return mIngressRequest;
}

LoopCycleDirective LoopCycleStateMachine::renderDirective() const noexcept {
    return mDirective;
}

LoopCycleAudibleEvidence LoopCycleStateMachine::audibleEvidence() const noexcept {
    return mAudibleEvidence;
}

bool LoopCycleStateMachine::blocksNaturalEnd(
    std::uint64_t loopRevision,
    const LoopCycleOwner &owner) const noexcept {
    return mIntent.enabled && loopRevision == mIntent.revision && owner == mIntent.owner &&
        mCacheAccepted;
}

LoopCycleStateSnapshot LoopCycleStateMachine::snapshot() const noexcept {
    const bool audiblyProven = currentCycleAudiblyProven();
    return {
        .mode = mMode,
        .phase = mPhase,
        .activation = mActivation,
        .latestRevision = mLatestRevision,
        .owner = mIntent.owner,
        .renderCycle = mRenderCycle,
        .activeIngressEpoch = mActiveIngressEpoch,
        .preparedIngressEpoch = mPreparedIngressEpoch,
        .cacheChecksum = mCacheChecksum,
        .cacheAccepted = mCacheAccepted,
        .renderInstalled = mRenderInstalled,
        .renderActive = audiblyProven,
        .audiblyProven = audiblyProven,
        .ingressRequestPending = mIngressRequest.valid,
        .renderDirectivePending = mDirective.valid,
        .boundaryMode = mIntent.boundaryMode,
        .boundaryResolved = mBoundaryResolved,
        .resolvedLoopEndTimelineFrameExclusive = mResolvedLoopEnd,
    };
}

LoopCycleDiagnostics LoopCycleStateMachine::diagnostics() const noexcept {
    return mDiagnostics;
}

}  // namespace stemwise::audio::engine
