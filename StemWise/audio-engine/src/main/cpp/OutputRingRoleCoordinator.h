#pragma once

#include "GuardedAtomicSnapshot.h"

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <limits>

namespace stemwise::audio {

#if defined(STEMWISE_OUTPUT_ROLE_TEST_HOOKS)
namespace role_test {
void beforeCallbackClaim() noexcept;
void beforeCancellationClaim() noexcept;
}
#endif

enum class OutputHandoffStatus : std::uint8_t {
    Accepted,
    StaleGeneration,
    AwaitingAcknowledgement,
    NoOpenStage,
    WrongGeneration,
    EmptyStage,
    WrongMode,
    WrongActiveGeneration,
    InvalidTransitionRevision,
    AutomaticDrainFenced,
};

enum class OutputHandoffMode : std::uint8_t {
    Immediate = 0,
    DrainActive = 1,
    /** Same-owner prepared A continuation adopted only at an exact callback loop boundary. */
    LoopCutover = 2,
};

enum class OutputDrainCancelStatus : std::uint8_t {
    AcceptedOpenStage,
    AcceptedAwaitingCallback,
    AlreadyRequested,
    NoMatchingDrain,
    TooLate,
};

struct OutputHandoffBeginResult final {
    OutputHandoffStatus status{OutputHandoffStatus::StaleGeneration};
    std::uint32_t stagingRing{0};

    [[nodiscard]] constexpr bool accepted() const noexcept {
        return status == OutputHandoffStatus::Accepted;
    }
};

struct OutputRoleTarget final {
    std::uint32_t ring{0};
    std::uint64_t generation{0};

    [[nodiscard]] constexpr bool valid() const noexcept {
        return ring < 2U && generation != 0U;
    }
};

struct OutputRoleSwap final {
    std::uint64_t sequence{0};
    std::uint32_t ring{0};
    std::uint64_t generation{0};
    OutputHandoffMode mode{OutputHandoffMode::Immediate};
    std::uint64_t expectedActiveGeneration{0};
    std::uint64_t transitionRevision{0};

    [[nodiscard]] constexpr bool valid() const noexcept {
        return sequence != 0U && ring < 2U && generation != 0U &&
            expectedActiveGeneration != 0U &&
            (mode == OutputHandoffMode::Immediate || transitionRevision != 0U);
    }
};

struct OutputDrainCancellation final {
    std::uint64_t sequence{0};
    std::uint64_t expectedActiveGeneration{0};
    std::uint64_t stagedGeneration{0};
    std::uint64_t transitionRevision{0};

    [[nodiscard]] constexpr bool valid() const noexcept {
        return sequence != 0U && expectedActiveGeneration != 0U &&
            stagedGeneration != 0U && transitionRevision != 0U;
    }
};

/**
 * Lock-free two-ring ownership state shared by one producer and one audio callback.
 *
 * Immediate handoff preserves the original contract: begin() redirects the producer to the
 * inactive ring and commit() publishes a callback-boundary replacement. Drain-active handoff is a
 * separate contract. beginDrain() leaves the acknowledged active generation writable while the
 * same producer fills the inactive successor ring. commitDrain() freezes the outgoing generation;
 * the callback may then adopt the successor only after the active ring is empty. Both paths retain
 * immutable publication fields until the callback acknowledges the exact publication sequence.
 */
class OutputRingRoleCoordinator final {
public:
    explicit OutputRingRoleCoordinator(std::uint64_t initialGeneration) noexcept
        : mProducerGeneration(initialGeneration),
          mAcknowledgedActiveGeneration(initialGeneration) {}

    /** Original immediate-handoff begin contract. */
    [[nodiscard]] OutputHandoffBeginResult begin(std::uint64_t generation) noexcept {
        return beginInternal(
            mAcknowledgedActiveGeneration.load(std::memory_order_acquire),
            generation,
            OutputHandoffMode::Immediate,
            0U);
    }

    /**
     * Opens one dormant successor stage without redirecting or invalidating outgoing writes.
     * Repeated uncommitted calls may supersede the inactive stage in bounded space.
     */
    [[nodiscard]] OutputHandoffBeginResult beginDrain(
        std::uint64_t expectedActiveGeneration,
        std::uint64_t stagedGeneration,
        std::uint64_t transitionRevision) noexcept {
        if (automaticDrainFenced()) {
            return {OutputHandoffStatus::AutomaticDrainFenced, 0U};
        }
        if (transitionRevision == 0U) {
            return {OutputHandoffStatus::InvalidTransitionRevision, 0U};
        }
        return beginInternal(
            expectedActiveGeneration,
            stagedGeneration,
            OutputHandoffMode::DrainActive,
            transitionRevision);
    }

    /** Opens the inactive ring for one exact same-generation loop-policy replacement. */
    [[nodiscard]] OutputHandoffBeginResult beginLoopCutover(
        std::uint64_t expectedActiveGeneration,
        std::uint64_t loopPolicyRevision) noexcept {
        if (expectedActiveGeneration == 0U || loopPolicyRevision == 0U) {
            return {OutputHandoffStatus::InvalidTransitionRevision, 0U};
        }
        if (commitAwaitingAcknowledgement()) {
            return {OutputHandoffStatus::AwaitingAcknowledgement, 0U};
        }
        if (mStageOpen.load(std::memory_order_acquire)) {
            return {OutputHandoffStatus::AwaitingAcknowledgement, 0U};
        }
        if (expectedActiveGeneration !=
            mAcknowledgedActiveGeneration.load(std::memory_order_acquire)) {
            return {OutputHandoffStatus::WrongActiveGeneration, 0U};
        }
        const std::uint32_t active =
            mAcknowledgedActiveRing.load(std::memory_order_acquire);
        const std::uint32_t staging = active == 0U ? 1U : 0U;
        mStageRing.store(staging, std::memory_order_relaxed);
        mStageGeneration.store(expectedActiveGeneration, std::memory_order_relaxed);
        mStageExpectedActiveGeneration.store(expectedActiveGeneration, std::memory_order_relaxed);
        mStageTransitionRevision.store(loopPolicyRevision, std::memory_order_relaxed);
        mStageMode.store(OutputHandoffMode::LoopCutover, std::memory_order_relaxed);
        mLoopAttemptState.store(kLoopAttemptPreparing, std::memory_order_relaxed);
        // Active reserve remains immutable/readable while the sole producer fills the prepared A
        // stage through the explicit loop-staging route.
        mActiveWritesAllowed.store(true, std::memory_order_relaxed);
        mStageOpen.store(true, std::memory_order_release);
        return {OutputHandoffStatus::Accepted, staging};
    }

    /** Original immediate callback-boundary publication. */
    [[nodiscard]] OutputHandoffStatus commit(
        std::uint64_t generation,
        std::size_t stagedFrames) noexcept {
        return commitInternal(
            mStageExpectedActiveGeneration.load(std::memory_order_acquire),
            generation,
            0U,
            stagedFrames,
            OutputHandoffMode::Immediate);
    }

    /** Publishes a non-empty successor that may swap only after the outgoing ring drains. */
    [[nodiscard]] OutputHandoffStatus commitDrain(
        std::uint64_t expectedActiveGeneration,
        std::uint64_t stagedGeneration,
        std::uint64_t transitionRevision,
        std::size_t stagedFrames) noexcept {
        if (automaticDrainFenced()) return OutputHandoffStatus::AutomaticDrainFenced;
        if (transitionRevision == 0U) return OutputHandoffStatus::InvalidTransitionRevision;
        return commitInternal(
            expectedActiveGeneration,
            stagedGeneration,
            transitionRevision,
            stagedFrames,
            OutputHandoffMode::DrainActive);
    }

    [[nodiscard]] OutputHandoffStatus commitLoopCutover(
        std::uint64_t expectedActiveGeneration,
        std::uint64_t loopPolicyRevision,
        std::size_t stagedFrames) noexcept {
        if (loopPolicyRevision == 0U) return OutputHandoffStatus::InvalidTransitionRevision;
        const OutputHandoffStatus status = commitInternal(
            expectedActiveGeneration,
            expectedActiveGeneration,
            loopPolicyRevision,
            stagedFrames,
            OutputHandoffMode::LoopCutover);
        if (status != OutputHandoffStatus::Accepted) return status;

        std::uint8_t preparing = kLoopAttemptPreparing;
        if (mLoopAttemptState.compare_exchange_strong(
                preparing,
                kLoopAttemptPublished,
                std::memory_order_release,
                std::memory_order_acquire)) {
            return OutputHandoffStatus::Accepted;
        }

        // Cancellation may win while this producer is publishing. Synchronously retract that
        // publication; an uncommitted continuation may never resurrect later.
        const std::uint64_t sequence = mPublishedSequence.load(std::memory_order_acquire);
        std::uint8_t pending = kPublicationPending;
        if (claimPublicationDisposition(sequence,
                pending,
                kPublicationClaimedForCancel,
                std::memory_order_acq_rel,
                std::memory_order_acquire)) {
                mActiveWritesAllowed.store(true, std::memory_order_relaxed);
            storePublicationDisposition(kPublicationIdle, std::memory_order_relaxed);
            mAcknowledgedSequence.store(sequence, std::memory_order_release);
        }
        mLoopAttemptState.store(kLoopAttemptIdle, std::memory_order_release);
        return OutputHandoffStatus::NoOpenStage;
    }

    /** Producer cancellation for an exact superseded loop stage/publication. */
    [[nodiscard]] OutputDrainCancelStatus cancelLoopCutover(
        std::uint64_t expectedActiveGeneration,
        std::uint64_t loopPolicyRevision) noexcept {
        if (mStageOpen.load(std::memory_order_acquire)) {
            if (!stageMatches(
                    expectedActiveGeneration,
                    expectedActiveGeneration,
                    loopPolicyRevision,
                    OutputHandoffMode::LoopCutover)) {
                return OutputDrainCancelStatus::NoMatchingDrain;
            }
            mStageOpen.store(false, std::memory_order_release);
            mActiveWritesAllowed.store(true, std::memory_order_release);
                mLoopAttemptState.store(kLoopAttemptIdle, std::memory_order_release);
            return OutputDrainCancelStatus::AcceptedOpenStage;
        }
        const std::uint64_t sequence = mPublishedSequence.load(std::memory_order_acquire);
        if (sequence == mAcknowledgedSequence.load(std::memory_order_acquire) ||
            mPublishedMode.load(std::memory_order_relaxed) !=
                OutputHandoffMode::LoopCutover ||
            mPublishedExpectedActiveGeneration.load(std::memory_order_relaxed) !=
                expectedActiveGeneration ||
            mPublishedGeneration.load(std::memory_order_relaxed) !=
                expectedActiveGeneration ||
            mPublishedTransitionRevision.load(std::memory_order_relaxed) !=
                loopPolicyRevision) {
            return OutputDrainCancelStatus::NoMatchingDrain;
        }
        // Claiming the still-pending publication proves the callback has not acquired either the
        // staged ring or its swap. Loop replacement can therefore reclaim that ring synchronously
        // instead of waiting one callback quantum before preparing the newest A.
        std::uint8_t disposition = kPublicationPending;
        if (claimPublicationDisposition(sequence, disposition, kPublicationClaimedForCancel,
                std::memory_order_acq_rel, std::memory_order_acquire)) {
                mActiveWritesAllowed.store(true, std::memory_order_relaxed);
            storePublicationDisposition(kPublicationIdle, std::memory_order_relaxed);
            mAcknowledgedSequence.store(sequence, std::memory_order_release);
            mLoopAttemptState.store(kLoopAttemptIdle, std::memory_order_release);
            return OutputDrainCancelStatus::AcceptedOpenStage;
        }
        if (disposition == kPublicationCancelRequested ||
            disposition == kPublicationClaimedForCancel) {
            return OutputDrainCancelStatus::AlreadyRequested;
        }
        return OutputDrainCancelStatus::TooLate;
    }

    /** Publishes an already reset empty stage for unload/recovery. */
    [[nodiscard]] OutputHandoffStatus commitEmpty(std::uint64_t generation) noexcept {
        if (commitAwaitingAcknowledgement()) {
            return OutputHandoffStatus::AwaitingAcknowledgement;
        }
        if (!mStageOpen.load(std::memory_order_acquire)) {
            return OutputHandoffStatus::NoOpenStage;
        }
        if (mStageMode.load(std::memory_order_acquire) != OutputHandoffMode::Immediate) {
            return OutputHandoffStatus::WrongMode;
        }
        if (generation != mStageGeneration.load(std::memory_order_acquire)) {
            return OutputHandoffStatus::WrongGeneration;
        }
        mStageOpen.store(false, std::memory_order_release);
        mActiveWritesAllowed.store(false, std::memory_order_release);
        publish(
            mStageRing.load(std::memory_order_relaxed),
            generation,
            OutputHandoffMode::Immediate,
            mStageExpectedActiveGeneration.load(std::memory_order_relaxed),
            0U);
        return OutputHandoffStatus::Accepted;
    }

    /**
     * Cancels an exact drain stage. An uncommitted stage is immediately producer-owned again. A
     * published stage is cancellation-requested and becomes reusable only after callback ack.
     */
    [[nodiscard]] OutputDrainCancelStatus cancelDrain(
        std::uint64_t expectedActiveGeneration,
        std::uint64_t stagedGeneration,
        std::uint64_t transitionRevision) noexcept {
        if (transitionRevision == 0U) return OutputDrainCancelStatus::NoMatchingDrain;
        if (mStageOpen.load(std::memory_order_acquire)) {
            if (!stageMatches(
                    expectedActiveGeneration,
                    stagedGeneration,
                    transitionRevision,
                    OutputHandoffMode::DrainActive)) {
                return OutputDrainCancelStatus::NoMatchingDrain;
            }
            mStageOpen.store(false, std::memory_order_release);
            mActiveWritesAllowed.store(true, std::memory_order_release);
                return OutputDrainCancelStatus::AcceptedOpenStage;
        }

        const std::uint64_t sequence = mPublishedSequence.load(std::memory_order_acquire);
        if (sequence == mAcknowledgedSequence.load(std::memory_order_acquire) ||
            mPublishedMode.load(std::memory_order_relaxed) != OutputHandoffMode::DrainActive ||
            mPublishedExpectedActiveGeneration.load(std::memory_order_relaxed) !=
                expectedActiveGeneration ||
            mPublishedGeneration.load(std::memory_order_relaxed) != stagedGeneration ||
            mPublishedTransitionRevision.load(std::memory_order_relaxed) != transitionRevision) {
            return OutputDrainCancelStatus::NoMatchingDrain;
        }
        std::uint8_t disposition = kPublicationPending;
        if (claimPublicationDisposition(sequence, disposition, kPublicationCancelRequested,
                std::memory_order_acq_rel, std::memory_order_acquire)) {
            return OutputDrainCancelStatus::AcceptedAwaitingCallback;
        }
        if (disposition == kPublicationCancelRequested ||
            disposition == kPublicationClaimedForCancel) {
            return OutputDrainCancelStatus::AlreadyRequested;
        }
        return OutputDrainCancelStatus::TooLate;
    }

    /** Callback only. Claims an exact cancellation publication, if one is pending. */
    [[nodiscard]] OutputDrainCancellation callbackCancellation(
        std::uint64_t appliedSequence) noexcept {
        const std::uint64_t sequence = mPublishedSequence.load(std::memory_order_acquire);
        if (sequence == appliedSequence ||
            sequence == mAcknowledgedSequence.load(std::memory_order_acquire)) {
            return {};
        }
        std::uint8_t disposition = kPublicationCancelRequested;
        if (!claimPublicationDisposition(sequence,
                disposition,
                kPublicationClaimedForCancel,
                std::memory_order_acq_rel,
                std::memory_order_acquire)) {
            return {};
        }
        return {
            sequence,
            mPublishedExpectedActiveGeneration.load(std::memory_order_relaxed),
            mPublishedGeneration.load(std::memory_order_relaxed),
            mPublishedTransitionRevision.load(std::memory_order_relaxed),
        };
    }

    /**
     * Callback only. Immediate publications are eligible at callback start. Drain-active
     * publications are eligible only when the callback has proved the active ring empty.
     */
    [[nodiscard]] OutputRoleSwap callbackSwap(
        std::uint64_t appliedSequence,
        bool activeDrained = false,
        bool callbackBoundary = true) noexcept {
        const std::uint64_t sequence = mPublishedSequence.load(std::memory_order_acquire);
        if (sequence == appliedSequence ||
            sequence == mAcknowledgedSequence.load(std::memory_order_acquire)) {
            return {};
        }
        const OutputHandoffMode mode = mPublishedMode.load(std::memory_order_relaxed);
        if (mode == OutputHandoffMode::LoopCutover) return {};
        if (mode == OutputHandoffMode::DrainActive && automaticDrainFenced()) return {};
        if (mode == OutputHandoffMode::DrainActive && !activeDrained) return {};
        if (mode == OutputHandoffMode::Immediate && !callbackBoundary) return {};
#if defined(STEMWISE_OUTPUT_ROLE_TEST_HOOKS)
        role_test::beforeCallbackClaim();
#endif
        if (!claimPublication(sequence)) return {};
        // A manual selection may arrive between the first fence read and this exact publication
        // claim. Give the publication back to the existing cancellation path; never return a
        // stale successor role after the synchronous manual boundary has closed.
        if (mode == OutputHandoffMode::DrainActive && automaticDrainFenced()) {
            storePublicationDisposition(kPublicationCancelRequested, std::memory_order_release);
            return {};
        }
        return {
            sequence,
            mPublishedRing.load(std::memory_order_relaxed),
            mPublishedGeneration.load(std::memory_order_relaxed),
            mode,
            mPublishedExpectedActiveGeneration.load(std::memory_order_relaxed),
            mPublishedTransitionRevision.load(std::memory_order_relaxed),
        };
    }

    /** Callback-only observation of the immutable published loop role; does not claim it. */
    [[nodiscard]] OutputRoleSwap publishedLoopCutover(
        std::uint64_t appliedSequence) const noexcept {
        if (mLoopAttemptState.load(std::memory_order_acquire) !=
                kLoopAttemptPublished ||
            publicationDisposition(std::memory_order_acquire) !=
                kPublicationPending) {
            return {};
        }
        const std::uint64_t sequence = mPublishedSequence.load(std::memory_order_acquire);
        if (sequence == appliedSequence ||
            sequence == mAcknowledgedSequence.load(std::memory_order_acquire) ||
            mPublishedMode.load(std::memory_order_relaxed) !=
                OutputHandoffMode::LoopCutover) {
            return {};
        }
        return {
            sequence,
            mPublishedRing.load(std::memory_order_relaxed),
            mPublishedGeneration.load(std::memory_order_relaxed),
            OutputHandoffMode::LoopCutover,
            mPublishedExpectedActiveGeneration.load(std::memory_order_relaxed),
            mPublishedTransitionRevision.load(std::memory_order_relaxed),
        };
    }

    /** Callback-only exact same-owner loop swap after B, or immediate for Clear/dormant moves. */
    [[nodiscard]] OutputRoleSwap callbackLoopSwap(
        std::uint64_t appliedSequence,
        std::uint64_t expectedActiveGeneration,
        std::uint64_t loopPolicyRevision,
        bool cutoverAuthorized) noexcept {
        if (!cutoverAuthorized || expectedActiveGeneration == 0U ||
            loopPolicyRevision == 0U) return {};
        if (mLoopAttemptState.load(std::memory_order_acquire) !=
            kLoopAttemptPublished) return {};
        const std::uint64_t sequence = mPublishedSequence.load(std::memory_order_acquire);
        if (sequence == appliedSequence ||
            sequence == mAcknowledgedSequence.load(std::memory_order_acquire) ||
            mPublishedMode.load(std::memory_order_relaxed) !=
                OutputHandoffMode::LoopCutover ||
            mPublishedExpectedActiveGeneration.load(std::memory_order_relaxed) !=
                expectedActiveGeneration ||
            mPublishedGeneration.load(std::memory_order_relaxed) !=
                expectedActiveGeneration ||
            mPublishedTransitionRevision.load(std::memory_order_relaxed) !=
                loopPolicyRevision) {
            return {};
        }
#if defined(STEMWISE_OUTPUT_ROLE_TEST_HOOKS)
        role_test::beforeCallbackClaim();
#endif
        if (!claimPublication(sequence)) return {};
        return {
            sequence,
            mPublishedRing.load(std::memory_order_relaxed),
            expectedActiveGeneration,
            OutputHandoffMode::LoopCutover,
            expectedActiveGeneration,
            loopPolicyRevision,
        };
    }

    /** Callback only. Releases an unused claim after its immutable PCM scan missed B. */
    [[nodiscard]] bool releaseCallbackLoopSwap(const OutputRoleSwap &swap) noexcept {
        if (!swap.valid() || swap.mode != OutputHandoffMode::LoopCutover ||
            swap.sequence != mPublishedSequence.load(std::memory_order_acquire) ||
            swap.generation != mPublishedGeneration.load(std::memory_order_relaxed) ||
            swap.expectedActiveGeneration !=
                mPublishedExpectedActiveGeneration.load(std::memory_order_relaxed) ||
            swap.transitionRevision !=
                mPublishedTransitionRevision.load(std::memory_order_relaxed)) {
            return false;
        }
        std::uint8_t claimed = kPublicationClaimedForSwap;
        return claimPublicationDisposition(swap.sequence,
            claimed,
            kPublicationPending,
            std::memory_order_release,
            std::memory_order_acquire);
    }

    /**
     * Callback linearization point immediately before changing its physical ring/generation.
     * Manual admission may atomically turn an automatic drain claim into cancellation until this
     * succeeds. Once it succeeds the swap is logically earlier than any later manual fence.
     */
    [[nodiscard]] bool commitCallbackSwap(const OutputRoleSwap &swap) noexcept {
        if (!swap.valid() || swap.sequence !=
            mPublishedSequence.load(std::memory_order_acquire)) {
            return false;
        }
        std::uint8_t disposition = kPublicationClaimedForSwap;
        return claimPublicationDisposition(swap.sequence,
            disposition,
            kPublicationSwapCommitted,
            std::memory_order_acq_rel,
            std::memory_order_acquire);
    }

    /** Callback only; release proves the previous active ring can be reset by the producer. */
    void acknowledge(const OutputRoleSwap &swap) noexcept {
        if (!swap.valid() ||
            publicationDisposition(std::memory_order_acquire) !=
                kPublicationSwapCommitted ||
            swap.sequence != mPublishedSequence.load(std::memory_order_acquire)) {
            return;
        }
        // The callback can switch rings before the render worker observes the new physical loop
        // revision. Close old-pipeline writes BEFORE exposing the new ring. The matching acquire
        // in ringForGeneration makes a reader of that ring also observe this closed write gate.
        if (swap.mode == OutputHandoffMode::LoopCutover) {
            mActiveWritesAllowed.store(false, std::memory_order_relaxed);
        }
        {
            auto publication = mActiveTargetPublication.publish();
            mAcknowledgedActiveRing.store(swap.ring, std::memory_order_release);
            mAcknowledgedActiveGeneration.store(swap.generation, std::memory_order_release);
        }
        if (swap.mode != OutputHandoffMode::LoopCutover) {
            mActiveWritesAllowed.store(true, std::memory_order_relaxed);
        }
        // Clear the claim before publishing acknowledgement. A producer may publish the next
        // stage as soon as it observes the acknowledgement; doing this in the opposite order lets
        // this callback's stale idle store overwrite that newer pending publication.
        storePublicationDisposition(kPublicationIdle, std::memory_order_relaxed);
        if (swap.mode == OutputHandoffMode::LoopCutover) {
            mLoopAttemptState.store(kLoopAttemptIdle, std::memory_order_relaxed);
        }
        mAcknowledgedSequence.store(swap.sequence, std::memory_order_release);
    }

    /** Producer-only release after it has adopted the callback-installed continuation pipeline. */
    [[nodiscard]] bool releaseLoopCutoverWrites(
        std::uint64_t generation,
        std::uint64_t loopPolicyRevision) noexcept {
        if (generation == 0U || loopPolicyRevision == 0U ||
            mPublishedSequence.load(std::memory_order_acquire) !=
                mAcknowledgedSequence.load(std::memory_order_acquire) ||
            mPublishedMode.load(std::memory_order_relaxed) !=
                OutputHandoffMode::LoopCutover ||
            mPublishedGeneration.load(std::memory_order_relaxed) != generation ||
            mPublishedTransitionRevision.load(std::memory_order_relaxed) !=
                loopPolicyRevision ||
            mAcknowledgedActiveGeneration.load(std::memory_order_relaxed) != generation) {
            return false;
        }
        mActiveWritesAllowed.store(true, std::memory_order_release);
        return true;
    }

    /** Callback only; cancellation keeps the outgoing ring/generation active. */
    void acknowledgeCancellation(const OutputDrainCancellation &cancellation) noexcept {
        if (!cancellation.valid() ||
            publicationDisposition(std::memory_order_acquire) !=
                kPublicationClaimedForCancel ||
            cancellation.sequence != mPublishedSequence.load(std::memory_order_acquire)) {
            return;
        }
        mActiveWritesAllowed.store(true, std::memory_order_relaxed);
        storePublicationDisposition(kPublicationIdle, std::memory_order_relaxed);
        mAcknowledgedSequence.store(cancellation.sequence, std::memory_order_release);
    }

    /**
     * Active identity is callback-published as one tuple. Only the sole render producer mutates
     * stage identity, after the preceding acknowledgement; it cannot recycle that stage during
     * its own routing/enqueue operation. Other callers may inspect targets, never mutate a ring.
     */
    [[nodiscard]] OutputRoleTarget ringForGeneration(std::uint64_t generation) const noexcept {
        if (generation == 0U) return {};
        const auto active = mActiveTargetPublication.read([&](std::uint64_t) noexcept {
            return OutputRoleTarget{
                mAcknowledgedActiveRing.load(std::memory_order_acquire),
                mAcknowledgedActiveGeneration.load(std::memory_order_acquire)};
        });
        if (!active.valid()) return {};
        if (generation == active.generation) return active;
        const std::uint64_t stagedGeneration = mStageGeneration.load(std::memory_order_acquire);
        if (generation == stagedGeneration &&
            (mStageOpen.load(std::memory_order_acquire) || commitAwaitingAcknowledgement())) {
            return {mStageRing.load(std::memory_order_relaxed), stagedGeneration};
        }
        return {};
    }

    /** Explicit same-generation staging route; normal generation lookup remains unambiguous. */
    [[nodiscard]] OutputRoleTarget loopStagingTarget(
        std::uint64_t generation,
        std::uint64_t loopPolicyRevision) const noexcept {
        if (generation == 0U || loopPolicyRevision == 0U ||
            mStageMode.load(std::memory_order_acquire) != OutputHandoffMode::LoopCutover ||
            mStageGeneration.load(std::memory_order_relaxed) != generation ||
            mStageTransitionRevision.load(std::memory_order_relaxed) != loopPolicyRevision ||
            (!mStageOpen.load(std::memory_order_acquire) &&
             !commitAwaitingAcknowledgement())) {
            return {};
        }
        return {mStageRing.load(std::memory_order_relaxed), generation};
    }

    [[nodiscard]] OutputRoleTarget loopStagingTarget(
        std::uint64_t generation) const noexcept {
        if (generation == 0U ||
            mStageMode.load(std::memory_order_acquire) != OutputHandoffMode::LoopCutover ||
            mStageGeneration.load(std::memory_order_relaxed) != generation ||
            (!mStageOpen.load(std::memory_order_acquire) &&
             !commitAwaitingAcknowledgement())) {
            return {};
        }
        return {mStageRing.load(std::memory_order_relaxed), generation};
    }

    /** Producer-only exact routing; committed drain freezes outgoing writes before callback swap. */
    [[nodiscard]] OutputRoleTarget writableRingForGeneration(
        std::uint64_t generation) const noexcept {
        const OutputRoleTarget target = ringForGeneration(generation);
        if (!target.valid()) return {};
        const std::uint64_t activeGeneration =
            mAcknowledgedActiveGeneration.load(std::memory_order_acquire);
        if (generation == activeGeneration &&
            !mActiveWritesAllowed.load(std::memory_order_acquire)) {
            return {};
        }
        return target;
    }

    /**
     * Control thread only, after the callback can no longer run (the stream is closed or has never
     * been started) and the producer is stopped/joined or is the sole calling producer thread.
     */
    [[nodiscard]] std::uint64_t forceResetAfterCallbackStopped(
        std::uint64_t generation,
        std::uint32_t activeRing = 0U) noexcept {
        if (generation <= mProducerGeneration.load(std::memory_order_acquire) ||
            activeRing >= 2U) {
            return 0U;
        }
        const std::uint64_t previousSequence =
            mPublishedSequence.load(std::memory_order_relaxed);
        if (previousSequence == std::numeric_limits<std::uint64_t>::max()) return 0U;
        const std::uint64_t sequence = previousSequence + 1U;
        mStageOpen.store(false, std::memory_order_relaxed);
        mLoopAttemptState.store(kLoopAttemptIdle, std::memory_order_relaxed);
        mActiveWritesAllowed.store(true, std::memory_order_relaxed);
        mProducerGeneration.store(generation, std::memory_order_relaxed);
        mStageRing.store(activeRing == 0U ? 1U : 0U, std::memory_order_relaxed);
        mStageGeneration.store(0U, std::memory_order_relaxed);
        mPublishedRing.store(activeRing, std::memory_order_relaxed);
        mPublishedGeneration.store(generation, std::memory_order_relaxed);
        mPublishedMode.store(OutputHandoffMode::Immediate, std::memory_order_relaxed);
        mPublishedExpectedActiveGeneration.store(generation, std::memory_order_relaxed);
        mPublishedTransitionRevision.store(0U, std::memory_order_relaxed);
        storePublicationDisposition(kPublicationIdle, std::memory_order_relaxed);
        mPublishedSequence.store(sequence, std::memory_order_release);
        {
            auto publication = mActiveTargetPublication.publish();
            mAcknowledgedActiveRing.store(activeRing, std::memory_order_release);
            mAcknowledgedActiveGeneration.store(generation, std::memory_order_release);
        }
        mAcknowledgedSequence.store(sequence, std::memory_order_release);
        mAutomaticDrainFenced.store(false, std::memory_order_release);
        mExplicitLoopDrainFenced.store(false, std::memory_order_release);
        return sequence;
    }

    [[nodiscard]] bool commitAwaitingAcknowledgement() const noexcept {
        return mPublishedSequence.load(std::memory_order_acquire) !=
            mAcknowledgedSequence.load(std::memory_order_acquire);
    }

    /** Monotonic high-water generation, including a dormant/cancelled stage. */
    [[nodiscard]] std::uint64_t producerGeneration() const noexcept {
        return mProducerGeneration.load(std::memory_order_acquire);
    }

    [[nodiscard]] std::uint32_t activeRing() const noexcept {
        return mAcknowledgedActiveRing.load(std::memory_order_acquire);
    }

    [[nodiscard]] std::uint64_t activeGeneration() const noexcept {
        return mAcknowledgedActiveGeneration.load(std::memory_order_acquire);
    }

    /** Synchronous manual-selection fence; it never creates a second exact drain canceller. */
    void armAutomaticDrainFence() noexcept {
        mAutomaticDrainFenced.store(true, std::memory_order_seq_cst);
        requestAutomaticDrainCancellation();
    }

    /** Explicit A/B demand must win the same callback race as a manual selection. */
    [[nodiscard]] bool setExplicitLoopDrainFence(bool enabled) noexcept {
        const bool previous = mExplicitLoopDrainFenced.exchange(
            enabled,
            std::memory_order_seq_cst);
        if (enabled) requestAutomaticDrainCancellation();
        return previous;
    }

private:
    [[nodiscard]] bool claimPublication(std::uint64_t sequence) noexcept {
        std::uint8_t disposition = kPublicationPending;
        return claimPublicationDisposition(sequence, disposition, kPublicationClaimedForSwap,
            std::memory_order_acq_rel, std::memory_order_acquire);
    }

    [[nodiscard]] std::uint8_t publicationDisposition(std::memory_order order) const noexcept {
        return static_cast<std::uint8_t>(mPublishedDisposition.load(order) & 7U);
    }

    void storePublicationDisposition(std::uint8_t disposition, std::memory_order order) noexcept {
        mPublishedDisposition.store((mPublishedSequence.load(std::memory_order_relaxed) << 3U) | disposition, order);
    }

    [[nodiscard]] bool claimPublicationDisposition(std::uint64_t sequence,
        std::uint8_t &expected, std::uint8_t desired,
        std::memory_order success, std::memory_order failure) noexcept {
        std::uint64_t word = (sequence << 3U) | expected;
        const bool claimed = mPublishedDisposition.compare_exchange_strong(word,
            (sequence << 3U) | desired, success, failure);
        if (!claimed) expected = static_cast<std::uint8_t>(word & 7U);
        return claimed;
    }

    void requestAutomaticDrainCancellation() noexcept {
        const std::uint64_t sequence = mPublishedSequence.load(std::memory_order_acquire);
        if (mPublishedMode.load(std::memory_order_acquire) !=
            OutputHandoffMode::DrainActive) return;
#if defined(STEMWISE_OUTPUT_ROLE_TEST_HOOKS)
        role_test::beforeCancellationClaim();
#endif
        // Pin identity before exposing cancellation: a stale canceller cannot cancel a replacement.
        std::uint8_t disposition = kPublicationPending;
        if (claimPublicationDisposition(sequence, disposition, kPublicationCancelRequested,
                std::memory_order_seq_cst, std::memory_order_acquire)) return;
        disposition = kPublicationClaimedForSwap;
        static_cast<void>(claimPublicationDisposition(sequence, disposition, kPublicationCancelRequested,
            std::memory_order_seq_cst, std::memory_order_acquire));
    }

public:

    /**
     * The caller must first prove that the manual render gate accepted this exact generation.
     */
    [[nodiscard]] bool clearAutomaticDrainFenceForAuthorizedGeneration(
        std::uint64_t generation) noexcept {
        if (generation == 0U) return false;
        bool expected = true;
        return mAutomaticDrainFenced.compare_exchange_strong(
            expected,
            false,
            std::memory_order_seq_cst,
            std::memory_order_acquire);
    }

    [[nodiscard]] bool automaticDrainFenced() const noexcept {
        return mAutomaticDrainFenced.load(std::memory_order_seq_cst) ||
            mExplicitLoopDrainFenced.load(std::memory_order_seq_cst);
    }

    /** Callback is stopped and route recovery has established a fresh ownership boundary. */
    void resetAutomaticDrainFenceAfterCallbackStopped() noexcept {
        mAutomaticDrainFenced.store(false, std::memory_order_release);
        mExplicitLoopDrainFenced.store(false, std::memory_order_release);
    }

private:
    static constexpr std::uint8_t kPublicationIdle = 0U;
    static constexpr std::uint8_t kPublicationPending = 1U;
    static constexpr std::uint8_t kPublicationCancelRequested = 2U;
    static constexpr std::uint8_t kPublicationClaimedForSwap = 3U;
    static constexpr std::uint8_t kPublicationClaimedForCancel = 4U;
    static constexpr std::uint8_t kPublicationSwapCommitted = 5U;
    static constexpr std::uint8_t kLoopAttemptIdle = 0U;
    static constexpr std::uint8_t kLoopAttemptPreparing = 1U;
    static constexpr std::uint8_t kLoopAttemptPublished = 2U;

    [[nodiscard]] OutputHandoffBeginResult beginInternal(
        std::uint64_t expectedActiveGeneration,
        std::uint64_t generation,
        OutputHandoffMode mode,
        std::uint64_t transitionRevision) noexcept {
        if (generation <= mProducerGeneration.load(std::memory_order_acquire)) {
            return {OutputHandoffStatus::StaleGeneration, 0U};
        }
        if (commitAwaitingAcknowledgement()) {
            return {OutputHandoffStatus::AwaitingAcknowledgement, 0U};
        }
        if (expectedActiveGeneration !=
            mAcknowledgedActiveGeneration.load(std::memory_order_acquire)) {
            return {OutputHandoffStatus::WrongActiveGeneration, 0U};
        }
        const std::uint32_t active =
            mAcknowledgedActiveRing.load(std::memory_order_acquire);
        const std::uint32_t staging = active == 0U ? 1U : 0U;
        mStageRing.store(staging, std::memory_order_relaxed);
        mStageGeneration.store(generation, std::memory_order_relaxed);
        mStageExpectedActiveGeneration.store(expectedActiveGeneration, std::memory_order_relaxed);
        mStageTransitionRevision.store(transitionRevision, std::memory_order_relaxed);
        mStageMode.store(mode, std::memory_order_relaxed);
        mProducerGeneration.store(generation, std::memory_order_release);
        mActiveWritesAllowed.store(mode == OutputHandoffMode::DrainActive,
                                   std::memory_order_release);
        mStageOpen.store(true, std::memory_order_release);
        return {OutputHandoffStatus::Accepted, staging};
    }

    [[nodiscard]] bool stageMatches(
        std::uint64_t expectedActiveGeneration,
        std::uint64_t stagedGeneration,
        std::uint64_t transitionRevision,
        OutputHandoffMode mode) const noexcept {
        return mStageMode.load(std::memory_order_acquire) == mode &&
            mStageExpectedActiveGeneration.load(std::memory_order_relaxed) ==
                expectedActiveGeneration &&
            mStageGeneration.load(std::memory_order_relaxed) == stagedGeneration &&
            mStageTransitionRevision.load(std::memory_order_relaxed) == transitionRevision;
    }

    [[nodiscard]] OutputHandoffStatus commitInternal(
        std::uint64_t expectedActiveGeneration,
        std::uint64_t stagedGeneration,
        std::uint64_t transitionRevision,
        std::size_t stagedFrames,
        OutputHandoffMode mode) noexcept {
        if (commitAwaitingAcknowledgement()) {
            return OutputHandoffStatus::AwaitingAcknowledgement;
        }
        if (!mStageOpen.load(std::memory_order_acquire)) {
            return OutputHandoffStatus::NoOpenStage;
        }
        if (mStageMode.load(std::memory_order_acquire) != mode) {
            return OutputHandoffStatus::WrongMode;
        }
        if (expectedActiveGeneration !=
            mAcknowledgedActiveGeneration.load(std::memory_order_acquire)) {
            return OutputHandoffStatus::WrongActiveGeneration;
        }
        if (!stageMatches(
                expectedActiveGeneration,
                stagedGeneration,
                transitionRevision,
                mode)) {
            return stagedGeneration != mStageGeneration.load(std::memory_order_acquire)
                ? OutputHandoffStatus::WrongGeneration
                : OutputHandoffStatus::InvalidTransitionRevision;
        }
        if (stagedFrames == 0U) return OutputHandoffStatus::EmptyStage;
        mStageOpen.store(false, std::memory_order_release);
        // A prepared loop role may be published far ahead of its exact tagged boundary. Keep the
        // outgoing producer live until the callback claims that boundary; otherwise its finite
        // reserve drains into silence before C can ever become reachable. Generation handoffs still
        // freeze outgoing writes so a short read remains a terminal drain.
        mActiveWritesAllowed.store(
            mode == OutputHandoffMode::LoopCutover,
            std::memory_order_release);
        publish(
            mStageRing.load(std::memory_order_relaxed),
            stagedGeneration,
            mode,
            expectedActiveGeneration,
            transitionRevision);
        return OutputHandoffStatus::Accepted;
    }

    void publish(
        std::uint32_t ring,
        std::uint64_t generation,
        OutputHandoffMode mode,
        std::uint64_t expectedActiveGeneration,
        std::uint64_t transitionRevision) noexcept {
        mPublishedRing.store(ring, std::memory_order_relaxed);
        mPublishedGeneration.store(generation, std::memory_order_relaxed);
        mPublishedMode.store(mode, std::memory_order_relaxed);
        mPublishedExpectedActiveGeneration.store(
            expectedActiveGeneration,
            std::memory_order_relaxed);
        mPublishedTransitionRevision.store(transitionRevision, std::memory_order_relaxed);
        mPublishedSequence.fetch_add(1U, std::memory_order_release);
        // Pending is the publication's final release: a successful claimant must see its sequence.
        storePublicationDisposition(kPublicationPending, std::memory_order_release);
    }

    std::atomic<std::uint64_t> mProducerGeneration;
    std::atomic<bool> mStageOpen{false};
    std::atomic<bool> mActiveWritesAllowed{true};
    std::atomic<std::uint32_t> mStageRing{1};
    std::atomic<std::uint64_t> mStageGeneration{0};
    std::atomic<std::uint64_t> mStageExpectedActiveGeneration{0};
    std::atomic<std::uint64_t> mStageTransitionRevision{0};
    std::atomic<OutputHandoffMode> mStageMode{OutputHandoffMode::Immediate};
    std::atomic<std::uint8_t> mLoopAttemptState{kLoopAttemptIdle};

    std::atomic<std::uint64_t> mPublishedSequence{0};
    std::atomic<std::uint32_t> mPublishedRing{0};
    std::atomic<std::uint64_t> mPublishedGeneration{0};
    std::atomic<OutputHandoffMode> mPublishedMode{OutputHandoffMode::Immediate};
    std::atomic<std::uint64_t> mPublishedExpectedActiveGeneration{0};
    std::atomic<std::uint64_t> mPublishedTransitionRevision{0};
    // Low three bits are disposition; the remaining bits are a lifetime publication ticket.
    std::atomic<std::uint64_t> mPublishedDisposition{kPublicationIdle};

    std::atomic<std::uint64_t> mAcknowledgedSequence{0};
    GuardedAtomicSnapshot mActiveTargetPublication{};
    std::atomic<std::uint32_t> mAcknowledgedActiveRing{0};
    std::atomic<std::uint64_t> mAcknowledgedActiveGeneration;
    std::atomic<bool> mAutomaticDrainFenced{false};
    std::atomic<bool> mExplicitLoopDrainFenced{false};
};

static_assert(std::atomic<std::uint64_t>::is_always_lock_free);
static_assert(std::atomic<std::uint32_t>::is_always_lock_free);
static_assert(std::atomic<std::uint8_t>::is_always_lock_free);
static_assert(std::atomic<bool>::is_always_lock_free);

}  // namespace stemwise::audio
