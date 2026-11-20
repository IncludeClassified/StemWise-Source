#pragma once

#include "SpscTaggedMultibusRingBuffer.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <type_traits>

namespace stemwise::audio {

/** Exact callback-side fixed-loop policy. B is an exclusive PCM boundary. */
struct NativeAudioOutputLoopPolicy final {
    std::uint64_t revision{0U};
    std::uint64_t generation{0U};
    std::uint64_t selectionToken{0U};
    bool enabled{false};
    /** False publishes logical A/B immediately while physical continuation staging catches up. */
    bool physicalReady{true};
    std::uint64_t aTimelineFrame{0U};
    std::uint64_t bTimelineFrameExclusive{0U};
    /**
     * One pending physical role-swap boundary, distinct from logical B.
     *
     * Zero means the adopted policy already runs on the active ring. A non-zero value lets a
     * replacement continue at `continuationTimelineFrame` without forcing a logical B move to
     * seek to A (for example, extending B or clearing a loop at the old B).
     */
    std::uint64_t cutoverTimelineFrameExclusive{0U};
    std::uint64_t continuationTimelineFrame{0U};
    /** Exact outgoing active-ring policy revision; zero denotes the initial linear stream. */
    std::uint64_t cutoverSourceRevision{0U};
    /** Immutable prepared-continuation PCM identity; compatible B edits retain this revision. */
    std::uint64_t continuationRevision{0U};
    /** Direct policy admission timestamp; lets callback evidence include producer preparation. */
    std::uint64_t acceptedAtMonotonicMicros{0U};
    /** Exact timestamp sampled after the native atomic publication succeeded. */
    std::uint64_t publishedAtMonotonicMicros{0U};
    /** Originating Android input uptime; zero for non-pointer policy changes. */
    std::uint64_t inputEventAtMonotonicMicros{0U};
    /** Cursor that crossed captured B; a later smaller cursor proves a concurrent wrap. */
    std::int64_t captureReferenceTimelineFrame{-1};
    /** Non-zero only for publications from one physical B-handle gesture. */
    std::uint64_t heldBGestureId{0U};
    /** Logical demand was inside A..B at publication; a later callback must not disarm it. */
    bool boundaryArmedAtPublication{false};
    /** Prepared A-head spare; callback may claim it only for a newer logical revision. */
    bool nextRevisionOnly{false};

    [[nodiscard]] constexpr bool operator==(
        const NativeAudioOutputLoopPolicy &) const noexcept = default;

    [[nodiscard]] constexpr bool valid() const noexcept {
        constexpr std::uint64_t maximumTimelineFrame =
            static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max());
        return revision != 0U && generation != 0U && selectionToken != 0U &&
            captureReferenceTimelineFrame >= -1 &&
            (!enabled ||
             (aTimelineFrame < bTimelineFrameExclusive &&
              bTimelineFrameExclusive <= maximumTimelineFrame)) &&
            cutoverTimelineFrameExclusive <= maximumTimelineFrame &&
            continuationTimelineFrame <= maximumTimelineFrame &&
            (cutoverTimelineFrameExclusive != 0U ||
             continuationTimelineFrame == 0U) &&
            physicalRevision() != 0U;
    }

    [[nodiscard]] constexpr std::uint64_t physicalRevision() const noexcept {
        return continuationRevision == 0U ? revision : continuationRevision;
    }

    [[nodiscard]] constexpr bool hasPhysicalCutover() const noexcept {
        return cutoverTimelineFrameExclusive != 0U;
    }

    [[nodiscard]] constexpr bool callbackPhysicalCutoverReady() const noexcept {
        return physicalReady && hasPhysicalCutover();
    }

    [[nodiscard]] constexpr bool isPhysicalPromotionOf(
        const NativeAudioOutputLoopPolicy &prior) const noexcept {
        return revision == prior.revision && generation == prior.generation &&
            selectionToken == prior.selectionToken && enabled == prior.enabled &&
            aTimelineFrame == prior.aTimelineFrame &&
            bTimelineFrameExclusive == prior.bTimelineFrameExclusive &&
            boundaryArmedAtPublication == prior.boundaryArmedAtPublication &&
            acceptedAtMonotonicMicros == prior.acceptedAtMonotonicMicros &&
            publishedAtMonotonicMicros == prior.publishedAtMonotonicMicros &&
            inputEventAtMonotonicMicros == prior.inputEventAtMonotonicMicros &&
            heldBGestureId == prior.heldBGestureId &&
            !prior.physicalReady && physicalReady;
    }

    /** A newer prepared role for the same logical A/B demand. */
    [[nodiscard]] constexpr bool isPhysicalCycleSuccessorOf(
        const NativeAudioOutputLoopPolicy &prior) const noexcept {
        return physicalReady && prior.physicalReady && hasPhysicalCutover() &&
            prior.hasPhysicalCutover() && physicalRevision() > prior.physicalRevision() &&
            revision == prior.revision && generation == prior.generation &&
            selectionToken == prior.selectionToken && enabled == prior.enabled &&
            aTimelineFrame == prior.aTimelineFrame &&
            bTimelineFrameExclusive == prior.bTimelineFrameExclusive &&
            acceptedAtMonotonicMicros == prior.acceptedAtMonotonicMicros &&
            publishedAtMonotonicMicros == prior.publishedAtMonotonicMicros &&
            inputEventAtMonotonicMicros == prior.inputEventAtMonotonicMicros &&
            heldBGestureId == prior.heldBGestureId &&
            boundaryArmedAtPublication == prior.boundaryArmedAtPublication;
    }

    /** Physical PCM prepared for this exact logical A/B demand; revision domains never mix. */
    [[nodiscard]] constexpr bool realizesLogicalDemand(
        const NativeAudioOutputLoopPolicy &demand) const noexcept {
        return physicalReady && !nextRevisionOnly && !demand.physicalReady &&
            revision == demand.revision && generation == demand.generation &&
            selectionToken == demand.selectionToken && enabled == demand.enabled &&
            aTimelineFrame == demand.aTimelineFrame &&
            bTimelineFrameExclusive == demand.bTimelineFrameExclusive &&
            acceptedAtMonotonicMicros == demand.acceptedAtMonotonicMicros &&
            publishedAtMonotonicMicros == demand.publishedAtMonotonicMicros &&
            inputEventAtMonotonicMicros == demand.inputEventAtMonotonicMicros &&
            heldBGestureId == demand.heldBGestureId &&
            boundaryArmedAtPublication == demand.boundaryArmedAtPublication;
    }

    /** B-only MOVE reuses the same continuation while that continuation remains before B. */
    [[nodiscard]] constexpr bool canRetargetPreparedContinuationTo(
        const NativeAudioOutputLoopPolicy &demand,
        bool capturedBoundaryPending = false) const noexcept {
        // A natural wrap can adopt the revision before its pending capture claims the A spare.
        const bool capturedA = capturedBoundaryPending &&
            continuationTimelineFrame == demand.aTimelineFrame;
        return physicalReady && hasPhysicalCutover() && !demand.physicalReady &&
            enabled && demand.enabled && generation == demand.generation &&
            selectionToken == demand.selectionToken &&
            (!capturedBoundaryPending || capturedA) &&
            (!nextRevisionOnly || demand.revision > revision ||
                (capturedA && demand.revision == revision)) &&
            aTimelineFrame == demand.aTimelineFrame &&
            !(nextRevisionOnly && cutoverSourceRevision != 0U &&
              continuationTimelineFrame == aTimelineFrame &&
              demand.bTimelineFrameExclusive > cutoverTimelineFrameExclusive &&
              !capturedA) &&
            (continuationTimelineFrame == demand.aTimelineFrame ||
             continuationTimelineFrame < demand.bTimelineFrameExclusive);
    }

};

/** Dormant demand already behind the exact owner cursor cannot fence drain/EOS. */
[[nodiscard]] constexpr bool nativeAudioOutputLoopDemandBlocksDrain(
    const NativeAudioOutputLoopPolicy &policy,
    std::uint64_t presentedGeneration,
    std::uint64_t presentedSelectionToken,
    std::int64_t presentedTimelineFrame) noexcept {
    if (!policy.enabled) return false;
    return policy.boundaryArmedAtPublication || presentedTimelineFrame < 0 ||
        presentedGeneration != policy.generation ||
        presentedSelectionToken != policy.selectionToken ||
        static_cast<std::uint64_t>(presentedTimelineFrame) <
            policy.bTimelineFrameExclusive;
}

/** B is logical state: bind its newest value to immutable prepared PCM in the callback. */
[[nodiscard]] constexpr NativeAudioOutputLoopPolicy bindLogicalDemandToPreparedContinuation(
    const NativeAudioOutputLoopPolicy &prepared,
    const NativeAudioOutputLoopPolicy &demand,
    bool capturedBoundaryPending = false) noexcept {
    if (!prepared.canRetargetPreparedContinuationTo(demand, capturedBoundaryPending)) return prepared;
    NativeAudioOutputLoopPolicy bound = prepared;
    bound.revision = demand.revision;
    bound.bTimelineFrameExclusive = demand.bTimelineFrameExclusive;
    if (prepared.cutoverSourceRevision == 0U) {
        bound.cutoverTimelineFrameExclusive = demand.bTimelineFrameExclusive;
    } else if (demand.bTimelineFrameExclusive < bound.cutoverTimelineFrameExclusive) {
        bound.cutoverTimelineFrameExclusive = demand.bTimelineFrameExclusive;
    }
    bound.acceptedAtMonotonicMicros = demand.acceptedAtMonotonicMicros;
    bound.publishedAtMonotonicMicros = demand.publishedAtMonotonicMicros;
    bound.inputEventAtMonotonicMicros = demand.inputEventAtMonotonicMicros;
    bound.captureReferenceTimelineFrame = demand.captureReferenceTimelineFrame;
    bound.heldBGestureId = demand.heldBGestureId;
    bound.boundaryArmedAtPublication = demand.boundaryArmedAtPublication;
    bound.nextRevisionOnly = false;
    return bound;
}

/** Immutable callback proof that one gesture capture physically cut B to A. */
struct NativeAudioOutputLoopCaptureEvidence final {
    std::uint64_t revision{0U};
    std::uint64_t callbackSequence{0U};
    std::uint64_t generation{0U};
    std::uint64_t selectionToken{0U};
    std::uint64_t aTimelineFrame{0U};
    std::uint64_t heldBGestureId{0U};

    [[nodiscard]] constexpr bool valid() const noexcept {
        return revision != 0U && callbackSequence != 0U && generation != 0U &&
            selectionToken != 0U && heldBGestureId != 0U;
    }
};

/** Producer-owned one-shot latch for repeated B crossings during one uninterrupted gesture. */
class NativeAudioOutputLoopGestureCaptureGate final {
public:
    void reset() noexcept { *this = {}; }

    [[nodiscard]] bool shouldCapture(
        const NativeAudioOutputLoopPolicy &policy,
        bool explicitCrossing,
        std::uint64_t presentedGeneration,
        std::uint64_t presentedSelectionToken,
        std::int64_t presentedTimelineFrame,
        std::uint64_t presentedCallbackSequence,
        const NativeAudioOutputLoopCaptureEvidence &cutoverEvidence) noexcept;

    void retryCapture(std::uint64_t revision) noexcept {
        if (mAwaitedCaptureRevision != revision) return;
        mAwaitedCaptureRevision = 0U;
        mArmed = true;
    }

private:
    std::uint64_t mGeneration{0U};
    std::uint64_t mSelectionToken{0U};
    std::uint64_t mA{0U};
    std::uint64_t mGestureId{0U};
    std::uint64_t mAwaitedCaptureRevision{0U};
    bool mArmed{false};
};

/** One latest-only SPSC publication from the control path to the device callback. */
class AtomicNativeAudioOutputLoopPolicy final {
public:
    [[nodiscard]] bool publish(const NativeAudioOutputLoopPolicy &policy) noexcept;
    [[nodiscard]] bool tryRead(NativeAudioOutputLoopPolicy &policy) const noexcept;

private:
    alignas(64) std::atomic<std::uint64_t> mGuard{0U};
    std::atomic<std::uint64_t> mRevision{0U};
    std::atomic<std::uint64_t> mGeneration{0U};
    std::atomic<std::uint64_t> mSelectionToken{0U};
    std::atomic<std::uint64_t> mA{0U};
    std::atomic<std::uint64_t> mB{0U};
    std::atomic<std::uint64_t> mCutover{0U};
    std::atomic<std::uint64_t> mContinuationFrame{0U};
    std::atomic<std::uint64_t> mCutoverSourceRevision{0U};
    std::atomic<std::uint64_t> mContinuationRevision{0U};
    std::atomic<std::uint64_t> mAcceptedAtMonotonicMicros{0U};
    std::atomic<std::uint64_t> mPublishedAtMonotonicMicros{0U};
    std::atomic<std::uint64_t> mInputEventAtMonotonicMicros{0U};
    std::atomic<std::int64_t> mCaptureReferenceTimelineFrame{-1};
    std::atomic<std::uint64_t> mHeldBGestureId{0U};
    std::atomic<std::uint32_t> mEnabled{0U};
    std::atomic<std::uint32_t> mBoundaryArmedAtPublication{0U};
    std::atomic<std::uint32_t> mNextRevisionOnly{0U};
    std::atomic<std::uint32_t> mPhysicalReady{1U};

    static_assert(std::atomic<std::uint64_t>::is_always_lock_free);
    static_assert(std::atomic<std::uint32_t>::is_always_lock_free);
};

/** Three-slot latest-only SPSC mailbox; neither finger publisher nor callback ever waits. */
class LatestNativeAudioOutputLoopPolicyMailbox final {
public:
    /** Serialized producer; exact same-revision retries remain idempotent. */
    [[nodiscard]] bool publish(const NativeAudioOutputLoopPolicy &policy) noexcept;

    /** Claims the newest unread value with one lock-free ownership exchange. */
    [[nodiscard]] bool readLatest(NativeAudioOutputLoopPolicy &policy) noexcept;

    /** Retains a future-owner value until that exact callback generation is adopted. */
    [[nodiscard]] bool readLatestForGeneration(
        std::uint64_t generation,
        NativeAudioOutputLoopPolicy &policy) noexcept;

private:
    static constexpr std::size_t kSlotCount = 3U;
    static constexpr std::size_t kSlotMask = 3U;
    static constexpr std::size_t kUnread = 1U << 2U;
    [[nodiscard]] static constexpr std::size_t packed(
        std::size_t slot,
        bool unread) noexcept {
        return slot | (unread ? kUnread : 0U);
    }
    [[nodiscard]] static constexpr std::size_t slot(std::size_t state) noexcept {
        return state & kSlotMask;
    }

    std::array<NativeAudioOutputLoopPolicy, kSlotCount> mSlots{};
    alignas(64) std::atomic<std::size_t> mMiddleState{packed(1U, false)};
    std::size_t mProducerBackSlot{2U};
    std::size_t mConsumerFrontSlot{0U};
    NativeAudioOutputLoopPolicy mConsumerPendingPolicy{};
    bool mConsumerHasPendingPolicy{false};
    NativeAudioOutputLoopPolicy mProducerLastPolicy{};
    bool mProducerHasPolicy{false};

    static_assert(std::atomic<std::size_t>::is_always_lock_free);
    static_assert(std::is_trivially_copyable_v<NativeAudioOutputLoopPolicy>);
};

/** Callback-safe exact prefix scan used by the production two-ring cutover and its closure test.
 * captureReferenceTimelineFrame is supplied only for a pending captured crossing with ready A
 * and an exact outgoing owner; ordinary cutovers leave it unset.
 */
[[nodiscard]] std::size_t taggedFramesBeforeLoopBoundary(
    const SpscTaggedMultibusRingBuffer &ring,
    std::uint64_t generation,
    std::uint64_t selectionToken,
    std::uint64_t bTimelineFrameExclusive,
    std::size_t maximumFrames,
    bool &boundaryReached,
    bool detectBackwardDiscontinuity = false,
    std::int64_t previousTimelineFrame = -1,
    std::uint64_t expectedLoopPolicyRevision =
        std::numeric_limits<std::uint64_t>::max(),
    std::uint64_t *boundaryLoopPolicyRevision = nullptr,
    std::int64_t captureReferenceTimelineFrame = -1) noexcept;

/**
 * Exact unread-queue planner for a dormant loop demand.
 *
 * Frames before A and frames already beyond B remain linear. Once an exact-owner unread tag first
 * enters [A, B), the same scan becomes an active B fence and reports that entry to the callback.
 * No submitted, UI, or hardware-position estimate participates in this decision.
 */
[[nodiscard]] std::size_t taggedFramesBeforeDormantLoopBoundary(
    const SpscTaggedMultibusRingBuffer &ring,
    std::uint64_t generation,
    std::uint64_t selectionToken,
    std::uint64_t aTimelineFrame,
    std::uint64_t bTimelineFrameExclusive,
    std::size_t maximumFrames,
    bool &rangeEntered,
    bool &boundaryReached,
    std::uint64_t expectedLoopPolicyRevision =
        std::numeric_limits<std::uint64_t>::max(),
    std::uint64_t *boundaryLoopPolicyRevision = nullptr) noexcept;

/** PCM is gated only by a physically installed or callback-claimable policy, never by demand. */
enum class NativeAudioOutputLoopPcmAuthority : std::uint8_t {
    Linear,
    PreparedCutover,
};

[[nodiscard]] constexpr NativeAudioOutputLoopPcmAuthority
nativeAudioOutputLoopPcmAuthority(bool preparedCutoverRealizesDemand) noexcept {
    return preparedCutoverRealizesDemand
        ? NativeAudioOutputLoopPcmAuthority::PreparedCutover
        : NativeAudioOutputLoopPcmAuthority::Linear;
}

/** Retains one crossed B fence while the latest visible B continues moving. */
[[nodiscard]] constexpr std::uint64_t nativeAudioOutputCapturedLoopCutover(
    std::uint64_t preparedCutoverFrameExclusive,
    std::uint64_t latestBFrameExclusive,
    std::uint64_t capturedBFrameExclusive,
    bool capturePending) noexcept {
    return capturePending
        ? std::min(
            preparedCutoverFrameExclusive,
            std::min(latestBFrameExclusive, capturedBFrameExclusive))
        : preparedCutoverFrameExclusive;
}

/** A preserved B crossing always owns an armed callback fence, even after newer MOVE demand. */
[[nodiscard]] constexpr NativeAudioOutputLoopPolicy bindCapturedLoopCutover(
    NativeAudioOutputLoopPolicy policy,
    std::uint64_t latestBFrameExclusive,
    std::uint64_t capturedBFrameExclusive) noexcept {
    policy.cutoverTimelineFrameExclusive = nativeAudioOutputCapturedLoopCutover(
        policy.cutoverTimelineFrameExclusive,
        latestBFrameExclusive,
        capturedBFrameExclusive,
        true);
    policy.boundaryArmedAtPublication = true;
    return policy;
}

/** Exact prepared PCM blocks EOS while its outgoing owner remains inside A/B. */
[[nodiscard]] constexpr bool nativeAudioOutputPreparedLoopBlocksEnd(
    bool preparedCutoverRealizesDemand,
    bool exactOutgoingPhysicalTag,
    bool outgoingFrameInsideRange) noexcept {
    return preparedCutoverRealizesDemand && exactOutgoingPhysicalTag &&
        outgoingFrameInsideRange;
}

/** An armed prepared A owns the callback immediately once its exact outgoing stream drained. */
[[nodiscard]] constexpr bool nativeAudioOutputPreparedLoopOwnsTerminalCutover(
    bool preparedCutoverRealizesDemand,
    bool boundaryArmedAtPublication,
    bool exactOutgoingPhysicalTag,
    bool terminalAfterFrame,
    bool outgoingQueueEmpty) noexcept {
    return preparedCutoverRealizesDemand && boundaryArmedAtPublication &&
        exactOutgoingPhysicalTag && terminalAfterFrame && outgoingQueueEmpty;
}

} // namespace stemwise::audio
