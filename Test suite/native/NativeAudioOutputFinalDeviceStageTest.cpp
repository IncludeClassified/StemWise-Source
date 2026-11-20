#include "AllocationProbeAttributes.h"
#include "NativeAudioOutputFinalDeviceStage.h"
#include "OutputCallbackOrchestrator.h"
#include "OutputGenerationTransition.h"

#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <limits>
#include <new>
#include <stdexcept>

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

STEMWISE_ALLOCATION_HOOK void operator delete(void *memory) noexcept { std::free(memory); }
STEMWISE_ALLOCATION_HOOK void operator delete[](void *memory) noexcept { ::operator delete(memory); }
STEMWISE_ALLOCATION_HOOK void operator delete(void *memory, std::size_t) noexcept { std::free(memory); }
STEMWISE_ALLOCATION_HOOK void operator delete[](void *memory, std::size_t) noexcept { ::operator delete(memory); }

namespace {

using stemwise::audio::NativeAudioOutputFinalDeviceStage;
using stemwise::audio::OutputGenerationTransition;
using stemwise::audio::OutputOwnedFrameSpan;
using stemwise::audio::OutputTransitionCoverage;
using stemwise::audio::transitionTouchesAnyOwnedSpan;

constexpr float kTolerance = 1.0e-6F;

void require(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}

void requireNear(float actual, float expected, const char *message) {
    require(std::abs(actual - expected) <= kTolerance, message);
}

void finalOrderIsTransitionThenFiniteGuard() {
    NativeAudioOutputFinalDeviceStage finalStage;
    OutputGenerationTransition transition;
    OutputTransitionCoverage coverage;
    std::array<float, 32U> pcm{};
    for (std::size_t frame = 0U; frame < 16U; ++frame) {
        pcm[frame * 2U] = 0.50F;
        pcm[frame * 2U + 1U] = 0.50F;
    }

    transition.begin(0.10F, 0.10F, 16U);
    const std::size_t transformed = transition.apply(pcm.data(), 16U);
    require(coverage.record(0U, transformed), "record exact transformed prefix");
    finalStage.finalize(pcm.data(), 16U);

    require(transformed == 16U, "whole transition transformed");
    requireNear(pcm[0U], 0.125F, "generation transition begins at the exact mixed PCM");
    requireNear(pcm[30U], 0.50F, "transition endpoint preserves new PCM");
    require(!transition.active(), "transition completes exactly");
}

void everyPostStageSampleIsFiniteAndInsideDeviceCeilingWithoutAllocation() {
    NativeAudioOutputFinalDeviceStage finalStage;
    OutputGenerationTransition transition;
    stemwise::audio::OutputCallbackRecovery recovery;
    std::array<float, 32U> pcm{};
    for (std::size_t frame = 0U; frame < 16U; ++frame) {
        pcm[frame * 2U] = frame == 0U
            ? std::numeric_limits<float>::infinity()
            : 10.0F;
        pcm[frame * 2U + 1U] = frame == 0U
            ? std::numeric_limits<float>::quiet_NaN()
            : -10.0F;
    }

    allocation_probe::count = 0U;
    allocation_probe::enabled = true;
    transition.begin(0.98F, -0.98F, 16U);
    static_cast<void>(stemwise::audio::finalizeOutputCallback(
        pcm.data(), 16U, std::array{OutputOwnedFrameSpan{0U, 16U}}, true, std::numeric_limits<std::size_t>::max(),
        0.98F, -0.98F, 48'000, transition, finalStage, recovery));
    allocation_probe::enabled = false;

    require(allocation_probe::count == 0U, "final callback stages allocate nothing");
    for (float sample : pcm) {
        require(std::isfinite(sample), "final sample finite");
        require(
            std::abs(sample) <= NativeAudioOutputFinalDeviceStage::kDeviceCeiling,
            "final sample inside exact device ceiling");
    }
    require(
        finalStage.diagnostics().nonFiniteSamplesScrubbed == 2U,
        "both non-finite inputs scrubbed");
    require(finalStage.diagnostics().safetyClamps != 0U, "final finite ceiling guard clipped malformed input");

    std::array<float, 2U> quiet{0.5F, -0.5F};
    finalStage.finalize(quiet.data(), 1U);
    requireNear(quiet[0U], 0.5F, "the finite guard must not duck the next already-limited block");
    requireNear(quiet[1U], -0.5F, "the finite guard must not retain gain reduction");
}

void transitionCoverageDisqualifiesTheWholeOwnedCallback() {
    OutputTransitionCoverage coverage;
    require(coverage.record(8U, 4U), "record predecessor tail");
    require(coverage.record(12U, 6U), "record successor head");
    require(!coverage.overlaps(0U, 8U), "left-exclusive boundary does not overlap");
    require(coverage.overlaps(7U, 2U), "range entering transition overlaps");
    require(coverage.overlaps(17U, 1U), "last transformed frame overlaps");
    require(!coverage.overlaps(18U, 2U), "right-exclusive boundary does not overlap");

    constexpr std::array<OutputOwnedFrameSpan, 2U> mixedSpans{{
        {.outputOffset = 0U, .frameCount = 12U},
        {.outputOffset = 12U, .frameCount = 12U},
    }};
    require(
        transitionTouchesAnyOwnedSpan(coverage, mixedSpans.data(), mixedSpans.size()),
        "one transformed predecessor/successor span disqualifies whole callback evidence");

    constexpr std::array<OutputOwnedFrameSpan, 1U> untouched{{
        {.outputOffset = 24U, .frameCount = 8U},
    }};
    require(
        !transitionTouchesAnyOwnedSpan(coverage, untouched.data(), untouched.size()),
        "later untouched callback may establish evidence");
}

void underrunRecoveryUsesLastDeviceEndpoint() {
    NativeAudioOutputFinalDeviceStage finalStage;
    OutputGenerationTransition transition;
    stemwise::audio::OutputCallbackRecovery recovery;
    constexpr auto noOwnerChange = std::numeric_limits<std::size_t>::max();
    std::array<float, 256U> silence{};
    auto result = stemwise::audio::finalizeOutputCallback(
        silence.data(), 128U, {}, true, noOwnerChange, .4F, -.4F,
        48'000, transition, finalStage, recovery);
    require(result.complete && !transition.active(), "shortage finishes its fade");
    requireNear(silence.back(), 0.0F, "shortage endpoint is zero");
    std::array<float, 256U> recovered{};
    for (std::size_t frame = 0U; frame < 128U; ++frame) {
        recovered[frame * 2U] = .4F;
        recovered[frame * 2U + 1U] = -.4F;
    }
    result = stemwise::audio::finalizeOutputCallback(
        recovered.data(), 128U, std::array{OutputOwnedFrameSpan{0U, 128U}}, true, noOwnerChange, 0.0F, 0.0F,
        48'000, transition, finalStage, recovery);
    require(std::abs(recovered[0U]) <= .4F / 96.0F + kTolerance,
        "recovered same-owner PCM ramps from the emitted zero endpoint");
    require(result.coverage.overlaps(0U, 96U), "recovery is excluded from identity evidence");
    requireNear(recovered[254U], .4F, "recovery reaches untouched PCM without extra consumption");

    // Recovery may arrive before the shortage fade finishes, and may itself be partial.
    recovery.reset();
    transition.cancel();
    std::array<float, 16U> empty{};
    static_cast<void>(stemwise::audio::finalizeOutputCallback(empty.data(), 8U, {}, true,
        noOwnerChange, .4F, -.4F, 48'000, transition, finalStage, recovery));
    const float endpoint = empty[14U];
    std::array<float, 16U> partial{};
    std::fill_n(partial.data(), 8U, .4F);
    result = stemwise::audio::finalizeOutputCallback(partial.data(), 8U, std::array{OutputOwnedFrameSpan{0U, 4U}}, true,
        noOwnerChange, endpoint, -endpoint, 48'000, transition, finalStage, recovery);
    require(std::abs(partial[0U] - endpoint) <= .4F / 96.0F + kTolerance,
        "early recovery starts at the actual unfinished fade endpoint");
    require(result.coverage.overlaps(0U, 4U) && recovery.shortage,
        "partial recovered owner is transformed and the next shortage is retained");
    for (unsigned callback = 0U; callback < 16U; ++callback) {
        empty.fill(0.0F);
        const float from = partial[14U];
        static_cast<void>(stemwise::audio::finalizeOutputCallback(empty.data(), 8U, {}, true,
            noOwnerChange, from, from, 48'000, transition, finalStage, recovery));
        partial = empty;
    }
    requireNear(empty.back(), 0.0F, "repeated empty callbacks finish at silence");
    recovered.fill(.4F);
    result = stemwise::audio::finalizeOutputCallback(recovered.data(), 128U, std::array{OutputOwnedFrameSpan{0U, 128U}}, true,
        16U, 0.0F, 0.0F, 48'000, transition, finalStage, recovery);
    require(!recovery.shortage && result.coverage.overlaps(16U, 96U),
        "simultaneous owner or loop cutover supersedes same-owner recovery at its exact offset");
    recovery.reset();
    transition.cancel();
    recovered.fill(.4F);
    result = stemwise::audio::finalizeOutputCallback(recovered.data(), 128U, std::array{OutputOwnedFrameSpan{0U, 128U}}, true,
        noOwnerChange, 0.0F, 0.0F, 48'000, transition, finalStage, recovery);
    require(!result.coverage.overlaps(0U, 128U), "explicit reset retires shortage recovery");
}

void rejectedOwnedPcmStillReceivesSyntheticContinuityTreatment() {
    NativeAudioOutputFinalDeviceStage finalStage;
    OutputGenerationTransition transition;
    stemwise::audio::OutputCallbackRecovery recovery;
    std::array<float, 64U> pcm{};
    const auto result = stemwise::audio::finalizeOutputCallback(
        pcm.data(), 32U, {}, true, std::numeric_limits<std::size_t>::max(),
        .4F, -.4F, 48'000, transition, finalStage, recovery);
    require(pcm[0U] > 0.0F && pcm[1U] < 0.0F && recovery.shortage &&
        result.coverage.overlaps(0U, 32U),
        "consumed but unauthorized PCM must receive a synthetic shortage fade");

    transition.cancel();
    recovery.reset();
    std::array<float, 192U> interior{};
    std::fill_n(interior.data(), 64U, .4F);
    std::fill_n(interior.data() + 128U, 64U, .6F);
    constexpr std::array<OutputOwnedFrameSpan, 2U> owned{{{0U, 32U}, {64U, 32U}}};
    allocation_probe::count = 0U;
    allocation_probe::enabled = true;
    const auto middle = stemwise::audio::finalizeOutputCallback(
        interior.data(), 96U, owned, true, std::numeric_limits<std::size_t>::max(),
        .4F, .4F, 48'000, transition, finalStage, recovery);
    allocation_probe::enabled = false;
    require(allocation_probe::count == 0U && middle.complete,
        "position-aware synthetic treatment is bounded and allocation-free");
    requireNear(interior[62U], .4F, "valid prefix before the gap stays untouched");
    require(interior[64U] > 0.0F && interior[64U] < .4F &&
        interior[128U] > interior[126U] && interior[128U] < .6F,
        "interior rejection fades down and recovery begins at its actual endpoint");
    require(!middle.coverage.overlaps(0U, 32U) &&
        middle.coverage.overlaps(32U, 32U) && middle.coverage.overlaps(64U, 32U) &&
        !recovery.shortage,
        "interior synthetic frames never masquerade as an owned suffix");
}

}  // namespace

int main() {
    try {
        finalOrderIsTransitionThenFiniteGuard();
        everyPostStageSampleIsFiniteAndInsideDeviceCeilingWithoutAllocation();
        transitionCoverageDisqualifiesTheWholeOwnedCallback();
        underrunRecoveryUsesLastDeviceEndpoint();
        rejectedOwnedPcmStillReceivesSyntheticContinuityTreatment();
        std::cout << "Native output final-device stage tests passed\n";
        return 0;
    } catch (const std::exception &error) {
        allocation_probe::enabled = false;
        std::cerr << "Native output final-device stage test failed: "
                  << error.what() << '\n';
        return 1;
    }
}
