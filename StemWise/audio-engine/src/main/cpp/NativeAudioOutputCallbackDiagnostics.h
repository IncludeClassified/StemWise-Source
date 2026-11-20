#pragma once
#include "GuardedAtomicSnapshot.h"
#include <atomic>
#include <cstdint>

namespace stemwise::audio {
/** Callback-owned cumulative counters and the last attempted owner/control tuple. */
struct NativeAudioCallbackMixDiagnostics final {
    std::uint64_t sequence{0U};
    std::uint64_t callbackSequence{0U};
    std::uint64_t generation{0U};
    std::uint64_t selectionToken{0U};
    std::uint64_t controlRevision{0U};
    std::uint64_t missingControlFrames{0U};
    std::uint64_t staleControlFrames{0U};
    std::uint64_t mixerRejectedFrames{0U};
    std::uint64_t invalidTagFrames{0U};
    std::uint64_t advanceFailures{0U};
    std::uint64_t safeOriginalFallbackFrames{0U};
    std::uint64_t mixerNonFiniteSamplesScrubbed{0U};
    std::uint64_t mixerLimiterActiveFrames{0U};
    std::uint64_t mixerLimiterSafetyClamps{0U};
    std::uint64_t finalNonFiniteSamplesScrubbed{0U};
    std::uint64_t finalSafetyClamps{0U};
};

/** One callback writer; bounded coherent reads never inspect a live mixer's non-atomic state. */
class NativeAudioCallbackMixPublication final {
public:
    void publish(const NativeAudioCallbackMixDiagnostics &value) noexcept {
        const auto publication = guard.publish();
        callbackSequence.store(value.callbackSequence, std::memory_order_release);
        generation.store(value.generation, std::memory_order_release);
        selectionToken.store(value.selectionToken, std::memory_order_release);
        controlRevision.store(value.controlRevision, std::memory_order_release);
        missingControlFrames.store(value.missingControlFrames, std::memory_order_release);
        staleControlFrames.store(value.staleControlFrames, std::memory_order_release);
        mixerRejectedFrames.store(value.mixerRejectedFrames, std::memory_order_release);
        invalidTagFrames.store(value.invalidTagFrames, std::memory_order_release);
        advanceFailures.store(value.advanceFailures, std::memory_order_release);
        safeOriginalFallbackFrames.store(value.safeOriginalFallbackFrames, std::memory_order_release);
        mixerNonFiniteSamplesScrubbed.store(value.mixerNonFiniteSamplesScrubbed, std::memory_order_release);
        mixerLimiterActiveFrames.store(value.mixerLimiterActiveFrames, std::memory_order_release);
        mixerLimiterSafetyClamps.store(value.mixerLimiterSafetyClamps, std::memory_order_release);
        finalNonFiniteSamplesScrubbed.store(value.finalNonFiniteSamplesScrubbed, std::memory_order_release);
        finalSafetyClamps.store(value.finalSafetyClamps, std::memory_order_release);
    }
    [[nodiscard]] NativeAudioCallbackMixDiagnostics read() const noexcept {
        return guard.read([this](std::uint64_t sequence) noexcept {
            return NativeAudioCallbackMixDiagnostics{.sequence = sequence,
                .callbackSequence = callbackSequence.load(std::memory_order_acquire),
                .generation = generation.load(std::memory_order_acquire),
                .selectionToken = selectionToken.load(std::memory_order_acquire),
                .controlRevision = controlRevision.load(std::memory_order_acquire),
                .missingControlFrames = missingControlFrames.load(std::memory_order_acquire),
                .staleControlFrames = staleControlFrames.load(std::memory_order_acquire),
                .mixerRejectedFrames = mixerRejectedFrames.load(std::memory_order_acquire),
                .invalidTagFrames = invalidTagFrames.load(std::memory_order_acquire),
                .advanceFailures = advanceFailures.load(std::memory_order_acquire),
                .safeOriginalFallbackFrames = safeOriginalFallbackFrames.load(std::memory_order_acquire),
                .mixerNonFiniteSamplesScrubbed = mixerNonFiniteSamplesScrubbed.load(std::memory_order_acquire),
                .mixerLimiterActiveFrames = mixerLimiterActiveFrames.load(std::memory_order_acquire),
                .mixerLimiterSafetyClamps = mixerLimiterSafetyClamps.load(std::memory_order_acquire),
                .finalNonFiniteSamplesScrubbed = finalNonFiniteSamplesScrubbed.load(std::memory_order_acquire),
                .finalSafetyClamps = finalSafetyClamps.load(std::memory_order_acquire),
            };
        });
    }
private:
    GuardedAtomicSnapshot guard;
    std::atomic<std::uint64_t> callbackSequence{0U};
    std::atomic<std::uint64_t> generation{0U};
    std::atomic<std::uint64_t> selectionToken{0U};
    std::atomic<std::uint64_t> controlRevision{0U};
    std::atomic<std::uint64_t> missingControlFrames{0U};
    std::atomic<std::uint64_t> staleControlFrames{0U};
    std::atomic<std::uint64_t> mixerRejectedFrames{0U};
    std::atomic<std::uint64_t> invalidTagFrames{0U};
    std::atomic<std::uint64_t> advanceFailures{0U};
    std::atomic<std::uint64_t> safeOriginalFallbackFrames{0U};
    std::atomic<std::uint64_t> mixerNonFiniteSamplesScrubbed{0U};
    std::atomic<std::uint64_t> mixerLimiterActiveFrames{0U};
    std::atomic<std::uint64_t> mixerLimiterSafetyClamps{0U};
    std::atomic<std::uint64_t> finalNonFiniteSamplesScrubbed{0U};
    std::atomic<std::uint64_t> finalSafetyClamps{0U};
};
} // namespace stemwise::audio
