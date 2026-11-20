#include "native_engine/dsp/SampleRateConverter.h"
#include "native_engine/dsp/TimePitchProcessor.h"
#include "NineLaneMergerTimeline.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <vector>

namespace {

using stemwise::audio::engine::SampleRateConverter;
using stemwise::audio::engine::SampleRateDiagnostics;
using stemwise::audio::engine::SampleRateProcessStatus;
using stemwise::audio::engine::TimePitchPrimeStatus;
using stemwise::audio::engine::TimePitchProcessStatus;
using stemwise::audio::engine::TimePitchProcessor;
using stemwise::audio::engine::TimePitchTimelineState;

constexpr std::size_t kLaneCount = 7U;
constexpr std::size_t kChannelCount = kLaneCount * 2U;
constexpr std::uint32_t kSourceRate = 44'100U;
constexpr std::uint32_t kRenderRate = 48'000U;

void require(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}

[[nodiscard]] float deterministicSample(std::uint64_t frame, std::uint64_t seed) noexcept {
    std::uint64_t value = frame + 0x9e3779b97f4a7c15ULL * (seed + 1U);
    value = (value ^ (value >> 30U)) * 0xbf58476d1ce4e5b9ULL;
    value = (value ^ (value >> 27U)) * 0x94d049bb133111ebULL;
    value ^= value >> 31U;
    const std::int32_t centered =
        static_cast<std::int32_t>((value >> 48U) & 0xffffU) - 32'768;
    return static_cast<float>(centered) * (0.18F / 32'768.0F);
}

void fillStereoSignal(
    std::vector<float> &samples,
    std::size_t frames,
    std::uint64_t sourceStartFrame,
    std::uint64_t songSeed) {
    require(samples.size() >= frames * 2U, "stereo fixture capacity");
    for (std::size_t frame = 0U; frame < frames; ++frame) {
        const std::uint64_t timelineFrame = sourceStartFrame + frame;
        samples[frame * 2U] = deterministicSample(timelineFrame, songSeed * 2U + 1U);
        samples[frame * 2U + 1U] =
            deterministicSample(timelineFrame, songSeed * 2U + 2U);
    }
}

void fillIdenticalSevenLaneSignal(
    std::vector<float> &samples,
    std::size_t frames,
    std::uint64_t sourceStartFrame,
    std::uint64_t songSeed) {
    require(samples.size() >= frames * kChannelCount, "multibus fixture capacity");
    for (std::size_t frame = 0U; frame < frames; ++frame) {
        const std::uint64_t timelineFrame = sourceStartFrame + frame;
        const float left = deterministicSample(timelineFrame, songSeed * 2U + 1U);
        const float right = deterministicSample(timelineFrame, songSeed * 2U + 2U);
        for (std::size_t lane = 0U; lane < kLaneCount; ++lane) {
            samples[frame * kChannelCount + lane * 2U] = left;
            samples[frame * kChannelCount + lane * 2U + 1U] = right;
        }
    }
}

enum class TimelineBoundary : std::uint8_t {
    Continue,
    Discontinuity,
    Seek,
};

struct SourceSegment final {
    TimelineBoundary boundary{TimelineBoundary::Continue};
    std::uint64_t sourceStartFrame{0U};
    std::uint64_t songSeed{1U};
};

void requireMatchingSampleRateDiagnostics(
    const SampleRateDiagnostics &expected,
    const SampleRateDiagnostics &actual) {
    require(actual.generation == expected.generation, "SRC lane generation identity");
    require(actual.processCalls == expected.processCalls, "SRC lane process-call identity");
    require(
        actual.inputFramesConsumed == expected.inputFramesConsumed,
        "SRC lane cumulative input identity");
    require(
        actual.outputFramesProduced == expected.outputFramesProduced,
        "SRC lane cumulative output identity");
    require(
        actual.generationInputFramesConsumed == expected.generationInputFramesConsumed,
        "SRC lane generation input identity");
    require(
        actual.generationOutputFramesProduced == expected.generationOutputFramesProduced,
        "SRC lane generation output identity");
    require(
        actual.generationExpectedOutputFramesFloor ==
            expected.generationExpectedOutputFramesFloor,
        "SRC lane rational floor identity");
    require(
        actual.generationExpectedOutputRemainderNumerator ==
            expected.generationExpectedOutputRemainderNumerator,
        "SRC lane rational remainder identity");
    require(
        actual.generationOutputFrameError == expected.generationOutputFrameError,
        "SRC lane compensated drift identity");
    require(actual.resetCount == expected.resetCount, "SRC lane reset identity");
    require(
        actual.discontinuityCount == expected.discontinuityCount,
        "SRC lane discontinuity identity");
    require(actual.seekCount == expected.seekCount, "SRC lane seek identity");
}

std::uint64_t fourMinutesOfIndependentSrcRemainSampleExact() {
    constexpr std::size_t kMaximumInputFrames = 4'410U;
    constexpr std::uint64_t kSegmentFrames = kSourceRate * 30ULL;
    constexpr std::array<SourceSegment, 8U> kSegments{{
        {TimelineBoundary::Continue, 0U, 1U},
        {TimelineBoundary::Continue, 0U, 1U},
        {TimelineBoundary::Discontinuity, kSourceRate * 3ULL, 1U},
        {TimelineBoundary::Continue, 0U, 1U},
        {TimelineBoundary::Seek, kSourceRate * 127ULL, 1U},
        {TimelineBoundary::Continue, 0U, 1U},
        {TimelineBoundary::Discontinuity, kSourceRate * 3ULL, 1U},
        {TimelineBoundary::Discontinuity, 0U, 2U},
    }};

    std::array<std::unique_ptr<SampleRateConverter>, kLaneCount> converters{};
    for (auto &converter : converters) {
        converter = std::make_unique<SampleRateConverter>(
            kSourceRate,
            kRenderRate,
            kMaximumInputFrames);
    }
    const std::size_t maximumOutputFrames =
        converters.front()->diagnostics().maximumOutputFrames;
    std::vector<float> input(kMaximumInputFrames * 2U, 0.0F);
    std::array<std::vector<float>, kLaneCount> output{};
    for (auto &laneOutput : output) {
        laneOutput.resize(maximumOutputFrames * 2U, 0.0F);
    }

    std::uint64_t totalOutputFrames = 0U;
    std::uint64_t sourceCursor = 0U;
    std::uint64_t songSeed = 1U;
    for (const SourceSegment &segment : kSegments) {
        if (segment.boundary != TimelineBoundary::Continue) {
            for (auto &converter : converters) {
                if (segment.boundary == TimelineBoundary::Seek) {
                    converter->onSeek();
                } else {
                    converter->onDiscontinuity();
                }
            }
            sourceCursor = segment.sourceStartFrame;
            songSeed = segment.songSeed;
        }

        std::uint64_t segmentFramesProcessed = 0U;
        while (segmentFramesProcessed < kSegmentFrames) {
            const std::size_t inputFrames = static_cast<std::size_t>(std::min<std::uint64_t>(
                kMaximumInputFrames,
                kSegmentFrames - segmentFramesProcessed));
            fillStereoSignal(input, inputFrames, sourceCursor, songSeed);

            std::size_t baselineProduced = 0U;
            for (std::size_t lane = 0U; lane < kLaneCount; ++lane) {
                const auto result = converters[lane]->process(
                    input.data(),
                    inputFrames,
                    output[lane].data(),
                    maximumOutputFrames);
                require(result.status == SampleRateProcessStatus::Ok, "seven-lane SRC status");
                require(result.inputFramesConsumed == inputFrames, "seven-lane SRC input count");
                require(result.outputFramesProduced != 0U, "seven-lane SRC made progress");
                if (lane == 0U) {
                    baselineProduced = result.outputFramesProduced;
                } else {
                    require(
                        result.outputFramesProduced == baselineProduced,
                        "seven-lane SRC frame-count identity");
                    require(
                        std::memcmp(
                            output[0].data(),
                            output[lane].data(),
                            baselineProduced * 2U * sizeof(float)) == 0,
                        "seven-lane SRC sample identity");
                }
            }
            totalOutputFrames += baselineProduced;
            sourceCursor += inputFrames;
            segmentFramesProcessed += inputFrames;
        }

        const SampleRateDiagnostics baseline = converters.front()->diagnostics();
        require(
            baseline.generationOutputFrameError >= -1 &&
                baseline.generationOutputFrameError <= 1,
            "seven-lane SRC generation drift stays within one frame");
        for (std::size_t lane = 1U; lane < kLaneCount; ++lane) {
            requireMatchingSampleRateDiagnostics(baseline, converters[lane]->diagnostics());
        }
    }

    const SampleRateDiagnostics finalDiagnostics = converters.front()->diagnostics();
    require(finalDiagnostics.discontinuityCount == 3U, "SRC discontinuity count");
    require(finalDiagnostics.seekCount == 1U, "SRC seek count");
    require(
        finalDiagnostics.inputFramesConsumed == kSegmentFrames * kSegments.size(),
        "SRC four-minute cumulative input count");
    require(
        finalDiagnostics.outputFramesProduced == totalOutputFrames,
        "SRC four-minute cumulative output count");
    return totalOutputFrames;
}

struct TimePitchSegment final {
    TimelineBoundary boundary{TimelineBoundary::Continue};
    float tempo{1.0F};
    float pitchSemitones{0.0F};
    std::uint64_t sourceStartFrame{0U};
    std::uint64_t songSeed{1U};
    bool captureLoopReplay{false};
    bool compareLoopReplay{false};
};

float eightySecondsOfSharedTimePitchRemainLaneExact() {
    constexpr std::size_t kMaximumOutputFrames = 512U;
    constexpr std::uint64_t kSegmentOutputFrames = kRenderRate * 10ULL;
    constexpr std::array<TimePitchSegment, 8U> kSegments{{
        {TimelineBoundary::Continue, 0.50F, -7.0F, 0U, 1U, false, false},
        {TimelineBoundary::Continue, 1.25F, 3.0F, 0U, 1U, false, false},
        {TimelineBoundary::Discontinuity, 1.25F, 3.0F, kRenderRate * 2ULL, 1U, true, false},
        {TimelineBoundary::Continue, 1.25F, 3.0F, 0U, 1U, false, false},
        {TimelineBoundary::Discontinuity, 1.25F, 3.0F, kRenderRate * 2ULL, 1U, false, true},
        {TimelineBoundary::Seek, 2.00F, -12.0F, kRenderRate * 43ULL, 1U, false, false},
        {TimelineBoundary::Continue, 0.80F, 12.0F, 0U, 1U, false, false},
        {TimelineBoundary::Discontinuity, 1.00F, 5.0F, 0U, 2U, false, false},
    }};

    TimePitchProcessor processor(kRenderRate, kMaximumOutputFrames, kChannelCount);
    require(processor.channelCount() == kChannelCount, "shared time/pitch channel count");
    const std::size_t maximumInputFrames = processor.diagnostics().maximumInputFrames;
    std::vector<float> input(maximumInputFrames * kChannelCount, 0.0F);
    std::vector<float> output(kMaximumOutputFrames * kChannelCount, 0.0F);
    std::vector<float> firstLoopOutput;
    std::uint64_t sourceCursor = 0U;
    std::uint64_t songSeed = 1U;
    std::uint64_t renderedFrames = 0U;
    double squareSum = 0.0;
    float maximumLaneDifference = 0.0F;

    for (const TimePitchSegment &segment : kSegments) {
        if (segment.boundary != TimelineBoundary::Continue) {
            if (segment.boundary == TimelineBoundary::Seek) {
                processor.onSeek();
            } else {
                processor.onDiscontinuity();
            }
            sourceCursor = segment.sourceStartFrame;
            songSeed = segment.songSeed;
        }
        require(
            processor.setControls(segment.tempo, segment.pitchSemitones),
            "time/pitch controls accepted");

        if (processor.diagnostics().timelineState == TimePitchTimelineState::PrimeRequired) {
            const auto requirements = processor.primeRequirements();
            require(requirements.lookaheadInputFrames != 0U, "time/pitch prime lookahead");
            std::vector<float> lookahead(
                requirements.lookaheadInputFrames * kChannelCount,
                0.0F);
            fillIdenticalSevenLaneSignal(
                lookahead,
                requirements.lookaheadInputFrames,
                sourceCursor,
                songSeed);
            const auto primeResult = processor.prime(
                lookahead.data(),
                requirements.lookaheadInputFrames);
            require(primeResult.status == TimePitchPrimeStatus::Ok, "time/pitch prime status");
            require(
                primeResult.lookaheadInputFramesConsumed == requirements.lookaheadInputFrames,
                "time/pitch prime consumes exact lookahead");
            require(
                primeResult.nextInputOffsetFrames == requirements.nextInputOffsetFrames,
                "time/pitch prime next-input fence");
            sourceCursor += primeResult.nextInputOffsetFrames;
        }

        std::uint64_t segmentOutputFrames = 0U;
        bool firstBlock = true;
        while (segmentOutputFrames < kSegmentOutputFrames) {
            const std::size_t requestedOutputFrames =
                static_cast<std::size_t>(std::min<std::uint64_t>(
                    kMaximumOutputFrames,
                    kSegmentOutputFrames - segmentOutputFrames));
            const std::size_t requiredInputFrames =
                processor.requiredInputFrames(requestedOutputFrames);
            require(
                requiredInputFrames <= maximumInputFrames,
                "time/pitch input requirement stays preallocated");
            fillIdenticalSevenLaneSignal(
                input,
                requiredInputFrames,
                sourceCursor,
                songSeed);
            const auto result = processor.process(
                requiredInputFrames == 0U ? nullptr : input.data(),
                requiredInputFrames,
                output.data(),
                kMaximumOutputFrames,
                requestedOutputFrames);
            require(result.status == TimePitchProcessStatus::Ok, "time/pitch render status");
            require(
                result.inputFramesConsumed == requiredInputFrames,
                "time/pitch consumes exact scheduled input");
            require(
                result.outputFramesProduced == requestedOutputFrames,
                "time/pitch produces exact requested output");

            for (std::size_t frame = 0U; frame < requestedOutputFrames; ++frame) {
                const float expectedLeft = output[frame * kChannelCount];
                const float expectedRight = output[frame * kChannelCount + 1U];
                squareSum += static_cast<double>(expectedLeft) * expectedLeft;
                squareSum += static_cast<double>(expectedRight) * expectedRight;
                for (std::size_t lane = 1U; lane < kLaneCount; ++lane) {
                    const float leftDifference = std::abs(
                        output[frame * kChannelCount + lane * 2U] - expectedLeft);
                    const float rightDifference = std::abs(
                        output[frame * kChannelCount + lane * 2U + 1U] - expectedRight);
                    maximumLaneDifference = std::max(
                        maximumLaneDifference,
                        std::max(leftDifference, rightDifference));
                    require(leftDifference <= 1.0e-5F, "time/pitch left-lane synchronization");
                    require(rightDifference <= 1.0e-5F, "time/pitch right-lane synchronization");
                }
            }

            if (firstBlock && segment.captureLoopReplay) {
                firstLoopOutput.assign(
                    output.begin(),
                    output.begin() +
                        static_cast<std::ptrdiff_t>(requestedOutputFrames * kChannelCount));
            }
            if (firstBlock && segment.compareLoopReplay) {
                require(
                    firstLoopOutput.size() == requestedOutputFrames * kChannelCount,
                    "loop replay fixture size");
                require(
                    std::memcmp(
                        firstLoopOutput.data(),
                        output.data(),
                        firstLoopOutput.size() * sizeof(float)) == 0,
                    "loop discontinuity replays the exact seven-lane output block");
            }
            firstBlock = false;
            sourceCursor += result.inputFramesConsumed;
            segmentOutputFrames += result.outputFramesProduced;
            renderedFrames += result.outputFramesProduced;
        }
    }

    const auto diagnostics = processor.diagnostics();
    require(diagnostics.discontinuityCount == 3U, "time/pitch discontinuity count");
    require(diagnostics.seekCount == 1U, "time/pitch seek count");
    require(
        diagnostics.outputFramesProduced == renderedFrames,
        "time/pitch cumulative output count");
    require(
        renderedFrames == kSegmentOutputFrames * kSegments.size(),
        "time/pitch eighty-second output duration");
    require(squareSum / static_cast<double>(renderedFrames * 2U) > 1.0e-6,
        "time/pitch long-duration output remains audible");
    return maximumLaneDifference;
}

}  // namespace

int main(int argc, char **argv) {
    try {
        merger_timeline::run();
        if (argc == 2 && std::string(argv[1]) == "--merger-timeline") return 0;
        const std::uint64_t resampledFrames =
            fourMinutesOfIndependentSrcRemainSampleExact();
        const float maximumLaneDifference =
            eightySecondsOfSharedTimePitchRemainLaneExact();
        std::cout << "Seven-lane synchronization tests passed"
                  << " (SRC output frames=" << resampledFrames
                  << ", maximum shared-DSP lane difference=" << maximumLaneDifference
                  << ")\n";
        return 0;
    } catch (const std::exception &error) {
        std::cerr << "Seven-lane synchronization test failure: " << error.what() << '\n';
        return 1;
    }
}
