#pragma once

#include <array>
#include <cstddef>
#include <cstdint>

namespace stemwise::audio {

/** Exact callback-local ranges transformed by OutputGenerationTransition. */
class OutputTransitionCoverage final {
public:
    static constexpr std::size_t kMaximumRanges = 4U;

    [[nodiscard]] bool record(std::size_t outputOffset, std::size_t frameCount) noexcept;
    [[nodiscard]] bool overlaps(std::size_t outputOffset, std::size_t frameCount) const noexcept;
    [[nodiscard]] std::size_t rangeCount() const noexcept { return mRangeCount; }

private:
    struct Range final {
        std::size_t outputOffset{0U};
        std::size_t frameCount{0U};
    };

    std::array<Range, kMaximumRanges> mRanges{};
    std::size_t mRangeCount{0U};
};

struct OutputOwnedFrameSpan final {
    std::size_t outputOffset{0U};
    std::size_t frameCount{0U};
};

/** Whole-callback evidence gate: one touched owned span disqualifies every owner in the callback. */
[[nodiscard]] bool transitionTouchesAnyOwnedSpan(
    const OutputTransitionCoverage &coverage,
    const OutputOwnedFrameSpan *spans,
    std::size_t spanCount) noexcept;

struct NativeAudioOutputFinalDeviceStageDiagnostics final {
    std::uint64_t processedFrames{0U};
    std::uint64_t nonFiniteSamplesScrubbed{0U};
    std::uint64_t safetyClamps{0U};
};

/**
 * Last callback-owned PCM boundary before evidence and the device.
 *
 * The sole musical soft-knee limiter lives in LateStemMixer. Constant-sum transitions preserve
 * its ceiling; this last boundary only scrubs non-finite values and guards the exact device ceiling.
 */
class NativeAudioOutputFinalDeviceStage final {
public:
    static constexpr float kDeviceCeiling = 0.98F;

    /** Stateless finite-value and exact ceiling guard; no additional musical dynamics. */
    void finalize(float *interleavedStereo, std::size_t frameCount) noexcept;

    [[nodiscard]] const NativeAudioOutputFinalDeviceStageDiagnostics &diagnostics() const noexcept;

private:
    void scrub(float &sample) noexcept;
    void clamp(float &sample) noexcept;

    NativeAudioOutputFinalDeviceStageDiagnostics mDiagnostics{};
};

}  // namespace stemwise::audio
