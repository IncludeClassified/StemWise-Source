#include "AllocationProbeAttributes.h"
#include "TimePitchProcessor.h"
#include "TimePitchTransition.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <chrono>
#include <ctime>
#include <cstddef>
#include <cstdlib>
#include <cstdint>
#include <cstring>
#include <signalsmith-linear/stft.h>
#include <iostream>
#include <limits>
#include <new>
#include <stdexcept>
#include <string>
#include <vector>

namespace allocation_probe {
bool enabled = false;
std::uint64_t count = 0;
std::size_t liveBytes{}, peakBytes{};
struct alignas(std::max_align_t) Header { std::size_t size; bool tracked; };
}  // namespace allocation_probe

STEMWISE_ALLOCATION_HOOK void *operator new(std::size_t size) {
    using namespace allocation_probe;
    auto *header = static_cast<Header *>(std::malloc(sizeof(Header) + size));
    if (!header) throw std::bad_alloc();
    header->size = size; header->tracked = enabled;
    if (enabled) { ++count; liveBytes += size; peakBytes = std::max(peakBytes, liveBytes); }
    return header + 1;
}
STEMWISE_ALLOCATION_HOOK void *operator new[](std::size_t size) { return ::operator new(size); }
STEMWISE_ALLOCATION_HOOK void operator delete(void *memory) noexcept {
    if (!memory) return;
    auto *header = static_cast<allocation_probe::Header *>(memory) - 1;
    if (header->tracked) allocation_probe::liveBytes -= header->size;
    std::free(header);
}
STEMWISE_ALLOCATION_HOOK void operator delete[](void *memory) noexcept { ::operator delete(memory); }
STEMWISE_ALLOCATION_HOOK void operator delete(void *memory, std::size_t) noexcept { ::operator delete(memory); }
STEMWISE_ALLOCATION_HOOK void operator delete[](void *memory, std::size_t) noexcept { ::operator delete(memory); }

namespace {

using stemwise::audio::kStemwiseMusicChannelCount;
using stemwise::audio::kStemwiseMusicBusCount;
using stemwise::audio::engine::TimePitchPrimeStatus;
using stemwise::audio::engine::TimePitchProcessStatus;
using stemwise::audio::engine::TimePitchProcessor;
using stemwise::audio::engine::TimePitchTimelineState;
using stemwise::audio::engine::TimePitchTransition;
using stemwise::audio::engine::TimePitchTransitionStatus;

constexpr std::uint32_t kSampleRate = 48'000;
constexpr double kPi = 3.14159265358979323846;
std::size_t measuredLatestFirstAudibleFrame = 0;
float measuredTransitionBoundaryError = 0.0F;
float measuredMaximumAutomationJump = 0.0F;

void require(bool condition, const std::string &message) {
    if (!condition) throw std::runtime_error(message);
}

std::vector<float> stereoTone(
    std::size_t frames,
    std::uint64_t sourceStartFrame,
    double frequencyHz = 440.0,
    float amplitude = 0.55F) {
    std::vector<float> samples(frames * TimePitchProcessor::kChannelCount);
    for (std::size_t frame = 0; frame < frames; ++frame) {
        const double phase = 2.0 * kPi * frequencyHz *
            static_cast<double>(sourceStartFrame + frame) / kSampleRate;
        const float sample = static_cast<float>(amplitude * std::sin(phase));
        samples[frame * 2U] = sample;
        samples[frame * 2U + 1U] = sample;
    }
    return samples;
}

constexpr std::array<double, kStemwiseMusicBusCount> kMultibusToneFrequencies{
    317.0,
    521.0,
    631.0,
    733.0,
    839.0,
    947.0,
    1'051.0,
    1'159.0, 1'267.0, 1'373.0,
};

std::vector<float> multibusTones(
    std::size_t frames,
    std::uint64_t sourceStartFrame,
    float amplitude = 0.12F) {
    constexpr std::size_t kChannels = TimePitchProcessor::kMaximumChannelCount;
    std::vector<float> samples(frames * kChannels, 0.0F);
    for (std::size_t frame = 0; frame < frames; ++frame) {
        for (std::size_t bus = 0; bus < kMultibusToneFrequencies.size(); ++bus) {
            const double phase = 2.0 * kPi * kMultibusToneFrequencies[bus] *
                static_cast<double>(sourceStartFrame + frame) / kSampleRate;
            const float sample = static_cast<float>(amplitude * std::sin(phase));
            samples[frame * kChannels + bus * 2U] = sample;
            samples[frame * kChannels + bus * 2U + 1U] = sample;
        }
    }
    return samples;
}

double interleavedChannelRootMeanSquare(
    const std::vector<float> &samples,
    std::size_t channelCount,
    std::size_t channel,
    std::size_t startFrame) {
    const std::size_t frameCount = samples.size() / channelCount;
    double squareSum = 0.0;
    for (std::size_t frame = startFrame; frame < frameCount; ++frame) {
        const double sample = samples[frame * channelCount + channel];
        squareSum += sample * sample;
    }
    const std::size_t count = frameCount - startFrame;
    return count == 0 ? 0.0 : std::sqrt(squareSum / static_cast<double>(count));
}

double rootMeanSquare(const std::vector<float> &samples, std::size_t start = 0) {
    double squareSum = 0.0;
    for (std::size_t index = start; index < samples.size(); ++index) {
        const double sample = samples[index];
        squareSum += sample * sample;
    }
    const std::size_t count = samples.size() - start;
    return count == 0 ? 0.0 : std::sqrt(squareSum / static_cast<double>(count));
}

double toneMagnitude(
    const std::vector<float> &samples,
    std::size_t start,
    double frequencyHz) {
    double real = 0.0;
    double imaginary = 0.0;
    for (std::size_t index = start; index < samples.size(); ++index) {
        const double phase = 2.0 * kPi * frequencyHz *
            static_cast<double>(index - start) / kSampleRate;
        real += static_cast<double>(samples[index]) * std::cos(phase);
        imaginary -= static_cast<double>(samples[index]) * std::sin(phase);
    }
    const std::size_t count = samples.size() - start;
    return count == 0 ? 0.0 :
        2.0 * std::sqrt(real * real + imaginary * imaginary) /
            static_cast<double>(count);
}

std::size_t firstAudibleFrame(const std::vector<float> &interleaved, float threshold = 0.001F) {
    for (std::size_t frame = 0; frame < interleaved.size() / 2U; ++frame) {
        if (std::abs(interleaved[frame * 2U]) > threshold ||
            std::abs(interleaved[frame * 2U + 1U]) > threshold) {
            return frame;
        }
    }
    return std::numeric_limits<std::size_t>::max();
}

std::size_t primeTone(
    TimePitchProcessor &processor,
    std::uint64_t sourceStartFrame,
    double frequencyHz = 440.0) {
    const auto requirements = processor.primeRequirements();
    require(requirements.lookaheadInputFrames > 0, "prime lookahead is nonzero");
    auto lookahead = stereoTone(
        requirements.lookaheadInputFrames,
        sourceStartFrame,
        frequencyHz);
    const auto result = processor.prime(lookahead.data(), requirements.lookaheadInputFrames);
    require(result.status == TimePitchPrimeStatus::Ok, "prime status");
    require(
        result.lookaheadInputFramesConsumed == requirements.lookaheadInputFrames,
        "prime consumed exact lookahead");
    require(
        result.nextInputOffsetFrames == requirements.nextInputOffsetFrames,
        "prime next-input offset");
    return result.nextInputOffsetFrames;
}

float percussionSample(std::uint64_t sourceFrame) {
    constexpr std::uint64_t kPeriodFrames = kSampleRate / 2U;
    constexpr std::uint64_t kFirstTransientFrame = kSampleRate / 4U;
    if (sourceFrame < kFirstTransientFrame) return 0.0F;
    const std::uint64_t offset = (sourceFrame - kFirstTransientFrame) % kPeriodFrames;
    if (offset >= 144U) return 0.0F;
    const double time = static_cast<double>(offset) / kSampleRate;
    const double envelope = std::exp(-static_cast<double>(offset) / 28.0);
    const double body =
        0.62 * std::sin(2.0 * kPi * 173.0 * time) +
        0.28 * std::sin(2.0 * kPi * 2'731.0 * time) +
        (offset == 0 ? 0.9 : 0.0);
    return static_cast<float>(envelope * body);
}

std::vector<float> stereoPercussion(std::size_t frames, std::uint64_t sourceStartFrame) {
    std::vector<float> samples(frames * TimePitchProcessor::kChannelCount);
    for (std::size_t frame = 0; frame < frames; ++frame) {
        const float sample = percussionSample(sourceStartFrame + frame);
        samples[frame * 2U] = sample;
        samples[frame * 2U + 1U] = sample;
    }
    return samples;
}

struct TransientMetrics final {
    double coreEnergyFraction{0.0};
    double preEchoEnergyFraction{0.0};
    double postEchoEnergyFraction{0.0};
    std::size_t secondaryPeakCount{0};
};

float gatedChordSample(std::uint64_t sourceFrame) {
    constexpr std::uint64_t kPeriod = kSampleRate;
    const std::uint64_t phaseFrame = sourceFrame % kPeriod;
    if (phaseFrame < kSampleRate / 4U || phaseFrame >= kSampleRate / 2U) return 0.0F;
    const double time = static_cast<double>(sourceFrame) / kSampleRate;
    return static_cast<float>(
        0.22 * std::sin(2.0 * kPi * 220.0 * time) +
        0.18 * std::sin(2.0 * kPi * 277.182631 * time) +
        0.14 * std::sin(2.0 * kPi * 329.627557 * time) +
        0.10 * std::sin(2.0 * kPi * 659.255114 * time));
}

std::vector<float> stereoGatedChord(std::size_t frames, std::uint64_t sourceStartFrame) {
    std::vector<float> samples(frames * TimePitchProcessor::kChannelCount);
    for (std::size_t frame = 0; frame < frames; ++frame) {
        const float sample = gatedChordSample(sourceStartFrame + frame);
        samples[frame * 2U] = sample;
        samples[frame * 2U + 1U] = sample;
    }
    return samples;
}

double slowedChordReleaseTailRatio(float tempo) {
    constexpr std::size_t kBlockFrames = 256;
    constexpr std::size_t kOutputFrames = kSampleRate * 6U;
    TimePitchProcessor processor(kSampleRate, kBlockFrames);
    require(processor.setControls(tempo, 0.0F), "chord controls accepted");
    const auto requirements = processor.primeRequirements();
    auto lookahead = stereoGatedChord(requirements.lookaheadInputFrames, 0);
    require(
        processor.prime(lookahead.data(), requirements.lookaheadInputFrames).status ==
            TimePitchPrimeStatus::Ok,
        "chord prime status");
    std::uint64_t sourceFrame = requirements.nextInputOffsetFrames;
    std::vector<float> output;
    output.reserve(kOutputFrames);
    std::vector<float> blockOutput(kBlockFrames * 2U, 0.0F);
    for (std::size_t rendered = 0; rendered < kOutputFrames; rendered += kBlockFrames) {
        const std::size_t requested = std::min(kBlockFrames, kOutputFrames - rendered);
        const std::size_t required = processor.requiredInputFrames(requested);
        auto input = stereoGatedChord(required, sourceFrame);
        const auto result = processor.process(
            required == 0 ? nullptr : input.data(),
            required,
            blockOutput.data(),
            kBlockFrames,
            requested);
        require(result.status == TimePitchProcessStatus::Ok, "chord render status");
        sourceFrame += result.inputFramesConsumed;
        for (std::size_t frame = 0; frame < requested; ++frame) {
            output.push_back(blockOutput[frame * 2U]);
        }
    }

    double activeMeanSquare = 0.0;
    double tailMeanSquare = 0.0;
    std::size_t activeFrames = 0;
    std::size_t tailFrames = 0;
    for (std::uint64_t period = 0; period < 2U; ++period) {
        const double sourceBase = static_cast<double>(period * kSampleRate);
        const std::size_t activeStart = static_cast<std::size_t>(std::llround(
            (sourceBase + kSampleRate * 0.32) / tempo));
        const std::size_t activeEnd = static_cast<std::size_t>(std::llround(
            (sourceBase + kSampleRate * 0.47) / tempo));
        const std::size_t tailStart = static_cast<std::size_t>(std::llround(
            (sourceBase + kSampleRate * 0.52) / tempo));
        const std::size_t tailEnd = static_cast<std::size_t>(std::llround(
            (sourceBase + kSampleRate * 0.65) / tempo));
        if (tailEnd > output.size()) continue;
        for (std::size_t frame = activeStart; frame < activeEnd; ++frame) {
            activeMeanSquare += static_cast<double>(output[frame]) * output[frame];
            ++activeFrames;
        }
        for (std::size_t frame = tailStart; frame < tailEnd; ++frame) {
            tailMeanSquare += static_cast<double>(output[frame]) * output[frame];
            ++tailFrames;
        }
    }
    require(activeFrames != 0U && tailFrames != 0U, "chord windows measured");
    activeMeanSquare /= activeFrames;
    tailMeanSquare /= tailFrames;
    require(activeMeanSquare > 0.000001, "chord active interval audible");
    return tailMeanSquare / activeMeanSquare;
}

TransientMetrics slowedPercussionMetrics(float tempo) {
    constexpr std::size_t kBlockFrames = 256;
    constexpr std::size_t kOutputFrames = kSampleRate * 6U;
    TimePitchProcessor processor(kSampleRate, kBlockFrames);
    require(processor.setControls(tempo, 0.0F), "percussion controls accepted");
    const auto requirements = processor.primeRequirements();
    auto lookahead = stereoPercussion(requirements.lookaheadInputFrames, 0);
    require(
        processor.prime(lookahead.data(), requirements.lookaheadInputFrames).status ==
            TimePitchPrimeStatus::Ok,
        "percussion prime status");

    std::uint64_t sourceFrame = requirements.nextInputOffsetFrames;
    std::vector<float> output;
    output.reserve(kOutputFrames);
    std::vector<float> blockOutput(kBlockFrames * 2U, 0.0F);
    for (std::size_t rendered = 0; rendered < kOutputFrames; rendered += kBlockFrames) {
        const std::size_t requested = std::min(kBlockFrames, kOutputFrames - rendered);
        const std::size_t required = processor.requiredInputFrames(requested);
        auto input = stereoPercussion(required, sourceFrame);
        const auto result = processor.process(
            required == 0 ? nullptr : input.data(),
            required,
            blockOutput.data(),
            kBlockFrames,
            requested);
        require(result.status == TimePitchProcessStatus::Ok, "percussion render status");
        sourceFrame += result.inputFramesConsumed;
        for (std::size_t frame = 0; frame < requested; ++frame) {
            output.push_back(blockOutput[frame * 2U]);
        }
    }

    constexpr std::size_t kSearchRadius = kSampleRate / 10U;
    constexpr std::size_t kAnalysisRadius = kSampleRate / 10U;
    constexpr std::size_t kCoreRadius = kSampleRate / 200U;
    constexpr std::size_t kEchoGap = kSampleRate / 100U;
    constexpr std::size_t kEnvelopeRadius = 24U;
    constexpr std::size_t kMinimumPeakDistance = kSampleRate / 200U;
    double accumulatedCore = 0.0;
    double accumulatedPre = 0.0;
    double accumulatedPost = 0.0;
    std::size_t accumulatedSecondaryPeaks = 0;
    std::size_t measuredEvents = 0;
    constexpr std::uint64_t kFirstTransientFrame = kSampleRate / 4U;
    constexpr std::uint64_t kPeriodFrames = kSampleRate / 2U;
    for (std::uint64_t sourceEvent = kFirstTransientFrame;
         sourceEvent < static_cast<std::uint64_t>(kSampleRate) * 2U;
         sourceEvent += kPeriodFrames) {
        const std::size_t expected = static_cast<std::size_t>(
            std::llround(static_cast<double>(sourceEvent) / tempo));
        if (expected < kSearchRadius || expected + kSearchRadius >= output.size()) continue;
        const std::size_t searchStart = expected - kSearchRadius;
        const std::size_t searchEnd = expected + kSearchRadius;
        std::size_t peak = searchStart;
        for (std::size_t frame = searchStart + 1U; frame <= searchEnd; ++frame) {
            if (std::abs(output[frame]) > std::abs(output[peak])) peak = frame;
        }
        if (peak < kAnalysisRadius || peak + kAnalysisRadius >= output.size()) continue;

        const std::size_t analysisStart = peak - kAnalysisRadius;
        const std::size_t analysisEnd = peak + kAnalysisRadius;
        const std::size_t coreStart = peak - kCoreRadius;
        const std::size_t coreEnd = peak + kCoreRadius;
        double totalEnergy = 0.0;
        double coreEnergy = 0.0;
        double preEnergy = 0.0;
        double postEnergy = 0.0;
        for (std::size_t frame = analysisStart; frame <= analysisEnd; ++frame) {
            const double energy = static_cast<double>(output[frame]) * output[frame];
            totalEnergy += energy;
            if (frame >= coreStart && frame <= coreEnd) coreEnergy += energy;
            if (frame + kEchoGap < peak) preEnergy += energy;
            if (frame > peak + kEchoGap) postEnergy += energy;
        }
        require(totalEnergy > 0.000001, "percussion transient remains audible");
        accumulatedCore += coreEnergy / totalEnergy;
        accumulatedPre += preEnergy / totalEnergy;
        accumulatedPost += postEnergy / totalEnergy;

        std::vector<double> envelope(analysisEnd - analysisStart + 1U, 0.0);
        for (std::size_t frame = analysisStart; frame <= analysisEnd; ++frame) {
            const std::size_t local = frame - analysisStart;
            const std::size_t windowStart = frame > kEnvelopeRadius
                ? frame - kEnvelopeRadius
                : 0U;
            const std::size_t windowEnd = std::min(output.size() - 1U, frame + kEnvelopeRadius);
            double energy = 0.0;
            for (std::size_t index = windowStart; index <= windowEnd; ++index) {
                energy += static_cast<double>(output[index]) * output[index];
            }
            envelope[local] = energy;
        }
        const double peakEnvelope = *std::max_element(envelope.begin(), envelope.end());
        std::size_t lastPeak = 0;
        bool hasPeak = false;
        for (std::size_t index = 1; index + 1U < envelope.size(); ++index) {
            const std::size_t absoluteFrame = analysisStart + index;
            if (absoluteFrame >= peak - kEchoGap && absoluteFrame <= peak + kEchoGap) continue;
            if (envelope[index] < peakEnvelope * 0.12 ||
                envelope[index] < envelope[index - 1U] ||
                envelope[index] <= envelope[index + 1U]) {
                continue;
            }
            if (!hasPeak || index - lastPeak >= kMinimumPeakDistance) {
                ++accumulatedSecondaryPeaks;
                lastPeak = index;
                hasPeak = true;
            }
        }
        ++measuredEvents;
    }
    require(measuredEvents >= 1U, "enough percussion events measured");
    return {
        accumulatedCore / measuredEvents,
        accumulatedPre / measuredEvents,
        accumulatedPost / measuredEvents,
        accumulatedSecondaryPeaks,
    };
}

void directVirginBypassCopiesEveryBit() {
    constexpr std::size_t kFrames = 257;
    TimePitchProcessor processor(kSampleRate, 512);
    auto input = stereoTone(kFrames, 123);
    input[0] = -0.0F;
    std::vector<float> output(input.size(), 123.0F);

    const auto result = processor.process(
        input.data(), kFrames, output.data(), kFrames, kFrames);
    require(result.status == TimePitchProcessStatus::Ok, "virgin bypass status");
    require(
        std::memcmp(input.data(), output.data(), input.size() * sizeof(float)) == 0,
        "virgin bypass bit identity");
    const auto diagnostics = processor.diagnostics();
    require(diagnostics.exactBypass, "virgin bypass diagnostic");
    require(
        diagnostics.timelineState == TimePitchTimelineState::DirectVirgin,
        "virgin timeline state");
    require(!diagnostics.processedTimelineActivated, "virgin timeline never activated");
    require(diagnostics.activeInputLatencyFrames == 0, "virgin input latency");
    require(diagnostics.activeOutputLatencyFrames == 0, "virgin output latency");
    require(
        diagnostics.transposeTonalityLimitHz ==
            TimePitchProcessor::kTransposeTonalityLimitHz,
        "recommended transpose tonality limit configured");
    require(
        diagnostics.configuredAnalysisBlockFrames ==
            kSampleRate * TimePitchProcessor::kAnalysisBlockMilliseconds / 1'000U,
        "high-resolution analysis block configured");
    require(
        diagnostics.configuredAnalysisIntervalFrames ==
            kSampleRate * TimePitchProcessor::kAnalysisIntervalMilliseconds / 1'000U,
        "dense anti-reverb analysis interval configured");
}

void productTempoBoundaryClampsEveryDirectNativeRequest() {
    TimePitchProcessor processor(kSampleRate, 256U);
    require(!processor.setControls(0.499F, 0.0F), "below-product tempo is not exact");
    require(
        processor.diagnostics().tempo == TimePitchProcessor::kMinimumTempo,
        "below-product tempo clamps to 0.5x");
    require(!processor.setControls(2.001F, 0.0F), "above-product tempo is not exact");
    require(
        processor.diagnostics().tempo == TimePitchProcessor::kMaximumTempo,
        "above-product tempo clamps to 2.0x");
    require(
        processor.diagnostics().clampedControlUpdateCount == 2U,
        "both out-of-window controls are counted as clamped");
}

void exactLookaheadAndFirstPostPrimeAudioCoverControlRange() {
    constexpr std::size_t kFrames = 256;
    const std::array<std::pair<float, float>, 6> controls{{
        {0.5F, -12.0F},
        {0.9F, 0.0F},
        {1.0F, 12.0F},
        {1.0F, -12.0F},
        {2.0F, 0.0F},
        {2.0F, 12.0F},
    }};

    for (const auto &[tempo, pitch] : controls) {
        TimePitchProcessor processor(kSampleRate, kFrames);
        require(processor.setControls(tempo, pitch), "range controls accepted");
        const auto requirements = processor.primeRequirements();
        const auto configured = processor.diagnostics();
        const std::size_t expectedLookahead = configured.configuredInputLatencyFrames +
            static_cast<std::size_t>(
                tempo * static_cast<float>(configured.configuredOutputLatencyFrames));
        require(
            requirements.lookaheadInputFrames == expectedLookahead,
            "exact Signalsmith outputSeek lookahead");
        require(
            requirements.nextInputOffsetFrames == expectedLookahead,
            "exact next-source offset");

        std::vector<float> untouched(kFrames * 2U, 0.25F);
        const auto blocked = processor.process(
            nullptr, 0, untouched.data(), kFrames, kFrames);
        require(blocked.status == TimePitchProcessStatus::PrimeRequired, "cold render blocked");
        require(untouched[0] == 0.25F, "blocked render emits no synthetic silence");

        constexpr std::uint64_t kTransitionSourceFrame = 12'345;
        const std::size_t nextOffset = primeTone(
            processor,
            kTransitionSourceFrame,
            440.0);
        const std::size_t required = processor.requiredInputFrames(kFrames);
        auto input = stereoTone(
            required,
            kTransitionSourceFrame + nextOffset,
            440.0);
        std::vector<float> output(kFrames * 2U, 0.0F);
        const auto result = processor.process(
            required == 0 ? nullptr : input.data(),
            required,
            output.data(),
            kFrames,
            kFrames);
        require(result.status == TimePitchProcessStatus::Ok, "first primed render status");
        const std::size_t audible = firstAudibleFrame(output);
        require(audible != std::numeric_limits<std::size_t>::max(), "first primed block audible");
        require(audible <= 1, "first primed audio starts at transition boundary");
        measuredLatestFirstAudibleFrame = std::max(measuredLatestFirstAudibleFrame, audible);
        require(rootMeanSquare(output) > 0.005, "first primed block has useful energy");
        for (float sample : output) {
            require(std::isfinite(sample), "first primed sample finite");
            require(
                std::abs(sample) <= 4.0F,
                "first primed sample bounded");
        }
    }
}

void processedPitchSettlesAcrossTempoAndPitchLimits() {
    constexpr std::size_t kFrames = 256;
    struct Case final {
        float tempo;
        float pitch;
        double expectedFrequency;
        int blocks;
    };
    const std::array<Case, 7> cases{{
        {0.5F, 0.0F, 440.0, 260},
        {0.8F, 7.0F, 659.2551138257, 260},
        {0.9F, 0.0F, 440.0, 260},
        {1.0F, 12.0F, 880.0, 260},
        {1.0F, -12.0F, 220.0, 260},
        {2.0F, 0.0F, 440.0, 260},
        {2.0F, 12.0F, 880.0, 260},
    }};

    for (const Case &testCase : cases) {
        TimePitchProcessor processor(kSampleRate, kFrames);
        require(
            processor.setControls(testCase.tempo, testCase.pitch),
            "settling controls accepted");
        std::uint64_t sourceFrame = 4'321;
        sourceFrame += primeTone(processor, sourceFrame, 440.0);
        std::vector<float> output(kFrames * 2U, 0.0F);
        std::vector<float> observed;
        observed.reserve(static_cast<std::size_t>(testCase.blocks) * kFrames);

        for (int block = 0; block < testCase.blocks; ++block) {
            const std::size_t required = processor.requiredInputFrames(kFrames);
            auto input = stereoTone(required, sourceFrame, 440.0);
            const auto result = processor.process(
                required == 0 ? nullptr : input.data(),
                required,
                output.data(),
                kFrames,
                kFrames);
            require(result.status == TimePitchProcessStatus::Ok, "settling render status");
            sourceFrame += result.inputFramesConsumed;
            for (std::size_t frame = 0; frame < kFrames; ++frame) {
                observed.push_back(output[frame * 2U]);
            }
        }
        const std::size_t analysisFrames = std::min<std::size_t>(observed.size(), kSampleRate);
        const std::size_t analysisStart = observed.size() - analysisFrames;
        require(rootMeanSquare(observed, analysisStart) > 0.01, "settled output audible");
        require(
            toneMagnitude(observed, analysisStart, testCase.expectedFrequency) > 0.01,
            "settled output pitch correct");
    }
}

void rapidTempoAndPitchAutomationStaysContinuous() {
    constexpr std::size_t kFrames = 256;
    constexpr std::uint64_t kStartFrame = 19'000;
    TimePitchProcessor processor(kSampleRate, kFrames);
    require(processor.setControls(0.8F, 0.0F), "automation initial controls accepted");
    std::uint64_t sourceFrame = kStartFrame + primeTone(processor, kStartFrame, 523.25);
    std::vector<float> output(kFrames * 2U, 0.0F);
    float previousLeft = 0.0F;
    bool hasPrevious = false;
    const std::array<std::pair<float, float>, 7> controls{{
        {0.8F, 0.0F},
        {0.5F, 0.0F},
        {0.9F, 7.0F},
        {0.5F, -5.0F},
        {2.0F, 0.0F},
        {2.0F, -12.0F},
        {1.0F, 0.0F},
    }};
    for (const auto &[tempo, pitch] : controls) {
        require(processor.setControls(tempo, pitch), "automation controls accepted");
        for (int block = 0; block < 12; ++block) {
            const std::size_t required = processor.requiredInputFrames(kFrames);
            auto input = stereoTone(required, sourceFrame, 523.25);
            const auto result = processor.process(
                required == 0 ? nullptr : input.data(),
                required,
                output.data(),
                kFrames,
                kFrames);
            require(result.status == TimePitchProcessStatus::Ok, "automation render status");
            sourceFrame += result.inputFramesConsumed;
            for (std::size_t frame = 0; frame < kFrames; ++frame) {
                const float left = output[frame * 2U];
                if (hasPrevious) {
                    measuredMaximumAutomationJump = std::max(
                        measuredMaximumAutomationJump,
                        std::abs(left - previousLeft));
                }
                previousLeft = left;
                hasPrevious = true;
            }
        }
    }
    const auto diagnostics = processor.diagnostics();
    require(
        measuredMaximumAutomationJump < 0.35F,
        "rapid tempo/pitch automation introduced an audible discontinuity");
    require(diagnostics.nonFiniteOutputSampleCount == 0, "automation output remains finite");
}

void crossfadeIsConstantSumAndBoundaryContinuous() {
    constexpr std::size_t kFrames = 256;
    constexpr std::size_t kFadeFrames = 512;
    TimePitchTransition transition(kFrames, kFadeFrames);

    std::vector<float> direct(kFrames * 2U, 0.25F);
    std::vector<float> processed(kFrames * 2U, -0.5F);
    std::vector<float> output(kFrames * 2U, 0.0F);
    transition.begin();
    for (int block = 0; block < 2; ++block) {
        const auto result = transition.mix(
            direct.data(), processed.data(), output.data(), kFrames, kFrames);
        require(result.status == TimePitchTransitionStatus::Ok, "constant-sum mix status");
        for (std::size_t frame = 0; frame < kFrames; ++frame) {
            const std::size_t cursor = static_cast<std::size_t>(block) * kFrames + frame;
            const double progress = static_cast<double>(cursor) /
                static_cast<double>(kFadeFrames - 1U);
            const float processedWeight = cursor == 0
                ? 0.0F
                : cursor + 1U == kFadeFrames
                    ? 1.0F
                    : static_cast<float>(0.5 - 0.5 * std::cos(kPi * progress));
            const float expected = 0.25F * (1.0F - processedWeight) - 0.5F * processedWeight;
            require(
                std::abs(output[frame * 2U] - expected) < 0.000001F,
                "raised-cosine constant-sum ramp");
        }
    }
    require(transition.diagnostics().firstOutputDirectError == 0.0F, "first sample direct exact");
    require(!transition.diagnostics().active, "constant-sum transition completes");

    TimePitchTransition endpointTransition(kFadeFrames, kFadeFrames);
    std::vector<float> silence(kFadeFrames * 2U, 0.0F);
    std::vector<float> unity(kFadeFrames * 2U, 1.0F);
    std::vector<float> weights(kFadeFrames * 2U, 0.0F);
    endpointTransition.begin();
    require(
        endpointTransition.mix(
            silence.data(), unity.data(), weights.data(), kFadeFrames, kFadeFrames).status ==
            TimePitchTransitionStatus::Ok,
        "endpoint-smooth transition render");
    const float firstStep = weights[2U] - weights[0U];
    const std::size_t midpointFrame = kFadeFrames / 2U;
    const float midpointStep = weights[midpointFrame * 2U] -
        weights[(midpointFrame - 1U) * 2U];
    const float finalStep = weights[(kFadeFrames - 1U) * 2U] -
        weights[(kFadeFrames - 2U) * 2U];
    require(weights[0] == 0.0F, "raised-cosine first endpoint exact");
    require(weights[(kFadeFrames - 1U) * 2U] == 1.0F, "raised-cosine final endpoint exact");
    require(firstStep > 0.0F && finalStep > 0.0F, "raised-cosine endpoints advance");
    require(firstStep < midpointStep * 0.01F, "raised-cosine direct endpoint slope is smooth");
    require(finalStep < midpointStep * 0.01F, "raised-cosine processed endpoint slope is smooth");

    TimePitchProcessor processor(kSampleRate, kFrames);
    require(processor.setControls(1.0F, 7.0F), "transition controls accepted");
    constexpr std::uint64_t kTransitionFrame = 10'000;
    const std::size_t nextOffset = primeTone(processor, kTransitionFrame, 440.0);
    auto processedInput = stereoTone(kFrames, kTransitionFrame + nextOffset, 440.0);
    std::vector<float> processedOutput(kFrames * 2U, 0.0F);
    const auto render = processor.process(
        processedInput.data(), kFrames, processedOutput.data(), kFrames, kFrames);
    require(render.status == TimePitchProcessStatus::Ok, "transition processed render");
    auto alignedDirect = stereoTone(kFrames, kTransitionFrame, 440.0);
    transition.reset();
    transition.begin();
    const auto mixed = transition.mix(
        alignedDirect.data(), processedOutput.data(), output.data(), kFrames, kFrames);
    require(mixed.status == TimePitchTransitionStatus::Ok, "audible transition mix");
    measuredTransitionBoundaryError = std::max(
        std::abs(output[0] - alignedDirect[0]),
        std::abs(output[1] - alignedDirect[1]));
    require(measuredTransitionBoundaryError == 0.0F, "transition boundary sample exact");
    require(rootMeanSquare(output) > 0.01, "transition contains no silence gap");
}

void neutralControlsRetainTheProcessedTimeline() {
    constexpr std::size_t kFrames = 256;
    TimePitchProcessor processor(kSampleRate, kFrames);
    require(processor.setControls(1.0F, 7.0F), "activation controls accepted");
    std::uint64_t sourceFrame = 2'000;
    sourceFrame += primeTone(processor, sourceFrame);
    auto input = stereoTone(kFrames, sourceFrame);
    std::vector<float> output(kFrames * 2U, 0.0F);
    require(
        processor.process(input.data(), kFrames, output.data(), kFrames, kFrames).status ==
            TimePitchProcessStatus::Ok,
        "activated render");

    require(processor.setControls(1.0F, 0.0F), "neutral controls accepted");
    const auto diagnostics = processor.diagnostics();
    require(!diagnostics.exactBypass, "neutral never returns to direct bypass");
    require(diagnostics.processedTimelineActivated, "processed ownership retained");
    require(
        diagnostics.timelineState == TimePitchTimelineState::Processed,
        "neutral processed state");
    const auto neutral = processor.process(
        input.data(), kFrames, output.data(), kFrames, kFrames);
    require(neutral.status == TimePitchProcessStatus::Ok, "neutral processed render");
}

void seekRequiresPrimeAndReplaysDeterministically() {
    constexpr std::size_t kFrames = 256;
    TimePitchProcessor processor(kSampleRate, kFrames);
    require(processor.setControls(0.75F, -5.0F), "seek controls accepted");
    constexpr std::uint64_t kSeekFrame = 25'000;
    const std::size_t nextOffset = primeTone(processor, kSeekFrame);
    const std::size_t required = processor.requiredInputFrames(kFrames);
    auto input = stereoTone(required, kSeekFrame + nextOffset);
    std::vector<float> first(kFrames * 2U, 0.0F);
    require(
        processor.process(input.data(), required, first.data(), kFrames, kFrames).status ==
            TimePitchProcessStatus::Ok,
        "first seek render");

    const auto beforeSeek = processor.diagnostics();
    processor.onSeek();
    require(
        processor.diagnostics().timelineState == TimePitchTimelineState::PrimeRequired,
        "seek requires re-prime");
    std::vector<float> replay(kFrames * 2U, 0.0F);
    require(
        processor.process(input.data(), required, replay.data(), kFrames, kFrames).status ==
            TimePitchProcessStatus::PrimeRequired,
        "seek cannot emit cold processed samples");
    require(primeTone(processor, kSeekFrame) == nextOffset, "seek lookahead reproducible");
    require(
        processor.process(input.data(), required, replay.data(), kFrames, kFrames).status ==
            TimePitchProcessStatus::Ok,
        "replay seek render");
    require(
        std::memcmp(first.data(), replay.data(), first.size() * sizeof(float)) == 0,
        "seek replay sample deterministic");
    const auto afterSeek = processor.diagnostics();
    require(afterSeek.generation == beforeSeek.generation + 1, "seek generation");
    require(afterSeek.seekCount == beforeSeek.seekCount + 1, "seek counter");
}

void nonFiniteInputAndBoundedErrorsAreHandled() {
    constexpr std::size_t kFrames = 128;
    TimePitchProcessor processor(kSampleRate, kFrames);
    require(processor.setControls(1.0F, 4.0F), "sanitization controls accepted");
    const auto requirements = processor.primeRequirements();
    auto lookahead = stereoTone(requirements.lookaheadInputFrames, 100);
    lookahead[3] = std::numeric_limits<float>::quiet_NaN();
    require(
        processor.prime(lookahead.data(), requirements.lookaheadInputFrames).status ==
            TimePitchPrimeStatus::Ok,
        "sanitized prime");
    auto input = stereoTone(kFrames, 100 + requirements.nextInputOffsetFrames);
    input[9] = std::numeric_limits<float>::infinity();
    std::vector<float> output(kFrames * 2U, 0.0F);
    require(
        processor.process(input.data(), kFrames, output.data(), kFrames, kFrames).status ==
            TimePitchProcessStatus::Ok,
        "sanitized render");
    for (float sample : output) require(std::isfinite(sample), "sanitized output finite");
    require(processor.diagnostics().nonFiniteInputSampleCount == 2, "input sanitation counter");

    std::vector<float> untouched(output.size(), 0.25F);
    const auto oversized = processor.process(
        input.data(), kFrames, untouched.data(), kFrames, kFrames + 1U);
    require(
        oversized.status == TimePitchProcessStatus::OutputCapacityExceeded,
        "oversized render rejected");
    require(untouched[0] == 0.25F, "rejected render leaves output untouched");
}

void extremeFiniteInputIsRejectedBeforeSpectralProcessing() {
    for (float amplitude : {1.0e14F, 1.0e15F, std::numeric_limits<float>::max()}) {
        TimePitchProcessor processor(kSampleRate, 480U);
        require(processor.setControls(0.75F, 3.0F), "extreme input controls");
        const auto required = processor.primeRequirements();
        auto input = stereoTone(required.lookaheadInputFrames, 0U, 12'000.0, amplitude);
        require(processor.prime(input.data(), required.lookaheadInputFrames).status ==
            TimePitchPrimeStatus::ProcessingFailure, "finite spectral overflow must reject prime");
        require(processor.primeRequirements().lookaheadInputFrames == required.lookaheadInputFrames,
            "rejected prime must preserve the cold timeline");
    }
}

void spectralAmplitudeBoundaryPreservesFailureStateAndNeutralBits() {
    constexpr auto limit = TimePitchProcessor::kMaximumSpectralInputMagnitude;
    TimePitchProcessor processor(kSampleRate, 480U);
    require(processor.setControls(0.75F, 3.0F), "domain boundary controls");
    const auto requirements = processor.primeRequirements();
    std::vector<float> head(requirements.lookaheadInputFrames * 2U, limit);
    require(processor.prime(head.data(), requirements.lookaheadInputFrames).status == TimePitchPrimeStatus::Ok,
        "documented spectral boundary must remain supported");
    const auto frames = processor.requiredInputFrames(480U);
    std::vector<float> input(frames * 2U, std::nextafter(limit, std::numeric_limits<float>::infinity()));
    std::vector<float> output(960U, 0.25F);
    const auto before = processor.diagnostics();
    require(processor.process(input.data(), frames, output.data(), 480U, 480U).status ==
        TimePitchProcessStatus::ProcessingFailure, "above-domain spectral process must reject");
    const auto after = processor.diagnostics();
    require(output == std::vector<float>(960U, 0.25F) && before.inputFramesConsumed == after.inputFramesConsumed &&
        before.outputFramesProduced == after.outputFramesProduced, "rejection must preserve output and timeline");
    TimePitchProcessor direct(kSampleRate, 480U);
    input.assign(960U, std::numeric_limits<float>::max());
    require(direct.process(input.data(), 480U, output.data(), 480U, 480U).status == TimePitchProcessStatus::Ok &&
        input == output, "neutral direct PCM must retain all finite bits");
}

void finiteResidualHeadroomSurvivesProcessedPitch() {
    TimePitchTransition transition(64U, 32U);
    transition.begin();
    std::vector<float> direct(128U, 17.0F), processed(128U, 17.0F), mixed(128U);
    require(transition.mix(direct.data(), processed.data(), mixed.data(), 64U, 64U).status ==
        TimePitchTransitionStatus::Ok, "residual headroom transition");
    require(std::all_of(mixed.begin(), mixed.end(), [](float value) { return std::abs(value - 17.0F) < 0.00001F; }),
        "transition clipped a finite residual before mixing");
    constexpr std::size_t frames = 256U;
    TimePitchProcessor quiet(kSampleRate, frames), loud(kSampleRate, frames);
    require(quiet.setControls(1.25F, 3.0F) && loud.setControls(1.25F, 3.0F),
        "residual headroom controls");
    const auto prime = quiet.primeRequirements();
    auto quietHead = stereoTone(prime.lookaheadInputFrames, 0U, 631.0, 0.1F);
    auto loudHead = stereoTone(prime.lookaheadInputFrames, 0U, 631.0, 8.0F);
    require(quiet.prime(quietHead.data(), prime.lookaheadInputFrames).status == TimePitchPrimeStatus::Ok &&
        loud.prime(loudHead.data(), prime.lookaheadInputFrames).status == TimePitchPrimeStatus::Ok,
        "residual headroom primed");
    std::uint64_t source = prime.nextInputOffsetFrames;
    std::vector<float> quietOutput(frames * 2U), loudOutput(frames * 2U);
    float peak = 0.0F;
    float maximumError = 0.0F;
    for (int block = 0; block < 20; ++block) {
        const auto required = quiet.requiredInputFrames(frames);
        auto quietInput = stereoTone(required, source, 631.0, 0.1F);
        auto loudInput = stereoTone(required, source, 631.0, 8.0F);
        require(quiet.process(quietInput.data(), required, quietOutput.data(), frames, frames).status ==
            TimePitchProcessStatus::Ok && loud.process(loudInput.data(), required, loudOutput.data(), frames,
            frames).status == TimePitchProcessStatus::Ok, "residual headroom rendered");
        source += required;
        for (std::size_t index = 0; index < loudOutput.size(); ++index) {
            require(std::isfinite(loudOutput[index]), "finite residual became nonfinite");
            maximumError = std::max(maximumError,
                std::abs(loudOutput[index] - quietOutput[index] * 80.0F));
            peak = std::max(peak, std::abs(loudOutput[index]));
        }
    }
    // Spectral phase decisions have small level-dependent rounding; a four-unit clamp loses
    // several whole sample units here, while the unchanged DSP stays within one percent.
    require(maximumError < peak * 0.01F, "finite residual scale mismatch: " + std::to_string(maximumError) +
        ", peak " + std::to_string(peak));
    require(peak > 4.5F,
        "residual headroom never passed the obsolete four-unit limit");
}

void primeControlRenderResetAndTransitionDoNotAllocate() {
    constexpr std::size_t kFrames = 256;
    TimePitchProcessor processor(kSampleRate, kFrames);
    TimePitchTransition transition(kFrames, 512);

    require(processor.setControls(1.0F, 4.0F), "allocation sizing controls");
    const auto requirements = processor.primeRequirements();
    auto lookahead = stereoTone(requirements.lookaheadInputFrames, 5'000);
    require(processor.setControls(1.0F, 0.0F), "allocation setup returns virgin");
    auto input = stereoTone(kFrames, 5'000 + requirements.nextInputOffsetFrames);
    auto direct = stereoTone(kFrames, 5'000);
    std::vector<float> processed(kFrames * 2U, 0.0F);
    std::vector<float> output(kFrames * 2U, 0.0F);

    const std::uint64_t before = allocation_probe::count;
    allocation_probe::enabled = true;
    const bool controlsAccepted = processor.setControls(1.0F, 4.0F);
    const auto primeResult = processor.prime(lookahead.data(), requirements.lookaheadInputFrames);
    const auto processResult = processor.process(
        input.data(), kFrames, processed.data(), kFrames, kFrames);
    transition.begin();
    const auto transitionResult = transition.mix(
        direct.data(), processed.data(), output.data(), kFrames, kFrames);
    const bool automationAccepted = processor.setControls(0.9F, -2.0F);
    processor.onDiscontinuity();
    const auto diagnostics = processor.diagnostics();
    allocation_probe::enabled = false;

    require(controlsAccepted && automationAccepted, "allocation controls accepted");
    require(primeResult.status == TimePitchPrimeStatus::Ok, "allocation prime status");
    require(processResult.status == TimePitchProcessStatus::Ok, "allocation render status");
    require(
        transitionResult.status == TimePitchTransitionStatus::Ok,
        "allocation transition status");
    require(diagnostics.timelineState == TimePitchTimelineState::PrimeRequired, "reset state");
    require(allocation_probe::count == before, "steady transition path allocated memory");
}

void maximumChannelTransitionPreservesEveryBusAcrossIrregularBlocks() {
    constexpr std::size_t kChannels = TimePitchTransition::kMaximumChannelCount;
    constexpr std::size_t kMaximumBlockFrames = 64U;
    constexpr std::size_t kCrossfadeFrames = 173U;
    constexpr std::size_t kTotalFrames = 257U;
    constexpr std::array<std::size_t, 6U> kBlockFrames{37U, 64U, 11U, 59U, 64U, 22U};
    static_assert(
        37U + 64U + 11U + 59U + 64U + 22U == kTotalFrames,
        "irregular transition blocks must cover the fixture");

    std::vector<float> direct(kTotalFrames * kChannels, 0.0F);
    std::vector<float> processed(kTotalFrames * kChannels, 0.0F);
    std::vector<float> output(kTotalFrames * kChannels, 0.0F);
    for (std::size_t frame = 0U; frame < kTotalFrames; ++frame) {
        for (std::size_t channel = 0U; channel < kChannels; ++channel) {
            direct[frame * kChannels + channel] = static_cast<float>(
                0.008 * static_cast<double>(channel + 1U) +
                0.00003 * static_cast<double>(frame));
            processed[frame * kChannels + channel] = static_cast<float>(
                -0.011 * static_cast<double>(channel + 1U) +
                0.00002 * static_cast<double>(frame));
        }
    }

    TimePitchTransition transition(kMaximumBlockFrames, kCrossfadeFrames, kChannels);
    transition.begin();
    std::size_t firstFrame = 0U;
    for (const std::size_t frames : kBlockFrames) {
        const auto result = transition.mix(
            direct.data() + firstFrame * kChannels,
            processed.data() + firstFrame * kChannels,
            output.data() + firstFrame * kChannels,
            frames,
            frames);
        require(result.status == TimePitchTransitionStatus::Ok, "maximum-width transition status");
        require(result.framesMixed == frames, "maximum-width transition exact block quantum");
        firstFrame += frames;
    }
    require(firstFrame == kTotalFrames, "maximum-width transition fixture coverage");

    for (std::size_t frame = 0U; frame < kTotalFrames; ++frame) {
        const float processedWeight = frame == 0U
            ? 0.0F
            : frame + 1U >= kCrossfadeFrames
                ? 1.0F
                : static_cast<float>(
                    0.5 - 0.5 * std::cos(
                        kPi * static_cast<double>(frame) /
                        static_cast<double>(kCrossfadeFrames - 1U)));
        for (std::size_t channel = 0U; channel < kChannels; ++channel) {
            const std::size_t index = frame * kChannels + channel;
            const float expected = direct[index] * (1.0F - processedWeight) +
                processed[index] * processedWeight;
            require(
                std::abs(output[index] - expected) <= 1.0e-6F,
                "maximum-width transition mixed, shifted, or dropped a bus");
        }
    }
    require(
        std::memcmp(direct.data(), output.data(), kChannels * sizeof(float)) == 0,
        "maximum-width transition first frame was not exact direct PCM");
    require(
        std::memcmp(
            processed.data() + (kCrossfadeFrames - 1U) * kChannels,
            output.data() + (kCrossfadeFrames - 1U) * kChannels,
            kChannels * sizeof(float)) == 0,
        "maximum-width transition endpoint was not exact processed PCM");
    require(!transition.diagnostics().active, "maximum-width transition did not complete");
    require(transition.diagnostics().completedCount == 1U, "maximum-width completion count");
    require(transition.diagnostics().firstOutputDirectError == 0.0F, "maximum-width first error");
    // The render pipeline reuses its processed block as the transition destination.
    auto inPlace = processed;
    TimePitchTransition inPlaceTransition(kMaximumBlockFrames, kCrossfadeFrames, kChannels);
    inPlaceTransition.begin();
    firstFrame = 0U;
    for (const std::size_t frames : kBlockFrames) {
        auto *window = inPlace.data() + firstFrame * kChannels;
        const auto result = inPlaceTransition.mix(
            direct.data() + firstFrame * kChannels, window, window, frames, frames);
        require(result.status == TimePitchTransitionStatus::Ok, "in-place transition status");
        firstFrame += frames;
    }
    require(std::memcmp(inPlace.data(), output.data(), output.size() * sizeof(float)) == 0,
            "in-place transition changed multibus PCM");

    TimePitchTransition allocationTransition(
        kMaximumBlockFrames, kCrossfadeFrames, kChannels);
    allocationTransition.begin();
    bool allocationRenderSucceeded = true;
    firstFrame = 0U;
    const std::uint64_t allocationBefore = allocation_probe::count;
    allocation_probe::enabled = true;
    for (const std::size_t frames : kBlockFrames) {
        const auto result = allocationTransition.mix(
            direct.data() + firstFrame * kChannels,
            processed.data() + firstFrame * kChannels,
            output.data() + firstFrame * kChannels,
            frames,
            frames);
        allocationRenderSucceeded = allocationRenderSucceeded &&
            result.status == TimePitchTransitionStatus::Ok &&
            result.framesMixed == frames;
        firstFrame += frames;
    }
    allocation_probe::enabled = false;
    require(allocationRenderSucceeded, "maximum-width allocation-probed transition status");
    require(
        allocation_probe::count == allocationBefore,
        "maximum-width transition allocated during steady mixing");
}

void maximumChannelBypassAndTunedIsolationRemainSynchronized() {
    constexpr std::size_t kChannels = TimePitchProcessor::kMaximumChannelCount;
    constexpr std::size_t kBlockFrames = 256U;
    constexpr std::size_t kOutputFrames = kSampleRate * 3U;

    TimePitchProcessor direct(kSampleRate, kBlockFrames, kChannels);
    require(direct.channelCount() == kChannels, "multibus configured channel count");
    auto directInput = multibusTones(kBlockFrames, 91U);
    std::vector<float> directOutput(directInput.size(), 0.0F);
    const auto directResult = direct.process(
        directInput.data(),
        kBlockFrames,
        directOutput.data(),
        kBlockFrames,
        kBlockFrames);
    require(directResult.status == TimePitchProcessStatus::Ok, "multibus bypass status");
    require(
        std::memcmp(
            directInput.data(),
            directOutput.data(),
            directInput.size() * sizeof(float)) == 0,
        "multibus neutral bypass changed a sample bit");

    TimePitchProcessor tuned(kSampleRate, kBlockFrames, kChannels);
    require(tuned.setControls(1.25F, 3.0F), "multibus tune controls accepted");
    const auto requirements = tuned.primeRequirements();
    auto lookahead = multibusTones(requirements.lookaheadInputFrames, 0U);
    const auto primeResult = tuned.prime(
        lookahead.data(), requirements.lookaheadInputFrames);
    require(primeResult.status == TimePitchPrimeStatus::Ok, "multibus prime status");

    std::uint64_t sourceFrame = primeResult.nextInputOffsetFrames;
    std::vector<float> rendered(kOutputFrames * kChannels, 0.0F);
    std::vector<float> blockOutput(kBlockFrames * kChannels, 0.0F);
    for (std::size_t outputFrame = 0; outputFrame < kOutputFrames;) {
        const std::size_t requested = std::min(kBlockFrames, kOutputFrames - outputFrame);
        const std::size_t required = tuned.requiredInputFrames(requested);
        auto input = multibusTones(required, sourceFrame);
        const auto result = tuned.process(
            required == 0U ? nullptr : input.data(),
            required,
            blockOutput.data(),
            kBlockFrames,
            requested);
        require(result.status == TimePitchProcessStatus::Ok, "multibus tuned render status");
        require(result.outputFramesProduced == requested, "multibus exact output quantum");
        std::memcpy(
            rendered.data() + outputFrame * kChannels,
            blockOutput.data(),
            requested * kChannels * sizeof(float));
        sourceFrame += result.inputFramesConsumed;
        outputFrame += requested;
    }

    const std::size_t analysisStart = kSampleRate;
    for (std::size_t bus = 0; bus < kMultibusToneFrequencies.size(); ++bus) {
        const std::size_t leftChannel = bus * 2U;
        const std::size_t rightChannel = leftChannel + 1U;
        const double leftRms = interleavedChannelRootMeanSquare(
            rendered, kChannels, leftChannel, analysisStart);
        const double rightRms = interleavedChannelRootMeanSquare(
            rendered, kChannels, rightChannel, analysisStart);
        require(leftRms >= 0.06, "multibus tuned lane lost its authored energy");
        require(
            std::abs(leftRms - rightRms) <= 1.0e-6,
            "multibus tuned stereo pair lost channel alignment");
        float maximumStereoDifference = 0.0F;
        for (std::size_t frame = analysisStart; frame < kOutputFrames; ++frame) {
            maximumStereoDifference = std::max(
                maximumStereoDifference,
                std::abs(
                    rendered[frame * kChannels + leftChannel] -
                    rendered[frame * kChannels + rightChannel]));
        }
        require(
            maximumStereoDifference <= 1.0e-4F,
            "multibus tuned stereo pair lost sample alignment");
    }

    // A shared maximum-width analysis must synchronize lanes without numerically mixing them.
    // Exercise the processed path with exactly one authored channel and demand silence in all
    // other output channels after the prime history has settled.
    TimePitchProcessor isolated(kSampleRate, kBlockFrames, kChannels);
    require(isolated.setControls(1.25F, 3.0F), "isolated multibus controls accepted");
    const auto isolatedRequirements = isolated.primeRequirements();
    constexpr std::size_t kAuthoredChannel = 8U;
    const auto isolatedInput = [](std::size_t frames, std::uint64_t start) {
        std::vector<float> samples(frames * kChannels, 0.0F);
        for (std::size_t frame = 0; frame < frames; ++frame) {
            const double phase = 2.0 * kPi * 733.0 *
                static_cast<double>(start + frame) / kSampleRate;
            samples[frame * kChannels + kAuthoredChannel] =
                static_cast<float>(0.12 * std::sin(phase));
        }
        return samples;
    };
    auto isolatedLookahead = isolatedInput(isolatedRequirements.lookaheadInputFrames, 0U);
    const auto isolatedPrime = isolated.prime(
        isolatedLookahead.data(), isolatedRequirements.lookaheadInputFrames);
    require(isolatedPrime.status == TimePitchPrimeStatus::Ok, "isolated multibus prime");
    std::uint64_t isolatedSource = isolatedPrime.nextInputOffsetFrames;
    std::vector<float> isolatedRendered(kSampleRate * kChannels, 0.0F);
    for (std::size_t outputFrame = 0; outputFrame < kSampleRate;) {
        const std::size_t requested = std::min(kBlockFrames, kSampleRate - outputFrame);
        const std::size_t inputFrames = isolated.requiredInputFrames(requested);
        auto input = isolatedInput(inputFrames, isolatedSource);
        const auto result = isolated.process(
            inputFrames == 0U ? nullptr : input.data(),
            inputFrames,
            blockOutput.data(),
            kBlockFrames,
            requested);
        require(result.status == TimePitchProcessStatus::Ok, "isolated multibus render");
        std::memcpy(
            isolatedRendered.data() + outputFrame * kChannels,
            blockOutput.data(),
            requested * kChannels * sizeof(float));
        isolatedSource += result.inputFramesConsumed;
        outputFrame += requested;
    }
    require(
        interleavedChannelRootMeanSquare(
            isolatedRendered, kChannels, kAuthoredChannel, kSampleRate / 2U) >= 0.05,
        "isolated authored multibus channel became silent");
    for (std::size_t channel = 0; channel < kChannels; ++channel) {
        if (channel == kAuthoredChannel) continue;
        require(
            interleavedChannelRootMeanSquare(
                isolatedRendered, kChannels, channel, kSampleRate / 2U) <= 1.0e-7,
            "multibus tuned processing leaked into an unauthored channel");
    }

    const std::size_t required = tuned.requiredInputFrames(kBlockFrames);
    auto allocationInput = multibusTones(required, sourceFrame);
    const std::uint64_t allocationBefore = allocation_probe::count;
    allocation_probe::enabled = true;
    const auto allocationResult = tuned.process(
        allocationInput.data(),
        required,
        blockOutput.data(),
        kBlockFrames,
        kBlockFrames);
    allocation_probe::enabled = false;
    require(allocationResult.status == TimePitchProcessStatus::Ok, "multibus allocation render");
    require(
        allocation_probe::count == allocationBefore,
        "multibus steady process allocated memory");
}

void activeChannelCountUsesFixedStrideWithoutLeakingInactivePcm() {
    constexpr std::size_t stride = kStemwiseMusicChannelCount;
    constexpr std::size_t frames = 64U;
    std::array<float, frames * 2U * stride> input{};
    std::array<float, frames * stride> output{};
    for (const std::size_t partCount : {0U, 2U, 6U, 9U, 0U}) {
        const std::size_t activeChannels = (partCount + 1U) * 2U;
        TimePitchProcessor processor(kSampleRate, frames, activeChannels, stride);
        require(processor.channelCount() == activeChannels, "dynamic spectral width incorrect");
        for (std::size_t frame = 0U; frame < frames; ++frame) {
            for (std::size_t channel = 0U; channel < stride; ++channel) {
                input[frame * stride + channel] = channel < activeChannels
                    ? static_cast<float>(channel + 1U) * 0.005F + static_cast<float>(frame) * 0.00001F
                    : 0.9F;
            }
        }
        const auto allocationsBefore = allocation_probe::count;
        allocation_probe::enabled = true;
        const auto result = processor.process(input.data(), frames, output.data(), frames, frames);
        allocation_probe::enabled = false;
        require(result.status == TimePitchProcessStatus::Ok && result.outputFramesProduced == frames,
            "dynamic-stride direct render failed");
        require(allocation_probe::count == allocationsBefore, "dynamic-stride render allocated");
        for (std::size_t frame = 0U; frame < frames; ++frame) {
            require(std::memcmp(input.data() + frame * stride, output.data() + frame * stride,
                activeChannels * sizeof(float)) == 0, "dynamic-stride active PCM changed");
            for (std::size_t channel = activeChannels; channel < stride; ++channel) {
                require(output[frame * stride + channel] == 0.0F, "inactive spectral channel leaked");
            }
        }
        TimePitchProcessor cold(kSampleRate, frames, activeChannels, stride);
        require(cold.setControls(2.0F, 12.0F), "cold dynamic spectral controls");
        const auto requirements = cold.primeRequirements();
        std::vector<float> lookahead(requirements.lookaheadInputFrames * stride, 0.0F);
        for (std::size_t frame = 0U; frame < requirements.lookaheadInputFrames; ++frame) {
            for (std::size_t channel = 0U; channel < activeChannels; ++channel) {
                lookahead[frame * stride + channel] = static_cast<float>(
                    .1 * std::sin(2.0 * kPi * (220.0 + channel * 31.0) * frame / kSampleRate));
            }
        }
        const auto coldAllocationsBefore = allocation_probe::count;
        allocation_probe::enabled = true;
        const auto prime = cold.prime(lookahead.data(), requirements.lookaheadInputFrames);
        const auto processed = cold.process(input.data(), frames * 2U,
            output.data(), frames, frames);
        allocation_probe::enabled = false;
        require(prime.status == TimePitchPrimeStatus::Ok, "cold dynamic spectral prime failed");
        require(processed.status == TimePitchProcessStatus::Ok, "cold dynamic spectral render failed");
        require(allocation_probe::count == coldAllocationsBefore,
            "first spectral prime/render for configured channel count allocated");
    }
}

void changingActiveChannelsPreservesExistingHistoryAndDoesNotAllocate() {
    constexpr std::size_t stride = kStemwiseMusicChannelCount, block = 257U;
    for (const auto &[tempo, pitch] : std::array<std::pair<float, float>, 4>{{
            {.5F, -12.F}, {.5F, 12.F}, {2.F, -12.F}, {2.F, 12.F}}}) {
        TimePitchProcessor reference(kSampleRate, block, kStemwiseMusicChannelCount, stride);
        TimePitchProcessor changing(kSampleRate, block, 2U, stride);
        require(reference.setControls(tempo, pitch) && changing.setControls(tempo, pitch), "channel history controls");
        const auto requirements = reference.primeRequirements();
        std::vector<float> input((requirements.lookaheadInputFrames + 60'000U) * stride, 0.F);
        for (std::size_t frame = 0U; frame < input.size() / stride; ++frame) {
            input[frame * stride] = input[frame * stride + 1U] =
                .1F * std::sin(float(frame) * .037F) + .03F * std::sin(float(frame) * .083F);
        }
        require(reference.prime(input.data(), requirements.lookaheadInputFrames).status == TimePitchPrimeStatus::Ok &&
            changing.prime(input.data(), requirements.lookaheadInputFrames).status == TimePitchPrimeStatus::Ok,
            "channel history primes the same actual source timeline");
        const auto original = changing.diagnostics();
        std::vector<float> expected(block * stride), actual(block * stride);
        std::size_t cursor = requirements.nextInputOffsetFrames;
        for (const auto width : {2U, 10U, static_cast<unsigned>(kStemwiseMusicChannelCount), 2U, 10U}) {
            const auto allocations = allocation_probe::count;
            allocation_probe::enabled = true;
            const bool admitted = changing.setActiveChannelCount(width);
            allocation_probe::enabled = false;
            require(admitted && allocation_probe::count == allocations, "active width admission must not allocate");
            for (unsigned iteration = 0U; iteration != 20U; ++iteration) {
                const auto needed = reference.requiredInputFrames(block);
                require(needed == changing.requiredInputFrames(block), "attachment reset the fractional scheduler");
                allocation_probe::enabled = true;
                const auto referenceResult = reference.process(input.data() + cursor * stride, needed,
                    expected.data(), block, block);
                const auto result = changing.process(input.data() + cursor * stride, needed, actual.data(), block, block);
                allocation_probe::enabled = false;
                require(result.status == TimePitchProcessStatus::Ok && referenceResult.status == TimePitchProcessStatus::Ok,
                    "active width attachment must retain the processed timeline");
                require(allocation_probe::count == allocations, "active width render must not allocate");
                for (std::size_t frame = 0U; frame != block; ++frame) {
                    require(actual[frame * stride] == expected[frame * stride] &&
                        actual[frame * stride + 1U] == expected[frame * stride + 1U],
                        "active width attachment changed existing Original phase/PCM history");
                    for (std::size_t channel = width; channel < stride; ++channel)
                        require(actual[frame * stride + channel] == 0.F, "removed channels leaked stale PCM");
                }
                cursor += needed;
            }
            const auto now = changing.diagnostics();
            require(now.activeChannelCount == width && now.channelCapacity == stride &&
                now.generation == original.generation && now.primeCount == original.primeCount &&
                now.resetCount == original.resetCount && now.seekCount == original.seekCount,
                "width attachment reset spectral history or processed unused FFT channels");
        }
    }
}

void immediateOptionalChannelReadmissionClearsRetiredHistory() {
    constexpr std::size_t stride = 4U;
    for (const std::size_t block : {64U, 257U, 2048U}) {
        for (const auto &[tempo, pitch] : std::array<std::pair<float, float>, 2>{{{.5F, -7.F}, {2.F, 7.F}}}) {
            TimePitchProcessor processor(kSampleRate, block, stride, stride);
            require(processor.setControls(tempo, pitch), "readmission controls");
            const auto prime = processor.primeRequirements();
            std::vector<float> input((prime.lookaheadInputFrames + 300'000U) * stride);
            for (std::size_t frame = 0; frame < input.size() / stride; ++frame) {
                const float value = .1F * std::sin(float(frame) * .037F);
                for (std::size_t channel = 0; channel < stride; ++channel) input[frame * stride + channel] = value;
            }
            require(processor.prime(input.data(), prime.lookaheadInputFrames).status == TimePitchPrimeStatus::Ok,
                "readmission prime");
            std::vector<float> output(block * stride);
            std::size_t cursor = prime.nextInputOffsetFrames;
            auto render = [&] {
                const auto needed = processor.requiredInputFrames(block);
                const auto result = processor.process(input.data() + cursor * stride, needed,
                    output.data(), block, block);
                require(result.status == TimePitchProcessStatus::Ok, "readmission render");
                cursor += needed;
            };
            for (unsigned i = 0; i < 20; ++i) render();
            const auto before = processor.diagnostics();
            for (std::size_t frame = cursor; frame < input.size() / stride; ++frame)
                input[frame * stride + 2U] = input[frame * stride + 3U] = 0.F;
            const auto allocations = allocation_probe::count;
            allocation_probe::enabled = true;
            const bool retired = processor.setActiveChannelCount(2U);
            const bool readmitted = processor.setActiveChannelCount(stride);
            allocation_probe::enabled = false;
            require(retired && readmitted && allocations == allocation_probe::count,
                "immediate readmission must not allocate");
            float originalPeak = 0.F;
            for (unsigned i = 0; i < 20; ++i) {
                render();
                for (std::size_t frame = 0; frame < block; ++frame) {
                    originalPeak = std::max(originalPeak, std::abs(output[frame * stride]));
                    require(output[frame * stride + 2U] == 0.F && output[frame * stride + 3U] == 0.F,
                        "readmitted silent optional channels replayed retired spectral history");
                }
            }
            require(originalPeak > .01F, "clearing retired optional history silenced Original");
            const auto after = processor.diagnostics();
            require(after.resetCount == before.resetCount && after.primeCount == before.primeCount &&
                after.generation == before.generation, "optional readmission reset Original history");
        }
    }
}

void optionalChannelReadmissionDuringPartialPrimeCannotReplayOldPcm() {
    constexpr std::size_t channels = 4U, block = 256U, quantum = 96U;
    for (const std::size_t admitAfterSteps : {0U, 1U, 7U, 39U}) {
        TimePitchProcessor reference(kSampleRate, block, channels, channels);
        TimePitchProcessor changing(kSampleRate, block, channels, channels);
        require(reference.setControls(1.25F, 3.F) && changing.setControls(1.25F, 3.F), "partial readmission controls");
        const auto requirements = reference.primeRequirements();
        std::vector<float> input((requirements.lookaheadInputFrames + 40'000U) * channels);
        for (std::size_t frame = 0U; frame < input.size() / channels; ++frame)
            for (std::size_t channel = 0U; channel < channels; ++channel)
                input[frame * channels + channel] = .1F * std::sin(float(frame) * .037F);
        require(reference.prime(input.data(), requirements.lookaheadInputFrames).status == TimePitchPrimeStatus::Ok &&
            changing.prime(input.data(), requirements.lookaheadInputFrames).status == TimePitchPrimeStatus::Ok,
            "old optional PCM must populate the real preroll buffer");
        require(changing.setActiveChannelCount(2U), "retire optional channels before seek");
        reference.onSeek();
        changing.onSeek();
        for (std::size_t frame = 0U; frame < input.size() / channels; ++frame)
            input[frame * channels + 2U] = input[frame * channels + 3U] = 0.F;
        const auto allocations = allocation_probe::count;
        allocation_probe::enabled = true;
        std::size_t step = 0U;
        bool admitted = false;
        TimePitchPrimeStatus expectedStatus{}, actualStatus{};
        do {
            if (step == admitAfterSteps) admitted = changing.setActiveChannelCount(channels);
            expectedStatus = reference.prime(step == 0U ? input.data() : nullptr,
                step == 0U ? requirements.lookaheadInputFrames : 0U, quantum).status;
            actualStatus = changing.prime(step == 0U ? input.data() : nullptr,
                step == 0U ? requirements.lookaheadInputFrames : 0U, quantum).status;
            ++step;
        } while (expectedStatus == TimePitchPrimeStatus::InProgress && step < 200U);
        allocation_probe::enabled = false;
        require(admitted && expectedStatus == TimePitchPrimeStatus::Ok && actualStatus == TimePitchPrimeStatus::Ok,
            "partial readmission must finish the same bounded prime");
        require(allocation_probe::count == allocations, "partial prime channel readmission allocated");
        std::vector<float> expected(block * channels), actual(block * channels);
        std::size_t cursor = requirements.nextInputOffsetFrames;
        float originalPeak = 0.F;
        for (unsigned iteration = 0U; iteration < 48U; ++iteration) {
            const auto needed = reference.requiredInputFrames(block);
            require(needed == changing.requiredInputFrames(block), "readmission moved Original input clock");
            require(reference.process(input.data() + cursor * channels, needed, expected.data(), block, block).status ==
                TimePitchProcessStatus::Ok && changing.process(input.data() + cursor * channels, needed,
                actual.data(), block, block).status == TimePitchProcessStatus::Ok, "post-readmission render failed");
            for (std::size_t frame = 0U; frame < block; ++frame) {
                originalPeak = std::max(originalPeak, std::abs(actual[frame * channels]));
                require(actual[frame * channels] == expected[frame * channels] &&
                    actual[frame * channels + 1U] == expected[frame * channels + 1U],
                    "partial readmission changed same-position Original PCM");
                require(expected[frame * channels + 2U] == 0.F && expected[frame * channels + 3U] == 0.F &&
                    actual[frame * channels + 2U] == 0.F && actual[frame * channels + 3U] == 0.F,
                    "partial prime replayed retired optional-channel PCM");
            }
            cursor += needed;
        }
        require(originalPeak > .01F, "readmission must preserve audible Original");
    }
}

void measureAdmittedSpectralWork() {
    using Clock = std::chrono::steady_clock;
    const auto nanoseconds = [](auto duration) -> std::int64_t {
        return std::chrono::duration_cast<std::chrono::nanoseconds>(duration).count();
    };
    constexpr std::size_t stride = kStemwiseMusicChannelCount, block = 256U;
    constexpr std::size_t iterations = 188U, primeQuantum = 96U;
    constexpr std::size_t maximumParts = kStemwiseMusicBusCount - 1U;
    constexpr std::array<std::size_t, 3> measuredParts{0U, maximumParts / 2U, maximumParts};
    for (const auto parts : measuredParts) for (const auto tempo : {.5F, 2.F})
        for (const auto pitch : {-12.F, 12.F}) {
            require(allocation_probe::liveBytes == 0U, "previous measured DSP allocation remained live");
            allocation_probe::peakBytes = 0U;
            allocation_probe::enabled = true;
            const auto started = Clock::now();
            {
                TimePitchProcessor processor(kSampleRate, block, (parts + 1U) * 2U, stride);
                const auto constructed = Clock::now();
                allocation_probe::enabled = false;
                require(processor.setControls(tempo, pitch), "measured spectral controls");
                const auto setupComplete = Clock::now();
                allocation_probe::enabled = true;
                const auto requirements = processor.primeRequirements();
                std::vector<float> input((requirements.lookaheadInputFrames + 513U) * stride, 0.F);
                std::vector<float> output(block * stride);
                for (std::size_t frame = 0U; frame < input.size() / stride; ++frame)
                    for (std::size_t channel = 0U; channel < processor.channelCount(); ++channel)
                        input[frame * stride + channel] = .1F * std::sin(float(frame) * (.02F + channel * .001F));
                allocation_probe::enabled = false;
                const auto primeAllocations = allocation_probe::count;
                const auto maximumPrimeSteps =
                    (requirements.preparedOutputLatencyFrames + primeQuantum - 1U) / primeQuantum;
                std::size_t primeSteps = 0U;
                std::int64_t primeNanoseconds = 0, maximumPrimeStepNanoseconds = 0;
                auto primeStatus = TimePitchPrimeStatus::InProgress;
                while (primeStatus == TimePitchPrimeStatus::InProgress && primeSteps < maximumPrimeSteps) {
                    allocation_probe::enabled = true;
                    const auto primeStart = Clock::now();
                    const auto prime = processor.prime(primeSteps == 0U ? input.data() : nullptr,
                        primeSteps == 0U ? requirements.lookaheadInputFrames : 0U, primeQuantum);
                    const auto elapsed = nanoseconds(Clock::now() - primeStart);
                    allocation_probe::enabled = false;
                    primeNanoseconds += elapsed;
                    maximumPrimeStepNanoseconds = std::max(maximumPrimeStepNanoseconds, elapsed);
                    ++primeSteps;
                    primeStatus = prime.status;
                    require(prime.status == TimePitchPrimeStatus::InProgress ||
                        prime.status == TimePitchPrimeStatus::Ok, "measured bounded spectral prime status");
                    require(prime.lookaheadInputFramesConsumed ==
                        (prime.status == TimePitchPrimeStatus::Ok ? requirements.lookaheadInputFrames : 0U) &&
                        prime.nextInputOffsetFrames ==
                        (prime.status == TimePitchPrimeStatus::Ok ? requirements.nextInputOffsetFrames : 0U),
                        "measured bounded spectral prime consumed the wrong source extent");
                }
                require(primeStatus == TimePitchPrimeStatus::Ok, "measured bounded spectral prime terminates");
                require(allocation_probe::count == primeAllocations, "measured bounded spectral prime allocated");
                const auto allocations = allocation_probe::count;
                std::array<std::int64_t, iterations> processNanoseconds{};
                std::int64_t totalProcessNanoseconds = 0;
                const auto renderStart = Clock::now();
                const auto cpuStart = std::clock();
                for (std::size_t iteration = 0U; iteration != iterations; ++iteration) {
                    const auto needed = processor.requiredInputFrames(block);
                    allocation_probe::enabled = true;
                    const auto processStart = Clock::now();
                    const auto result = processor.process(input.data(), needed, output.data(), block, block);
                    processNanoseconds[iteration] = nanoseconds(Clock::now() - processStart);
                    allocation_probe::enabled = false;
                    totalProcessNanoseconds += processNanoseconds[iteration];
                    require(result.status == TimePitchProcessStatus::Ok, "measured spectral render");
                }
                const auto cpuMicros = static_cast<std::uint64_t>((std::clock() - cpuStart) * 1'000'000.0 / CLOCKS_PER_SEC);
                const auto elapsed = std::chrono::duration_cast<std::chrono::microseconds>(
                    Clock::now() - renderStart).count();
                allocation_probe::enabled = false;
                require(allocation_probe::count == allocations, "measured active spectral render allocated");
                std::sort(processNanoseconds.begin(), processNanoseconds.end());
                const auto diagnostics = processor.diagnostics();
                std::cout << "active-DSP parts=" << parts << " tempo=" << tempo << " pitch=" << pitch
                    << " render-us-per-audio-second=" << elapsed * kSampleRate / (iterations * block)
                    << " cpu-us-per-audio-second=" << cpuMicros * kSampleRate / (iterations * block)
                    << " transient-owned-peak-bytes=" << allocation_probe::peakBytes
                    << " setup-plus-prime-us=" << std::chrono::duration_cast<std::chrono::microseconds>(renderStart - started).count()
                    << " constructor-ns=" << nanoseconds(constructed - started)
                    << " setup-ns=" << nanoseconds(setupComplete - started)
                    << " prime-total-ns=" << primeNanoseconds
                    << " prime-max-step-ns=" << maximumPrimeStepNanoseconds
                    << " prime-quantum-frames=" << primeQuantum << " prime-steps=" << primeSteps
                    << " process-total-ns=" << totalProcessNanoseconds
                    << " process-p50-ns=" << processNanoseconds[iterations / 2U]
                    << " process-p95-ns=" << processNanoseconds[(iterations - 1U) * 95U / 100U]
                    << " process-p99-ns=" << processNanoseconds[(iterations - 1U) * 99U / 100U]
                    << " process-max-ns=" << processNanoseconds.back()
                    << " sample-rate=" << kSampleRate << " block-frames=" << block
                    << " process-calls=" << iterations
                    << " lookahead-input-frames=" << requirements.lookaheadInputFrames
                    << " configured-input-latency-frames=" << diagnostics.configuredInputLatencyFrames
                    << " configured-output-latency-frames=" << diagnostics.configuredOutputLatencyFrames
                    << '\n';
            }
            require(allocation_probe::liveBytes == 0U, "measured DSP teardown retained allocations");
        }
}

void boundedPrimePreservesLookaheadAndAudio() {
    constexpr std::size_t channels = kStemwiseMusicChannelCount;
    constexpr std::size_t quantum = 96U;
    for (const auto &[tempo, pitch] : std::array<std::pair<float, float>, 3>{{
            {.5F, -12.F}, {2.F, 12.F}, {1.35F, 7.F}}}) {
        TimePitchProcessor reference(kSampleRate, 480U, channels);
        TimePitchProcessor stepped(kSampleRate, 480U, channels);
        require(reference.setControls(tempo, pitch) && stepped.setControls(tempo, pitch), "prime controls");
        const auto requirements = reference.primeRequirements();
        std::vector<float> input((requirements.lookaheadInputFrames + 960U) * channels);
        for (std::size_t f = 0U; f < input.size() / channels; ++f) {
            for (std::size_t c = 0U; c < channels; ++c) {
                input[f * channels + c] = .1F * std::sin(float(f) * (.02F + float(c) * .001F));
            }
        }
        require(reference.prime(input.data(), requirements.lookaheadInputFrames).status ==
            TimePitchPrimeStatus::Ok, "reference prime");
        auto abandoned = input;
        for (auto &sample : abandoned) sample = -sample;
        require(stepped.prime(abandoned.data(), requirements.lookaheadInputFrames, quantum).status ==
            TimePitchPrimeStatus::InProgress, "obsolete prime begins");
        stepped.onSeek();
        auto result = stepped.prime(input.data(), requirements.lookaheadInputFrames, quantum);
        require(result.status == TimePitchPrimeStatus::InProgress &&
            result.nextInputOffsetFrames == 0U && stepped.primeRequirements().inProgress,
            "bounded prime must yield without consuming the source timeline");
        std::size_t steps = 1U;
        allocation_probe::enabled = true;
        const auto allocationsBefore = allocation_probe::count;
        while (result.status == TimePitchPrimeStatus::InProgress && steps++ < 200U) {
            result = stepped.prime(nullptr, 0U, quantum);
        }
        allocation_probe::enabled = false;
        require(result.status == TimePitchPrimeStatus::Ok && steps > 2U &&
            result.nextInputOffsetFrames == requirements.nextInputOffsetFrames,
            "bounded prime must finish at the exact reference input offset");
        require(allocation_probe::count == allocationsBefore, "bounded prime must not allocate");
        std::vector<float> expected(480U * channels), actual(expected.size());
        const auto needed = reference.requiredInputFrames(480U);
        const auto *next = input.data() + requirements.nextInputOffsetFrames * channels;
        require(reference.process(next, needed, expected.data(), 480U, 480U).status == TimePitchProcessStatus::Ok &&
            stepped.process(next, needed, actual.data(), 480U, 480U).status == TimePitchProcessStatus::Ok,
            "post-prime audio must render");
        double squaredError = 0.0;
        for (std::size_t i = 0U; i < actual.size(); ++i) squaredError += std::pow(actual[i] - expected[i], 2);
        require(std::sqrt(squaredError / actual.size()) < .001, "bounded prime changed audible PCM");
        require(stepped.diagnostics().primeCount == 1U, "partial steps must not count as complete primes");
    }
}

}  // namespace

int main(int argc, char **argv) {
    try {
        if (argc == 2 && std::string(argv[1]) == "--stft-counter") {
            signalsmith::linear::DynamicSTFT<float> single, batch;
            single.configure(1U, 1U, 16U);
            batch.configure(1U, 1U, 16U);
            single.synthesise();
            batch.synthesise();
            const auto &readOnly = single;
            require(readOnly.analyzeSteps() == readOnly.analyseSteps() &&
                readOnly.synthesizeSteps() == readOnly.synthesiseSteps(),
                "STFT spelling aliases retain const count results");
            for (std::size_t advance : {2U, 14U, 1U, 16U, 7U}) {
                for (std::size_t i = 0U; i < advance; ++i) single.moveOutput(1U);
                batch.moveOutput(advance);
                require(single.samplesSinceSynthesis() == batch.samplesSinceSynthesis(),
                    "single-sample STFT advances must match batched advances across wraparound");
            }
            return 0;
        }
        if (argc == 2 && std::string(argv[1]) == "--partial-prime-channels") {
            optionalChannelReadmissionDuringPartialPrimeCannotReplayOldPcm();
            boundedPrimePreservesLookaheadAndAudio();
            changingActiveChannelsPreservesExistingHistoryAndDoesNotAllocate();
            immediateOptionalChannelReadmissionClearsRetiredHistory();
            std::cout << "PASS partial-prime channel history, Original PCM, and allocation checks\n";
            return 0;
        }
        if (argc == 2 && std::string(argv[1]) == "--active-channels") {
            changingActiveChannelsPreservesExistingHistoryAndDoesNotAllocate();
            immediateOptionalChannelReadmissionClearsRetiredHistory();
            measureAdmittedSpectralWork();
            std::cout << "PASS active-channel history, allocation, and target measurements\n";
            std::cout << "Active spectral width/history tests passed\n";
            return 0;
        }
        const TransientMetrics minimumTempoMetrics = slowedPercussionMetrics(0.5F);
        const TransientMetrics maximumTempoMetrics = slowedPercussionMetrics(2.0F);
        const double minimumTempoChordTail = slowedChordReleaseTailRatio(0.5F);
        const double maximumTempoChordTail = slowedChordReleaseTailRatio(2.0F);
        std::cout << "TimePitch transient metrics"
                  << " 0.5x=" << minimumTempoMetrics.coreEnergyFraction << '/'
                  << minimumTempoMetrics.preEchoEnergyFraction << '/'
                  << minimumTempoMetrics.postEchoEnergyFraction << '/'
                  << minimumTempoMetrics.secondaryPeakCount
                  << " 2.0x=" << maximumTempoMetrics.coreEnergyFraction << '/'
                  << maximumTempoMetrics.preEchoEnergyFraction << '/'
                  << maximumTempoMetrics.postEchoEnergyFraction << '/'
                  << maximumTempoMetrics.secondaryPeakCount
                  << " chord-tail-0.5x/2.0x=" << minimumTempoChordTail << '/'
                  << maximumTempoChordTail << '\n';
        require(
            minimumTempoMetrics.coreEnergyFraction >= 0.75 &&
                minimumTempoMetrics.preEchoEnergyFraction +
                    minimumTempoMetrics.postEchoEnergyFraction <= 0.25 &&
                minimumTempoMetrics.secondaryPeakCount <= 1,
            "0.5x percussion has excessive smear or duplicate attacks");
        require(
            maximumTempoMetrics.coreEnergyFraction >= 0.75 &&
                maximumTempoMetrics.preEchoEnergyFraction +
                    maximumTempoMetrics.postEchoEnergyFraction <= 0.25 &&
                maximumTempoMetrics.secondaryPeakCount <= 1,
            "2.0x percussion has excessive smear or duplicate attacks");
        require(
            minimumTempoChordTail <= 0.01 && maximumTempoChordTail <= 0.01,
            "qualified-range gated chord acquired a reverberant release tail");
        directVirginBypassCopiesEveryBit();
        productTempoBoundaryClampsEveryDirectNativeRequest();
        exactLookaheadAndFirstPostPrimeAudioCoverControlRange();
        processedPitchSettlesAcrossTempoAndPitchLimits();
        rapidTempoAndPitchAutomationStaysContinuous();
        crossfadeIsConstantSumAndBoundaryContinuous();
        neutralControlsRetainTheProcessedTimeline();
        seekRequiresPrimeAndReplaysDeterministically();
        nonFiniteInputAndBoundedErrorsAreHandled();
        extremeFiniteInputIsRejectedBeforeSpectralProcessing();
        spectralAmplitudeBoundaryPreservesFailureStateAndNeutralBits();
        finiteResidualHeadroomSurvivesProcessedPitch();
        primeControlRenderResetAndTransitionDoNotAllocate();
        maximumChannelTransitionPreservesEveryBusAcrossIrregularBlocks();
        maximumChannelBypassAndTunedIsolationRemainSynchronized();
        activeChannelCountUsesFixedStrideWithoutLeakingInactivePcm();
        boundedPrimePreservesLookaheadAndAudio();
        changingActiveChannelsPreservesExistingHistoryAndDoesNotAllocate();
        immediateOptionalChannelReadmissionClearsRetiredHistory();
        optionalChannelReadmissionDuringPartialPrimeCannotReplayOldPcm();
        std::cout << "TimePitch transition tests passed"
                  << " (latest first-audible frame=" << measuredLatestFirstAudibleFrame
                  << ", boundary error=" << measuredTransitionBoundaryError
                  << ", max automation jump=" << measuredMaximumAutomationJump
                  << ", steady-state allocations=" << allocation_probe::count
                  << ", 0.5x core/pre/post/peaks="
                  << minimumTempoMetrics.coreEnergyFraction << '/'
                  << minimumTempoMetrics.preEchoEnergyFraction << '/'
                  << minimumTempoMetrics.postEchoEnergyFraction << '/'
                  << minimumTempoMetrics.secondaryPeakCount
                  << ", 2.0x core/pre/post/peaks="
                  << maximumTempoMetrics.coreEnergyFraction << '/'
                  << maximumTempoMetrics.preEchoEnergyFraction << '/'
                  << maximumTempoMetrics.postEchoEnergyFraction << '/'
                  << maximumTempoMetrics.secondaryPeakCount
                  << ", chord-tail-0.5x/2.0x=" << minimumTempoChordTail << '/'
                  << maximumTempoChordTail << ")\n";
        return 0;
    } catch (const std::exception &error) {
        allocation_probe::enabled = false;
        std::cerr << "TimePitch transition test failure: " << error.what() << '\n';
        return 1;
    }
}
