#pragma once

#include "../../StemwiseMultibus.h"

#include <cstddef>
#include <cstdint>
#include <vector>

namespace stemwise::audio::engine {

enum class TimePitchTransitionStatus : std::uint8_t {
    Ok,
    InvalidBuffer,
    BlockTooLarge,
    OutputCapacityExceeded,
};

struct TimePitchTransitionResult final {
    TimePitchTransitionStatus status{TimePitchTransitionStatus::Ok};
    std::size_t framesMixed{0};
    bool transitionComplete{false};
};

struct TimePitchTransitionDiagnostics final {
    std::size_t crossfadeFrames{0};
    std::size_t crossfadeCursorFrames{0};
    bool active{false};
    std::uint64_t beginCount{0};
    std::uint64_t completedCount{0};
    std::uint64_t mixedFrames{0};
    std::uint64_t nonFiniteInputSampleCount{0};
    std::uint64_t safetyLimitedOutputSampleCount{0};
    float firstOutputDirectError{0.0F};
    float maximumAdjacentOutputStep{0.0F};
};

/**
 * Allocation-free constant-sum, raised-cosine boundary from a caller-aligned direct stream to
 * primed DSP. The endpoint-smooth curve reduces the audible slope change at activation without
 * extending the transition.
 *
 * begin() makes the first mixed sample exactly direct (weights 1/0) and the final ramp sample
 * exactly processed (weights 0/1). The ramp may span render blocks. Once complete, direct input
 * may be null and processed samples are copied through. Construction is the only throwing setup.
 */
class TimePitchTransition final {
public:
    static constexpr std::size_t kDefaultChannelCount = 2U;
    static constexpr std::size_t kMaximumChannelCount = kStemwiseMusicChannelCount;

    TimePitchTransition(
        std::size_t maximumBlockFrames,
        std::size_t crossfadeFrames,
        std::size_t channelCount = kDefaultChannelCount);

    void begin() noexcept;
    void reverse() noexcept;
    void reset() noexcept;

    [[nodiscard]] TimePitchTransitionResult mix(
        const float *alignedDirect,
        const float *primedProcessed,
        float *output,
        std::size_t outputCapacityFrames,
        std::size_t requestedFrames) noexcept;

    [[nodiscard]] TimePitchTransitionDiagnostics diagnostics() const noexcept;

private:
    std::size_t mMaximumBlockFrames;
    std::size_t mCrossfadeFrames;
    std::size_t mChannelCount;
    std::size_t mCursorFrames{0};
    bool mActive{false};
    bool mHasPreviousOutput{false};
    std::vector<float> mPreviousOutput;
    std::uint64_t mBeginCount{0};
    std::uint64_t mCompletedCount{0};
    std::uint64_t mMixedFrames{0};
    std::uint64_t mNonFiniteInputSampleCount{0};
    std::uint64_t mSafetyLimitedOutputSampleCount{0};
    float mFirstOutputDirectError{0.0F};
    float mMaximumAdjacentOutputStep{0.0F};
};

}  // namespace stemwise::audio::engine
