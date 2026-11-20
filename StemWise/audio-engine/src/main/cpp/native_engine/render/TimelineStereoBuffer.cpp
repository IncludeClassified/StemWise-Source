#include "TimelineStereoBuffer.h"

#include <algorithm>
#include <cstring>
#include <limits>
#include <stdexcept>
#include <vector>

namespace stemwise::audio::engine {
namespace {

void saturatingIncrement(std::uint64_t &counter) noexcept {
    if (counter != std::numeric_limits<std::uint64_t>::max()) ++counter;
}

void saturatingAdd(std::uint64_t &counter, std::size_t amount) noexcept {
    const std::uint64_t maximum = std::numeric_limits<std::uint64_t>::max();
    const auto wideAmount = static_cast<std::uint64_t>(amount);
    counter = wideAmount > maximum - counter ? maximum : counter + wideAmount;
}

std::size_t checkedCapacity(std::size_t capacityFrames) {
    if (capacityFrames < TimelineStereoBuffer::kMinimumCapacityFrames ||
        capacityFrames > TimelineStereoBuffer::kMaximumCapacityFrames) {
        throw std::invalid_argument("TimelineStereoBuffer capacity is invalid");
    }
    return capacityFrames;
}

}  // namespace

class TimelineStereoBuffer::Impl final {
public:
    explicit Impl(std::size_t configuredCapacityFrames)
        : capacityFrames(checkedCapacity(configuredCapacityFrames)),
          samples(capacityFrames * TimelineStereoBuffer::kChannelCount, 0.0F) {}

    [[nodiscard]] std::size_t storedFrames() const noexcept {
        return static_cast<std::size_t>(writePosition - readPosition);
    }

    [[nodiscard]] std::uint64_t endTimelineFrame() const noexcept {
        const std::size_t stored = storedFrames();
        const std::uint64_t maximum = std::numeric_limits<std::uint64_t>::max();
        return static_cast<std::uint64_t>(stored) > maximum - firstTimelineFrame
            ? maximum
            : firstTimelineFrame + static_cast<std::uint64_t>(stored);
    }

    void resetInternal(
        std::uint64_t nextGeneration,
        std::uint64_t nextTimelineFrame,
        bool generationReset,
        bool discontinuityReset) noexcept {
        readPosition = writePosition;
        generation = nextGeneration;
        firstTimelineFrame = nextTimelineFrame;
        saturatingIncrement(resetCount);
        if (generationReset) saturatingIncrement(generationResetCount);
        if (discontinuityReset) saturatingIncrement(discontinuityResetCount);
    }

    void copyIntoRing(
        std::uint64_t absoluteWritePosition,
        const float *source,
        std::size_t frameCount) noexcept {
        const std::size_t ringFrame = static_cast<std::size_t>(
            absoluteWritePosition % capacityFrames);
        const std::size_t firstFrames = std::min(frameCount, capacityFrames - ringFrame);
        std::memcpy(
            samples.data() + ringFrame * TimelineStereoBuffer::kChannelCount,
            source,
            firstFrames * TimelineStereoBuffer::kChannelCount * sizeof(float));
        const std::size_t secondFrames = frameCount - firstFrames;
        if (secondFrames != 0) {
            std::memcpy(
                samples.data(),
                source + firstFrames * TimelineStereoBuffer::kChannelCount,
                secondFrames * TimelineStereoBuffer::kChannelCount * sizeof(float));
        }
    }

    void copyFromRing(
        std::uint64_t absoluteReadPosition,
        float *destination,
        std::size_t frameCount) const noexcept {
        const std::size_t ringFrame = static_cast<std::size_t>(
            absoluteReadPosition % capacityFrames);
        const std::size_t firstFrames = std::min(frameCount, capacityFrames - ringFrame);
        std::memcpy(
            destination,
            samples.data() + ringFrame * TimelineStereoBuffer::kChannelCount,
            firstFrames * TimelineStereoBuffer::kChannelCount * sizeof(float));
        const std::size_t secondFrames = frameCount - firstFrames;
        if (secondFrames != 0) {
            std::memcpy(
                destination + firstFrames * TimelineStereoBuffer::kChannelCount,
                samples.data(),
                secondFrames * TimelineStereoBuffer::kChannelCount * sizeof(float));
        }
    }

    std::size_t capacityFrames;
    std::vector<float> samples;
    std::uint64_t readPosition{0};
    std::uint64_t writePosition{0};
    std::uint64_t generation{0};
    std::uint64_t firstTimelineFrame{0};
    std::size_t highWaterFrames{0};

    std::uint64_t appendCalls{0};
    mutable std::uint64_t copyCalls{0};
    std::uint64_t resetCount{0};
    std::uint64_t generationResetCount{0};
    std::uint64_t discontinuityResetCount{0};
    std::uint64_t queuedFrames{0};
    mutable std::uint64_t copiedFrames{0};
    std::uint64_t discardedFrames{0};
    std::uint64_t overlapFramesDiscarded{0};
    std::uint64_t staleFramesDiscarded{0};
    std::uint64_t partialAppendCount{0};
    std::uint64_t backpressureCount{0};
    std::uint64_t gapRejectionCount{0};
};

TimelineStereoBuffer::TimelineStereoBuffer(std::size_t capacityFrames)
    : mImpl(std::make_unique<Impl>(capacityFrames)) {}

TimelineStereoBuffer::~TimelineStereoBuffer() = default;

TimelineAppendResult TimelineStereoBuffer::append(
    std::uint64_t requestedGeneration,
    std::uint64_t requestedFirstTimelineFrame,
    const float *interleavedStereo,
    std::size_t frameCount,
    bool explicitDiscontinuity) noexcept {
    saturatingIncrement(mImpl->appendCalls);
    if (requestedGeneration == 0 || interleavedStereo == nullptr || frameCount == 0 ||
        static_cast<std::uint64_t>(frameCount) >
            std::numeric_limits<std::uint64_t>::max() - requestedFirstTimelineFrame) {
        return {TimelineAppendStatus::InvalidArgument};
    }
    if (mImpl->generation != 0 && requestedGeneration < mImpl->generation) {
        saturatingAdd(mImpl->staleFramesDiscarded, frameCount);
        return {
            TimelineAppendStatus::StaleGeneration,
            frameCount,
            0,
            frameCount,
            false,
        };
    }

    bool timelineReset = false;
    if (mImpl->generation == 0 || requestedGeneration > mImpl->generation ||
        explicitDiscontinuity) {
        const bool generationReset = mImpl->generation != 0 &&
            requestedGeneration > mImpl->generation;
        mImpl->resetInternal(
            requestedGeneration,
            requestedFirstTimelineFrame,
            generationReset,
            explicitDiscontinuity);
        timelineReset = true;
    }

    const std::uint64_t retainedEnd = mImpl->endTimelineFrame();
    std::size_t overlapFrames = 0;
    if (requestedFirstTimelineFrame < retainedEnd) {
        overlapFrames = static_cast<std::size_t>(std::min<std::uint64_t>(
            retainedEnd - requestedFirstTimelineFrame,
            frameCount));
        saturatingAdd(mImpl->overlapFramesDiscarded, overlapFrames);
    }
    if (overlapFrames == frameCount) {
        return {
            TimelineAppendStatus::Accepted,
            frameCount,
            0,
            overlapFrames,
            timelineReset,
        };
    }

    const std::uint64_t suffixTimelineFrame =
        requestedFirstTimelineFrame + static_cast<std::uint64_t>(overlapFrames);
    if (suffixTimelineFrame != retainedEnd) {
        saturatingIncrement(mImpl->gapRejectionCount);
        return {
            TimelineAppendStatus::GapRejected,
            overlapFrames,
            0,
            overlapFrames,
            timelineReset,
        };
    }

    const std::size_t available = mImpl->capacityFrames - mImpl->storedFrames();
    const std::size_t remaining = frameCount - overlapFrames;
    const std::size_t acceptedSuffix = std::min(remaining, available);
    if (acceptedSuffix != 0) {
        mImpl->copyIntoRing(
            mImpl->writePosition,
            interleavedStereo + overlapFrames * kChannelCount,
            acceptedSuffix);
        mImpl->writePosition += acceptedSuffix;
        saturatingAdd(mImpl->queuedFrames, acceptedSuffix);
        mImpl->highWaterFrames = std::max(
            mImpl->highWaterFrames,
            mImpl->storedFrames());
    }

    const std::size_t accepted = overlapFrames + acceptedSuffix;
    if (accepted == frameCount) {
        return {
            TimelineAppendStatus::Accepted,
            accepted,
            acceptedSuffix,
            overlapFrames,
            timelineReset,
        };
    }
    if (accepted == 0) {
        saturatingIncrement(mImpl->backpressureCount);
        return {
            TimelineAppendStatus::Backpressured,
            0,
            0,
            0,
            timelineReset,
        };
    }
    saturatingIncrement(mImpl->partialAppendCount);
    return {
        TimelineAppendStatus::Partial,
        accepted,
        acceptedSuffix,
        overlapFrames,
        timelineReset,
    };
}

TimelineCopyResult TimelineStereoBuffer::copyAt(
    std::uint64_t requestedGeneration,
    std::uint64_t requestedFirstTimelineFrame,
    float *interleavedStereoOutput,
    std::size_t outputCapacityFrames,
    std::size_t requestedFrames) const noexcept {
    saturatingIncrement(mImpl->copyCalls);
    if (requestedGeneration == 0 || interleavedStereoOutput == nullptr ||
        requestedFrames == 0 || requestedFrames > outputCapacityFrames) {
        return {TimelineCopyStatus::InvalidArgument, 0};
    }
    if (requestedGeneration != mImpl->generation) {
        return {TimelineCopyStatus::StaleGeneration, 0};
    }
    const std::uint64_t retainedEnd = mImpl->endTimelineFrame();
    if (requestedFirstTimelineFrame < mImpl->firstTimelineFrame ||
        requestedFirstTimelineFrame >= retainedEnd) {
        return {TimelineCopyStatus::Unavailable, 0};
    }
    const std::size_t available = static_cast<std::size_t>(
        retainedEnd - requestedFirstTimelineFrame);
    const std::size_t copied = std::min(requestedFrames, available);
    const std::uint64_t offset = requestedFirstTimelineFrame - mImpl->firstTimelineFrame;
    mImpl->copyFromRing(mImpl->readPosition + offset, interleavedStereoOutput, copied);
    saturatingAdd(mImpl->copiedFrames, copied);
    return {
        copied == requestedFrames ? TimelineCopyStatus::Complete : TimelineCopyStatus::Partial,
        copied,
    };
}

std::size_t TimelineStereoBuffer::discardBefore(std::uint64_t timelineFrame) noexcept {
    if (timelineFrame <= mImpl->firstTimelineFrame) return 0;
    const std::uint64_t retainedEnd = mImpl->endTimelineFrame();
    const std::size_t discarded = static_cast<std::size_t>(
        std::min(timelineFrame, retainedEnd) - mImpl->firstTimelineFrame);
    mImpl->readPosition += discarded;
    mImpl->firstTimelineFrame += discarded;
    saturatingAdd(mImpl->discardedFrames, discarded);
    return discarded;
}

void TimelineStereoBuffer::reset(
    std::uint64_t generation,
    std::uint64_t firstTimelineFrame) noexcept {
    if (generation == 0) return;
    mImpl->resetInternal(
        generation,
        firstTimelineFrame,
        mImpl->generation != 0 && generation != mImpl->generation,
        true);
}

std::size_t TimelineStereoBuffer::availableFrom(
    std::uint64_t generation,
    std::uint64_t firstTimelineFrame) const noexcept {
    if (generation == 0 || generation != mImpl->generation ||
        firstTimelineFrame < mImpl->firstTimelineFrame ||
        firstTimelineFrame >= mImpl->endTimelineFrame()) {
        return 0;
    }
    return static_cast<std::size_t>(mImpl->endTimelineFrame() - firstTimelineFrame);
}

TimelineStereoBufferDiagnostics TimelineStereoBuffer::diagnostics() const noexcept {
    return {
        .capacityFrames = mImpl->capacityFrames,
        .storedFrames = mImpl->storedFrames(),
        .highWaterFrames = mImpl->highWaterFrames,
        .generation = mImpl->generation,
        .firstTimelineFrame = mImpl->firstTimelineFrame,
        .endTimelineFrameExclusive = mImpl->endTimelineFrame(),
        .appendCalls = mImpl->appendCalls,
        .copyCalls = mImpl->copyCalls,
        .resetCount = mImpl->resetCount,
        .generationResetCount = mImpl->generationResetCount,
        .discontinuityResetCount = mImpl->discontinuityResetCount,
        .queuedFrames = mImpl->queuedFrames,
        .copiedFrames = mImpl->copiedFrames,
        .discardedFrames = mImpl->discardedFrames,
        .overlapFramesDiscarded = mImpl->overlapFramesDiscarded,
        .staleFramesDiscarded = mImpl->staleFramesDiscarded,
        .partialAppendCount = mImpl->partialAppendCount,
        .backpressureCount = mImpl->backpressureCount,
        .gapRejectionCount = mImpl->gapRejectionCount,
    };
}

}  // namespace stemwise::audio::engine
