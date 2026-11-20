#include "AllocationProbeAttributes.h"
#include "LateStemMixer.h"
#include "NativeAudioOutputBridge.h"
#include "MergerKnownAnswer.h"
#include "merger-oracle.h"

#include <algorithm>
#include <array>
#include <bit>
#include <cfenv>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <iomanip>
#include <limits>
#include <new>
#include <stdexcept>
#include <string>
#include <vector>

namespace allocation_probe {
bool enabled = false;
std::uint64_t count = 0U;
}  // namespace allocation_probe

STEMWISE_ALLOCATION_HOOK void *operator new(std::size_t size) {
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

using stemwise::audio::LateStemMixControlSnapshot;
using stemwise::audio::LateStemMixer;
using stemwise::audio::LateStemSourceMode;
using stemwise::audio::kStemwiseAllStemBusesReadyMask;
using stemwise::audio::kStemwiseMultibusClickChannel;
using stemwise::audio::kStemwiseMultibusEnvelopeChannel;
using stemwise::audio::kStemwiseMultibusStride;
using stemwise::audio::kStemwiseStemBusCount;
using stemwise::audio::stemwiseOriginalChannel;
using stemwise::audio::stemwiseStemChannel;

constexpr std::size_t kRampFrames = 240U;
constexpr float kTolerance = 1.0e-6F;
constexpr std::uint32_t kSampleRateHz = 48'000U;
constexpr std::size_t kPrefilledFrames = 2'304U;
constexpr std::size_t kCommandBoundaryFrame = 480U;
constexpr std::size_t kCallbackFrames = 96U;
constexpr std::size_t kAuthoredStemCount = 6U;
constexpr std::size_t kMaximumSpectralColumns = kAuthoredStemCount * 2U;
constexpr double kPi = 3.14159265358979323846;
constexpr std::array<double, kAuthoredStemCount> kStemToneFrequenciesHz{
    521.0,
    631.0,
    733.0,
    839.0,
    947.0,
    1'051.0,
};
constexpr std::array<float, kAuthoredStemCount> kStemToneAmplitudes{
    0.031F,
    0.033F,
    0.035F,
    0.037F,
    0.039F,
    0.041F,
};

static_assert(kCommandBoundaryFrame % kCallbackFrames == 0U);
static_assert((kPrefilledFrames - kCommandBoundaryFrame) % kCallbackFrames == 0U);

void require(bool condition, const std::string &message) {
    if (!condition) throw std::runtime_error(message);
}

LateStemMixControlSnapshot controls(
    std::uint64_t sequence,
    LateStemSourceMode source = LateStemSourceMode::Stems) {
    LateStemMixControlSnapshot snapshot;
    snapshot.publicationSequence = sequence;
    snapshot.generation = 17U;
    snapshot.selectionToken = 29U;
    snapshot.canonicalControlRevision = 11U;
    snapshot.sourceIntentRevision = 7U;
    snapshot.stemIntentRevision = 9U;
    snapshot.sourceMode = source;
    snapshot.requiredStemMask = 0x3FU;
    std::fill_n(snapshot.effectiveStemGains.begin(), kAuthoredStemCount, 1.0F);
    snapshot.musicGain = 1.0F;
    snapshot.mixHeadroom = 1.0F;
    snapshot.sourceRampFrames = kRampFrames;
    snapshot.laneRampFrames = kRampFrames;
    snapshot.masterRampFrames = kRampFrames;
    return snapshot;
}

std::vector<float> payload(std::size_t frames) {
    std::vector<float> result(frames * kStemwiseMultibusStride, 0.0F);
    for (std::size_t frame = 0U; frame < frames; ++frame) {
        result[frame * kStemwiseMultibusStride + kStemwiseMultibusEnvelopeChannel] = 1.0F;
    }
    return result;
}

std::vector<float> sixTonePayload() {
    auto queued = payload(kPrefilledFrames);
    for (std::size_t frame = 0U; frame < kPrefilledFrames; ++frame) {
        for (std::size_t lane = 0U; lane < kAuthoredStemCount; ++lane) {
            const double phase = 2.0 * kPi * kStemToneFrequenciesHz[lane] *
                static_cast<double>(frame) / static_cast<double>(kSampleRateHz);
            const float sample = static_cast<float>(
                static_cast<double>(kStemToneAmplitudes[lane]) * std::sin(phase));
            queued[frame * kStemwiseMultibusStride + stemwiseStemChannel(lane, 0U)] =
                sample;
            queued[frame * kStemwiseMultibusStride + stemwiseStemChannel(lane, 1U)] =
                sample;
        }
    }
    return queued;
}

void mixRange(
    LateStemMixer &mixer,
    const std::vector<float> &queued,
    std::vector<float> &output,
    std::size_t firstFrame,
    std::size_t frameCount) {
    std::size_t mixedFrames = 0U;
    while (mixedFrames < frameCount) {
        const std::size_t quantum = std::min(kCallbackFrames, frameCount - mixedFrames);
        const std::size_t frame = firstFrame + mixedFrames;
        require(
            mixer.mix(
                queued.data() + frame * kStemwiseMultibusStride,
                quantum,
                kStemwiseAllStemBusesReadyMask,
                output.data() + frame * 2U),
            "six-tone callback mix");
        mixedFrames += quantum;
    }
}

struct FixtureRender final {
    std::vector<float> queued;
    std::vector<float> output;
};

FixtureRender renderPrefilledSixToneCommand(
    const std::array<float, kStemwiseStemBusCount> &targetGains) {
    LateStemMixer mixer(kSampleRateHz);
    require(mixer.applyControl(controls(1U)), "six-tone initial controls accepted");

    // All 48 ms of source PCM exist before the command. Only callback-time mixing may alter it.
    FixtureRender rendered{sixTonePayload(), std::vector<float>(kPrefilledFrames * 2U, 0.0F)};
    mixRange(
        mixer,
        rendered.queued,
        rendered.output,
        0U,
        kCommandBoundaryFrame);

    auto changed = controls(2U);
    changed.effectiveStemGains = targetGains;
    require(mixer.applyControl(changed), "six-tone command accepted after payload prefill");
    mixRange(
        mixer,
        rendered.queued,
        rendered.output,
        kCommandBoundaryFrame,
        kPrefilledFrames - kCommandBoundaryFrame);
    require(
        mixer.diagnostics().limiterActiveFrames == 0U,
        "spectral fixture accidentally entered the nonlinear limiter");
    return rendered;
}

[[nodiscard]] bool laneChanged(std::uint32_t changedMask, std::size_t lane) {
    const auto laneBit = std::uint32_t{1U} << static_cast<std::uint32_t>(lane);
    return (changedMask & laneBit) != 0U;
}

double commandProgress(std::size_t frame, bool immediate) {
    if (frame < kCommandBoundaryFrame) return 0.0;
    if (immediate) return 1.0;
    const std::size_t rampFrame = frame - kCommandBoundaryFrame;
    if (rampFrame >= kRampFrames - 1U) return 1.0;
    return static_cast<double>(rampFrame) / static_cast<double>(kRampFrames - 1U);
}

struct SpectralColumns final {
    std::array<std::size_t, kStemwiseStemBusCount> before{};
    std::array<std::size_t, kStemwiseStemBusCount> after{};
    std::size_t count{0U};
};

SpectralColumns spectralColumns(std::uint32_t changedMask) {
    SpectralColumns columns;
    for (std::size_t lane = 0U; lane < kAuthoredStemCount; ++lane) {
        columns.before[lane] = columns.count++;
        if (laneChanged(changedMask, lane)) {
            columns.after[lane] = columns.count++;
        } else {
            columns.after[lane] = columns.before[lane];
        }
    }
    return columns;
}

std::array<double, kMaximumSpectralColumns> spectralRow(
    const std::vector<float> &queued,
    std::size_t frame,
    std::uint32_t changedMask,
    bool immediate,
    const SpectralColumns &columns) {
    std::array<double, kMaximumSpectralColumns> row{};
    const double progress = commandProgress(frame, immediate);
    for (std::size_t lane = 0U; lane < kAuthoredStemCount; ++lane) {
        const double tone = queued[
            frame * kStemwiseMultibusStride + stemwiseStemChannel(lane, 0U)];
        if (laneChanged(changedMask, lane)) {
            row[columns.before[lane]] = tone * (1.0 - progress);
            row[columns.after[lane]] = tone * progress;
        } else {
            row[columns.before[lane]] = tone;
        }
    }
    return row;
}

struct SpectralFit final {
    std::array<double, kStemwiseStemBusCount> beforeGain{};
    std::array<double, kStemwiseStemBusCount> afterGain{};
    double rootMeanSquareResidual{0.0};
    double maximumAbsoluteResidual{0.0};
    double maximumStereoDifference{0.0};
};

SpectralFit fitSimultaneousStemAmplitudes(
    const FixtureRender &rendered,
    std::uint32_t changedMask,
    bool immediate) {
    const SpectralColumns columns = spectralColumns(changedMask);
    std::array<std::array<double, kMaximumSpectralColumns + 1U>,
        kMaximumSpectralColumns> augmented{};

    for (std::size_t frame = 0U; frame < kPrefilledFrames; ++frame) {
        const auto row = spectralRow(
            rendered.queued, frame, changedMask, immediate, columns);
        const double observation = 0.5 * static_cast<double>(
            rendered.output[frame * 2U] + rendered.output[frame * 2U + 1U]);
        for (std::size_t rowIndex = 0U; rowIndex < columns.count; ++rowIndex) {
            for (std::size_t column = 0U; column < columns.count; ++column) {
                augmented[rowIndex][column] += row[rowIndex] * row[column];
            }
            augmented[rowIndex][columns.count] += row[rowIndex] * observation;
        }
    }

    for (std::size_t pivot = 0U; pivot < columns.count; ++pivot) {
        std::size_t strongestRow = pivot;
        for (std::size_t row = pivot + 1U; row < columns.count; ++row) {
            if (std::abs(augmented[row][pivot]) >
                std::abs(augmented[strongestRow][pivot])) {
                strongestRow = row;
            }
        }
        require(
            std::abs(augmented[strongestRow][pivot]) > 1.0e-12,
            "six-tone least-squares system was singular");
        if (strongestRow != pivot) std::swap(augmented[strongestRow], augmented[pivot]);
        const double divisor = augmented[pivot][pivot];
        for (std::size_t column = pivot; column <= columns.count; ++column) {
            augmented[pivot][column] /= divisor;
        }
        for (std::size_t row = 0U; row < columns.count; ++row) {
            if (row == pivot) continue;
            const double factor = augmented[row][pivot];
            for (std::size_t column = pivot; column <= columns.count; ++column) {
                augmented[row][column] -= factor * augmented[pivot][column];
            }
        }
    }

    std::array<double, kMaximumSpectralColumns> coefficients{};
    for (std::size_t column = 0U; column < columns.count; ++column) {
        coefficients[column] = augmented[column][columns.count];
    }

    SpectralFit fit;
    for (std::size_t lane = 0U; lane < kAuthoredStemCount; ++lane) {
        fit.beforeGain[lane] = coefficients[columns.before[lane]];
        fit.afterGain[lane] = coefficients[columns.after[lane]];
    }
    double residualSquareSum = 0.0;
    for (std::size_t frame = 0U; frame < kPrefilledFrames; ++frame) {
        const auto row = spectralRow(
            rendered.queued, frame, changedMask, immediate, columns);
        double prediction = 0.0;
        for (std::size_t column = 0U; column < columns.count; ++column) {
            prediction += row[column] * coefficients[column];
        }
        const double left = rendered.output[frame * 2U];
        const double right = rendered.output[frame * 2U + 1U];
        const double observation = 0.5 * (left + right);
        const double residual = observation - prediction;
        residualSquareSum += residual * residual;
        fit.maximumAbsoluteResidual = std::max(
            fit.maximumAbsoluteResidual, std::abs(residual));
        fit.maximumStereoDifference = std::max(
            fit.maximumStereoDifference, std::abs(left - right));
    }
    fit.rootMeanSquareResidual = std::sqrt(
        residualSquareSum / static_cast<double>(kPrefilledFrames));
    return fit;
}

void requireSpectralFit(
    const SpectralFit &fit,
    const std::array<float, kStemwiseStemBusCount> &targetGains) {
    for (std::size_t lane = 0U; lane < kAuthoredStemCount; ++lane) {
        require(
            std::abs(fit.beforeGain[lane] - 1.0) <= 2.0e-5,
            "least-squares pre-command stem amplitude was wrong");
        require(
            std::abs(fit.afterGain[lane] - static_cast<double>(targetGains[lane])) <= 2.0e-5,
            "least-squares post-command stem amplitude was wrong");
    }
    require(
        fit.rootMeanSquareResidual <= 2.0e-7,
        "six-tone PCM did not follow the exact simultaneous gain model");
    require(
        fit.maximumAbsoluteResidual <= 2.0e-6,
        "six-tone PCM exposed a delayed onset, endpoint, or lane clock");
    require(
        fit.maximumStereoDifference <= 1.0e-7,
        "stereo stem pair lost sample alignment");
}

void requireExactPcmEnvelope(
    const FixtureRender &rendered,
    const std::array<float, kStemwiseStemBusCount> &targetGains,
    std::uint32_t changedMask,
    bool immediate) {
    double maximumError = 0.0;
    for (std::size_t frame = 0U; frame < kPrefilledFrames; ++frame) {
        const double progress = commandProgress(frame, immediate);
        double expected = 0.0;
        for (std::size_t lane = 0U; lane < kAuthoredStemCount; ++lane) {
            const double gain = laneChanged(changedMask, lane)
                ? 1.0 + (static_cast<double>(targetGains[lane]) - 1.0) * progress
                : 1.0;
            expected += static_cast<double>(rendered.queued[
                frame * kStemwiseMultibusStride + stemwiseStemChannel(lane, 0U)]) * gain;
        }
        maximumError = std::max(
            maximumError,
            std::abs(static_cast<double>(rendered.output[frame * 2U]) - expected));
    }
    require(maximumError <= 2.0e-6, "callback PCM missed the commanded sample envelope");
}

void requireRampMilestones() {
    require(
        commandProgress(kCommandBoundaryFrame, false) == 0.0,
        "ramp sample zero was not the old endpoint");
    require(
        commandProgress(kCommandBoundaryFrame + 1U, false) > 0.0,
        "ramp did not begin on sample one");
    require(
        commandProgress(kCommandBoundaryFrame + 228U, false) >= 0.95,
        "ramp had not reached 95 percent by sample 228");
    require(
        commandProgress(kCommandBoundaryFrame + 239U, false) == 1.0,
        "ramp missed the exact sample-239 endpoint");
}

void prefilledQueueCannotDelayOneLanePcmCommand() {
    std::array<float, kStemwiseStemBusCount> targetGains{};
    targetGains.fill(1.0F);
    targetGains[0] = 0.15F;
    constexpr std::uint32_t kChangedMask = 0x01U;
    const FixtureRender rendered = renderPrefilledSixToneCommand(targetGains);
    const SpectralFit fit = fitSimultaneousStemAmplitudes(
        rendered, kChangedMask, false);
    requireSpectralFit(fit, targetGains);
    requireExactPcmEnvelope(rendered, targetGains, kChangedMask, false);
    requireRampMilestones();
}

void twoLaneCommandUsesOneCallbackClockAndLeavesFourUntouched() {
    std::array<float, kStemwiseStemBusCount> targetGains{};
    targetGains.fill(1.0F);
    targetGains[1] = 0.0F;
    targetGains[4] = 0.55F;
    constexpr std::uint32_t kChangedMask = 0x12U;
    const FixtureRender rendered = renderPrefilledSixToneCommand(targetGains);
    const SpectralFit fit = fitSimultaneousStemAmplitudes(
        rendered, kChangedMask, false);
    requireSpectralFit(fit, targetGains);
    requireExactPcmEnvelope(rendered, targetGains, kChangedMask, false);
}

void soloEffectiveEndpointSuppressesFiveLanesOnOneClock() {
    std::array<float, kStemwiseStemBusCount> targetGains{};
    targetGains[2] = 1.0F;
    constexpr std::uint32_t kChangedMask = 0x3BU;
    const FixtureRender rendered = renderPrefilledSixToneCommand(targetGains);
    const SpectralFit fit = fitSimultaneousStemAmplitudes(
        rendered, kChangedMask, false);
    requireSpectralFit(fit, targetGains);
    requireExactPcmEnvelope(rendered, targetGains, kChangedMask, false);
}

void allMutedRampsMusicAndStaysMutedThroughReadinessChanges() {
    LateStemMixer mixer;
    auto initial = controls(1U);
    require(mixer.applyControl(initial), "mute initial controls");
    auto queued = payload(kRampFrames);
    for (std::size_t frame = 0U; frame < kRampFrames; ++frame) {
        queued[frame * kStemwiseMultibusStride + stemwiseOriginalChannel(0U)] = 0.3F;
        queued[frame * kStemwiseMultibusStride + stemwiseOriginalChannel(1U)] = 0.3F;
        queued[frame * kStemwiseMultibusStride + stemwiseStemChannel(0U, 0U)] = 0.1F;
        queued[frame * kStemwiseMultibusStride + stemwiseStemChannel(0U, 1U)] = 0.1F;
        queued[frame * kStemwiseMultibusStride + kStemwiseMultibusClickChannel] = 0.04F;
    }
    std::vector<float> output(kRampFrames * 2U);
    require(mixer.mix(queued.data(), kRampFrames, initial.requiredStemMask, output.data()),
        "audible music before mute");
    const float before = output.back();
    auto muted = controls(2U);
    muted.effectiveStemGains.fill(0.0F);
    require(mixer.applyControl(muted), "all-muted control accepted");
    require(mixer.mix(queued.data(), kRampFrames, initial.requiredStemMask, output.data()),
        "final mute ramp");
    require(output.front() > 0.04F && std::abs(output.front() - before) < 0.002F,
        "final mute hard-zeroed music");
    for (std::size_t frame = 1U; frame < kRampFrames; ++frame)
        require(std::abs(output[frame * 2U] - output[(frame - 1U) * 2U]) < 0.002F,
            "final mute ramp discontinuity");
    require(std::abs(output.back() - 0.04F) < kTolerance, "mute failed to settle");
    for (const auto mask : {0U, initial.requiredStemMask, 0U}) {
        require(mixer.mix(queued.data(), kRampFrames, mask, output.data()), "readiness mute mix");
        for (const float sample : output)
            require(std::abs(sample - 0.04F) < kTolerance,
                "readiness fallback restored music or lost click");
    }
    auto original = controls(3U, LateStemSourceMode::Original);
    original.effectiveStemGains.fill(0.0F);
    require(mixer.applyControl(original, 0U), "explicit Original accepted");
    require(mixer.mix(queued.data(), kRampFrames, 0U, output.data()), "explicit Original mix");
    require(output.back() > 0.3F, "explicit Original remained music-muted");
}

void missingStemBusFallsBackToOriginalAndLimiterProtectsOutput() {
    LateStemMixer fallbackMixer;
    require(fallbackMixer.applyControl(controls(1U)), "fallback initial controls");
    auto queued = payload(kRampFrames);
    for (std::size_t frame = 0U; frame < kRampFrames; ++frame) {
        queued[frame * kStemwiseMultibusStride + stemwiseOriginalChannel(0U)] = 0.2F;
        queued[frame * kStemwiseMultibusStride + stemwiseOriginalChannel(1U)] = 0.2F;
        queued[frame * kStemwiseMultibusStride + stemwiseStemChannel(0U, 0U)] = 0.05F;
        queued[frame * kStemwiseMultibusStride + stemwiseStemChannel(0U, 1U)] = 0.05F;
    }
    std::vector<float> output(kRampFrames * 2U, 0.0F);
    require(
        fallbackMixer.mix(queued.data(), kRampFrames, 0x1FU, output.data()),
        "safe Original fallback mix");
    require(
        std::abs(output[239U * 2U] - 0.2F) <= kTolerance,
        "missing stem bus did not settle to Original");
    require(
        fallbackMixer.diagnostics().safeOriginalFallbackFrames == kRampFrames,
        "safe Original fallback diagnostics");

    LateStemMixer limiterMixer;
    require(limiterMixer.applyControl(controls(1U)), "limiter initial controls");
    auto loud = payload(8U);
    for (std::size_t frame = 0U; frame < 8U; ++frame) {
        for (std::size_t lane = 0U; lane < kAuthoredStemCount; ++lane) {
            loud[frame * kStemwiseMultibusStride + stemwiseStemChannel(lane, 0U)] = 4.0F;
            loud[frame * kStemwiseMultibusStride + stemwiseStemChannel(lane, 1U)] = -4.0F;
        }
    }
    std::vector<float> limited(16U, 0.0F);
    require(
        limiterMixer.mix(
            loud.data(),
            8U,
            kStemwiseAllStemBusesReadyMask,
            limited.data()),
        "limiter callback mix");
    for (float sample : limited) {
        require(
            std::abs(sample) <= LateStemMixer::kLimiterCeiling + kTolerance,
            "callback limiter exceeded ceiling");
    }
}

void unrelatedPublicationsPreserveRampTiming() {
    LateStemMixer reference;
    LateStemMixer republished;
    auto initial = controls(1U, LateStemSourceMode::Original);
    require(reference.applyControl(initial), "reference initial control");
    require(republished.applyControl(initial), "republished initial control");
    auto changed = controls(2U);
    changed.effectiveStemGains[0] = 0.25F;
    changed.musicGain = 0.5F;
    require(reference.applyControl(changed), "reference ramp control");
    require(republished.applyControl(changed), "republished ramp control");
    auto queued = payload(kRampFrames + 1U);
    for (std::size_t frame = 0; frame <= kRampFrames; ++frame) {
        queued[frame * kStemwiseMultibusStride + stemwiseOriginalChannel(0U)] = 0.1F;
        queued[frame * kStemwiseMultibusStride + stemwiseStemChannel(0U, 0U)] = 0.2F;
    }
    std::vector<float> expected((kRampFrames + 1U) * 2U);
    std::vector<float> actual(expected.size());
    mixRange(reference, queued, expected, 0U, kRampFrames + 1U);
    for (std::size_t frame = 0; frame <= kRampFrames; ++frame) {
        if (frame != 0U && frame % 48U == 0U) {
            ++changed.publicationSequence;
            // Lane 1 is silent: its new endpoint must not retime source, master, or lane 0.
            changed.effectiveStemGains[1] = frame % 96U == 0U ? 0.5F : 0.75F;
            require(republished.applyControl(changed), "unrelated lane control");
        }
        mixRange(republished, queued, actual, frame, 1U);
    }
    for (std::size_t sample = 0; sample < expected.size(); ++sample) {
        require(std::abs(actual[sample] - expected[sample]) <= kTolerance,
                "unrelated publication restarted an in-flight ramp");
    }
}

void staleOwnerAndSteadyCallbackAllocationFailClosed() {
    LateStemMixer mixer;
    require(mixer.applyControl(controls(2U)), "allocation initial controls");
    auto stale = controls(3U);
    stale.selectionToken = 31U;
    require(!mixer.applyControl(stale), "wrong-owner endpoint was accepted");
    require(!mixer.applyControl(controls(1U)), "stale endpoint was accepted");

    auto queued = payload(256U);
    for (std::size_t frame = 0U; frame < 256U; ++frame) {
        queued[frame * kStemwiseMultibusStride + stemwiseStemChannel(0U, 0U)] = 0.1F;
    }
    std::vector<float> output(512U, 0.0F);
    const std::uint64_t before = allocation_probe::count;
    allocation_probe::enabled = true;
    const bool mixed = mixer.mix(
        queued.data(),
        256U,
        kStemwiseAllStemBusesReadyMask,
        output.data());
    allocation_probe::enabled = false;
    require(mixed, "allocation callback mix");
    require(allocation_probe::count == before, "callback finalizer allocated memory");
}

void dynamicPartsGateOnlyDeclaredBusesAndKeepLastPartAudible() {
    for (const std::size_t count : std::array<std::size_t, 4U>{0U, 2U, 6U, kStemwiseStemBusCount}) {
        LateStemMixer mixer;
        auto endpoint = controls(1U, count == 0U
            ? LateStemSourceMode::Original : LateStemSourceMode::Stems);
        endpoint.requiredStemMask = (1U << count) - 1U;
        endpoint.effectiveStemGains.fill(0.0F);
        std::fill_n(endpoint.effectiveStemGains.begin(), count, 1.0F);
        if (count != 0U) endpoint.effectiveStemGains[count - 1U] = 0.5F;
        endpoint.sourceRampFrames = endpoint.laneRampFrames = endpoint.masterRampFrames = 1U;
        auto queued = payload(8U);
        for (std::size_t frame = 0U; frame < 8U; ++frame) {
            queued[frame * kStemwiseMultibusStride + stemwiseOriginalChannel(0U)] = 0.2F;
            queued[frame * kStemwiseMultibusStride + stemwiseOriginalChannel(1U)] = -0.2F;
            for (std::size_t lane = 0U; lane < kStemwiseStemBusCount; ++lane) {
                // Undeclared PCM must neither sound nor gate a complete selected package.
                const float value = lane < count ? 0.01F : 0.9F;
                queued[frame * kStemwiseMultibusStride + stemwiseStemChannel(lane, 0U)] = value;
                queued[frame * kStemwiseMultibusStride + stemwiseStemChannel(lane, 1U)] = -value;
            }
        }
        std::array<float, 16U> output{};
        const auto allocationsBefore = allocation_probe::count;
        allocation_probe::enabled = true;
        const bool accepted = mixer.applyControl(endpoint, endpoint.requiredStemMask);
        const bool mixed = mixer.mix(queued.data(), 8U, endpoint.requiredStemMask, output.data());
        allocation_probe::enabled = false;
        require(accepted && mixed, "dynamic callback package rejected");
        require(allocation_probe::count == allocationsBefore, "dynamic callback allocated");
        const float expected = count == 0U ? 0.2F : 0.01F * static_cast<float>(count) - 0.005F;
        for (std::size_t frame = 0U; frame < 8U; ++frame) {
            require(std::abs(output[frame * 2U] - expected) <= kTolerance,
                "dynamic callback lost active part or mixed an inactive part");
            require(std::abs(output[frame * 2U + 1U] + expected) <= kTolerance,
                "dynamic callback lost right-channel alignment");
        }
        if (count != 0U) {
            require(mixer.mix(queued.data(), 8U,
                endpoint.requiredStemMask & ~(1U << (count - 1U)), output.data()),
                "dynamic missing-last-part fallback rejected");
            require(std::abs(output.back() + 0.2F) <= kTolerance,
                "missing declared last part failed to fall back to Original");
        }
    }
}

void unityStemsAlwaysRenderSelectedPartsAndRemainingInsteadOfOriginal() {
    constexpr std::size_t frames = 32U;
    for (const std::size_t count : {2U, 9U}) {
        LateStemMixer mixer;
        auto endpoint = controls(1U, LateStemSourceMode::Stems);
        endpoint.requiredStemMask = (1U << count) - 1U;
        endpoint.effectiveStemGains.fill(0.0F);
        std::fill_n(endpoint.effectiveStemGains.begin(), count, 1.0F);
        endpoint.sourceRampFrames = endpoint.laneRampFrames = endpoint.masterRampFrames = 1U;
        auto queued = payload(frames);
        for (std::size_t frame = 0U; frame < frames; ++frame) {
            // Original deliberately disagrees with the separated sum. Real residuals can
            // resemble Original closely, which would hide a unity-gain shortcut in a fixture.
            const float offset = static_cast<float>(frame);
            queued[frame * kStemwiseMultibusStride + stemwiseOriginalChannel(0U)] = -0.4F + offset * 0.003F;
            queued[frame * kStemwiseMultibusStride + stemwiseOriginalChannel(1U)] = 0.55F - offset * 0.001F;
            for (std::size_t lane = 0U; lane + 1U < count; ++lane) {
                const float part = static_cast<float>(lane + 1U);
                queued[frame * kStemwiseMultibusStride + stemwiseStemChannel(lane, 0U)] =
                    part * 0.0007F + offset * 0.00011F;
                queued[frame * kStemwiseMultibusStride + stemwiseStemChannel(lane, 1U)] =
                    -part * 0.0009F + offset * 0.00007F;
            }
            // The last declared source is Remaining and must participate in the same sum.
            queued[frame * kStemwiseMultibusStride + stemwiseStemChannel(count - 1U, 0U)] = 0.11F + offset * 0.001F;
            queued[frame * kStemwiseMultibusStride + stemwiseStemChannel(count - 1U, 1U)] = -0.13F + offset * 0.0007F;
        }
        std::array<float, frames * 2U> output{};
        require(mixer.applyControl(endpoint, endpoint.requiredStemMask), "unity Stems endpoint rejected");
        const auto assertSeparatedPcm = [&](float remainingGain) {
            require(mixer.mix(queued.data(), frames, endpoint.requiredStemMask, output.data()),
                "complete unity Stems mix rejected");
            const float selected = static_cast<float>(count - 1U);
            const float triangular = selected * (selected + 1.0F) * 0.5F;
            for (std::size_t frame = 0U; frame < frames; ++frame) {
                const float offset = static_cast<float>(frame);
                // Independent closed-form fixture sum, not the mixer's accumulation algorithm.
                const float left = triangular * 0.0007F + selected * offset * 0.00011F +
                    remainingGain * (0.11F + offset * 0.001F);
                const float right = -triangular * 0.0009F + selected * offset * 0.00007F +
                    remainingGain * (-0.13F + offset * 0.0007F);
                require(std::abs(output[frame * 2U] - left) <= kTolerance &&
                    std::abs(output[frame * 2U + 1U] - right) <= kTolerance,
                    "Stems used Original or omitted an actual selected source at unity gain");
            }
            require(mixer.diagnostics().safeOriginalFallbackFrames == 0U,
                "complete selected Stems were reported as an Original fallback");
        };
        assertSeparatedPcm(1.0F);
        endpoint.publicationSequence++;
        endpoint.effectiveStemGains[count - 1U] = 0.0F;
        require(mixer.applyControl(endpoint, endpoint.requiredStemMask), "Remaining mute rejected");
        assertSeparatedPcm(0.0F);
        endpoint.publicationSequence++;
        endpoint.effectiveStemGains[count - 1U] = 1.0F;
        require(mixer.applyControl(endpoint, endpoint.requiredStemMask), "Remaining unity restoration rejected");
        assertSeparatedPcm(1.0F);

        endpoint.publicationSequence++;
        endpoint.sourceMode = LateStemSourceMode::Original;
        require(mixer.applyControl(endpoint, endpoint.requiredStemMask) &&
            mixer.mix(queued.data(), frames, endpoint.requiredStemMask, output.data()),
            "explicit Original endpoint rejected");
        for (std::size_t frame = 0U; frame < frames; ++frame) {
            require(std::abs(output[frame * 2U] - (-0.4F + static_cast<float>(frame) * 0.003F)) <= kTolerance &&
                std::abs(output[frame * 2U + 1U] - (0.55F - static_cast<float>(frame) * 0.001F)) <= kTolerance,
                "Original/Stems selection did not select distinct actual audio sources");
        }
    }
}

void defaultEndpointsCoverEveryCapacityLane() {
    const LateStemMixControlSnapshot mixer{};
    const stemwise::audio::NativeAudioLateMixControl wire{};
    for (std::size_t lane = 0U; lane != stemwise::audio::kStemwiseStemBusCount; ++lane) {
        require(mixer.effectiveStemGains[lane] == 1.0F && wire.effectiveStemGains[lane] == 1.0F,
            "default endpoint silences an upper capacity lane");
    }
}

void nineLaneSubsetsGainsRampsAndReadiness() {
    using namespace merger_known_answer;
    constexpr std::size_t frames = 257U;
    auto queued = payload(frames);
    for (std::size_t frame = 0U; frame < frames; ++frame)
        payloadFrame(queued.data() + frame * kStemwiseMultibusStride, frame);
    std::vector<float> output(frames * 2U);
    LateStemMixer mixer;
    auto endpoint = controls(1U);
    endpoint.requiredStemMask = kStemwiseAllStemBusesReadyMask;
    endpoint.sourceRampFrames = endpoint.laneRampFrames = endpoint.masterRampFrames = 9U;
    std::uint64_t sequence = 0U;
    auto check = [&](const QuarterGains &gains) {
        endpoint.publicationSequence = ++sequence;
        for (std::size_t lane = 0U; lane < kLanes; ++lane)
            endpoint.effectiveStemGains[lane] = static_cast<float>(gains[lane]) / 4.0F;
        require(mixer.applyControl(endpoint), "nine-lane endpoint rejected");
        require(mixer.mix(queued.data(), frames, kStemwiseAllStemBusesReadyMask, output.data()),
            "nine-lane render rejected");
        // Stable endpoints are checked independently of the separately tested nine-frame ramp.
        for (std::size_t frame = 9U; frame < frames; ++frame)
            for (std::size_t channel = 0U; channel < 2U; ++channel)
                require(output[frame * 2U + channel] == expected(frame, channel, gains),
                    "nine-lane exact weighted sum: sequence=" + std::to_string(sequence) +
                    " frame=" + std::to_string(frame) + " channel=" + std::to_string(channel));
        require(mixer.diagnostics().safeOriginalFallbackFrames == 0U &&
            mixer.diagnostics().limiterActiveFrames == 0U &&
            mixer.diagnostics().limiterSafetyClamps == 0U,
            "linear complete-Stems assertion crossed fallback or limiter");
    };
    for (std::uint32_t mask = 0U; mask < 512U; ++mask) {
        QuarterGains gains{};
        for (std::size_t lane = 0U; lane < kLanes; ++lane)
            gains[lane] = (mask & (1U << lane)) != 0U ? 4 : 0;
        check(gains);
    }
    for (std::size_t lane = 0U; lane < kLanes; ++lane) {
        for (const std::int32_t gain : {0, 2, 4, 6, 8}) {
            QuarterGains gains{};
            gains.fill(4);
            gains[lane] = gain;
            check(gains);
        }
    }
    check({1, 2, 3, 4, 5, 6, 7, 8, 0});

    // An exactly dyadic step makes every ramp sample independently calculable without a tolerance.
    mixer.reset();
    sequence = 0U;
    QuarterGains start{}; start.fill(4);
    check(start);
    endpoint.publicationSequence = ++sequence;
    endpoint.effectiveStemGains.fill(0.5F);
    require(mixer.applyControl(endpoint), "ramp endpoint rejected");
    require(mixer.mix(queued.data(), 10U, kStemwiseAllStemBusesReadyMask, output.data()),
        "ramp render rejected");
    for (std::size_t frame = 0U; frame < 10U; ++frame) {
        std::int32_t sum[2]{};
        for (std::size_t lane = 0U; lane < kLanes; ++lane)
            for (std::size_t channel = 0U; channel < 2U; ++channel)
                sum[channel] += numerator(frame, lane, channel);
        const auto sixteenthGain = static_cast<std::int32_t>(16U - std::min<std::size_t>(frame, 8U));
        for (std::size_t channel = 0U; channel < 2U; ++channel)
            require(output[frame * 2U + channel] ==
                static_cast<float>(sum[channel] * sixteenthGain) / 1'048'576.0F,
                "nine-lane ramp missed declared per-frame endpoint");
    }
    for (std::size_t lane = 0U; lane < kLanes; ++lane) {
        LateStemMixer missing;
        const auto ready = kStemwiseAllStemBusesReadyMask & ~(1U << lane);
        require(missing.applyControl(endpoint, ready), "missing-lane endpoint rejected");
        require(missing.mix(queued.data(), frames, ready, output.data()), "fallback rejected");
        require(missing.diagnostics().safeOriginalFallbackFrames == frames,
            "missing lane concealed complete-Stems failure");
        require(output.back() == -0.25F && output[frames * 2U - 2U] == 0.375F,
            "missing lane failed safe Original endpoint");
        require(output.back() != expected(frames - 1U, 1U, start),
            "Original sentinel accidentally equalled target sum");
    }
}

void independentDyadicOracleObservesProductionLinearSum() {
    static_assert(std::numeric_limits<float>::is_iec559 && sizeof(float) == 4U);
    require(std::fegetround() == FE_TONEAREST, "unsupported native rounding mode");
    bool allSeparate = true, allFused = true;
    double peakErrorUpper = 0.0, errorSquareSumUpper = 0.0, peakBound = 0.0;
    std::vector<std::uint32_t> observedBits;
    observedBits.reserve(merger_oracle::kMixCases.size());
    for (const auto &fixture : merger_oracle::kMixCases) {
        LateStemMixer mixer;
        auto endpoint = controls(1U);
        endpoint.requiredStemMask = kStemwiseAllStemBusesReadyMask;
        auto queued = payload(1U);
        for (std::size_t lane = 0U; lane < kStemwiseStemBusCount; ++lane) {
            endpoint.effectiveStemGains[lane] = std::bit_cast<float>(fixture.gainBits[lane]);
            for (std::size_t channel = 0U; channel < 2U; ++channel)
                queued[stemwiseStemChannel(lane, channel)] = std::bit_cast<float>(fixture.laneBits[lane]);
        }
        std::array<float, 2U> capture{}, output{};
        mixer.captureLinearStemsForTest(capture.data());
        require(mixer.applyControl(endpoint), std::string("oracle control: ") + fixture.id);
        require(mixer.mix(queued.data(), 1U, kStemwiseAllStemBusesReadyMask, output.data()),
            std::string("oracle mix: ") + fixture.id);
        const auto bits = std::bit_cast<std::uint32_t>(capture[0]);
        observedBits.push_back(bits);
        require(bits == std::bit_cast<std::uint32_t>(capture[1]), "oracle stereo mismatch");
        allSeparate = allSeparate && bits == fixture.separateBits;
        allFused = allFused && bits == fixture.fusedBits;
        require(bits == fixture.separateBits || bits == fixture.fusedBits,
            std::string("unmodelled arithmetic/FTZ profile: ") + fixture.id);
        const double actual = static_cast<double>(capture[0]);
        require(actual >= fixture.idealLower - fixture.boundUpper &&
            actual <= fixture.idealUpper + fixture.boundUpper,
            std::string("linear sum exceeds independently computed bound: ") + fixture.id);
        const double errorUpper = std::max(std::abs(actual - fixture.idealLower),
            std::abs(actual - fixture.idealUpper));
        peakErrorUpper = std::max(peakErrorUpper, errorUpper);
        errorSquareSumUpper += errorUpper * errorUpper;
        peakBound = std::max(peakBound, fixture.boundUpper);
        require(mixer.diagnostics().safeOriginalFallbackFrames == 0U,
            "oracle observed incomplete ownership");
    }
    require(allSeparate || allFused, "compiler mixes arithmetic profiles across oracle cases");
    require(!(allSeparate && allFused), "oracle corpus did not distinguish fused arithmetic");
    std::cout << "Native merger arithmetic profile="
              << (allSeparate ? "separate-RN32" : "fused-RN32")
              << ", oracle cases=" << merger_oracle::kMixCases.size() << '\n';
    std::cout << std::setprecision(17)
              << "{\"nativeLinearMixer\":{\"profile\":\"" << (allSeparate ? "separate-RN32" : "fused-RN32")
              << "\",\"cases\":" << merger_oracle::kMixCases.size()
              << ",\"bitMismatches\":0,\"peakErrorUpper\":" << peakErrorUpper
              << ",\"rmsErrorUpper\":" << std::sqrt(errorSquareSumUpper /
                    static_cast<double>(merger_oracle::kMixCases.size()))
              << ",\"peakPrecomputedBound\":" << peakBound
              << ",\"maximumBeyondBound\":0,\"fallbackFrames\":0,\"observedBits\":[";
    for (std::size_t index = 0U; index < observedBits.size(); ++index) {
        if (index != 0U) std::cout << ',';
        std::cout << observedBits[index];
    }
    std::cout << "]}}\n";
}

}  // namespace

int main(int argc, char **argv) {
    try {
        if (argc == 2 && std::string(argv[1]) == "--unity-stems") {
            unityStemsAlwaysRenderSelectedPartsAndRemainingInsteadOfOriginal();
            std::cout << "Unity Stems actual-source tests passed (2/9 parts).\n";
            return 0;
        }
        if (argc == 2 && std::string(argv[1]) == "--merger-math") {
            independentDyadicOracleObservesProductionLinearSum();
            nineLaneSubsetsGainsRampsAndReadiness();
            std::cout << "Merger controls passed: 512/512 subsets, 45 gain endpoints, mixed gains, "
                         "nine-frame ramp, 9/9 withheld lanes; no fallback/limiter in asserted mixes.\n";
            return 0;
        }
        independentDyadicOracleObservesProductionLinearSum();
        nineLaneSubsetsGainsRampsAndReadiness();
        defaultEndpointsCoverEveryCapacityLane();
        prefilledQueueCannotDelayOneLanePcmCommand();
        twoLaneCommandUsesOneCallbackClockAndLeavesFourUntouched();
        soloEffectiveEndpointSuppressesFiveLanesOnOneClock();
        allMutedRampsMusicAndStaysMutedThroughReadinessChanges();
        missingStemBusFallsBackToOriginalAndLimiterProtectsOutput();
        unrelatedPublicationsPreserveRampTiming();
        staleOwnerAndSteadyCallbackAllocationFailClosed();
        dynamicPartsGateOnlyDeclaredBusesAndKeepLastPartAudible();
        unityStemsAlwaysRenderSelectedPartsAndRemainingInsteadOfOriginal();
        std::cout << "Late stem mixer tests passed (prefilled reserve=2304 frames, "
                     "ramp onset=sample 1, 95%=sample 228, endpoint=sample 239, allocations="
                  << allocation_probe::count << ")\n";
        return 0;
    } catch (const std::exception &error) {
        allocation_probe::enabled = false;
        std::cerr << "Late stem mixer test failure: " << error.what() << '\n';
        return 1;
    }
}
