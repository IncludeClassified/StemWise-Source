#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>

namespace stemwise::audio {

/**
 * Callback-owned, allocation-free constant-sum transition between output generations.
 *
 * The old endpoint is the last stereo sample actually handed to the device, not a producer-side
 * estimate. For every transition frame the old and new weights sum to exactly one. The final
 * frame is the untouched new-owner sample, so no residual old owner survives the bounded ramp.
 */
class OutputGenerationTransition final {
public:
    static constexpr std::uint32_t kMinimumFrames = 16;
    static constexpr std::uint32_t kMaximumFrames = 256;

    [[nodiscard]] static constexpr std::uint32_t framesForSampleRate(
        std::int32_t sampleRateHz) noexcept {
        const std::int32_t positiveRate = sampleRateHz > 0 ? sampleRateHz : 48'000;
        const std::int32_t twoMilliseconds = positiveRate / 500;
        return static_cast<std::uint32_t>(
            std::clamp(
                twoMilliseconds,
                static_cast<std::int32_t>(kMinimumFrames),
                static_cast<std::int32_t>(kMaximumFrames)));
    }

    void begin(float lastEmittedLeft, float lastEmittedRight, std::uint32_t frames) noexcept {
        mFromLeft = std::isfinite(lastEmittedLeft) ? lastEmittedLeft : 0.0f;
        mFromRight = std::isfinite(lastEmittedRight) ? lastEmittedRight : 0.0f;
        mTotalFrames = std::clamp(frames, kMinimumFrames, kMaximumFrames);
        mAppliedFrames = 0;
    }

    void cancel() noexcept {
        mTotalFrames = 0;
        mAppliedFrames = 0;
    }

    [[nodiscard]] bool active() const noexcept {
        return mAppliedFrames < mTotalFrames;
    }

    void advance(std::size_t frames) noexcept {
        mAppliedFrames += static_cast<std::uint32_t>(std::min<std::size_t>(frames, mTotalFrames - mAppliedFrames));
    }

    /** Applies the remaining ramp to a bounded prefix and returns the frames transformed. */
    std::size_t apply(float *interleavedStereo, std::size_t frameCount) noexcept {
        if (interleavedStereo == nullptr || frameCount == 0 || !active()) return 0;
        const std::uint32_t remaining = mTotalFrames - mAppliedFrames;
        const std::size_t transformed = std::min<std::size_t>(frameCount, remaining);
        const float denominator = static_cast<float>(mTotalFrames);
        for (std::size_t frame = 0; frame < transformed; ++frame) {
            const float newWeight = static_cast<float>(
                mAppliedFrames + static_cast<std::uint32_t>(frame) + 1U) / denominator;
            const float oldWeight = 1.0f - newWeight;
            const std::size_t sample = frame * 2U;
            interleavedStereo[sample] =
                mFromLeft * oldWeight + interleavedStereo[sample] * newWeight;
            interleavedStereo[sample + 1U] =
                mFromRight * oldWeight + interleavedStereo[sample + 1U] * newWeight;
        }
        mAppliedFrames += static_cast<std::uint32_t>(transformed);
        return transformed;
    }

private:
    float mFromLeft{0.0f};
    float mFromRight{0.0f};
    std::uint32_t mTotalFrames{0};
    std::uint32_t mAppliedFrames{0};
};

}  // namespace stemwise::audio
