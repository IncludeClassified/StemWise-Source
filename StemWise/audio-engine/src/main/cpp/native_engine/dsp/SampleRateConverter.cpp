#include "SampleRateConverter.h"

#include <speex/speex_resampler.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <numeric>
#include <memory>
#include <stdexcept>
#include <vector>

namespace stemwise::audio::engine {
namespace {

constexpr std::size_t kMaximumConfiguredInputFrames = 1U << 20U;

std::uint32_t checkedRate(std::uint32_t rateHz) {
    if (rateHz < SampleRateConverter::kMinimumRateHz ||
        rateHz > SampleRateConverter::kMaximumRateHz) {
        throw std::invalid_argument("SampleRateConverter rate is unsupported");
    }
    return rateHz;
}

std::size_t checkedMaximumInputFrames(std::size_t frames) {
    if (frames == 0 || frames > kMaximumConfiguredInputFrames) {
        throw std::invalid_argument("SampleRateConverter input quantum is invalid");
    }
    return frames;
}

int checkedQuality(int quality) {
    if (quality < SampleRateConverter::kMinimumQuality ||
        quality > SampleRateConverter::kMaximumQuality) {
        throw std::invalid_argument("SampleRateConverter quality is invalid");
    }
    return quality;
}

void saturatingIncrement(std::uint64_t &counter) noexcept {
    if (counter != std::numeric_limits<std::uint64_t>::max()) ++counter;
}

void saturatingAdd(std::uint64_t &counter, std::size_t amount) noexcept {
    const auto maximum = std::numeric_limits<std::uint64_t>::max();
    const auto wideAmount = static_cast<std::uint64_t>(amount);
    counter = wideAmount > maximum - counter ? maximum : counter + wideAmount;
}

struct RationalProgress final {
    std::uint64_t quotient{0};
    std::uint32_t remainder{0};
};

RationalProgress expectedOutputProgress(
    std::uint64_t inputFrames,
    std::uint32_t sourceRateHz,
    std::uint32_t destinationRateHz) noexcept {
    // Split the ratio before multiplying so the exact calculation remains portable to MSVC,
    // whose C++ frontend does not expose Clang/GCC's unsigned __int128 extension. The remainder
    // product is bounded by 384000^2 and therefore fits comfortably in uint64_t.
    const std::uint64_t wholeSourceSeconds = inputFrames / sourceRateHz;
    const std::uint64_t remainderSourceFrames = inputFrames % sourceRateHz;
    const std::uint64_t remainderNumerator =
        remainderSourceFrames * destinationRateHz;
    const std::uint64_t fractionalQuotient = remainderNumerator / sourceRateHz;
    const std::uint64_t maximum = std::numeric_limits<std::uint64_t>::max();
    const bool wholeProductOverflows =
        wholeSourceSeconds > (maximum - fractionalQuotient) / destinationRateHz;
    const std::uint64_t quotient = wholeProductOverflows
        ? maximum
        : wholeSourceSeconds * destinationRateHz + fractionalQuotient;
    return {
        quotient,
        static_cast<std::uint32_t>(remainderNumerator % sourceRateHz),
    };
}

std::int64_t signedFrameDifference(
    std::uint64_t actual,
    std::uint64_t expected) noexcept {
    if (actual >= expected) {
        const std::uint64_t difference = actual - expected;
        return difference > static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max())
            ? std::numeric_limits<std::int64_t>::max()
            : static_cast<std::int64_t>(difference);
    }
    const std::uint64_t difference = expected - actual;
    const std::uint64_t negativeLimit =
        static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max()) + 1U;
    if (difference >= negativeLimit) return std::numeric_limits<std::int64_t>::min();
    return -static_cast<std::int64_t>(difference);
}

}  // namespace

class SampleRateConverter::Impl final {
public:
    Impl(
        std::uint32_t configuredSourceRateHz,
        std::uint32_t configuredDestinationRateHz,
        std::size_t configuredMaximumInputFrames,
        int configuredQuality)
        : sourceRateHz(checkedRate(configuredSourceRateHz)),
          destinationRateHz(checkedRate(configuredDestinationRateHz)),
          maximumInputFrames(checkedMaximumInputFrames(configuredMaximumInputFrames)),
          quality(checkedQuality(configuredQuality)),
          exactBypass(sourceRateHz == destinationRateHz),
          inputScratch(maximumInputFrames * SampleRateConverter::kChannelCount, 0.0F) {
        const std::uint32_t divisor = std::gcd(sourceRateHz, destinationRateHz);
        reducedRatioNumerator = destinationRateHz / divisor;
        reducedRatioDenominator = sourceRateHz / divisor;

        if (exactBypass) {
            maximumOutputFrames = maximumInputFrames;
            return;
        }

        int error = RESAMPLER_ERR_SUCCESS;
        resampler.reset(speex_resampler_init(
            static_cast<spx_uint32_t>(SampleRateConverter::kChannelCount),
            sourceRateHz,
            destinationRateHz,
            quality,
            &error));
        if (resampler == nullptr || error != RESAMPLER_ERR_SUCCESS) {
            throw std::runtime_error("Unable to initialize Speex sample-rate converter");
        }

        filterInputLatencyFrames = static_cast<std::size_t>(
            speex_resampler_get_input_latency(resampler.get()));
        filterOutputLatencyFrames = static_cast<std::size_t>(
            speex_resampler_get_output_latency(resampler.get()));
        error = speex_resampler_skip_zeros(resampler.get());
        if (error != RESAMPLER_ERR_SUCCESS) {
            throw std::runtime_error("Unable to compensate Speex startup delay");
        }
        initialZerosSkipped = true;

        const std::uint64_t scaledFrames =
            static_cast<std::uint64_t>(maximumInputFrames) * destinationRateHz;
        const std::uint64_t ratioCeiling =
            (scaledFrames + sourceRateHz - 1U) / sourceRateHz;
        const std::uint64_t conservativeMaximum =
            ratioCeiling + filterOutputLatencyFrames + 2U;
        if (conservativeMaximum > std::numeric_limits<spx_uint32_t>::max()) {
            throw std::invalid_argument("SampleRateConverter output quantum overflows Speex");
        }
        maximumOutputFrames = static_cast<std::size_t>(conservativeMaximum);
    }

    ~Impl() = default;

    void noteBackendFailure(int error) noexcept {
        lastBackendErrorCode = error;
        saturatingIncrement(backendFailureCount);
    }

    void resetInternal(SampleRateResetReason reason) noexcept {
        if (resampler != nullptr) {
            int error = speex_resampler_reset_mem(resampler.get());
            if (error == RESAMPLER_ERR_SUCCESS) {
                error = speex_resampler_skip_zeros(resampler.get());
            }
            if (error != RESAMPLER_ERR_SUCCESS) noteBackendFailure(error);
        }
        generationInputFramesConsumed = 0;
        generationOutputFramesProduced = 0;
        saturatingIncrement(generation);
        saturatingIncrement(resetCount);
        switch (reason) {
            case SampleRateResetReason::Discontinuity:
                saturatingIncrement(discontinuityCount);
                break;
            case SampleRateResetReason::Seek:
                saturatingIncrement(seekCount);
                break;
            case SampleRateResetReason::Explicit:
                break;
        }
    }

    std::uint32_t sourceRateHz;
    std::uint32_t destinationRateHz;
    std::uint32_t reducedRatioNumerator{1};
    std::uint32_t reducedRatioDenominator{1};
    std::size_t maximumInputFrames;
    std::size_t maximumOutputFrames{0};
    int quality;
    bool exactBypass;
    bool initialZerosSkipped{false};
    std::unique_ptr<SpeexResamplerState, decltype(&speex_resampler_destroy)>
        resampler{nullptr, &speex_resampler_destroy};
    std::vector<float> inputScratch;
    std::size_t filterInputLatencyFrames{0};
    std::size_t filterOutputLatencyFrames{0};

    std::uint64_t generation{0};
    std::uint64_t processCalls{0};
    std::uint64_t partialProcessCalls{0};
    std::uint64_t inputFramesConsumed{0};
    std::uint64_t outputFramesProduced{0};
    std::uint64_t generationInputFramesConsumed{0};
    std::uint64_t generationOutputFramesProduced{0};
    std::uint64_t resetCount{0};
    std::uint64_t discontinuityCount{0};
    std::uint64_t seekCount{0};
    std::uint64_t rejectedProcessCount{0};
    std::uint64_t backendFailureCount{0};
    int lastBackendErrorCode{0};
    std::uint64_t nonFiniteInputSampleCount{0};
    std::uint64_t nonFiniteOutputSampleCount{0};
};

SampleRateConverter::SampleRateConverter(
    std::uint32_t sourceRateHz,
    std::uint32_t destinationRateHz,
    std::size_t maximumInputFrames,
    int quality)
    : mImpl(std::make_unique<Impl>(
          sourceRateHz,
          destinationRateHz,
          maximumInputFrames,
          quality)) {}

SampleRateConverter::~SampleRateConverter() = default;

SampleRateProcessResult SampleRateConverter::process(
    const float *interleavedInput,
    std::size_t inputFrames,
    float *interleavedOutput,
    std::size_t outputCapacityFrames) noexcept {
    saturatingIncrement(mImpl->processCalls);
    const auto reject = [this](SampleRateProcessStatus status) noexcept {
        saturatingIncrement(mImpl->rejectedProcessCount);
        return SampleRateProcessResult{status, 0, 0, 0};
    };

    if (inputFrames > mImpl->maximumInputFrames) {
        return reject(SampleRateProcessStatus::InputQuantumExceeded);
    }
    if (outputCapacityFrames > mImpl->maximumOutputFrames) {
        return reject(SampleRateProcessStatus::OutputQuantumExceeded);
    }
    if (inputFrames == 0) return {};
    if (outputCapacityFrames == 0) {
        saturatingIncrement(mImpl->partialProcessCalls);
        return {SampleRateProcessStatus::OutputCapacityExhausted, 0, 0, 0};
    }
    if (interleavedInput == nullptr || interleavedOutput == nullptr) {
        return reject(SampleRateProcessStatus::InvalidBuffer);
    }

    if (mImpl->exactBypass) {
        const std::size_t copiedFrames = std::min(inputFrames, outputCapacityFrames);
        std::memmove(
            interleavedOutput,
            interleavedInput,
            copiedFrames * kChannelCount * sizeof(float));
        saturatingAdd(mImpl->inputFramesConsumed, copiedFrames);
        saturatingAdd(mImpl->outputFramesProduced, copiedFrames);
        saturatingAdd(mImpl->generationInputFramesConsumed, copiedFrames);
        saturatingAdd(mImpl->generationOutputFramesProduced, copiedFrames);
        if (copiedFrames != inputFrames) {
            saturatingIncrement(mImpl->partialProcessCalls);
            return {
                SampleRateProcessStatus::OutputCapacityExhausted,
                copiedFrames,
                copiedFrames,
                0,
            };
        }
        return {SampleRateProcessStatus::Ok, copiedFrames, copiedFrames, 0};
    }

    for (std::size_t sampleIndex = 0; sampleIndex < inputFrames * kChannelCount;
         ++sampleIndex) {
        const float sample = interleavedInput[sampleIndex];
        if (std::isfinite(sample)) {
            mImpl->inputScratch[sampleIndex] = sample;
        } else {
            mImpl->inputScratch[sampleIndex] = 0.0F;
            saturatingIncrement(mImpl->nonFiniteInputSampleCount);
        }
    }

    spx_uint32_t consumed = static_cast<spx_uint32_t>(inputFrames);
    spx_uint32_t produced = static_cast<spx_uint32_t>(outputCapacityFrames);
    const int error = speex_resampler_process_interleaved_float(
        mImpl->resampler.get(),
        mImpl->inputScratch.data(),
        &consumed,
        interleavedOutput,
        &produced);
    if (error != RESAMPLER_ERR_SUCCESS || consumed > inputFrames ||
        produced > outputCapacityFrames) {
        const int reportedError = error == RESAMPLER_ERR_SUCCESS
            ? RESAMPLER_ERR_BAD_STATE
            : error;
        mImpl->noteBackendFailure(reportedError);
        return {
            SampleRateProcessStatus::BackendFailure,
            0,
            0,
            reportedError,
        };
    }

    for (std::size_t sampleIndex = 0;
         sampleIndex < static_cast<std::size_t>(produced) * kChannelCount;
         ++sampleIndex) {
        float sample = interleavedOutput[sampleIndex];
        if (!std::isfinite(sample)) {
            sample = 0.0F;
            saturatingIncrement(mImpl->nonFiniteOutputSampleCount);
        }
        interleavedOutput[sampleIndex] = sample;
    }

    saturatingAdd(mImpl->inputFramesConsumed, consumed);
    saturatingAdd(mImpl->outputFramesProduced, produced);
    saturatingAdd(mImpl->generationInputFramesConsumed, consumed);
    saturatingAdd(mImpl->generationOutputFramesProduced, produced);
    if (consumed < inputFrames) {
        saturatingIncrement(mImpl->partialProcessCalls);
        return {
            SampleRateProcessStatus::OutputCapacityExhausted,
            consumed,
            produced,
            0,
        };
    }
    return {SampleRateProcessStatus::Ok, consumed, produced, 0};
}

void SampleRateConverter::reset(SampleRateResetReason reason) noexcept {
    mImpl->resetInternal(reason);
}

void SampleRateConverter::onDiscontinuity() noexcept {
    reset(SampleRateResetReason::Discontinuity);
}

void SampleRateConverter::onSeek() noexcept {
    reset(SampleRateResetReason::Seek);
}

SampleRateDiagnostics SampleRateConverter::diagnostics() const noexcept {
    const RationalProgress expected = expectedOutputProgress(
        mImpl->generationInputFramesConsumed,
        mImpl->sourceRateHz,
        mImpl->destinationRateHz);
    const std::uint64_t pendingFilterTail =
        mImpl->generationInputFramesConsumed == 0 ? 0U : mImpl->filterOutputLatencyFrames;
    const std::uint64_t latencyCompensatedOutput =
        pendingFilterTail >
                std::numeric_limits<std::uint64_t>::max() -
                    mImpl->generationOutputFramesProduced
            ? std::numeric_limits<std::uint64_t>::max()
            : mImpl->generationOutputFramesProduced + pendingFilterTail;
    return {
        .sourceRateHz = mImpl->sourceRateHz,
        .destinationRateHz = mImpl->destinationRateHz,
        .reducedRatioNumerator = mImpl->reducedRatioNumerator,
        .reducedRatioDenominator = mImpl->reducedRatioDenominator,
        .quality = mImpl->quality,
        .exactBypass = mImpl->exactBypass,
        .initialZerosSkipped = mImpl->initialZerosSkipped,
        .maximumInputFrames = mImpl->maximumInputFrames,
        .maximumOutputFrames = mImpl->maximumOutputFrames,
        .filterInputLatencyFrames = mImpl->filterInputLatencyFrames,
        .filterOutputLatencyFrames = mImpl->filterOutputLatencyFrames,
        .compensatedOutputLatencyFrames = 0,
        .generation = mImpl->generation,
        .processCalls = mImpl->processCalls,
        .partialProcessCalls = mImpl->partialProcessCalls,
        .inputFramesConsumed = mImpl->inputFramesConsumed,
        .outputFramesProduced = mImpl->outputFramesProduced,
        .generationInputFramesConsumed = mImpl->generationInputFramesConsumed,
        .generationOutputFramesProduced = mImpl->generationOutputFramesProduced,
        .generationExpectedOutputFramesFloor = expected.quotient,
        .generationExpectedOutputRemainderNumerator = expected.remainder,
        .generationRawOutputFrameError = signedFrameDifference(
            mImpl->generationOutputFramesProduced,
            expected.quotient),
        .generationOutputFrameError = signedFrameDifference(
            latencyCompensatedOutput,
            expected.quotient),
        .resetCount = mImpl->resetCount,
        .discontinuityCount = mImpl->discontinuityCount,
        .seekCount = mImpl->seekCount,
        .rejectedProcessCount = mImpl->rejectedProcessCount,
        .backendFailureCount = mImpl->backendFailureCount,
        .lastBackendErrorCode = mImpl->lastBackendErrorCode,
        .nonFiniteInputSampleCount = mImpl->nonFiniteInputSampleCount,
        .nonFiniteOutputSampleCount = mImpl->nonFiniteOutputSampleCount,
    };
}

}  // namespace stemwise::audio::engine
