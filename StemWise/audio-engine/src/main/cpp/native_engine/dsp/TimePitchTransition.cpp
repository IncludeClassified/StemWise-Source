#include "TimePitchTransition.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <stdexcept>

namespace stemwise::audio::engine {
namespace {

constexpr std::size_t kMaximumConfiguredFrames = 1U << 20U;
constexpr double kPi = 3.14159265358979323846;

void saturatingIncrement(std::uint64_t &counter) noexcept {
    if (counter != std::numeric_limits<std::uint64_t>::max()) ++counter;
}

void saturatingAdd(std::uint64_t &counter, std::size_t amount) noexcept {
    const auto maximum = std::numeric_limits<std::uint64_t>::max();
    const auto wideAmount = static_cast<std::uint64_t>(amount);
    counter = wideAmount > maximum - counter ? maximum : counter + wideAmount;
}

float finiteOrZero(float sample, std::uint64_t &counter) noexcept {
    if (std::isfinite(sample)) return sample;
    saturatingIncrement(counter);
    return 0.0F;
}

float raisedCosineProgress(std::size_t cursorFrames, std::size_t crossfadeFrames) noexcept {
    if (cursorFrames == 0) return 0.0F;
    if (cursorFrames + 1U >= crossfadeFrames) return 1.0F;
    const double linearProgress = static_cast<double>(cursorFrames) /
        static_cast<double>(crossfadeFrames - 1U);
    return static_cast<float>(0.5 - 0.5 * std::cos(kPi * linearProgress));
}

}  // namespace

TimePitchTransition::TimePitchTransition(
    std::size_t maximumBlockFrames,
    std::size_t crossfadeFrames,
    std::size_t channelCount)
    : mMaximumBlockFrames(maximumBlockFrames),
      mCrossfadeFrames(crossfadeFrames),
      mChannelCount(channelCount),
      mPreviousOutput(channelCount, 0.0F) {
    if (maximumBlockFrames == 0 || maximumBlockFrames > kMaximumConfiguredFrames) {
        throw std::invalid_argument("TimePitchTransition block quantum is invalid");
    }
    if (crossfadeFrames < 2 || crossfadeFrames > kMaximumConfiguredFrames) {
        throw std::invalid_argument("TimePitchTransition crossfade is invalid");
    }
    if (channelCount == 0 || channelCount > kMaximumChannelCount) {
        throw std::invalid_argument("TimePitchTransition channel count is invalid");
    }
}

void TimePitchTransition::begin() noexcept {
    mCursorFrames = 0;
    mActive = true;
    mHasPreviousOutput = false;
    mFirstOutputDirectError = 0.0F;
    mMaximumAdjacentOutputStep = 0.0F;
    saturatingIncrement(mBeginCount);
}

void TimePitchTransition::reverse() noexcept {
    // The caller swaps the two streams. Mirror the curve to preserve their current weights.
    if (mActive) mCursorFrames = mCrossfadeFrames - 1U - std::min(mCursorFrames, mCrossfadeFrames - 1U);
}

void TimePitchTransition::reset() noexcept {
    mCursorFrames = 0;
    mActive = false;
    mHasPreviousOutput = false;
    std::fill(mPreviousOutput.begin(), mPreviousOutput.end(), 0.0F);
    mFirstOutputDirectError = 0.0F;
    mMaximumAdjacentOutputStep = 0.0F;
}

TimePitchTransitionResult TimePitchTransition::mix(
    const float *alignedDirect,
    const float *primedProcessed,
    float *output,
    std::size_t outputCapacityFrames,
    std::size_t requestedFrames) noexcept {
    if (requestedFrames > mMaximumBlockFrames) {
        return {TimePitchTransitionStatus::BlockTooLarge, 0, !mActive};
    }
    if (requestedFrames > outputCapacityFrames) {
        return {TimePitchTransitionStatus::OutputCapacityExceeded, 0, !mActive};
    }
    if (requestedFrames == 0) return {TimePitchTransitionStatus::Ok, 0, !mActive};
    if (primedProcessed == nullptr || output == nullptr ||
        (mActive && alignedDirect == nullptr)) {
        return {TimePitchTransitionStatus::InvalidBuffer, 0, !mActive};
    }

    for (std::size_t frame = 0; frame < requestedFrames; ++frame) {
        const bool ramping = mActive && mCursorFrames < mCrossfadeFrames;
        const float processedWeight = ramping
            ? raisedCosineProgress(mCursorFrames, mCrossfadeFrames)
            : 1.0F;
        const float directWeight = 1.0F - processedWeight;
        for (std::size_t channel = 0; channel < mChannelCount; ++channel) {
            const float processed = finiteOrZero(
                primedProcessed[frame * mChannelCount + channel],
                mNonFiniteInputSampleCount);
            const float direct = ramping
                ? finiteOrZero(
                    alignedDirect[frame * mChannelCount + channel],
                    mNonFiniteInputSampleCount)
                : 0.0F;
            float mixed = direct * directWeight + processed * processedWeight;
            if (!std::isfinite(mixed)) {
                mixed = 0.0F;
                saturatingIncrement(mSafetyLimitedOutputSampleCount);
            }
            output[frame * mChannelCount + channel] = mixed;
            if (ramping && mCursorFrames == 0) {
                mFirstOutputDirectError = std::max(
                    mFirstOutputDirectError,
                    std::abs(mixed - direct));
            }
            if (mHasPreviousOutput) {
                mMaximumAdjacentOutputStep = std::max(
                    mMaximumAdjacentOutputStep,
                    std::abs(mixed - mPreviousOutput[channel]));
            }
            mPreviousOutput[channel] = mixed;
        }
        mHasPreviousOutput = true;

        if (ramping) {
            ++mCursorFrames;
            if (mCursorFrames == mCrossfadeFrames) {
                mActive = false;
                saturatingIncrement(mCompletedCount);
            }
        }
    }
    saturatingAdd(mMixedFrames, requestedFrames);
    return {TimePitchTransitionStatus::Ok, requestedFrames, !mActive};
}

TimePitchTransitionDiagnostics TimePitchTransition::diagnostics() const noexcept {
    return {
        .crossfadeFrames = mCrossfadeFrames,
        .crossfadeCursorFrames = mCursorFrames,
        .active = mActive,
        .beginCount = mBeginCount,
        .completedCount = mCompletedCount,
        .mixedFrames = mMixedFrames,
        .nonFiniteInputSampleCount = mNonFiniteInputSampleCount,
        .safetyLimitedOutputSampleCount = mSafetyLimitedOutputSampleCount,
        .firstOutputDirectError = mFirstOutputDirectError,
        .maximumAdjacentOutputStep = mMaximumAdjacentOutputStep,
    };
}

}  // namespace stemwise::audio::engine
