#pragma once
#include "GuardedAtomicSnapshot.h"
#include "NativeAudioOutputCounters.h"
#include "NativeAudioOutputLoopBoundaryGate.h"

namespace stemwise::audio {

/** Control and callback keep distinct single-writer publications; readers never wait. */
class NativeAudioOutputLoopDiagnostics final {
public:
    struct LoopDemandDiagnostics final {
        std::uint64_t acceptedDemandCount{0U};
        std::uint64_t acceptedCaptureCount{0U};
        std::uint64_t latestDemandRevision{0U};
        std::uint64_t latestDemandAFrame{0U};
        std::uint64_t latestDemandBFrameExclusive{0U};
        std::int64_t latestDemandPresentedFrame{-1};
        bool latestDemandBoundaryArmed{false};
        bool latestDemandBoundaryCrossed{false};
        std::uint64_t latestCaptureRevision{0U};
        std::uint64_t latestCaptureAFrame{0U};
        std::uint64_t latestCaptureBFrameExclusive{0U};
        std::int64_t latestCapturePresentedFrame{-1};
        std::uint64_t latestDemandGestureId{0U};
        std::uint64_t latestCaptureGestureId{0U};
        bool latestCaptureInferred{false};
    };

    void recordLoopDemandPublication(
        const NativeAudioOutputLoopPolicy &policy,
        std::int64_t presentedFrame,
        bool boundaryCrossedAtPublication,
        bool inferredCapture,
        std::uint64_t capturedBTimelineFrameExclusive) noexcept {
        beginGuardedPublication(mLoopDemandDiagnosticsGuard);
        saturatingIncrement(mLoopDemandAcceptedCount);
        mLoopLatestDemandRevision.store(policy.revision, std::memory_order_relaxed);
        mLoopLatestDemandAFrame.store(policy.aTimelineFrame, std::memory_order_relaxed);
        mLoopLatestDemandBFrameExclusive.store(
            policy.bTimelineFrameExclusive,
            std::memory_order_relaxed);
        mLoopLatestDemandPresentedFrame.store(presentedFrame, std::memory_order_relaxed);
        mLoopLatestDemandBoundaryArmed.store(
            policy.boundaryArmedAtPublication ? 1U : 0U,
            std::memory_order_relaxed);
        mLoopLatestDemandBoundaryCrossed.store(
            boundaryCrossedAtPublication ? 1U : 0U,
            std::memory_order_relaxed);
        mLoopLatestDemandGestureId.store(policy.heldBGestureId, std::memory_order_relaxed);
        if (boundaryCrossedAtPublication) {
            saturatingIncrement(mLoopCaptureAcceptedCount);
            mLoopLatestCaptureRevision.store(policy.revision, std::memory_order_relaxed);
            mLoopLatestCaptureAFrame.store(policy.aTimelineFrame, std::memory_order_relaxed);
            mLoopLatestCaptureBFrameExclusive.store(
                capturedBTimelineFrameExclusive,
                std::memory_order_relaxed);
            mLoopLatestCapturePresentedFrame.store(
                presentedFrame,
                std::memory_order_relaxed);
            mLoopLatestCaptureGestureId.store(
                policy.heldBGestureId,
                std::memory_order_relaxed);
            mLoopLatestCaptureInferred.store(
                inferredCapture ? 1U : 0U,
                std::memory_order_relaxed);
        }
        mLoopDemandDiagnosticsGuard.fetch_add(1U, std::memory_order_release);
    }

    [[nodiscard]] LoopDemandDiagnostics loopDemandDiagnostics() const noexcept {
        for (unsigned attempt = 0U; attempt < 8U; ++attempt) {
            const std::uint64_t before =
                mLoopDemandDiagnosticsGuard.load(std::memory_order_acquire);
            if ((before & 1U) != 0U) continue;
            const LoopDemandDiagnostics result{
                .acceptedDemandCount =
                    mLoopDemandAcceptedCount.load(std::memory_order_relaxed),
                .acceptedCaptureCount =
                    mLoopCaptureAcceptedCount.load(std::memory_order_relaxed),
                .latestDemandRevision =
                    mLoopLatestDemandRevision.load(std::memory_order_relaxed),
                .latestDemandAFrame =
                    mLoopLatestDemandAFrame.load(std::memory_order_relaxed),
                .latestDemandBFrameExclusive =
                    mLoopLatestDemandBFrameExclusive.load(std::memory_order_relaxed),
                .latestDemandPresentedFrame =
                    mLoopLatestDemandPresentedFrame.load(std::memory_order_relaxed),
                .latestDemandBoundaryArmed =
                    mLoopLatestDemandBoundaryArmed.load(std::memory_order_relaxed) != 0U,
                .latestDemandBoundaryCrossed =
                    mLoopLatestDemandBoundaryCrossed.load(std::memory_order_relaxed) != 0U,
                .latestCaptureRevision =
                    mLoopLatestCaptureRevision.load(std::memory_order_relaxed),
                .latestCaptureAFrame =
                    mLoopLatestCaptureAFrame.load(std::memory_order_relaxed),
                .latestCaptureBFrameExclusive =
                    mLoopLatestCaptureBFrameExclusive.load(std::memory_order_relaxed),
                .latestCapturePresentedFrame =
                    mLoopLatestCapturePresentedFrame.load(std::memory_order_relaxed),
                .latestDemandGestureId =
                    mLoopLatestDemandGestureId.load(std::memory_order_relaxed),
                .latestCaptureGestureId =
                    mLoopLatestCaptureGestureId.load(std::memory_order_relaxed),
                .latestCaptureInferred =
                    mLoopLatestCaptureInferred.load(std::memory_order_relaxed) != 0U,
            };
            std::atomic_thread_fence(std::memory_order_acquire);
            if (before == mLoopDemandDiagnosticsGuard.load(std::memory_order_acquire)) {
                return result;
            }
        }
        return {};
    }

    struct LoopCallbackDiagnostics final {
        std::uint64_t successfulCaptureCutoverCount{0U};
        std::uint64_t latestCutoverCaptureRevision{0U};
        std::uint64_t latestCutoverCallbackSequence{0U};
        std::uint64_t latestCutoverInputLatencyMicros{0U};
        std::uint64_t latestCutoverGeneration{0U};
        std::uint64_t latestCutoverSelectionToken{0U};
        std::uint64_t latestCutoverAFrame{0U};
        std::uint64_t latestCutoverGestureId{0U};
        std::uint64_t latestMissCaptureRevision{0U};
        std::uint64_t latestMissCallbackSequence{0U};
        std::int64_t latestMissPresentedFrame{-1};
    };

    void recordLoopCaptureCutover(
        const NativeAudioOutputLoopPolicy &captured,
        std::uint64_t callbackSequence,
        std::uint64_t callbackAtMicros) noexcept {
        beginGuardedPublication(mLoopCallbackDiagnosticsGuard);
        saturatingIncrement(mLoopCaptureCutoverCount);
        mLoopLatestCutoverCaptureRevision.store(
            captured.revision,
            std::memory_order_relaxed);
        mLoopLatestCutoverCallbackSequence.store(
            callbackSequence,
            std::memory_order_relaxed);
        mLoopLatestCutoverInputLatencyMicros.store(
            captured.inputEventAtMonotonicMicros != 0U &&
                    callbackAtMicros >= captured.inputEventAtMonotonicMicros
                ? callbackAtMicros - captured.inputEventAtMonotonicMicros
                : 0U,
            std::memory_order_relaxed);
        mLoopLatestCutoverGeneration.store(captured.generation, std::memory_order_relaxed);
        mLoopLatestCutoverSelectionToken.store(
            captured.selectionToken,
            std::memory_order_relaxed);
        mLoopLatestCutoverAFrame.store(captured.aTimelineFrame, std::memory_order_relaxed);
        mLoopLatestCutoverGestureId.store(
            captured.heldBGestureId,
            std::memory_order_relaxed);
        mLoopCallbackDiagnosticsGuard.fetch_add(1U, std::memory_order_release);
    }

    void recordLoopBoundaryMiss(
        const NativeAudioOutputLoopPolicy &captured,
        std::uint64_t callbackSequence,
        std::int64_t presentedFrame) noexcept {
        beginGuardedPublication(mLoopCallbackDiagnosticsGuard);
        mLoopLatestMissCaptureRevision.store(captured.revision, std::memory_order_relaxed);
        mLoopLatestMissCallbackSequence.store(callbackSequence, std::memory_order_relaxed);
        mLoopLatestMissPresentedFrame.store(presentedFrame, std::memory_order_relaxed);
        mLoopCallbackDiagnosticsGuard.fetch_add(1U, std::memory_order_release);
        saturatingIncrement(gOutputLoopBoundaryMissCount);
    }

    [[nodiscard]] bool tryLoopCallbackDiagnostics(
        LoopCallbackDiagnostics &result) const noexcept {
        const std::uint64_t before =
            mLoopCallbackDiagnosticsGuard.load(std::memory_order_acquire);
        if ((before & 1U) != 0U) return false;
        const LoopCallbackDiagnostics candidate{
                .successfulCaptureCutoverCount =
                    mLoopCaptureCutoverCount.load(std::memory_order_relaxed),
                .latestCutoverCaptureRevision =
                    mLoopLatestCutoverCaptureRevision.load(std::memory_order_relaxed),
                .latestCutoverCallbackSequence =
                    mLoopLatestCutoverCallbackSequence.load(std::memory_order_relaxed),
                .latestCutoverInputLatencyMicros =
                    mLoopLatestCutoverInputLatencyMicros.load(std::memory_order_relaxed),
                .latestCutoverGeneration =
                    mLoopLatestCutoverGeneration.load(std::memory_order_relaxed),
                .latestCutoverSelectionToken =
                    mLoopLatestCutoverSelectionToken.load(std::memory_order_relaxed),
                .latestCutoverAFrame =
                    mLoopLatestCutoverAFrame.load(std::memory_order_relaxed),
                .latestCutoverGestureId =
                    mLoopLatestCutoverGestureId.load(std::memory_order_relaxed),
                .latestMissCaptureRevision =
                    mLoopLatestMissCaptureRevision.load(std::memory_order_relaxed),
                .latestMissCallbackSequence =
                    mLoopLatestMissCallbackSequence.load(std::memory_order_relaxed),
                .latestMissPresentedFrame =
                    mLoopLatestMissPresentedFrame.load(std::memory_order_relaxed),
        };
        std::atomic_thread_fence(std::memory_order_acquire);
        if (before != mLoopCallbackDiagnosticsGuard.load(std::memory_order_acquire)) {
            return false;
        }
        result = candidate;
        return true;
    }

    [[nodiscard]] LoopCallbackDiagnostics loopCallbackDiagnostics() const noexcept {
        return boundedSnapshotRead<LoopCallbackDiagnostics>([this](auto &result) noexcept {
            return tryLoopCallbackDiagnostics(result);
        });
    }

private:
    /** Coherent loop-control telemetry; diagnostics reads it off the real-time callback. */
    std::atomic<std::uint64_t> mLoopDemandDiagnosticsGuard{0U};
    std::atomic<std::uint64_t> mLoopDemandAcceptedCount{0U};
    std::atomic<std::uint64_t> mLoopCaptureAcceptedCount{0U};
    std::atomic<std::uint64_t> mLoopLatestDemandRevision{0U};
    std::atomic<std::uint64_t> mLoopLatestDemandAFrame{0U};
    std::atomic<std::uint64_t> mLoopLatestDemandBFrameExclusive{0U};
    std::atomic<std::int64_t> mLoopLatestDemandPresentedFrame{-1};
    std::atomic<std::uint8_t> mLoopLatestDemandBoundaryArmed{0U};
    std::atomic<std::uint8_t> mLoopLatestDemandBoundaryCrossed{0U};
    std::atomic<std::uint64_t> mLoopLatestDemandGestureId{0U};
    std::atomic<std::uint64_t> mLoopLatestCaptureRevision{0U};
    std::atomic<std::uint64_t> mLoopLatestCaptureAFrame{0U};
    std::atomic<std::uint64_t> mLoopLatestCaptureBFrameExclusive{0U};
    std::atomic<std::int64_t> mLoopLatestCapturePresentedFrame{-1};
    std::atomic<std::uint64_t> mLoopLatestCaptureGestureId{0U};
    std::atomic<std::uint8_t> mLoopLatestCaptureInferred{0U};
    std::atomic<std::uint64_t> mLoopCallbackDiagnosticsGuard{0U};
    std::atomic<std::uint64_t> mLoopCaptureCutoverCount{0U};
    std::atomic<std::uint64_t> mLoopLatestCutoverCaptureRevision{0U};
    std::atomic<std::uint64_t> mLoopLatestCutoverCallbackSequence{0U};
    std::atomic<std::uint64_t> mLoopLatestCutoverInputLatencyMicros{0U};
    std::atomic<std::uint64_t> mLoopLatestCutoverGeneration{0U};
    std::atomic<std::uint64_t> mLoopLatestCutoverSelectionToken{0U};
    std::atomic<std::uint64_t> mLoopLatestCutoverAFrame{0U};
    std::atomic<std::uint64_t> mLoopLatestCutoverGestureId{0U};
    std::atomic<std::uint64_t> mLoopLatestMissCaptureRevision{0U};
    std::atomic<std::uint64_t> mLoopLatestMissCallbackSequence{0U};
    std::atomic<std::int64_t> mLoopLatestMissPresentedFrame{-1};
};

} // namespace stemwise::audio
