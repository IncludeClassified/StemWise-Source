#include "GuardedAtomicSnapshot.h"
#include "NativeAudioOutputLoopBoundaryGate.h"

#include <atomic>

namespace stemwise::audio {

std::size_t taggedFramesBeforeLoopBoundary(
    const SpscTaggedMultibusRingBuffer &ring,
    std::uint64_t generation,
    std::uint64_t selectionToken,
    std::uint64_t bTimelineFrameExclusive,
    std::size_t maximumFrames,
    bool &boundaryReached,
    bool detectBackwardDiscontinuity,
    std::int64_t previousTimelineFrame,
    std::uint64_t expectedLoopPolicyRevision,
    std::uint64_t *boundaryLoopPolicyRevision,
    std::int64_t captureReferenceTimelineFrame) noexcept {
    boundaryReached = false;
    if (maximumFrames == 0U || generation == 0U || selectionToken == 0U) return 0U;
    const TaggedMultibusReadLease lease = ring.acquireRead(maximumFrames);
    std::size_t prefix = 0U;
    // A capture can race a completed loop wrap. Retain the cursor that proved the crossing
    // so the exact outgoing tags still reveal that discontinuity after publication.
    std::int64_t prior = std::max(previousTimelineFrame, captureReferenceTimelineFrame);
    for (std::size_t spanIndex = 0U; spanIndex < lease.spanCount; ++spanIndex) {
        const TaggedMultibusReadSpan &span = lease.spans[spanIndex];
        for (std::size_t frame = 0U; frame < span.frameCount; ++frame) {
            const RenderBusFrameTag &tag = span.tags[frame];
            if (tag.generation == generation && tag.selectionToken == selectionToken &&
                tag.timelineFrame >= 0 &&
                (expectedLoopPolicyRevision ==
                     std::numeric_limits<std::uint64_t>::max() ||
                 tag.loopPolicyRevision == expectedLoopPolicyRevision)) {
                if (static_cast<std::uint64_t>(tag.timelineFrame) >=
                        bTimelineFrameExclusive ||
                    (detectBackwardDiscontinuity && prior >= 0 &&
                     tag.timelineFrame < prior)) {
                    boundaryReached = true;
                    if (boundaryLoopPolicyRevision != nullptr) {
                        *boundaryLoopPolicyRevision = tag.loopPolicyRevision;
                    }
                    return prefix;
                }
                prior = tag.timelineFrame;
                if (tag.nextTimelineFrame >= 0 &&
                    static_cast<std::uint64_t>(tag.nextTimelineFrame) >=
                        bTimelineFrameExclusive) {
                    // The producer may stop exactly at B. Consume its final pre-B frame;
                    // waiting for a later tag would strand the already prepared A role.
                    boundaryReached = true;
                    if (boundaryLoopPolicyRevision != nullptr) {
                        *boundaryLoopPolicyRevision = tag.loopPolicyRevision;
                    }
                    return prefix + 1U;
                }
            }
            ++prefix;
        }
    }
    return prefix;
}

std::size_t taggedFramesBeforeDormantLoopBoundary(
    const SpscTaggedMultibusRingBuffer &ring,
    std::uint64_t generation,
    std::uint64_t selectionToken,
    std::uint64_t aTimelineFrame,
    std::uint64_t bTimelineFrameExclusive,
    std::size_t maximumFrames,
    bool &rangeEntered,
    bool &boundaryReached,
    std::uint64_t expectedLoopPolicyRevision,
    std::uint64_t *boundaryLoopPolicyRevision) noexcept {
    rangeEntered = false;
    boundaryReached = false;
    if (maximumFrames == 0U || generation == 0U || selectionToken == 0U ||
        aTimelineFrame >= bTimelineFrameExclusive) {
        return 0U;
    }

    const TaggedMultibusReadLease lease = ring.acquireRead(maximumFrames);
    std::size_t prefix = 0U;
    std::int64_t prior = -1;
    for (std::size_t spanIndex = 0U; spanIndex < lease.spanCount; ++spanIndex) {
        const TaggedMultibusReadSpan &span = lease.spans[spanIndex];
        for (std::size_t frame = 0U; frame < span.frameCount; ++frame) {
            const RenderBusFrameTag &tag = span.tags[frame];
            const bool exact = tag.generation == generation &&
                tag.selectionToken == selectionToken && tag.timelineFrame >= 0 &&
                (expectedLoopPolicyRevision ==
                     std::numeric_limits<std::uint64_t>::max() ||
                 tag.loopPolicyRevision == expectedLoopPolicyRevision);
            if (!exact) {
                ++prefix;
                continue;
            }

            const std::uint64_t timeline = static_cast<std::uint64_t>(tag.timelineFrame);
            if (!rangeEntered) {
                if (timeline < aTimelineFrame || timeline >= bTimelineFrameExclusive) {
                    ++prefix;
                    continue;
                }
                rangeEntered = true;
            }
            if (timeline >= bTimelineFrameExclusive ||
                (prior >= 0 && tag.timelineFrame < prior)) {
                boundaryReached = true;
                if (boundaryLoopPolicyRevision != nullptr) {
                    *boundaryLoopPolicyRevision = tag.loopPolicyRevision;
                }
                return prefix;
            }
            prior = tag.timelineFrame;
            if (tag.nextTimelineFrame >= 0 &&
                static_cast<std::uint64_t>(tag.nextTimelineFrame) >=
                    bTimelineFrameExclusive) {
                boundaryReached = true;
                if (boundaryLoopPolicyRevision != nullptr) {
                    *boundaryLoopPolicyRevision = tag.loopPolicyRevision;
                }
                return prefix + 1U;
            }
            ++prefix;
        }
    }
    return prefix;
}

bool NativeAudioOutputLoopGestureCaptureGate::shouldCapture(
    const NativeAudioOutputLoopPolicy &policy,
    bool explicitCrossing,
    std::uint64_t presentedGeneration,
    std::uint64_t presentedSelectionToken,
    std::int64_t presentedTimelineFrame,
    std::uint64_t presentedCallbackSequence,
    const NativeAudioOutputLoopCaptureEvidence &cutoverEvidence) noexcept {
    if (!policy.enabled || policy.heldBGestureId == 0U) {
        reset();
        return explicitCrossing;
    }
    const bool sameGesture = mGeneration == policy.generation &&
        mSelectionToken == policy.selectionToken && mA == policy.aTimelineFrame &&
        mGestureId == policy.heldBGestureId;
    if (!sameGesture) {
        mGeneration = policy.generation;
        mSelectionToken = policy.selectionToken;
        mA = policy.aTimelineFrame;
        mGestureId = policy.heldBGestureId;
        mAwaitedCaptureRevision = 0U;
        mArmed = policy.boundaryArmedAtPublication;
    }
    const bool exactPresentedOwner = presentedGeneration == policy.generation &&
        presentedSelectionToken == policy.selectionToken;
    if (mAwaitedCaptureRevision != 0U) {
        const bool exactCutoverCompleted = cutoverEvidence.valid() &&
            cutoverEvidence.revision == mAwaitedCaptureRevision &&
            cutoverEvidence.generation == policy.generation &&
            cutoverEvidence.selectionToken == policy.selectionToken &&
            cutoverEvidence.aTimelineFrame == policy.aTimelineFrame &&
            cutoverEvidence.heldBGestureId == policy.heldBGestureId &&
            exactPresentedOwner &&
            presentedCallbackSequence > cutoverEvidence.callbackSequence;
        if (!exactCutoverCompleted) return false;
        mAwaitedCaptureRevision = 0U;
        mArmed = true;
    } else if (policy.boundaryArmedAtPublication) {
        mArmed = true;
    }
    if (explicitCrossing) {
        mArmed = false;
        mAwaitedCaptureRevision = policy.revision;
        return true;
    }
    const bool crossedVisibleB = mArmed && exactPresentedOwner &&
        presentedTimelineFrame >= 0 &&
        static_cast<std::uint64_t>(presentedTimelineFrame) >=
            policy.bTimelineFrameExclusive;
    if (!crossedVisibleB) return false;
    mArmed = false;
    mAwaitedCaptureRevision = policy.revision;
    return true;
}

bool AtomicNativeAudioOutputLoopPolicy::publish(
    const NativeAudioOutputLoopPolicy &policy) noexcept {
    if (!policy.valid()) return false;
    const std::uint64_t current = mRevision.load(std::memory_order_acquire);
    if (policy.revision < current) return false;
    if (policy.revision == current) {
        NativeAudioOutputLoopPolicy existing{};
        if (!tryRead(existing)) return false;
        if (existing == policy) return true;
        if (!policy.isPhysicalPromotionOf(existing) &&
            !policy.isPhysicalCycleSuccessorOf(existing)) {
            return false;
        }
    }
    beginGuardedPublication(mGuard);
    mGeneration.store(policy.generation, std::memory_order_relaxed);
    mSelectionToken.store(policy.selectionToken, std::memory_order_relaxed);
    mEnabled.store(policy.enabled ? 1U : 0U, std::memory_order_relaxed);
    mBoundaryArmedAtPublication.store(
        policy.boundaryArmedAtPublication ? 1U : 0U,
        std::memory_order_relaxed);
    mNextRevisionOnly.store(
        policy.nextRevisionOnly ? 1U : 0U,
        std::memory_order_relaxed);
    mPhysicalReady.store(policy.physicalReady ? 1U : 0U, std::memory_order_relaxed);
    mA.store(policy.aTimelineFrame, std::memory_order_relaxed);
    mB.store(policy.bTimelineFrameExclusive, std::memory_order_relaxed);
    mCutover.store(policy.cutoverTimelineFrameExclusive, std::memory_order_relaxed);
    mContinuationFrame.store(
        policy.continuationTimelineFrame,
        std::memory_order_relaxed);
    mCutoverSourceRevision.store(
        policy.cutoverSourceRevision,
        std::memory_order_relaxed);
    mContinuationRevision.store(policy.continuationRevision, std::memory_order_relaxed);
    mAcceptedAtMonotonicMicros.store(
        policy.acceptedAtMonotonicMicros,
        std::memory_order_relaxed);
    mPublishedAtMonotonicMicros.store(
        policy.publishedAtMonotonicMicros,
        std::memory_order_relaxed);
    mInputEventAtMonotonicMicros.store(
        policy.inputEventAtMonotonicMicros,
        std::memory_order_relaxed);
    mCaptureReferenceTimelineFrame.store(policy.captureReferenceTimelineFrame,
        std::memory_order_relaxed);
    mHeldBGestureId.store(policy.heldBGestureId, std::memory_order_relaxed);
    mRevision.store(policy.revision, std::memory_order_relaxed);
    mGuard.fetch_add(1U, std::memory_order_release);
    return true;
}

bool AtomicNativeAudioOutputLoopPolicy::tryRead(
    NativeAudioOutputLoopPolicy &policy) const noexcept {
    const std::uint64_t before = mGuard.load(std::memory_order_acquire);
    if ((before & 1U) != 0U) return false;
    NativeAudioOutputLoopPolicy candidate{
        .revision = mRevision.load(std::memory_order_relaxed),
        .generation = mGeneration.load(std::memory_order_relaxed),
        .selectionToken = mSelectionToken.load(std::memory_order_relaxed),
        .enabled = mEnabled.load(std::memory_order_relaxed) != 0U,
        .physicalReady = mPhysicalReady.load(std::memory_order_relaxed) != 0U,
        .aTimelineFrame = mA.load(std::memory_order_relaxed),
        .bTimelineFrameExclusive = mB.load(std::memory_order_relaxed),
        .cutoverTimelineFrameExclusive = mCutover.load(std::memory_order_relaxed),
        .continuationTimelineFrame =
            mContinuationFrame.load(std::memory_order_relaxed),
        .cutoverSourceRevision =
            mCutoverSourceRevision.load(std::memory_order_relaxed),
        .continuationRevision =
            mContinuationRevision.load(std::memory_order_relaxed),
        .acceptedAtMonotonicMicros =
            mAcceptedAtMonotonicMicros.load(std::memory_order_relaxed),
        .publishedAtMonotonicMicros =
            mPublishedAtMonotonicMicros.load(std::memory_order_relaxed),
        .inputEventAtMonotonicMicros =
            mInputEventAtMonotonicMicros.load(std::memory_order_relaxed),
        .captureReferenceTimelineFrame =
            mCaptureReferenceTimelineFrame.load(std::memory_order_relaxed),
        .heldBGestureId = mHeldBGestureId.load(std::memory_order_relaxed),
        .boundaryArmedAtPublication =
            mBoundaryArmedAtPublication.load(std::memory_order_relaxed) != 0U,
        .nextRevisionOnly =
            mNextRevisionOnly.load(std::memory_order_relaxed) != 0U,
    };
    std::atomic_thread_fence(std::memory_order_acquire);
    const std::uint64_t after = mGuard.load(std::memory_order_relaxed);
    if (before != after || (after & 1U) != 0U || !candidate.valid()) return false;
    policy = candidate;
    return true;
}

bool LatestNativeAudioOutputLoopPolicyMailbox::publish(
    const NativeAudioOutputLoopPolicy &policy) noexcept {
    if (!policy.valid()) return false;
    if (mProducerHasPolicy) {
        if (policy.revision < mProducerLastPolicy.revision) return false;
        if (policy.revision == mProducerLastPolicy.revision) {
            return policy == mProducerLastPolicy;
        }
    }

    mSlots[mProducerBackSlot] = policy;
    const std::size_t previousMiddle = mMiddleState.exchange(
        packed(mProducerBackSlot, true),
        std::memory_order_acq_rel);
    mProducerBackSlot = slot(previousMiddle);
    mProducerLastPolicy = policy;
    mProducerHasPolicy = true;
    return true;
}

bool LatestNativeAudioOutputLoopPolicyMailbox::readLatest(
    NativeAudioOutputLoopPolicy &policy) noexcept {
    const std::size_t observed = mMiddleState.load(std::memory_order_acquire);
    if ((observed & kUnread) == 0U) return false;
    const std::size_t claimed = mMiddleState.exchange(
        packed(mConsumerFrontSlot, false),
        std::memory_order_acq_rel);
    if ((claimed & kUnread) == 0U) return false;
    mConsumerFrontSlot = slot(claimed);
    const NativeAudioOutputLoopPolicy candidate = mSlots[mConsumerFrontSlot];
    if (!candidate.valid()) return false;
    policy = candidate;
    return true;
}

bool LatestNativeAudioOutputLoopPolicyMailbox::readLatestForGeneration(
    std::uint64_t generation,
    NativeAudioOutputLoopPolicy &policy) noexcept {
    NativeAudioOutputLoopPolicy latest{};
    if (readLatest(latest) &&
        (!mConsumerHasPendingPolicy ||
         latest.revision >= mConsumerPendingPolicy.revision)) {
        mConsumerPendingPolicy = latest;
        mConsumerHasPendingPolicy = true;
    }
    if (!mConsumerHasPendingPolicy) return false;
    if (mConsumerPendingPolicy.generation < generation) {
        mConsumerPendingPolicy = {};
        mConsumerHasPendingPolicy = false;
        return false;
    }
    if (mConsumerPendingPolicy.generation != generation) return false;
    policy = mConsumerPendingPolicy;
    mConsumerPendingPolicy = {};
    mConsumerHasPendingPolicy = false;
    return true;
}

} // namespace stemwise::audio
