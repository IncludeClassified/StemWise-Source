#pragma once

#include "GuardedAtomicSnapshot.h"
#include "NativeAudioOutputBridge.h"

namespace stemwise::audio {

/** The callback publishes; the runtime republishes only after exact-owner admission. */
class StemGainCallbackPublication final {
public:
    void publish(const NativeAudioStemGainCallbackEvidence &evidence) noexcept {
        beginGuardedPublication(guard_);
        sequence_.store(evidence.sequence, std::memory_order_relaxed);
        generation_.store(evidence.generation, std::memory_order_relaxed);
        selectionToken_.store(evidence.selectionToken, std::memory_order_relaxed);
        publishedSequence_.store(evidence.publishedSequence, std::memory_order_relaxed);
        offeredSequence_.store(evidence.offeredSequence, std::memory_order_relaxed);
        activeMask_.store(evidence.activeMask, std::memory_order_relaxed);
        for (std::size_t lane = 0U; lane < evidence.gainBits.size(); ++lane) {
            gainBits_[lane].store(evidence.gainBits[lane], std::memory_order_relaxed);
        }
        callbackSequence_.store(evidence.callbackSequence, std::memory_order_relaxed);
        firstTaggedFrame_.store(evidence.firstTaggedFrame, std::memory_order_relaxed);
        lastTaggedFrame_.store(evidence.lastTaggedFrame, std::memory_order_relaxed);
        taggedFrameCount_.store(evidence.taggedFrameCount, std::memory_order_relaxed);
        nonZeroPcmFrameCount_.store(evidence.nonZeroPcmFrameCount, std::memory_order_relaxed);
        callbackObservedAtNs_.store(evidence.callbackObservedAtNs, std::memory_order_relaxed);
        guard_.fetch_add(1U, std::memory_order_release);
    }

    [[nodiscard]] bool read(NativeAudioStemGainCallbackEvidence &evidence) const noexcept {
        for (unsigned attempt = 0U; attempt < 8U; ++attempt) {
            const auto before = guard_.load(std::memory_order_acquire);
            if ((before & 1U) != 0U) continue;
            evidence.sequence = sequence_.load(std::memory_order_relaxed);
            evidence.generation = generation_.load(std::memory_order_relaxed);
            evidence.selectionToken = selectionToken_.load(std::memory_order_relaxed);
            evidence.publishedSequence = publishedSequence_.load(std::memory_order_relaxed);
            evidence.offeredSequence = offeredSequence_.load(std::memory_order_relaxed);
            evidence.activeMask = activeMask_.load(std::memory_order_relaxed);
            for (std::size_t lane = 0U; lane < evidence.gainBits.size(); ++lane) {
                evidence.gainBits[lane] = gainBits_[lane].load(std::memory_order_relaxed);
            }
            evidence.callbackSequence = callbackSequence_.load(std::memory_order_relaxed);
            evidence.firstTaggedFrame = firstTaggedFrame_.load(std::memory_order_relaxed);
            evidence.lastTaggedFrame = lastTaggedFrame_.load(std::memory_order_relaxed);
            evidence.taggedFrameCount = taggedFrameCount_.load(std::memory_order_relaxed);
            evidence.nonZeroPcmFrameCount = nonZeroPcmFrameCount_.load(std::memory_order_relaxed);
            evidence.callbackObservedAtNs = callbackObservedAtNs_.load(std::memory_order_relaxed);
            std::atomic_thread_fence(std::memory_order_acquire);
            if (guard_.load(std::memory_order_acquire) == before) return true;
        }
        return false;
    }

    [[nodiscard]] NativeAudioStemGainCallbackEvidence snapshot() const noexcept {
        NativeAudioStemGainCallbackEvidence evidence{};
        return read(evidence) ? evidence : NativeAudioStemGainCallbackEvidence{};
    }

private:
    std::atomic<std::uint64_t> guard_{0U};
    std::atomic<std::uint64_t> sequence_{0U};
    std::atomic<std::uint64_t> generation_{0U};
    std::atomic<std::uint64_t> selectionToken_{0U};
    std::atomic<std::uint64_t> publishedSequence_{0U};
    std::atomic<std::uint64_t> offeredSequence_{0U};
    std::atomic<std::uint32_t> activeMask_{0U};
    std::array<std::atomic<std::uint32_t>, kNativeAudioStemGainLaneCount> gainBits_{};
    std::atomic<std::uint64_t> callbackSequence_{0U};
    std::atomic<std::int64_t> firstTaggedFrame_{0};
    std::atomic<std::int64_t> lastTaggedFrame_{0};
    std::atomic<std::uint64_t> taggedFrameCount_{0U};
    std::atomic<std::uint64_t> nonZeroPcmFrameCount_{0U};
    std::atomic<std::uint64_t> callbackObservedAtNs_{0U};
};

} // namespace stemwise::audio
