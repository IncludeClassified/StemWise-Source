#include "TimePitchProcessor.h"

#include <cstring> // The pinned FFT templates require std::memcpy to be declared first.
#include <signalsmith-stretch/signalsmith-stretch.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <limits>
#include <stdexcept>
#include <vector>

namespace stemwise::audio::engine {
namespace {

constexpr std::uint32_t kMinimumSampleRateHz = 8'000;
constexpr std::uint32_t kMaximumSampleRateHz = 384'000;
constexpr std::size_t kMaximumConfiguredOutputFrames = 1U << 20U;
constexpr long kDeterministicSignalsmithSeed = 0x5354454D;
std::uint32_t checkedSampleRate(std::uint32_t sampleRateHz) {
    if (sampleRateHz < kMinimumSampleRateHz || sampleRateHz > kMaximumSampleRateHz) {
        throw std::invalid_argument("TimePitchProcessor sample rate is unsupported");
    }
    return sampleRateHz;
}

std::size_t checkedMaximumOutputFrames(std::size_t maximumOutputFrames) {
    if (maximumOutputFrames == 0 ||
        maximumOutputFrames > kMaximumConfiguredOutputFrames) {
        throw std::invalid_argument("TimePitchProcessor output quantum is invalid");
    }
    return maximumOutputFrames;
}

std::size_t checkedMaximumInputFrames(std::size_t maximumOutputFrames) {
    return checkedMaximumOutputFrames(maximumOutputFrames) * 2U + 1U;
}

std::size_t checkedChannelCount(std::size_t channelCount) {
    if (channelCount == 0 || channelCount > TimePitchProcessor::kMaximumChannelCount) {
        throw std::invalid_argument("TimePitchProcessor channel count is invalid");
    }
    return channelCount;
}

void saturatingIncrement(std::uint64_t &counter) noexcept {
    if (counter != std::numeric_limits<std::uint64_t>::max()) ++counter;
}

void saturatingAdd(std::uint64_t &counter, std::size_t amount) noexcept {
    const auto maximum = std::numeric_limits<std::uint64_t>::max();
    const auto wideAmount = static_cast<std::uint64_t>(amount);
    counter = wideAmount > maximum - counter ? maximum : counter + wideAmount;
}

bool controlsAreNeutral(float tempo, float pitchSemitones) noexcept {
    return tempo == 1.0F && pitchSemitones == 0.0F;
}

}  // namespace

class TimePitchProcessor::Impl final {
public:
    Impl(
        std::uint32_t configuredSampleRateHz,
        std::size_t configuredMaximumOutputFrames,
        std::size_t configuredChannelCount,
        std::size_t configuredStride)
        : sampleRateHz(checkedSampleRate(configuredSampleRateHz)),
          maximumOutputFrames(checkedMaximumOutputFrames(configuredMaximumOutputFrames)),
          maximumInputFrames(checkedMaximumInputFrames(configuredMaximumOutputFrames)),
          channelCount(checkedChannelCount(configuredChannelCount)),
          interleavedStride(configuredStride == 0U ? channelCount : checkedChannelCount(configuredStride)),
          channelCapacity(interleavedStride),
          stretch(kDeterministicSignalsmithSeed),
          inputChannels(channelCapacity, std::vector<float>(maximumInputFrames, 0.0F)),
          outputChannels(channelCapacity, std::vector<float>(maximumOutputFrames, 0.0F)),
          planarInput(channelCapacity, nullptr),
          planarOutput(channelCapacity, nullptr) {
        if (interleavedStride < channelCount) throw std::invalid_argument("TimePitchProcessor stride is invalid");
        // Keep the 120 ms interaction window and 20 ms control cadence qualified by the complete
        // seek/automation suite and the standalone 0.5x..2.0 S23 artifact qualification.
        // splitComputation distributes spectral work on the render worker; configuration and all
        // project-owned allocation remain complete before rendering.
        stretch.configure(
            static_cast<int>(channelCapacity),
            static_cast<int>(
                sampleRateHz * TimePitchProcessor::kAnalysisBlockMilliseconds / 1'000U),
            static_cast<int>(
                sampleRateHz * TimePitchProcessor::kAnalysisIntervalMilliseconds / 1'000U),
            true);
        static_cast<void>(stretch.setActiveChannels(static_cast<int>(channelCount)));
        stretch.reset();
        applyTransposeSemitones(0.0F);
        configuredAnalysisBlockFrames = static_cast<std::size_t>(stretch.blockSamples());
        configuredAnalysisIntervalFrames = static_cast<std::size_t>(stretch.intervalSamples());
        configuredInputLatencyFrames = static_cast<std::size_t>(stretch.inputLatency());
        configuredOutputLatencyFrames = static_cast<std::size_t>(stretch.outputLatency());
        maximumPrimeInputFrames = static_cast<std::size_t>(
            stretch.outputSeekLength(TimePitchProcessor::kMaximumTempo));
        const std::size_t scratchFrames = std::max(maximumInputFrames, maximumPrimeInputFrames);
        for (std::size_t channel = 0; channel < channelCapacity; ++channel) {
            inputChannels[channel].resize(scratchFrames);
            planarInput[channel] = inputChannels[channel].data();
            planarOutput[channel] = outputChannels[channel].data();
        }
        // The pinned configure() allocates its FFT, channel and pre-roll storage. First-prime
        // allocation checks cover every supported width; synthetic spectral renders here only
        // delay live lane changes on the render worker and do not prepare the song's actual PCM.
    }

    void applyTransposeSemitones(float semitones) noexcept {
        stretch.setTransposeSemitones(
            semitones,
            TimePitchProcessor::kTransposeTonalityLimitHz / static_cast<float>(sampleRateHz));
    }

    struct InputPlan final {
        std::size_t frames{0};
        double nextRemainder{0.0};
    };

    [[nodiscard]] InputPlan inputPlan(std::size_t outputFrames) const noexcept {
        if (outputFrames == 0) return {};
        const double exactInput = inputFrameRemainder +
            static_cast<double>(outputFrames) * static_cast<double>(tempo);
        const double wholeInput = std::floor(exactInput);
        if (wholeInput > static_cast<double>(maximumInputFrames)) {
            return {maximumInputFrames + 1U, 0.0};
        }
        return {
            static_cast<std::size_t>(wholeInput),
            exactInput - wholeInput,
        };
    }

    void resetInternal(TimePitchResetReason reason) noexcept {
        // Both outputSeek paths reset Signalsmith before pre-roll. Invalidate its timeline here,
        // without clearing the same large spectral buffers twice or clearing unused neutral DSP.
        primeOutputOffset = 0U;
        inputFrameRemainder = 0.0;
        if (timelineState == TimePitchTimelineState::Processed) {
            timelineState = TimePitchTimelineState::PrimeRequired;
        }
        saturatingIncrement(generation);
        saturatingIncrement(resetCount);
        switch (reason) {
            case TimePitchResetReason::Discontinuity:
                saturatingIncrement(discontinuityCount);
                break;
            case TimePitchResetReason::Seek:
                saturatingIncrement(seekCount);
                break;
            case TimePitchResetReason::Explicit:
            case TimePitchResetReason::ControlModeChange:
                break;
        }
    }

    std::uint32_t sampleRateHz;
    std::size_t maximumOutputFrames;
    std::size_t maximumInputFrames;
    std::size_t maximumPrimeInputFrames{0};
    std::size_t channelCount;
    std::size_t interleavedStride;
    std::size_t channelCapacity;
    signalsmith::stretch::SignalsmithStretch<float> stretch;
    std::vector<std::vector<float>> inputChannels;
    std::vector<std::vector<float>> outputChannels;
    std::vector<float *> planarInput;
    std::vector<float *> planarOutput;

    float tempo{1.0F};
    float pitchSemitones{0.0F};
    double inputFrameRemainder{0.0};
    TimePitchTimelineState timelineState{TimePitchTimelineState::DirectVirgin};
    bool processedTimelineActivated{false};
    std::size_t configuredAnalysisBlockFrames{0};
    std::size_t configuredAnalysisIntervalFrames{0};
    std::size_t configuredInputLatencyFrames{0};
    std::size_t configuredOutputLatencyFrames{0};
    std::size_t primeOutputOffset{0};

    std::uint64_t generation{0};
    std::uint64_t processCalls{0};
    std::uint64_t bypassCalls{0};
    std::uint64_t inputFramesConsumed{0};
    std::uint64_t outputFramesProduced{0};
    std::uint64_t exactBypassFrames{0};
    std::uint64_t primeCount{0};
    std::uint64_t primeLookaheadFramesConsumed{0};
    std::uint64_t resetCount{0};
    std::uint64_t discontinuityCount{0};
    std::uint64_t seekCount{0};
    std::uint64_t controlUpdateCount{0};
    std::uint64_t clampedControlUpdateCount{0};
    std::uint64_t rejectedControlUpdateCount{0};
    std::uint64_t rejectedProcessCount{0};
    std::uint64_t nonFiniteInputSampleCount{0};
    std::uint64_t nonFiniteOutputSampleCount{0};
};

TimePitchProcessor::TimePitchProcessor(
    std::uint32_t sampleRateHz,
    std::size_t maximumOutputFrames,
    std::size_t channelCount,
    std::size_t interleavedStride)
    : mImpl(std::make_unique<Impl>(sampleRateHz, maximumOutputFrames, channelCount, interleavedStride)) {}

TimePitchProcessor::~TimePitchProcessor() = default;

bool TimePitchProcessor::setControls(
    float requestedTempo,
    float requestedPitchSemitones,
    bool preserveProcessedTimeline) noexcept {
    saturatingIncrement(mImpl->controlUpdateCount);
    if (!std::isfinite(requestedTempo) || !std::isfinite(requestedPitchSemitones)) {
        saturatingIncrement(mImpl->rejectedControlUpdateCount);
        return false;
    }

    const float appliedTempo = std::clamp(requestedTempo, kMinimumTempo, kMaximumTempo);
    const float appliedPitch = std::clamp(
        requestedPitchSemitones,
        kMinimumPitchSemitones,
        kMaximumPitchSemitones);
    const bool exactRequest =
        appliedTempo == requestedTempo && appliedPitch == requestedPitchSemitones;
    if (!exactRequest) saturatingIncrement(mImpl->clampedControlUpdateCount);

    if (mImpl->primeOutputOffset != 0U &&
        (mImpl->tempo != appliedTempo || mImpl->pitchSemitones != appliedPitch)) {
        mImpl->resetInternal(TimePitchResetReason::ControlModeChange);
    }

    mImpl->tempo = appliedTempo;
    mImpl->pitchSemitones = appliedPitch;
    const bool nowNeutral = controlsAreNeutral(appliedTempo, appliedPitch);

    if (mImpl->timelineState == TimePitchTimelineState::DirectVirgin &&
        (!nowNeutral || preserveProcessedTimeline)) {
        mImpl->resetInternal(TimePitchResetReason::ControlModeChange);
        mImpl->timelineState = TimePitchTimelineState::PrimeRequired;
    } else if (
        mImpl->timelineState == TimePitchTimelineState::PrimeRequired &&
        !mImpl->processedTimelineActivated && nowNeutral && !preserveProcessedTimeline) {
        // A never-activated request may be cancelled without sacrificing virgin direct bypass.
        mImpl->resetInternal(TimePitchResetReason::ControlModeChange);
        mImpl->timelineState = TimePitchTimelineState::DirectVirgin;
    }
    // Processed remains processed at neutral. Signalsmith supports continuous pitch automation.
    mImpl->applyTransposeSemitones(appliedPitch);
    return exactRequest;
}

std::size_t TimePitchProcessor::requiredInputFrames(
    std::size_t requestedOutputFrames) const noexcept {
    if (requestedOutputFrames > mImpl->maximumOutputFrames) {
        return mImpl->maximumInputFrames + 1U;
    }
    return mImpl->inputPlan(requestedOutputFrames).frames;
}

TimePitchPrimeRequirements TimePitchProcessor::primeRequirements() const noexcept {
    if (mImpl->timelineState != TimePitchTimelineState::PrimeRequired) {
        return {
            .lookaheadInputFrames = 0,
            .nextInputOffsetFrames = 0,
            .preparedOutputLatencyFrames = 0,
            .tempo = mImpl->tempo,
            .pitchSemitones = mImpl->pitchSemitones,
        };
    }
    const std::size_t lookahead = static_cast<std::size_t>(
        mImpl->stretch.outputSeekLength(mImpl->tempo));
    return {
        .lookaheadInputFrames = lookahead,
        .nextInputOffsetFrames = lookahead,
        .preparedOutputLatencyFrames = mImpl->configuredOutputLatencyFrames,
        .tempo = mImpl->tempo,
        .pitchSemitones = mImpl->pitchSemitones,
        .inProgress = mImpl->primeOutputOffset != 0U,
    };
}

TimePitchPrimeResult TimePitchProcessor::prime(
    const float *interleavedLookahead,
    std::size_t availableLookaheadFrames,
    std::size_t maximumPrerollOutputFrames) noexcept {
    if (mImpl->timelineState != TimePitchTimelineState::PrimeRequired) {
        return {TimePitchPrimeStatus::NotRequired, 0, 0};
    }
    const TimePitchPrimeRequirements requirements = primeRequirements();
    if (!requirements.inProgress && availableLookaheadFrames < requirements.lookaheadInputFrames) {
        return {TimePitchPrimeStatus::InsufficientLookahead, 0, 0};
    }
    if (!requirements.inProgress && requirements.lookaheadInputFrames != 0 && interleavedLookahead == nullptr) {
        return {TimePitchPrimeStatus::InvalidBuffer, 0, 0};
    }
    if (requirements.lookaheadInputFrames > mImpl->maximumPrimeInputFrames) {
        return {TimePitchPrimeStatus::ProcessingFailure, 0, 0};
    }

    for (std::size_t frame = 0; !requirements.inProgress && frame < requirements.lookaheadInputFrames; ++frame) {
        for (std::size_t channel = 0; channel < mImpl->channelCount; ++channel) {
            const float sample =
                interleavedLookahead[frame * mImpl->interleavedStride + channel];
            // The spectral energy/interpolation path uses float intermediates. This deliberately
            // generous domain preserves stem headroom while bounding squared FFT energy.
            if (std::isfinite(sample) && std::abs(sample) > TimePitchProcessor::kMaximumSpectralInputMagnitude) {
                return {TimePitchPrimeStatus::ProcessingFailure, 0, 0};
            }
            if (std::isfinite(sample)) {
                mImpl->inputChannels[channel][frame] = sample;
            } else {
                mImpl->inputChannels[channel][frame] = 0.0F;
                saturatingIncrement(mImpl->nonFiniteInputSampleCount);
            }
        }
    }
    try {
        if (maximumPrerollOutputFrames == 0U && !requirements.inProgress) {
            mImpl->stretch.outputSeek(mImpl->planarInput,
                static_cast<int>(requirements.lookaheadInputFrames));
        } else {
            const auto remaining = mImpl->configuredOutputLatencyFrames - mImpl->primeOutputOffset;
            const auto frames = maximumPrerollOutputFrames == 0U ? remaining :
                std::min(remaining, maximumPrerollOutputFrames);
            mImpl->stretch.outputSeekStep(mImpl->planarInput,
                static_cast<int>(requirements.lookaheadInputFrames),
                static_cast<int>(mImpl->primeOutputOffset), static_cast<int>(frames));
            mImpl->primeOutputOffset += frames;
            if (mImpl->primeOutputOffset < mImpl->configuredOutputLatencyFrames) {
                return {TimePitchPrimeStatus::InProgress, 0U, 0U};
            }
        }
    } catch (...) {
        mImpl->resetInternal(TimePitchResetReason::Discontinuity);
        mImpl->timelineState = TimePitchTimelineState::PrimeRequired;
        return {TimePitchPrimeStatus::ProcessingFailure, 0, 0};
    }
    mImpl->inputFrameRemainder = 0.0;
    mImpl->primeOutputOffset = 0U;
    mImpl->timelineState = TimePitchTimelineState::Processed;
    mImpl->processedTimelineActivated = true;
    saturatingIncrement(mImpl->primeCount);
    saturatingAdd(mImpl->primeLookaheadFramesConsumed, requirements.lookaheadInputFrames);
    return {
        TimePitchPrimeStatus::Ok,
        requirements.lookaheadInputFrames,
        requirements.nextInputOffsetFrames,
    };
}

TimePitchProcessResult TimePitchProcessor::process(
    const float *interleavedInput,
    std::size_t availableInputFrames,
    float *interleavedOutput,
    std::size_t outputCapacityFrames,
    std::size_t requestedOutputFrames) noexcept {
    saturatingIncrement(mImpl->processCalls);
    const auto reject = [this](TimePitchProcessStatus status) noexcept {
        saturatingIncrement(mImpl->rejectedProcessCount);
        return TimePitchProcessResult{status, 0, 0};
    };

    if (requestedOutputFrames > outputCapacityFrames ||
        requestedOutputFrames > mImpl->maximumOutputFrames) {
        return reject(TimePitchProcessStatus::OutputCapacityExceeded);
    }
    if (requestedOutputFrames == 0) return {};
    if (interleavedOutput == nullptr) return reject(TimePitchProcessStatus::InvalidBuffer);
    if (mImpl->timelineState == TimePitchTimelineState::PrimeRequired) {
        return reject(TimePitchProcessStatus::PrimeRequired);
    }

    const Impl::InputPlan plan = mImpl->inputPlan(requestedOutputFrames);
    if (plan.frames > mImpl->maximumInputFrames || plan.frames > availableInputFrames) {
        return reject(TimePitchProcessStatus::InsufficientInput);
    }
    if (plan.frames != 0 && interleavedInput == nullptr) {
        return reject(TimePitchProcessStatus::InvalidBuffer);
    }

    if (mImpl->timelineState == TimePitchTimelineState::DirectVirgin) {
        // memmove preserves every float bit, including signed zero, and permits in-place rendering.
        std::memmove(
            interleavedOutput,
            interleavedInput,
            requestedOutputFrames * mImpl->interleavedStride * sizeof(float));
        if (mImpl->channelCount != mImpl->interleavedStride) {
            for (std::size_t frame = 0U; frame < requestedOutputFrames; ++frame) {
                std::fill_n(interleavedOutput + frame * mImpl->interleavedStride + mImpl->channelCount,
                    mImpl->interleavedStride - mImpl->channelCount, 0.0F);
            }
        }
        mImpl->inputFrameRemainder = plan.nextRemainder;
        saturatingIncrement(mImpl->bypassCalls);
        saturatingAdd(mImpl->exactBypassFrames, requestedOutputFrames);
        saturatingAdd(mImpl->inputFramesConsumed, plan.frames);
        saturatingAdd(mImpl->outputFramesProduced, requestedOutputFrames);
        return {TimePitchProcessStatus::Ok, plan.frames, requestedOutputFrames};
    }

    for (std::size_t frame = 0; frame < plan.frames; ++frame) {
        for (std::size_t channel = 0; channel < mImpl->channelCount; ++channel) {
            const float sample = interleavedInput[frame * mImpl->interleavedStride + channel];
            if (std::isfinite(sample) && std::abs(sample) > TimePitchProcessor::kMaximumSpectralInputMagnitude) {
                return reject(TimePitchProcessStatus::ProcessingFailure);
            }
            if (std::isfinite(sample)) {
                mImpl->inputChannels[channel][frame] = sample;
            } else {
                mImpl->inputChannels[channel][frame] = 0.0F;
                saturatingIncrement(mImpl->nonFiniteInputSampleCount);
            }
        }
    }
    try {
        mImpl->stretch.process(
            mImpl->planarInput,
            static_cast<int>(plan.frames),
            mImpl->planarOutput,
            static_cast<int>(requestedOutputFrames));
    } catch (...) {
        mImpl->resetInternal(TimePitchResetReason::Discontinuity);
        return reject(TimePitchProcessStatus::ProcessingFailure);
    }

    for (std::size_t frame = 0; frame < requestedOutputFrames; ++frame) {
        for (std::size_t channel = 0; channel < mImpl->channelCount; ++channel) {
            float sample = mImpl->outputChannels[channel][frame];
            if (!std::isfinite(sample)) {
                sample = 0.0F;
                saturatingIncrement(mImpl->nonFiniteOutputSampleCount);
            }
            interleavedOutput[frame * mImpl->interleavedStride + channel] = sample;
        }
        std::fill_n(interleavedOutput + frame * mImpl->interleavedStride + mImpl->channelCount,
            mImpl->interleavedStride - mImpl->channelCount, 0.0F);
    }

    mImpl->inputFrameRemainder = plan.nextRemainder;
    saturatingAdd(mImpl->inputFramesConsumed, plan.frames);
    saturatingAdd(mImpl->outputFramesProduced, requestedOutputFrames);
    return {TimePitchProcessStatus::Ok, plan.frames, requestedOutputFrames};
}

void TimePitchProcessor::reset(TimePitchResetReason reason) noexcept {
    mImpl->resetInternal(reason);
}

void TimePitchProcessor::onDiscontinuity() noexcept {
    reset(TimePitchResetReason::Discontinuity);
}

void TimePitchProcessor::onSeek() noexcept {
    reset(TimePitchResetReason::Seek);
}

TimePitchDiagnostics TimePitchProcessor::diagnostics() const noexcept {
    const bool bypass = mImpl->timelineState == TimePitchTimelineState::DirectVirgin;
    return {
        .sampleRateHz = mImpl->sampleRateHz,
        .maximumOutputFrames = mImpl->maximumOutputFrames,
        .maximumInputFrames = mImpl->maximumInputFrames,
        .activeChannelCount = static_cast<std::size_t>(mImpl->stretch.activeChannels()),
        .channelCapacity = mImpl->channelCapacity,
        .tempo = mImpl->tempo,
        .pitchSemitones = mImpl->pitchSemitones,
        .transposeTonalityLimitHz = kTransposeTonalityLimitHz,
        .exactBypass = bypass,
        .timelineState = mImpl->timelineState,
        .processedTimelineActivated = mImpl->processedTimelineActivated,
        .configuredAnalysisBlockFrames = mImpl->configuredAnalysisBlockFrames,
        .configuredAnalysisIntervalFrames = mImpl->configuredAnalysisIntervalFrames,
        .configuredInputLatencyFrames = mImpl->configuredInputLatencyFrames,
        .configuredOutputLatencyFrames = mImpl->configuredOutputLatencyFrames,
        .activeInputLatencyFrames = bypass ? 0U : mImpl->configuredInputLatencyFrames,
        .activeOutputLatencyFrames = bypass ? 0U : mImpl->configuredOutputLatencyFrames,
        .generation = mImpl->generation,
        .processCalls = mImpl->processCalls,
        .bypassCalls = mImpl->bypassCalls,
        .inputFramesConsumed = mImpl->inputFramesConsumed,
        .outputFramesProduced = mImpl->outputFramesProduced,
        .exactBypassFrames = mImpl->exactBypassFrames,
        .primeCount = mImpl->primeCount,
        .primeLookaheadFramesConsumed = mImpl->primeLookaheadFramesConsumed,
        .resetCount = mImpl->resetCount,
        .discontinuityCount = mImpl->discontinuityCount,
        .seekCount = mImpl->seekCount,
        .controlUpdateCount = mImpl->controlUpdateCount,
        .clampedControlUpdateCount = mImpl->clampedControlUpdateCount,
        .rejectedControlUpdateCount = mImpl->rejectedControlUpdateCount,
        .rejectedProcessCount = mImpl->rejectedProcessCount,
        .nonFiniteInputSampleCount = mImpl->nonFiniteInputSampleCount,
        .nonFiniteOutputSampleCount = mImpl->nonFiniteOutputSampleCount,
    };
}

bool TimePitchProcessor::setActiveChannelCount(std::size_t count) noexcept {
    if (count == 0U || count > mImpl->channelCapacity) return false;
    if (count == mImpl->channelCount) return true;
    if (!mImpl->stretch.setActiveChannels(static_cast<int>(count))) return false;
    for (auto channel = mImpl->channelCount; channel < count; ++channel) {
        std::fill(mImpl->inputChannels[channel].begin(), mImpl->inputChannels[channel].end(), 0.0F);
        std::fill(mImpl->outputChannels[channel].begin(), mImpl->outputChannels[channel].end(), 0.0F);
    }
    mImpl->channelCount = count;
    return true;
}

std::size_t TimePitchProcessor::channelCount() const noexcept {
    return mImpl->channelCount;
}

}  // namespace stemwise::audio::engine
