#include "AllocationProbeAttributes.h"
#include "SampleRateConverter.h"
#include "StemRoleLimits.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <limits>
#include <memory>
#include <new>
#include <stdexcept>
#include <string>
#include <vector>

namespace allocation_probe {
bool enabled = false;
std::uint64_t count = 0;
int failAfter = -1;
std::int64_t backendLiveAllocations = 0;
}  // namespace allocation_probe

// The test build remaps SpeexDSP's private calloc/realloc/free calls to these functions, allowing
// the same probe to cover both project-owned C++ storage and the C backend.
extern "C" void *stemwise_test_calloc(std::size_t count, std::size_t size) {
    if (allocation_probe::enabled) ++allocation_probe::count;
    void *memory = std::calloc(count, size);
    if (memory) ++allocation_probe::backendLiveAllocations;
    return memory;
}

extern "C" void *stemwise_test_realloc(void *memory, std::size_t size) {
    if (allocation_probe::enabled) ++allocation_probe::count;
    void *result = std::realloc(memory, size);
    if (result && !memory) ++allocation_probe::backendLiveAllocations;
    return result;
}

extern "C" void stemwise_test_free(void *memory) {
    if (memory) --allocation_probe::backendLiveAllocations;
    std::free(memory);
}

STEMWISE_ALLOCATION_HOOK void *operator new(std::size_t size) {
    if (allocation_probe::failAfter >= 0 && allocation_probe::failAfter-- == 0) throw std::bad_alloc();
    if (allocation_probe::enabled) ++allocation_probe::count;
    if (void *memory = std::malloc(size)) return memory;
    throw std::bad_alloc();
}

STEMWISE_ALLOCATION_HOOK void *operator new[](std::size_t size) {
    return ::operator new(size);
}

STEMWISE_ALLOCATION_HOOK void operator delete(void *memory) noexcept {
    std::free(memory);
}

STEMWISE_ALLOCATION_HOOK void operator delete[](void *memory) noexcept {
    ::operator delete(memory);
}

STEMWISE_ALLOCATION_HOOK void operator delete(void *memory, std::size_t) noexcept {
    std::free(memory);
}

STEMWISE_ALLOCATION_HOOK void operator delete[](void *memory, std::size_t) noexcept {
    ::operator delete(memory);
}

namespace {

using stemwise::audio::engine::SampleRateConverter;
using stemwise::audio::engine::SampleRateProcessStatus;

std::int64_t observedManyBlockDriftFrames = 0;

void require(bool condition, const std::string &message) {
    if (!condition) throw std::runtime_error(message);
}

std::vector<float> stereoSignal(std::size_t frames, std::uint32_t sampleRate) {
    std::vector<float> samples(frames * SampleRateConverter::kChannelCount);
    constexpr double kPi = 3.14159265358979323846;
    for (std::size_t frame = 0; frame < frames; ++frame) {
        const double time = static_cast<double>(frame) / sampleRate;
        samples[frame * 2U] = static_cast<float>(0.71 * std::sin(2.0 * kPi * 733.0 * time));
        samples[frame * 2U + 1U] =
            static_cast<float>(0.43 * std::cos(2.0 * kPi * 509.0 * time));
    }
    return samples;
}

struct ConvertedBlock final {
    std::vector<float> samples;
    std::size_t consumed{0};
    std::size_t produced{0};
};

ConvertedBlock convertOneBlock(SampleRateConverter &converter, const std::vector<float> &input) {
    const std::size_t inputFrames = input.size() / SampleRateConverter::kChannelCount;
    const std::size_t capacity = converter.diagnostics().maximumOutputFrames;
    ConvertedBlock converted{
        std::vector<float>(capacity * SampleRateConverter::kChannelCount, 0.0F),
        0,
        0,
    };
    const auto result = converter.process(
        input.data(), inputFrames, converted.samples.data(), capacity);
    require(result.status == SampleRateProcessStatus::Ok, "full-capacity conversion status");
    require(result.inputFramesConsumed == inputFrames, "full-capacity input consumed");
    converted.consumed = result.inputFramesConsumed;
    converted.produced = result.outputFramesProduced;
    converted.samples.resize(converted.produced * SampleRateConverter::kChannelCount);
    return converted;
}

void constructionFailureReleasesSpeexState() {
    for (int failAfter = 0; failAfter != 5; ++failAfter) {
        const auto before = allocation_probe::backendLiveAllocations;
        allocation_probe::failAfter = failAfter;
        try { SampleRateConverter converter(44'100U, 48'000U, 1'024U); }
        catch (const std::bad_alloc &) {}
        allocation_probe::failAfter = -1;
        require(allocation_probe::backendLiveAllocations == before,
            "failed construction leaked a Speex allocation");
    }
}

void finiteResidualHeadroomSurvivesResampling() {
    SampleRateConverter quiet(44'100U, 48'000U, 1'024U), loud(44'100U, 48'000U, 1'024U);
    auto low = stereoSignal(1'024U, 44'100U);
    auto high = low;
    for (float &sample : high) sample *= 16.0F;
    const auto reference = convertOneBlock(quiet, low);
    const auto actual = convertOneBlock(loud, high);
    require(reference.produced == actual.produced, "headroom SRC changed frame alignment");
    float peak = 0.0F;
    for (std::size_t index = 0; index < actual.samples.size(); ++index) {
        require(std::isfinite(actual.samples[index]) &&
            std::abs(actual.samples[index] - reference.samples[index] * 16.0F) < 0.0001F,
            "SRC clipped a finite residual before mixing");
        peak = std::max(peak, std::abs(actual.samples[index]));
    }
    require(peak > 4.5F,
        "SRC retained the obsolete residual amplitude guard");
}

void bypassIsBitExactAndZeroLatency() {
    constexpr std::size_t kFrames = 257;
    SampleRateConverter converter(48'000, 48'000, 512);
    auto input = stereoSignal(kFrames, 48'000);
    input[0] = -0.0F;
    std::vector<float> output(input.size(), 123.0F);
    const auto result = converter.process(
        input.data(), kFrames, output.data(), kFrames);

    require(result.status == SampleRateProcessStatus::Ok, "bypass status");
    require(result.inputFramesConsumed == kFrames, "bypass input count");
    require(result.outputFramesProduced == kFrames, "bypass output count");
    require(
        std::memcmp(input.data(), output.data(), input.size() * sizeof(float)) == 0,
        "bypass bit identity");

    const auto diagnostics = converter.diagnostics();
    require(diagnostics.exactBypass, "bypass diagnostic");
    require(diagnostics.filterInputLatencyFrames == 0, "bypass input latency");
    require(diagnostics.filterOutputLatencyFrames == 0, "bypass output latency");
    require(diagnostics.compensatedOutputLatencyFrames == 0, "bypass compensated latency");
    require(diagnostics.generationOutputFrameError == 0, "bypass rational progress");
}

void ratePairProducesBoundedRationalCount(
    std::uint32_t sourceRate,
    std::uint32_t destinationRate,
    std::size_t inputFrames) {
    SampleRateConverter converter(sourceRate, destinationRate, inputFrames);
    const auto input = stereoSignal(inputFrames, sourceRate);
    const auto output = convertOneBlock(converter, input);
    const auto diagnostics = converter.diagnostics();

    require(output.produced > 0, "rate pair produced audio");
    require(diagnostics.initialZerosSkipped, "rate pair startup compensation");
    require(diagnostics.filterInputLatencyFrames > 0, "rate pair input latency exposed");
    require(diagnostics.filterOutputLatencyFrames > 0, "rate pair output latency exposed");
    require(diagnostics.compensatedOutputLatencyFrames == 0, "rate pair compensated latency");
    require(
        std::abs(diagnostics.generationOutputFrameError) <= 1,
        "rate pair rational count bounded: " + std::to_string(sourceRate) + "->" +
            std::to_string(destinationRate) + " error=" +
            std::to_string(diagnostics.generationOutputFrameError) + " produced=" +
            std::to_string(output.produced) + " expected=" +
            std::to_string(diagnostics.generationExpectedOutputFramesFloor));
    require(
        std::abs(diagnostics.generationRawOutputFrameError) <=
            static_cast<std::int64_t>(diagnostics.filterOutputLatencyFrames),
        "rate pair raw deficit bounded by explicit filter tail");
    for (float sample : output.samples) {
        require(std::isfinite(sample), "rate pair finite sample");
        require(
            std::abs(sample) <= 4.0F,
            "rate pair bounded sample");
    }
}

void commonAndExtremeRatePairsStayBounded() {
    ratePairProducesBoundedRationalCount(44'100, 48'000, 4'410);
    ratePairProducesBoundedRationalCount(48'000, 44'100, 4'800);
    ratePairProducesBoundedRationalCount(8'000, 48'000, 800);
    ratePairProducesBoundedRationalCount(48'000, 8'000, 4'800);
}

void impulseChannelsRemainSampleAligned() {
    constexpr std::size_t kInputFrames = 4'096;
    SampleRateConverter converter(44'100, 48'000, kInputFrames);
    std::vector<float> input(kInputFrames * 2U, 0.0F);
    input[512U * 2U] = 1.0F;
    input[512U * 2U + 1U] = 0.5F;
    const auto output = convertOneBlock(converter, input);

    std::size_t leftPeak = 0;
    std::size_t rightPeak = 0;
    float leftMagnitude = 0.0F;
    float rightMagnitude = 0.0F;
    for (std::size_t frame = 0; frame < output.produced; ++frame) {
        const float left = std::abs(output.samples[frame * 2U]);
        const float right = std::abs(output.samples[frame * 2U + 1U]);
        if (left > leftMagnitude) {
            leftMagnitude = left;
            leftPeak = frame;
        }
        if (right > rightMagnitude) {
            rightMagnitude = right;
            rightPeak = frame;
        }
    }
    require(leftMagnitude > 0.1F, "left impulse survives");
    require(rightMagnitude > 0.05F, "right impulse survives");
    require(leftPeak == rightPeak, "stereo impulse frame alignment");
    require(std::abs(rightMagnitude * 2.0F - leftMagnitude) < 0.0001F, "channel gain relation");
}

void allLanesAreFrameAndSampleIdentical() {
    constexpr std::size_t kFrames = 997;
    const auto input = stereoSignal(kFrames, 44'100);
    std::vector<float> baseline;
    std::size_t baselineProduced = 0;

    for (std::size_t lane = 0U; lane < stemwise::audio::kStemwiseMaximumParts + 1U; ++lane) {
        SampleRateConverter converter(44'100, 48'000, kFrames);
        const auto converted = convertOneBlock(converter, input);
        if (lane == 0) {
            baseline = converted.samples;
            baselineProduced = converted.produced;
        } else {
            require(converted.produced == baselineProduced, "lane frame count identity");
            require(
                std::memcmp(
                    converted.samples.data(),
                    baseline.data(),
                    baseline.size() * sizeof(float)) == 0,
                "lane sample identity");
        }
    }
}

void repeatedStereoResetMatchesFreshConstructionAtEverySupportedRatio() {
    for (const auto& rates : {std::pair{44'100U, 48'000U}, std::pair{48'000U, 44'100U},
             std::pair{8'000U, 384'000U}, std::pair{384'000U, 8'000U}}) {
        const auto input = stereoSignal(4096U, rates.first);
        SampleRateConverter reference(rates.first, rates.second, 4096U);
        const auto expected = convertOneBlock(reference, input);
        for (std::size_t lane = 0U; lane < stemwise::audio::kStemwiseMaximumParts + 1U; ++lane) {
            SampleRateConverter converter(rates.first, rates.second, 4096U);
            for (int iteration = 0; iteration < 3; ++iteration) {
                static_cast<void>(convertOneBlock(converter, input));
                const auto allocations = allocation_probe::count;
                allocation_probe::enabled = true;
                converter.onSeek();
                allocation_probe::enabled = false;
                require(allocation_probe::count == allocations, "stereo reset allocated");
                const auto replay = convertOneBlock(converter, input);
                require(replay.produced == expected.produced && replay.samples.size() == expected.samples.size() &&
                    std::memcmp(replay.samples.data(), expected.samples.data(), expected.samples.size() * sizeof(float)) == 0,
                    "per-channel reset changed fresh stereo PCM");
            }
        }
    }
}

void manyBlocksDoNotAccumulateSilentRatioDrift() {
    constexpr std::size_t kFramesPerBlock = 127;
    constexpr int kBlockCount = 1'000;
    SampleRateConverter converter(44'100, 48'000, kFramesPerBlock);
    const auto input = stereoSignal(kFramesPerBlock, 44'100);
    const std::size_t capacity = converter.diagnostics().maximumOutputFrames;
    std::vector<float> output(capacity * 2U, 0.0F);

    for (int block = 0; block < kBlockCount; ++block) {
        const auto result = converter.process(
            input.data(), kFramesPerBlock, output.data(), capacity);
        require(result.status == SampleRateProcessStatus::Ok, "many-block status");
        require(result.inputFramesConsumed == kFramesPerBlock, "many-block input count");
    }
    const auto diagnostics = converter.diagnostics();
    observedManyBlockDriftFrames = diagnostics.generationOutputFrameError;
    require(
        diagnostics.generationInputFramesConsumed == kFramesPerBlock * kBlockCount,
        "many-block exact input counter");
    require(
        std::abs(diagnostics.generationOutputFrameError) <= 1,
        "many-block cumulative ratio error");
    require(
        diagnostics.generationExpectedOutputRemainderNumerator < 44'100,
        "many-block rational remainder bounded");
}

void seekResetReproducesTheInitialStreamExactly() {
    constexpr std::size_t kFrames = 1'024;
    SampleRateConverter converter(44'100, 48'000, kFrames);
    const auto input = stereoSignal(kFrames, 44'100);
    const auto first = convertOneBlock(converter, input);
    const auto intervening = convertOneBlock(converter, input);
    require(intervening.produced > 0, "intervening output");

    const auto beforeSeek = converter.diagnostics();
    converter.onSeek();
    const auto replay = convertOneBlock(converter, input);
    const auto afterSeek = converter.diagnostics();
    require(replay.produced == first.produced, "seek frame reproducibility");
    std::size_t mismatch = first.samples.size();
    for (std::size_t index = 0; index < first.samples.size(); ++index) {
        if (replay.samples[index] != first.samples[index]) {
            mismatch = index;
            break;
        }
    }
    require(
        std::memcmp(
            replay.samples.data(),
            first.samples.data(),
            first.samples.size() * sizeof(float)) == 0,
        "seek sample reproducibility mismatch=" + std::to_string(mismatch) +
            " first=" +
            (mismatch < first.samples.size() ? std::to_string(first.samples[mismatch]) : "bits") +
            " replay=" +
            (mismatch < replay.samples.size() ? std::to_string(replay.samples[mismatch]) : "bits"));
    require(afterSeek.generation == beforeSeek.generation + 1, "seek generation");
    require(afterSeek.seekCount == beforeSeek.seekCount + 1, "seek counter");
}

void nonFiniteInputIsSanitizedBeforeResampling() {
    constexpr std::size_t kFrames = 512;
    SampleRateConverter converter(44'100, 48'000, kFrames);
    auto input = stereoSignal(kFrames, 44'100);
    input[7] = std::numeric_limits<float>::quiet_NaN();
    input[22] = std::numeric_limits<float>::infinity();
    const auto output = convertOneBlock(converter, input);
    for (float sample : output.samples) require(std::isfinite(sample), "sanitized output finite");
    const auto diagnostics = converter.diagnostics();
    require(diagnostics.nonFiniteInputSampleCount == 2, "non-finite input counter");
    require(diagnostics.nonFiniteOutputSampleCount == 0, "no non-finite output escaped");
}

void insufficientOutputCapacityIsBoundedAndRetryable() {
    constexpr std::size_t kFrames = 128;
    SampleRateConverter converter(8'000, 48'000, kFrames);
    const auto input = stereoSignal(kFrames, 8'000);
    std::vector<float> output(6, 77.0F);
    const auto result = converter.process(input.data(), kFrames, output.data(), 1);

    require(
        result.status == SampleRateProcessStatus::OutputCapacityExhausted,
        "small output reports exhaustion");
    require(result.outputFramesProduced <= 1, "small output production bounded");
    require(result.inputFramesConsumed < kFrames, "small output leaves retryable input");
    require(output[2] == 77.0F && output[3] == 77.0F, "small output guard untouched");
}

void repeatedPartialCapacityRetriesMatchFullConversion() {
    constexpr std::size_t kFrames = 2'047;
    constexpr std::size_t kTinyOutputCapacity = 17;
    const auto input = stereoSignal(kFrames, 44'100);

    SampleRateConverter fullConverter(44'100, 48'000, kFrames);
    const auto full = convertOneBlock(fullConverter, input);

    SampleRateConverter partialConverter(44'100, 48'000, kFrames);
    std::vector<float> tinyOutput(kTinyOutputCapacity * 2U, 0.0F);
    std::vector<float> concatenated;
    concatenated.reserve(full.samples.size());
    std::size_t inputOffsetFrames = 0;
    int callCount = 0;
    while (inputOffsetFrames < kFrames) {
        const std::size_t remainingFrames = kFrames - inputOffsetFrames;
        const auto result = partialConverter.process(
            input.data() + inputOffsetFrames * 2U,
            remainingFrames,
            tinyOutput.data(),
            kTinyOutputCapacity);
        require(
            result.status == SampleRateProcessStatus::OutputCapacityExhausted ||
                result.status == SampleRateProcessStatus::Ok,
            "partial retry status");
        require(
            result.inputFramesConsumed > 0 || result.outputFramesProduced > 0,
            "partial retry makes progress");
        require(result.outputFramesProduced <= kTinyOutputCapacity, "partial retry capacity bound");
        inputOffsetFrames += result.inputFramesConsumed;
        concatenated.insert(
            concatenated.end(),
            tinyOutput.begin(),
            tinyOutput.begin() +
                static_cast<std::ptrdiff_t>(result.outputFramesProduced * 2U));
        require(++callCount < 10'000, "partial retry terminates");
    }

    const auto partialDiagnostics = partialConverter.diagnostics();
    require(inputOffsetFrames == kFrames, "partial retry consumes complete input");
    require(
        partialDiagnostics.generationInputFramesConsumed == kFrames,
        "partial retry exact consumed counter");
    require(
        partialDiagnostics.generationOutputFramesProduced == full.produced,
        "partial retry frame count matches full conversion");
    require(concatenated.size() == full.samples.size(), "partial retry sample count matches");
    require(
        std::memcmp(
            concatenated.data(),
            full.samples.data(),
            full.samples.size() * sizeof(float)) == 0,
        "partial retry concatenation matches full conversion");
}

void steadyStateOperationsDoNotAllocate() {
    constexpr std::size_t kFrames = 256;
    SampleRateConverter converter(44'100, 48'000, kFrames);
    const auto input = stereoSignal(kFrames, 44'100);
    const std::size_t capacity = converter.diagnostics().maximumOutputFrames;
    std::vector<float> output(capacity * 2U, 0.0F);

    const std::uint64_t before = allocation_probe::count;
    allocation_probe::enabled = true;
    const auto result = converter.process(input.data(), kFrames, output.data(), capacity);
    const auto diagnostics = converter.diagnostics();
    converter.onDiscontinuity();
    allocation_probe::enabled = false;

    require(result.status == SampleRateProcessStatus::Ok, "allocation-test process");
    require(diagnostics.outputFramesProduced > 0, "allocation-test diagnostics");
    require(allocation_probe::count == before, "steady-state SRC operation allocated memory");
}

}  // namespace

int main() {
    try {
        bypassIsBitExactAndZeroLatency();
        commonAndExtremeRatePairsStayBounded();
        constructionFailureReleasesSpeexState();
        finiteResidualHeadroomSurvivesResampling();
        impulseChannelsRemainSampleAligned();
        allLanesAreFrameAndSampleIdentical();
        repeatedStereoResetMatchesFreshConstructionAtEverySupportedRatio();
        manyBlocksDoNotAccumulateSilentRatioDrift();
        seekResetReproducesTheInitialStreamExactly();
        nonFiniteInputIsSanitizedBeforeResampling();
        insufficientOutputCapacityIsBoundedAndRetryable();
        repeatedPartialCapacityRetriesMatchFullConversion();
        steadyStateOperationsDoNotAllocate();
        std::cout << "SampleRateConverter deterministic tests passed"
                  << " (steady-state allocations=" << allocation_probe::count
                  << ", 1000-block compensated drift=" << observedManyBlockDriftFrames
                  << " frame)\n";
        return 0;
    } catch (const std::exception &error) {
        allocation_probe::enabled = false;
        std::cerr << "SampleRateConverter test failure: " << error.what() << '\n';
        return 1;
    }
}
