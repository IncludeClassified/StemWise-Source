#include "NativeAudioOutputFinalDeviceStage.h"

#include <algorithm>
#include <cmath>
#include <limits>

namespace stemwise::audio {
namespace {

void saturatingIncrement(std::uint64_t &value) noexcept {
    if (value != std::numeric_limits<std::uint64_t>::max()) ++value;
}

void saturatingAdd(std::uint64_t &value, std::size_t amount) noexcept {
    const auto converted = static_cast<std::uint64_t>(amount);
    const auto maximum = std::numeric_limits<std::uint64_t>::max();
    value = converted > maximum - value ? maximum : value + converted;
}

}  // namespace

bool OutputTransitionCoverage::record(
    std::size_t outputOffset,
    std::size_t frameCount) noexcept {
    if (frameCount == 0U) return true;
    if (mRangeCount >= mRanges.size() ||
        outputOffset > std::numeric_limits<std::size_t>::max() - frameCount) {
        return false;
    }
    mRanges[mRangeCount++] = {.outputOffset = outputOffset, .frameCount = frameCount};
    return true;
}

bool OutputTransitionCoverage::overlaps(
    std::size_t outputOffset,
    std::size_t frameCount) const noexcept {
    if (frameCount == 0U ||
        outputOffset > std::numeric_limits<std::size_t>::max() - frameCount) {
        return false;
    }
    const std::size_t end = outputOffset + frameCount;
    for (std::size_t index = 0U; index < mRangeCount; ++index) {
        const Range &range = mRanges[index];
        const std::size_t rangeEnd = range.outputOffset + range.frameCount;
        if (outputOffset < rangeEnd && range.outputOffset < end) return true;
    }
    return false;
}

bool transitionTouchesAnyOwnedSpan(
    const OutputTransitionCoverage &coverage,
    const OutputOwnedFrameSpan *spans,
    std::size_t spanCount) noexcept {
    if (spans == nullptr || spanCount == 0U) return false;
    for (std::size_t index = 0U; index < spanCount; ++index) {
        if (coverage.overlaps(spans[index].outputOffset, spans[index].frameCount)) {
            return true;
        }
    }
    return false;
}

void NativeAudioOutputFinalDeviceStage::scrub(float &sample) noexcept {
    if (std::isfinite(sample)) return;
    sample = 0.0F;
    saturatingIncrement(mDiagnostics.nonFiniteSamplesScrubbed);
}

void NativeAudioOutputFinalDeviceStage::clamp(float &sample) noexcept {
    const float limited = std::clamp(sample, -kDeviceCeiling, kDeviceCeiling);
    if (limited != sample) saturatingIncrement(mDiagnostics.safetyClamps);
    sample = limited;
}

void NativeAudioOutputFinalDeviceStage::finalize(
    float *interleavedStereo,
    std::size_t frameCount) noexcept {
    if (interleavedStereo == nullptr || frameCount == 0U) return;
    for (std::size_t frame = 0U; frame < frameCount; ++frame) {
        float &left = interleavedStereo[frame * 2U];
        float &right = interleavedStereo[frame * 2U + 1U];
        scrub(left);
        scrub(right);
        clamp(left);
        clamp(right);
    }
    saturatingAdd(mDiagnostics.processedFrames, frameCount);
}

const NativeAudioOutputFinalDeviceStageDiagnostics &
NativeAudioOutputFinalDeviceStage::diagnostics() const noexcept {
    return mDiagnostics;
}

}  // namespace stemwise::audio
