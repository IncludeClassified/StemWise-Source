#include "AllocationProbeAttributes.h"
#include "LoopHeadPreparationIngress.h"

#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <new>
#include <vector>

namespace allocation_probe {

std::atomic<bool> enabled{false};
std::atomic<std::uint64_t> count{0};

}  // namespace allocation_probe

STEMWISE_ALLOCATION_HOOK void *operator new(std::size_t size) {
    if (allocation_probe::enabled.load(std::memory_order_relaxed)) {
        allocation_probe::count.fetch_add(1U, std::memory_order_relaxed);
    }
    if (void *allocation = std::malloc(size)) return allocation;
    throw std::bad_alloc();
}

STEMWISE_ALLOCATION_HOOK void *operator new[](std::size_t size) {
    return ::operator new(size);
}

STEMWISE_ALLOCATION_HOOK void operator delete(void *allocation) noexcept {
    std::free(allocation);
}

STEMWISE_ALLOCATION_HOOK void operator delete[](void *allocation) noexcept {
    std::free(allocation);
}

STEMWISE_ALLOCATION_HOOK void operator delete(void *allocation, std::size_t) noexcept {
    std::free(allocation);
}

STEMWISE_ALLOCATION_HOOK void operator delete[](void *allocation, std::size_t) noexcept {
    std::free(allocation);
}

namespace {

using stemwise::audio::engine::LaneInputBlock;
using stemwise::audio::engine::LaneInputFormat;
using stemwise::audio::engine::LanePcmEncoding;
using stemwise::audio::engine::LoopHeadCache;
using stemwise::audio::engine::LoopHeadContentKey;
using stemwise::audio::engine::LoopHeadBoundaryMode;
using stemwise::audio::engine::LoopHeadPreparationBeginStatus;
using stemwise::audio::engine::LoopHeadPreparationConfigureStatus;
using stemwise::audio::engine::LoopHeadPreparationEndStatus;
using stemwise::audio::engine::LoopHeadPreparationIngress;
using stemwise::audio::engine::LoopHeadPreparationOfferStatus;
using stemwise::audio::engine::LoopHeadPreparationPhase;
static_assert(static_cast<int>(LoopHeadPreparationEndStatus::PaddingPending) == 13);

[[noreturn]] void failRequirement(
    const char *expression,
    const char *file,
    int line) noexcept {
    allocation_probe::enabled.store(false, std::memory_order_release);
    std::fprintf(
        stderr,
        "StemWise loop-head preparation requirement failed: %s (%s:%d)\n",
        expression,
        file,
        line);
    std::fflush(stderr);
    std::_Exit(EXIT_FAILURE);
}

#define REQUIRE(expression) \
    ((expression) ? static_cast<void>(0) : failRequirement(#expression, __FILE__, __LINE__))

[[nodiscard]] LoopHeadContentKey keyFor(
    std::uint64_t revision) noexcept {
    return {
        .songIdentityHash = 0x1111'2222'3333'4444ULL,
        .originalContentHash = 0x5555'6666'7777'8888ULL,
        .stemPackageHash = 0x9999'AAAA'BBBB'CCCCULL,
        .contentRevision = 9U,
        .loopRevision = revision,
        .startTimelineFrame = 0U,
        .endTimelineFrameExclusive = LoopHeadCache::kCapacityFrames,
    };
}

[[nodiscard]] std::uint64_t presentationTimeUs(
    std::uint64_t sourceFrame,
    std::uint32_t sourceRate) noexcept {
    return (sourceFrame * 1'000'000ULL + sourceRate / 2U) / sourceRate;
}

void fillPcm(std::vector<float> &pcm, std::size_t frames, float seed) noexcept {
    REQUIRE(pcm.size() >= frames * 2U);
    for (std::size_t frame = 0; frame < frames; ++frame) {
        const float sample = seed + static_cast<float>(frame % 197U) * 0.0001F;
        pcm[frame * 2U] = sample;
        pcm[frame * 2U + 1U] = -sample;
    }
}

struct FeedResult final {
    std::size_t inputFramesAccepted{0};
    LoopHeadPreparationOfferStatus terminal{
        LoopHeadPreparationOfferStatus::Accepted};
};

[[nodiscard]] FeedResult feed(
    LoopHeadPreparationIngress &preparation,
    std::uint64_t revision,
    std::size_t lane,
    std::uint64_t generation,
    std::uint32_t sourceRate,
    const std::vector<float> &pcm,
    std::size_t frames,
    std::size_t chunkFrames = 1'024U,
    std::uint64_t sourceOrigin = 0U) noexcept {
    FeedResult result{};
    while (result.inputFramesAccepted < frames) {
        const std::size_t remaining = frames - result.inputFramesAccepted;
        const std::size_t offeredFrames = std::min(chunkFrames, remaining);
        const auto offered = preparation.offer(
            revision,
            lane,
            LaneInputBlock{
                .data = pcm.data() + result.inputFramesAccepted * 2U,
                .frameCount = offeredFrames,
                .format = {
                    .sampleRateHz = sourceRate,
                    .channelCount = 2U,
                    .encoding = LanePcmEncoding::Float32,
                },
                .generation = generation,
                .presentationTimeUs = presentationTimeUs(
                    sourceOrigin + result.inputFramesAccepted,
                    sourceRate),
            });
        REQUIRE(offered.inputFramesAccepted <= offeredFrames);
        result.inputFramesAccepted += offered.inputFramesAccepted;
        result.terminal = offered.status;
        if (offered.status == LoopHeadPreparationOfferStatus::LaneCompleted ||
            offered.status == LoopHeadPreparationOfferStatus::CacheCommitted) {
            return result;
        }
        REQUIRE(offered.status == LoopHeadPreparationOfferStatus::Accepted ||
            offered.status == LoopHeadPreparationOfferStatus::Partial ||
            offered.status == LoopHeadPreparationOfferStatus::Backpressured);
        REQUIRE(offered.inputFramesAccepted != 0U);
    }
    return result;
}

void testSequentialResampledLanesCommitOneImmutableHead() {
    constexpr std::uint64_t revision = 1U;
    constexpr std::uint64_t loopFrames = 480U;
    constexpr std::uint64_t generation0 = 101U;
    constexpr std::uint64_t generation1 = 102U;
    LoopHeadPreparationIngress preparation;
    REQUIRE(preparation.begin(keyFor(revision), 0b11U) ==
        LoopHeadPreparationBeginStatus::Accepted);
    REQUIRE(preparation.configureLane(revision, 0U, 24'000U, generation0) ==
        LoopHeadPreparationConfigureStatus::Accepted);
    REQUIRE(preparation.configureLane(revision, 1U, 48'000U, generation1) ==
        LoopHeadPreparationConfigureStatus::LaneBusy);

    std::vector<float> first(240U * 2U);
    fillPcm(first, 240U, 0.1F);
    const FeedResult firstFeed = feed(
        preparation,
        revision,
        0U,
        generation0,
        24'000U,
        first,
        240U,
        73U);
    REQUIRE(firstFeed.inputFramesAccepted == 240U);
    REQUIRE(preparation.markEndOfStream(
        revision,
        0U,
        generation0,
        240U) == LoopHeadPreparationEndStatus::LaneCompleted);

    REQUIRE(preparation.configureLane(revision, 1U, 48'000U, generation1) ==
        LoopHeadPreparationConfigureStatus::Accepted);
    std::vector<float> second(loopFrames * 2U);
    fillPcm(second, loopFrames, 0.2F);
    const FeedResult secondFeed = feed(
        preparation,
        revision,
        1U,
        generation1,
        48'000U,
        second,
        loopFrames,
        91U);
    REQUIRE(secondFeed.inputFramesAccepted == loopFrames);
    const LoopHeadPreparationEndStatus ended = preparation.markEndOfStream(
        revision,
        1U,
        generation1,
        loopFrames);
    REQUIRE(ended == LoopHeadPreparationEndStatus::CacheCommitted ||
        secondFeed.terminal == LoopHeadPreparationOfferStatus::CacheCommitted);

    auto active = preparation.cache().acquireActive(keyFor(revision));
    REQUIRE(active.valid());
    REQUIRE(active.snapshot().frameCount == loopFrames);
    REQUIRE(active.snapshot().cacheOnly);
    REQUIRE(active.snapshot().requiredLaneMask == 0b11U);
    REQUIRE((active.snapshot().completedLaneMask & 0b11U) == 0b11U);
    REQUIRE(active.snapshot().laneChecksums[0] != 0U);
    REQUIRE(active.snapshot().laneChecksums[1] != 0U);
    REQUIRE(active.snapshot().combinedChecksum != 0U);
    REQUIRE(preparation.snapshot().phase == LoopHeadPreparationPhase::Committed);
}

void testNonzeroLoopHeadsPreserveTheRecordingPhaseAndHistory() {
    for (const auto rate : {8'000U, 44'100U, 48'000U, 96'000U}) {
        std::vector<float> pcm(rate * 3U * 2U);
        for (std::size_t frame = 0; frame < pcm.size() / 2U; ++frame) {
            const float sample = static_cast<float>(0.4 * std::sin(
                6.283185307179586 * (rate == 8'000U ? 2'600U : 10'000U) * frame / rate));
            pcm[frame * 2U] = sample; pcm[frame * 2U + 1U] = -sample;
        }
        const auto prepare = [&](std::uint64_t sourceOrigin, std::size_t partition) {
            LoopHeadPreparationIngress preparation;
            auto key = keyFor(100U);
            key.startTimelineFrame = 2'002U * 48U;
            key.endTimelineFrameExclusive = key.startTimelineFrame + 1'024U;
            REQUIRE(preparation.begin(key, 1U, LoopHeadBoundaryMode::RetainedWindow) ==
                LoopHeadPreparationBeginStatus::Accepted);
            REQUIRE(preparation.configureLane(100U, 0U, rate, 101U) ==
                LoopHeadPreparationConfigureStatus::Accepted);
            std::vector<float> suffix(pcm.begin() + static_cast<std::ptrdiff_t>(sourceOrigin * 2U), pcm.end());
            const auto fed = feed(preparation, 100U, 0U, 101U, rate, suffix,
                suffix.size() / 2U, partition, sourceOrigin);
            REQUIRE(fed.terminal == LoopHeadPreparationOfferStatus::CacheCommitted);
            auto view = preparation.cache().acquireActive(key);
            REQUIRE(view.valid());
            std::vector<float> output(2'048U);
            REQUIRE(view.copyLane(0U, key.startTimelineFrame, output.data(), 1'024U, 1'024U));
            return output;
        };
        const auto baseline = prepare(0U, 1'024U);
        REQUIRE(prepare(rate, 17U) == baseline);
        REQUIRE(prepare(rate, 193U) == baseline);
        if (rate == 44'100U) REQUIRE(prepare(88'245U, 193U) != baseline);
    }
}

void testLongLoopClampsAtFourSecondsWithoutAllocatingDuringOffers() {
    constexpr std::uint64_t revision = 2U;
    constexpr std::uint64_t generation = 201U;
    constexpr std::size_t decodedFrames = LoopHeadCache::kCapacityFrames + 8'192U;

    LoopHeadPreparationIngress preparation;
    REQUIRE(preparation.begin(keyFor(revision), 1U) ==
        LoopHeadPreparationBeginStatus::Accepted);
    REQUIRE(preparation.configureLane(revision, 0U, 48'000U, generation) ==
        LoopHeadPreparationConfigureStatus::Accepted);

    std::vector<float> pcm(decodedFrames * 2U);
    fillPcm(pcm, decodedFrames, 0.3F);
    allocation_probe::count.store(0U, std::memory_order_release);
    allocation_probe::enabled.store(true, std::memory_order_release);
    const FeedResult fed = feed(
        preparation,
        revision,
        0U,
        generation,
        48'000U,
        pcm,
        decodedFrames,
        1'333U);
    allocation_probe::enabled.store(false, std::memory_order_release);

    REQUIRE(fed.terminal == LoopHeadPreparationOfferStatus::CacheCommitted);
    REQUIRE(allocation_probe::count.load(std::memory_order_acquire) == 0U);
    auto active = preparation.cache().acquireActive(keyFor(revision));
    REQUIRE(active.valid());
    REQUIRE(active.snapshot().frameCount == LoopHeadCache::kCapacityFrames);
    REQUIRE(!active.snapshot().cacheOnly);
    REQUIRE(preparation.snapshot().cacheFramesWritten == LoopHeadCache::kCapacityFrames);
}

void testTimestampGapFailsClosed() {
    constexpr std::uint64_t revision = 3U;
    constexpr std::uint64_t generation = 301U;
    LoopHeadPreparationIngress preparation;
    REQUIRE(preparation.begin(keyFor(revision), 1U) ==
        LoopHeadPreparationBeginStatus::Accepted);
    REQUIRE(preparation.configureLane(revision, 0U, 48'000U, generation) ==
        LoopHeadPreparationConfigureStatus::Accepted);

    std::vector<float> pcm(200U * 2U);
    fillPcm(pcm, 200U, 0.4F);
    const auto first = preparation.offer(
        revision,
        0U,
        LaneInputBlock{
            .data = pcm.data(),
            .frameCount = 100U,
            .format = {48'000U, 2U, LanePcmEncoding::Float32},
            .generation = generation,
            .presentationTimeUs = 0U,
        });
    REQUIRE(first.status == LoopHeadPreparationOfferStatus::Accepted);
    const auto gap = preparation.offer(
        revision,
        0U,
        LaneInputBlock{
            .data = pcm.data() + 200U,
            .frameCount = 100U,
            .format = {48'000U, 2U, LanePcmEncoding::Float32},
            .generation = generation,
            .presentationTimeUs = presentationTimeUs(200U, 48'000U),
        });
    REQUIRE(gap.status == LoopHeadPreparationOfferStatus::TimelineDiscontinuity);
    REQUIRE(preparation.snapshot().phase == LoopHeadPreparationPhase::Failed);
    REQUIRE(!preparation.cache().acquireActive().valid());
}

void testRetainedWindowPadsShortStemThroughCommit() {
    constexpr std::size_t frames = 240'000U;
    constexpr std::size_t supplied = 1'000U;
    auto key = keyFor(10U);
    key.endTimelineFrameExclusive = frames;
    LoopHeadPreparationIngress preparation;
    REQUIRE(preparation.begin(key, 3U, LoopHeadBoundaryMode::RetainedWindow) ==
        LoopHeadPreparationBeginStatus::Accepted);
    REQUIRE(preparation.configureLane(10U, 0U, 48'000U, 100U) == LoopHeadPreparationConfigureStatus::Accepted);
    std::vector<float> pcm(frames * 2U);
    fillPcm(pcm, frames, 0.25F);
    REQUIRE(feed(preparation, 10U, 0U, 100U, 48'000U, pcm, frames).inputFramesAccepted == frames);
    REQUIRE(preparation.configureLane(10U, 1U, 48'000U, 101U) == LoopHeadPreparationConfigureStatus::Accepted);
    REQUIRE(feed(preparation, 10U, 1U, 101U, 48'000U, pcm, supplied).inputFramesAccepted == supplied);
    REQUIRE(preparation.markEndOfStream(10U, 1U, 101U, supplied - 1U) ==
        LoopHeadPreparationEndStatus::BoundaryMismatch);
    bool committed = false;
    for (std::size_t attempt = 0U; attempt < 256U && !committed; ++attempt) {
        const auto before = preparation.cache().diagnostics().silenceFramesWritten;
        const auto ended = preparation.markEndOfStream(10U, 1U, 101U, supplied);
        const auto after = preparation.cache().diagnostics().silenceFramesWritten;
        REQUIRE(after > before && after - before <= 2'048U);
        REQUIRE(ended == LoopHeadPreparationEndStatus::PaddingPending ||
            ended == LoopHeadPreparationEndStatus::CacheCommitted);
        REQUIRE(preparation.snapshot().phase != LoopHeadPreparationPhase::Failed);
        committed = ended == LoopHeadPreparationEndStatus::CacheCommitted;
    }
    REQUIRE(committed);
    auto view = preparation.cache().acquireActive(key);
    std::vector<float> output(frames * 2U);
    REQUIRE(view.copyLane(1U, 0U, output.data(), frames, frames));
    for (std::size_t sample = 0U; sample < output.size(); ++sample)
        REQUIRE(output[sample] == (sample < supplied * 2U ? pcm[sample] : 0.0F));
}

void testPendingPaddingRetainsExactOwnerUntilCancellationOrReplacement() {
    for (const bool replace : {false, true}) {
        auto key = keyFor(20U);
        key.endTimelineFrameExclusive = 240'000U;
        LoopHeadPreparationIngress preparation;
        REQUIRE(preparation.begin(key, 3U, LoopHeadBoundaryMode::RetainedWindow) ==
            LoopHeadPreparationBeginStatus::Accepted);
        std::vector<float> pcm(240'000U * 2U, 0.25F);
        REQUIRE(preparation.configureLane(20U, 0U, 48'000U, 200U) == LoopHeadPreparationConfigureStatus::Accepted);
        REQUIRE(feed(preparation, 20U, 0U, 200U, 48'000U, pcm, 240'000U).inputFramesAccepted == 240'000U);
        REQUIRE(preparation.configureLane(20U, 1U, 48'000U, 201U) == LoopHeadPreparationConfigureStatus::Accepted);
        REQUIRE(feed(preparation, 20U, 1U, 201U, 48'000U, pcm, 1'000U).inputFramesAccepted == 1'000U);
        REQUIRE(preparation.markEndOfStream(20U, 1U, 201U, 1'000U) == LoopHeadPreparationEndStatus::PaddingPending);
        REQUIRE(preparation.markEndOfStream(19U, 1U, 201U, 1'000U) == LoopHeadPreparationEndStatus::StaleRevision);
        REQUIRE(preparation.markEndOfStream(20U, 1U, 202U, 1'000U) == LoopHeadPreparationEndStatus::StaleGeneration);
        REQUIRE(preparation.markEndOfStream(20U, 1U, 201U, 1'001U) == LoopHeadPreparationEndStatus::BoundaryMismatch);
        REQUIRE(!preparation.cache().acquireActive().valid());
        if (!replace) {
            REQUIRE(preparation.abort(20U) == stemwise::audio::engine::LoopHeadPreparationAbortStatus::Accepted);
            REQUIRE(preparation.markEndOfStream(20U, 1U, 201U, 1'000U) == LoopHeadPreparationEndStatus::NoPreparation);
            REQUIRE(!preparation.cache().acquireActive().valid());
            continue;
        }
        key.loopRevision = 21U;
        REQUIRE(preparation.begin(key, 3U, LoopHeadBoundaryMode::RetainedWindow) == LoopHeadPreparationBeginStatus::Accepted);
        REQUIRE(preparation.markEndOfStream(20U, 1U, 201U, 1'000U) == LoopHeadPreparationEndStatus::StaleRevision);
        for (std::size_t lane = 0U; lane < 2U; ++lane) {
            REQUIRE(preparation.configureLane(21U, lane, 48'000U, 210U + lane) == LoopHeadPreparationConfigureStatus::Accepted);
            REQUIRE(feed(preparation, 21U, lane, 210U + lane, 48'000U, pcm, 240'000U).inputFramesAccepted == 240'000U);
        }
        REQUIRE(preparation.snapshot().phase == LoopHeadPreparationPhase::Committed);
        auto view = preparation.cache().acquireActive(key);
        std::vector<float> output(240'000U * 2U);
        REQUIRE(view.copyLane(1U, 0U, output.data(), 240'000U, 240'000U));
        REQUIRE(output == pcm);
    }
}

}  // namespace

int main() {
    testSequentialResampledLanesCommitOneImmutableHead();
    testNonzeroLoopHeadsPreserveTheRecordingPhaseAndHistory();
    testLongLoopClampsAtFourSecondsWithoutAllocatingDuringOffers();
    testTimestampGapFailsClosed();
    testRetainedWindowPadsShortStemThroughCommit();
    testPendingPaddingRetainsExactOwnerUntilCancellationOrReplacement();
    std::puts("StemWise loop-head preparation ingress tests passed");
    return EXIT_SUCCESS;
}
