#include "AllocationProbeAttributes.h"
#include "LoopHeadCache.h"

#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <limits>
#include <new>
#include <thread>
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

using stemwise::audio::engine::LoopHeadAbortStatus;
using stemwise::audio::engine::LoopHeadBeginStatus;
using stemwise::audio::engine::LoopHeadBoundaryMode;
using stemwise::audio::engine::LoopHeadCache;
using stemwise::audio::engine::LoopHeadCommitStatus;
using stemwise::audio::engine::LoopHeadContentKey;
using stemwise::audio::engine::LoopHeadSealStatus;
using stemwise::audio::engine::LoopHeadWriteStatus;
using stemwise::audio::engine::kLoopHeadAllLanesMask;
using stemwise::audio::engine::kLoopHeadLaneCount;

[[noreturn]] void failRequirement(
    const char *expression,
    const char *file,
    int line) noexcept {
    allocation_probe::enabled.store(false, std::memory_order_release);
    std::fprintf(
        stderr,
        "StemWise loop-head cache requirement failed: %s (%s:%d)\n",
        expression,
        file,
        line);
    std::fflush(stderr);
    std::_Exit(EXIT_FAILURE);
}

#define REQUIRE(expression) \
    ((expression) ? static_cast<void>(0) : failRequirement(#expression, __FILE__, __LINE__))

constexpr std::uint64_t kDefaultStartFrame = 96'000U;

[[nodiscard]] LoopHeadContentKey makeKey(
    std::uint64_t loopRevision,
    std::uint64_t startFrame = kDefaultStartFrame) noexcept {
    return {
        .songIdentityHash = 0x1111'2222'3333'4444ULL,
        .originalContentHash = 0x5555'6666'7777'8888ULL,
        .stemPackageHash = 0x9999'AAAA'BBBB'CCCCULL,
        .contentRevision = 17U,
        .loopRevision = loopRevision,
        .startTimelineFrame = startFrame,
        .endTimelineFrameExclusive = startFrame + LoopHeadCache::kCapacityFrames,
    };
}

[[nodiscard]] float expectedSample(
    std::size_t lane,
    std::size_t frame,
    std::size_t channel) noexcept {
    return static_cast<float>(lane + 1U) * 0.125F +
        static_cast<float>(frame % 251U) / 16'384.0F +
        static_cast<float>(channel) / 131'072.0F;
}

void fillLane(
    std::vector<float> &pcm,
    std::size_t frameCount,
    std::size_t lane) noexcept {
    REQUIRE(pcm.size() >= frameCount * LoopHeadCache::kChannelCount);
    for (std::size_t frame = 0; frame < frameCount; ++frame) {
        for (std::size_t channel = 0;
             channel < LoopHeadCache::kChannelCount;
             ++channel) {
            pcm[frame * LoopHeadCache::kChannelCount + channel] =
                expectedSample(lane, frame, channel);
        }
    }
}

void stageLane(
    LoopHeadCache &cache,
    const LoopHeadContentKey &key,
    std::size_t lane,
    const float *pcm,
    std::size_t frameCount) noexcept {
    const auto write = cache.writeStaging(
        key.loopRevision,
        lane,
        key.startTimelineFrame,
        pcm,
        frameCount);
    if (lane == 0U && frameCount < LoopHeadCache::kCapacityFrames) {
        REQUIRE(write.status == LoopHeadWriteStatus::Accepted);
        REQUIRE(cache.sealStagingLaneWithSilence(key.loopRevision, lane,
                    key.startTimelineFrame + frameCount) == LoopHeadSealStatus::Accepted);
    } else {
        REQUIRE(write.status == LoopHeadWriteStatus::LaneComplete);
    }
    REQUIRE(write.inputFramesConsumed == frameCount);
    REQUIRE(write.framesWritten == frameCount);
    REQUIRE(write.nextExpectedTimelineFrame ==
            key.startTimelineFrame + frameCount);
}

void fixedCapacityAndBoundaryClassificationAreExact() {
    static_assert(LoopHeadCache::kSampleRateHz == 48'000U);
    static_assert(LoopHeadCache::kCapacityFrames == 192'000U);
    static_assert(LoopHeadCache::kSlotCount == 2U);
    static_assert(kLoopHeadLaneCount == stemwise::audio::kStemwiseMusicBusCount);
    static_assert(LoopHeadCache::kPcmBytesPerSlot == 15'360'000U);
    static_assert(LoopHeadCache::kTotalPcmBytes == 30'720'000U);

    constexpr std::array<std::uint64_t, 5> lengths{
        4'800U,
        191'999U,
        192'000U,
        192'001U,
        30U * LoopHeadCache::kSampleRateHz,
    };
    std::vector<float> pcm(
        static_cast<std::size_t>(LoopHeadCache::kCapacityFrames) *
        LoopHeadCache::kChannelCount);
    fillLane(pcm, LoopHeadCache::kCapacityFrames, 0U);
    LoopHeadCache cache;

    for (std::size_t index = 0; index < lengths.size(); ++index) {
        const auto key = makeKey(index + 1U);
        const std::size_t expectedFrames = static_cast<std::size_t>(
            std::min<std::uint64_t>(lengths[index], LoopHeadCache::kCapacityFrames));
        REQUIRE(cache.beginStaging(key, 1U) == LoopHeadBeginStatus::Accepted);
        stageLane(cache, key, 0U, pcm.data(), expectedFrames);
        REQUIRE(cache.commitStaging(key.loopRevision) ==
                LoopHeadCommitStatus::Accepted);

        {
            auto active = cache.acquireActive(key);
            REQUIRE(active.valid());
            REQUIRE(active.snapshot().frameCount == expectedFrames);
            REQUIRE(active.snapshot().cacheOnly ==
                    (lengths[index] < LoopHeadCache::kCapacityFrames));
            REQUIRE(active.snapshot().requiredLaneMask == 1U);
            REQUIRE(active.snapshot().completedLaneMask == 1U);
            REQUIRE(active.snapshot().laneChecksums[0] != 0U);
            REQUIRE(active.snapshot().combinedChecksum != 0U);
        }
    }
}

void oversizedWritesClampAtTheFixedHeadAndLeaveTheBodyUnconsumed() {
    constexpr std::size_t inputFrames = LoopHeadCache::kCapacityFrames + 1U;
    const auto key = makeKey(1U);
    std::vector<float> pcm(inputFrames * LoopHeadCache::kChannelCount);
    fillLane(pcm, inputFrames, 0U);
    LoopHeadCache cache;

    REQUIRE(cache.beginStaging(key, 1U) == LoopHeadBeginStatus::Accepted);
    const auto write = cache.writeStaging(
        key.loopRevision,
        0U,
        key.startTimelineFrame,
        pcm.data(),
        inputFrames);
    REQUIRE(write.status == LoopHeadWriteStatus::LaneComplete);
    REQUIRE(write.inputFramesConsumed == LoopHeadCache::kCapacityFrames);
    REQUIRE(write.framesWritten == LoopHeadCache::kCapacityFrames);
    REQUIRE(write.nextExpectedTimelineFrame ==
            key.startTimelineFrame + LoopHeadCache::kCapacityFrames);
    REQUIRE(cache.commitStaging(key.loopRevision) == LoopHeadCommitStatus::Accepted);
    auto active = cache.acquireActive(key);
    REQUIRE(active.valid());
    REQUIRE(active.snapshot().frameCount == LoopHeadCache::kCapacityFrames);
    REQUIRE(!active.snapshot().cacheOnly);
}

void sevenCanonicalLanesRemainDistinctAndImmutable() {
    constexpr std::size_t frameCount = 4'800U;
    const auto key = makeKey(1U);
    std::vector<float> pcm(frameCount * LoopHeadCache::kChannelCount);
    LoopHeadCache cache;

    REQUIRE(cache.beginStaging(key, kLoopHeadAllLanesMask) ==
            LoopHeadBeginStatus::Accepted);
    for (std::size_t lane = 0; lane < kLoopHeadLaneCount; ++lane) {
        fillLane(pcm, frameCount, lane);
        stageLane(cache, key, lane, pcm.data(), frameCount);
    }
    REQUIRE(cache.commitStaging(key.loopRevision) == LoopHeadCommitStatus::Accepted);

    auto active = cache.acquireActive(key);
    REQUIRE(active.valid());
    REQUIRE(active.snapshot().requiredLaneMask == kLoopHeadAllLanesMask);
    REQUIRE(active.snapshot().completedLaneMask == kLoopHeadAllLanesMask);
    for (std::size_t lane = 0; lane < kLoopHeadLaneCount; ++lane) {
        REQUIRE(active.snapshot().laneChecksums[lane] != 0U);
        for (std::size_t other = 0; other < lane; ++other) {
            REQUIRE(active.snapshot().laneChecksums[lane] !=
                    active.snapshot().laneChecksums[other]);
        }

        constexpr std::size_t firstFrame = 123U;
        constexpr std::size_t copiedFrames = 8U;
        std::array<float, copiedFrames * LoopHeadCache::kChannelCount> output{};
        REQUIRE(active.copyLane(
            lane,
            key.startTimelineFrame + firstFrame,
            output.data(),
            copiedFrames,
            copiedFrames));
        for (std::size_t frame = 0; frame < copiedFrames; ++frame) {
            for (std::size_t channel = 0;
                 channel < LoopHeadCache::kChannelCount;
                 ++channel) {
                REQUIRE(output[frame * LoopHeadCache::kChannelCount + channel] ==
                        expectedSample(lane, firstFrame + frame, channel));
            }
        }
    }

    REQUIRE(!active.copyLane(
        kLoopHeadLaneCount,
        key.startTimelineFrame,
        pcm.data(),
        frameCount,
        1U));
    REQUIRE(!active.copyLane(
        0U,
        key.startTimelineFrame - 1U,
        pcm.data(),
        frameCount,
        1U));
    REQUIRE(!active.copyLane(
        0U,
        key.startTimelineFrame + frameCount,
        pcm.data(),
        frameCount,
        1U));

    auto wrongKey = key;
    wrongKey.originalContentHash += 1U;
    REQUIRE(!cache.acquireActive(wrongKey).valid());
    REQUIRE(cache.diagnostics().activeViewMisses == 1U);
}

void newestRevisionSupersedesStagingAndAbortNeverMutatesActive() {
    std::array<float, 16> pcm{};
    for (std::size_t sample = 0; sample < pcm.size(); ++sample) {
        pcm[sample] = static_cast<float>(sample + 1U) * 0.01F;
    }
    LoopHeadCache cache;

    REQUIRE(cache.beginStaging(makeKey(1U), 1U, LoopHeadBoundaryMode::Unresolved) ==
            LoopHeadBeginStatus::InvalidKey);
    auto authoredBoundary = makeKey(1U);
    authoredBoundary.endTimelineFrameExclusive = authoredBoundary.startTimelineFrame + 480U;
    REQUIRE(cache.beginStaging(authoredBoundary, 1U) == LoopHeadBeginStatus::InvalidKey);
    auto invalid = makeKey(1U);
    invalid.songIdentityHash = 0U;
    REQUIRE(cache.beginStaging(invalid, 1U) == LoopHeadBeginStatus::InvalidKey);
    REQUIRE(cache.beginStaging(makeKey(1U), 0U) ==
            LoopHeadBeginStatus::InvalidLaneMask);
    REQUIRE(cache.beginStaging(makeKey(1U), 2U) ==
            LoopHeadBeginStatus::InvalidLaneMask);
    auto missingStemIdentity = makeKey(1U);
    missingStemIdentity.stemPackageHash = 0U;
    REQUIRE(cache.beginStaging(missingStemIdentity, 3U) ==
            LoopHeadBeginStatus::InvalidKey);
    REQUIRE(cache.beginStaging(missingStemIdentity, 1U) ==
            LoopHeadBeginStatus::Accepted);
    REQUIRE(cache.abortStaging(missingStemIdentity.loopRevision) ==
            LoopHeadAbortStatus::Accepted);

    const auto first = makeKey(10U);
    REQUIRE(cache.beginStaging(first, 1U) == LoopHeadBeginStatus::Accepted);
    const auto partial = cache.writeStaging(
        first.loopRevision,
        0U,
        first.startTimelineFrame,
        pcm.data(),
        4U);
    REQUIRE(partial.status == LoopHeadWriteStatus::Accepted);
    REQUIRE(cache.commitStaging(first.loopRevision) ==
            LoopHeadCommitStatus::MissingRequiredLanes);

    const auto second = makeKey(11U);
    REQUIRE(cache.beginStaging(second, 1U) == LoopHeadBeginStatus::Accepted);
    REQUIRE(cache.writeStaging(
                first.loopRevision,
                0U,
                first.startTimelineFrame + 4U,
                pcm.data() + 8U,
                4U)
                .status == LoopHeadWriteStatus::StaleRevision);
    REQUIRE(cache.abortStaging(first.loopRevision) == LoopHeadAbortStatus::StaleRevision);
    REQUIRE(cache.abortStaging(second.loopRevision) == LoopHeadAbortStatus::Accepted);
    REQUIRE(!cache.acquireActive().valid());
    REQUIRE(cache.beginStaging(second, 3U) == LoopHeadBeginStatus::StaleRevision);
    REQUIRE(cache.beginStaging(second, 1U) == LoopHeadBeginStatus::StaleRevision);

    const auto committed = makeKey(12U);
    REQUIRE(cache.beginStaging(committed, 1U) == LoopHeadBeginStatus::Accepted);
    stageLane(cache, committed, 0U, pcm.data(), 8U);
    REQUIRE(cache.commitStaging(committed.loopRevision) ==
            LoopHeadCommitStatus::Accepted);
    const auto beforeAbortChecksum =
        cache.acquireActive(committed).snapshot().combinedChecksum;

    const auto aborted = makeKey(13U);
    REQUIRE(cache.beginStaging(aborted, 1U) == LoopHeadBeginStatus::Accepted);
    stageLane(cache, aborted, 0U, pcm.data(), 8U);
    REQUIRE(cache.abortStaging(aborted.loopRevision) == LoopHeadAbortStatus::Accepted);
    auto stillCommitted = cache.acquireActive(committed);
    REQUIRE(stillCommitted.valid());
    REQUIRE(stillCommitted.snapshot().combinedChecksum == beforeAbortChecksum);
}

void globalRevisionOrderingCrossesSongAndContentBoundaries() {
    std::array<float, 8> pcm{
        1.0F, -1.0F,
        2.0F, -2.0F,
        3.0F, -3.0F,
        4.0F, -4.0F,
    };
    LoopHeadCache cache;
    const auto firstSong = makeKey(100U);
    REQUIRE(cache.beginStaging(firstSong, 1U) == LoopHeadBeginStatus::Accepted);
    stageLane(cache, firstSong, 0U, pcm.data(), 4U);
    REQUIRE(cache.commitStaging(firstSong.loopRevision) ==
            LoopHeadCommitStatus::Accepted);

    auto staleSecondSong = makeKey(1U);
    staleSecondSong.songIdentityHash += 1U;
    staleSecondSong.originalContentHash += 1U;
    staleSecondSong.contentRevision += 1U;
    REQUIRE(cache.beginStaging(staleSecondSong, 1U) ==
            LoopHeadBeginStatus::StaleRevision);

    auto newerSecondSong = staleSecondSong;
    newerSecondSong.loopRevision = 101U;
    REQUIRE(cache.beginStaging(newerSecondSong, 1U) == LoopHeadBeginStatus::Accepted);
    stageLane(cache, newerSecondSong, 0U, pcm.data(), 4U);
    REQUIRE(cache.commitStaging(newerSecondSong.loopRevision) ==
            LoopHeadCommitStatus::Accepted);
    REQUIRE(cache.acquireActive(newerSecondSong).valid());
}

void checksumDoesNotDependOnChunkingOrSafeRepeatedPrefixes() {
    constexpr std::size_t frames = 257U;
    const auto key = makeKey(1U);
    std::vector<float> pcm(frames * LoopHeadCache::kChannelCount);
    fillLane(pcm, frames, 0U);
    LoopHeadCache contiguous;
    LoopHeadCache chunked;

    REQUIRE(contiguous.beginStaging(key, 1U) == LoopHeadBeginStatus::Accepted);
    stageLane(contiguous, key, 0U, pcm.data(), frames);
    REQUIRE(contiguous.commitStaging(key.loopRevision) ==
            LoopHeadCommitStatus::Accepted);

    REQUIRE(chunked.beginStaging(key, 1U) == LoopHeadBeginStatus::Accepted);
    const auto first = chunked.writeStaging(
        key.loopRevision,
        0U,
        key.startTimelineFrame,
        pcm.data(),
        100U);
    REQUIRE(first.status == LoopHeadWriteStatus::Accepted);
    const auto second = chunked.writeStaging(
        key.loopRevision,
        0U,
        key.startTimelineFrame + 90U,
        pcm.data() + 90U * LoopHeadCache::kChannelCount,
        80U);
    REQUIRE(second.status == LoopHeadWriteStatus::Accepted);
    REQUIRE(second.inputFramesConsumed == 80U);
    REQUIRE(second.framesWritten == 70U);
    const auto third = chunked.writeStaging(
        key.loopRevision,
        0U,
        key.startTimelineFrame + 170U,
        pcm.data() + 170U * LoopHeadCache::kChannelCount,
        frames - 170U);
    REQUIRE(third.status == LoopHeadWriteStatus::Accepted);
    REQUIRE(chunked.sealStagingLaneWithSilence(key.loopRevision, 0U,
                key.startTimelineFrame + frames) == LoopHeadSealStatus::Accepted);
    REQUIRE(chunked.commitStaging(key.loopRevision) == LoopHeadCommitStatus::Accepted);

    const auto contiguousSnapshot = contiguous.acquireActive(key).snapshot();
    const auto chunkedSnapshot = chunked.acquireActive(key).snapshot();
    REQUIRE(contiguousSnapshot.laneChecksums[0] == chunkedSnapshot.laneChecksums[0]);
    REQUIRE(contiguousSnapshot.combinedChecksum == chunkedSnapshot.combinedChecksum);
}

void strictAppendRejectsGapsAndNonFiniteSamplesWithoutPartialMutation() {
    const auto key = makeKey(1U);
    LoopHeadCache cache;
    REQUIRE(cache.beginStaging(key, 1U) == LoopHeadBeginStatus::Accepted);

    constexpr std::array<float, 8> first{
        1.0F, -1.0F,
        2.0F, -2.0F,
        3.0F, -3.0F,
        4.0F, -4.0F,
    };
    constexpr std::array<float, 8> overlapping{
        3.0F, -3.0F,
        4.0F, -4.0F,
        5.0F, -5.0F,
        6.0F, -6.0F,
    };
    constexpr std::array<float, 2> gap{8.0F, -8.0F};
    const std::array<float, 4> nonFinite{
        std::numeric_limits<float>::quiet_NaN(), -7.0F,
        8.0F, -8.0F,
    };
    constexpr std::array<float, 4> tail{
        7.0F, -7.0F,
        8.0F, -8.0F,
    };

    const auto firstWrite = cache.writeStaging(
        key.loopRevision,
        0U,
        key.startTimelineFrame,
        first.data(),
        4U);
    REQUIRE(firstWrite.status == LoopHeadWriteStatus::Accepted);
    REQUIRE(firstWrite.framesWritten == 4U);

    const auto overlapWrite = cache.writeStaging(
        key.loopRevision,
        0U,
        key.startTimelineFrame + 2U,
        overlapping.data(),
        4U);
    REQUIRE(overlapWrite.status == LoopHeadWriteStatus::Accepted);
    REQUIRE(overlapWrite.inputFramesConsumed == 4U);
    REQUIRE(overlapWrite.framesWritten == 2U);
    REQUIRE(overlapWrite.nextExpectedTimelineFrame == key.startTimelineFrame + 6U);

    const auto gapWrite = cache.writeStaging(
        key.loopRevision,
        0U,
        key.startTimelineFrame + 7U,
        gap.data(),
        1U);
    REQUIRE(gapWrite.status == LoopHeadWriteStatus::GapRejected);
    REQUIRE(gapWrite.nextExpectedTimelineFrame == key.startTimelineFrame + 6U);

    const auto invalidWrite = cache.writeStaging(
        key.loopRevision,
        0U,
        key.startTimelineFrame + 6U,
        nonFinite.data(),
        2U);
    REQUIRE(invalidWrite.status == LoopHeadWriteStatus::NonFinitePcm);
    REQUIRE(invalidWrite.framesWritten == 0U);
    REQUIRE(invalidWrite.nextExpectedTimelineFrame == key.startTimelineFrame + 6U);

    const auto tailWrite = cache.writeStaging(
        key.loopRevision,
        0U,
        key.startTimelineFrame + 6U,
        tail.data(),
        2U);
    REQUIRE(tailWrite.status == LoopHeadWriteStatus::Accepted);
    REQUIRE(cache.sealStagingLaneWithSilence(key.loopRevision, 0U,
                key.startTimelineFrame + 8U) == LoopHeadSealStatus::Accepted);
    REQUIRE(cache.commitStaging(key.loopRevision) == LoopHeadCommitStatus::Accepted);

    std::array<float, 16> output{};
    auto active = cache.acquireActive(key);
    REQUIRE(active.copyLane(
        0U,
        key.startTimelineFrame,
        output.data(),
        8U,
        8U));
    for (std::size_t frame = 0; frame < 8U; ++frame) {
        REQUIRE(output[frame * 2U] == static_cast<float>(frame + 1U));
        REQUIRE(output[frame * 2U + 1U] == -static_cast<float>(frame + 1U));
    }

    const auto diagnostics = cache.diagnostics();
    REQUIRE(diagnostics.overlappedFramesDiscarded == 2U);
    REQUIRE(diagnostics.gapRejections == 1U);
    REQUIRE(diagnostics.nonFiniteRejections == 1U);
    REQUIRE(diagnostics.writtenFrames == 8U);
}

void commitWaitsForEveryRequiredLane() {
    constexpr std::size_t frames = 32U;
    const auto key = makeKey(1U);
    std::vector<float> pcm(frames * LoopHeadCache::kChannelCount);
    LoopHeadCache cache;
    constexpr std::uint32_t originalAndFirstStem = (1U << 0U) | (1U << 1U);

    REQUIRE(cache.beginStaging(key, originalAndFirstStem) ==
            LoopHeadBeginStatus::Accepted);
    fillLane(pcm, frames, 0U);
    stageLane(cache, key, 0U, pcm.data(), frames);
    REQUIRE(cache.commitStaging(key.loopRevision) ==
            LoopHeadCommitStatus::MissingRequiredLanes);
    fillLane(pcm, frames, 1U);
    stageLane(cache, key, 1U, pcm.data(), frames);
    REQUIRE(cache.commitStaging(key.loopRevision) == LoopHeadCommitStatus::Accepted);
    REQUIRE(cache.acquireActive(key).valid());
}

void oldReaderDefersReuseWithoutBlockingNewPublication() {
    constexpr std::size_t frames = 16U;
    std::vector<float> pcm(frames * LoopHeadCache::kChannelCount);
    fillLane(pcm, frames, 0U);
    LoopHeadCache cache;

    const auto first = makeKey(1U);
    REQUIRE(cache.beginStaging(first, 1U) == LoopHeadBeginStatus::Accepted);
    stageLane(cache, first, 0U, pcm.data(), frames);
    REQUIRE(cache.commitStaging(first.loopRevision) == LoopHeadCommitStatus::Accepted);
    auto heldFirst = cache.acquireActive(first);
    REQUIRE(heldFirst.valid());
    const std::uint32_t firstSlot = heldFirst.snapshot().slotIndex;

    const auto second = makeKey(2U);
    REQUIRE(cache.beginStaging(second, 1U) == LoopHeadBeginStatus::Accepted);
    stageLane(cache, second, 0U, pcm.data(), frames);
    REQUIRE(cache.commitStaging(second.loopRevision) == LoopHeadCommitStatus::Accepted);
    auto current = cache.acquireActive(second);
    REQUIRE(current.valid());
    REQUIRE(current.snapshot().slotIndex != firstSlot);

    const auto third = makeKey(3U);
    REQUIRE(cache.beginStaging(third, 1U) == LoopHeadBeginStatus::ReadersActive);
    REQUIRE(cache.acquireActive(second).valid());
    heldFirst = LoopHeadCache::ReadView{};
    REQUIRE(cache.beginStaging(third, 1U) == LoopHeadBeginStatus::Accepted);
    stageLane(cache, third, 0U, pcm.data(), frames);
    REQUIRE(cache.commitStaging(third.loopRevision) == LoopHeadCommitStatus::Accepted);
    REQUIRE(cache.acquireActive(third).valid());
    REQUIRE(!cache.acquireActive(first).valid());

    const auto diagnostics = cache.diagnostics();
    REQUIRE(diagnostics.readerDeferrals == 1U);
}

void concurrentPublicationNeverExposesAReusedSlot() {
    constexpr std::size_t frames = 16U;
    constexpr std::uint64_t finalRevision = 20'000U;
    std::array<float, frames * LoopHeadCache::kChannelCount> pcm{};
    std::array<float, frames * LoopHeadCache::kChannelCount> initial{};
    for (std::size_t frame = 0; frame < frames; ++frame) {
        initial[frame * 2U] = 1.0F;
        initial[frame * 2U + 1U] = -1.0F;
    }
    LoopHeadCache cache;
    const auto first = makeKey(1U);
    REQUIRE(cache.beginStaging(first, 1U) == LoopHeadBeginStatus::Accepted);
    stageLane(cache, first, 0U, initial.data(), frames);
    REQUIRE(cache.commitStaging(first.loopRevision) == LoopHeadCommitStatus::Accepted);

    std::atomic<bool> stop{false};
    std::atomic<bool> corrupt{false};
    std::atomic<std::uint64_t> completedReads{0U};
    std::jthread reader([&](std::stop_token stopToken) noexcept {
        std::array<float, frames * LoopHeadCache::kChannelCount> output{};
        while (!stop.load(std::memory_order_acquire) && !stopToken.stop_requested()) {
            auto active = cache.acquireActive();
            if (!active.valid()) continue;
            const auto snapshot = active.snapshot();
            if (!active.copyLane(
                    0U,
                    snapshot.key.startTimelineFrame,
                    output.data(),
                    frames,
                    frames)) {
                corrupt.store(true, std::memory_order_release);
                break;
            }
            const float expected = static_cast<float>(snapshot.key.loopRevision);
            for (std::size_t frame = 0; frame < frames; ++frame) {
                if (output[frame * 2U] != expected ||
                    output[frame * 2U + 1U] != -expected) {
                    corrupt.store(true, std::memory_order_release);
                    break;
                }
            }
            if (corrupt.load(std::memory_order_acquire)) break;
            completedReads.fetch_add(1U, std::memory_order_relaxed);
        }
    });

    bool publicationCompleted = true;
    const auto publicationDeadline = std::chrono::steady_clock::now() + std::chrono::seconds(15);
    for (std::uint64_t revision = 2U; revision <= finalRevision; ++revision) {
        const float value = static_cast<float>(revision);
        for (std::size_t frame = 0; frame < frames; ++frame) {
            pcm[frame * 2U] = value;
            pcm[frame * 2U + 1U] = -value;
        }
        const auto key = makeKey(revision);
        LoopHeadBeginStatus begin = LoopHeadBeginStatus::ReadersActive;
        while (begin == LoopHeadBeginStatus::ReadersActive &&
               std::chrono::steady_clock::now() < publicationDeadline) {
            begin = cache.beginStaging(key, 1U);
            if (begin == LoopHeadBeginStatus::ReadersActive) std::this_thread::yield();
        }
        if (begin != LoopHeadBeginStatus::Accepted) {
            publicationCompleted = false;
            break;
        }
        stageLane(cache, key, 0U, pcm.data(), frames);
        REQUIRE(cache.commitStaging(key.loopRevision) == LoopHeadCommitStatus::Accepted);
        REQUIRE(!corrupt.load(std::memory_order_acquire));
    }

    stop.store(true, std::memory_order_release);
    reader.request_stop();
    reader.join();
    REQUIRE(publicationCompleted);
    REQUIRE(!corrupt.load(std::memory_order_acquire));
    REQUIRE(completedReads.load(std::memory_order_relaxed) != 0U);
    REQUIRE(cache.acquireActive(makeKey(finalRevision)).valid());
}

void tenThousandHeadBoundaryAccessesAllocateNothingAndKeepExactEndpoints() {
    constexpr std::size_t loopFrames = 4'800U;
    const auto key = makeKey(1U);
    std::vector<float> pcm(loopFrames * LoopHeadCache::kChannelCount);
    fillLane(pcm, loopFrames, 0U);
    std::array<float, 2> firstOutput{};
    std::array<float, 2> lastOutput{};
    LoopHeadCache cache;

    REQUIRE(cache.beginStaging(key, 1U) == LoopHeadBeginStatus::Accepted);
    // Initial setup reserves only this active lane. PCM writes, publication and reads
    // are the real-time contract; setup allocation is deliberately outside the probe.
    const std::uint64_t allocationsAfterSetup =
        allocation_probe::count.load(std::memory_order_relaxed);
    allocation_probe::enabled.store(true, std::memory_order_release);
    stageLane(cache, key, 0U, pcm.data(), loopFrames);
    REQUIRE(cache.commitStaging(key.loopRevision) == LoopHeadCommitStatus::Accepted);
    for (std::size_t cycle = 0; cycle < 10'000U; ++cycle) {
        auto active = cache.acquireActive(key);
        REQUIRE(active.valid());
        REQUIRE(active.copyLane(
            0U,
            key.startTimelineFrame + loopFrames - 1U,
            lastOutput.data(),
            1U,
            1U));
        REQUIRE(active.copyLane(
            0U,
            key.startTimelineFrame,
            firstOutput.data(),
            1U,
            1U));
        REQUIRE(lastOutput[0] == expectedSample(0U, loopFrames - 1U, 0U));
        REQUIRE(lastOutput[1] == expectedSample(0U, loopFrames - 1U, 1U));
        REQUIRE(firstOutput[0] == expectedSample(0U, 0U, 0U));
        REQUIRE(firstOutput[1] == expectedSample(0U, 0U, 1U));
    }
    const auto diagnostics = cache.diagnostics();

    allocation_probe::enabled.store(false, std::memory_order_release);
    REQUIRE(allocation_probe::count.load(std::memory_order_relaxed) ==
            allocationsAfterSetup);
    REQUIRE(diagnostics.activeViewAcquisitions == 10'000U);
    REQUIRE(diagnostics.commits == 1U);
    REQUIRE(diagnostics.writtenFrames == loopFrames);
}

void originalEosShrinksShortHeadsAndPadsOrClipsStemsToOriginal() {
    constexpr std::size_t originalFrames = 72'000U;
    constexpr std::size_t shortStemFrames = originalFrames - 137U;
    constexpr std::size_t longStemFrames = originalFrames + 251U;
    const auto key = makeKey(1U, 0U);
    std::vector<float> pcm(longStemFrames * LoopHeadCache::kChannelCount);
    LoopHeadCache cache;

    REQUIRE(cache.beginStaging(key, 0b111U, LoopHeadBoundaryMode::OriginalEos) ==
            LoopHeadBeginStatus::Accepted);
    REQUIRE(cache.sealStagingLaneWithSilence(key.loopRevision, 0U, key.startTimelineFrame) ==
            LoopHeadSealStatus::InvalidBoundary);
    REQUIRE(cache.commitStaging(key.loopRevision) == LoopHeadCommitStatus::MissingRequiredLanes);
    fillLane(pcm, originalFrames, 0U);
    auto original = cache.writeStaging(
        key.loopRevision,
        0U,
        key.startTimelineFrame,
        pcm.data(),
        originalFrames);
    REQUIRE(original.status == LoopHeadWriteStatus::Accepted);
    REQUIRE(cache.sealStagingLaneWithSilence(
                key.loopRevision,
                0U,
                originalFrames) == LoopHeadSealStatus::Accepted);
    auto staging = cache.stagingSnapshot();
    REQUIRE(staging.boundaryMode == LoopHeadBoundaryMode::OriginalEos);
    REQUIRE(staging.originalEndResolved);
    REQUIRE(staging.resolvedOriginalEndTimelineFrameExclusive == originalFrames);
    REQUIRE(staging.frameCount == originalFrames);
    REQUIRE(staging.cacheOnly);

    fillLane(pcm, shortStemFrames, 1U);
    auto shortStem = cache.writeStaging(
        key.loopRevision,
        1U,
        key.startTimelineFrame,
        pcm.data(),
        shortStemFrames);
    REQUIRE(shortStem.status == LoopHeadWriteStatus::Accepted);
    REQUIRE(cache.sealStagingLaneWithSilence(
                key.loopRevision,
                1U,
                shortStemFrames) == LoopHeadSealStatus::Accepted);

    fillLane(pcm, longStemFrames, 2U);
    auto longStem = cache.writeStaging(
        key.loopRevision,
        2U,
        key.startTimelineFrame,
        pcm.data(),
        longStemFrames);
    REQUIRE(longStem.status == LoopHeadWriteStatus::LaneComplete);
    REQUIRE(longStem.inputFramesConsumed == originalFrames);
    REQUIRE(longStem.framesWritten == originalFrames);
    REQUIRE(cache.commitStaging(key.loopRevision) == LoopHeadCommitStatus::Accepted);

    auto active = cache.acquireActive(key);
    REQUIRE(active.valid());
    REQUIRE(active.snapshot().frameCount == originalFrames);
    std::array<float, 2> padded{};
    REQUIRE(active.copyLane(
        1U,
        originalFrames - 1U,
        padded.data(),
        1U,
        1U));
    REQUIRE(padded[0] == 0.0F);
    REQUIRE(padded[1] == 0.0F);
}

void originalEosFullRunwayCommitsUnresolvedForExactOrLongContent() {
    const auto key = makeKey(1U, 0U);
    std::vector<float> pcm(
        static_cast<std::size_t>(LoopHeadCache::kCapacityFrames) *
        LoopHeadCache::kChannelCount);
    fillLane(pcm, LoopHeadCache::kCapacityFrames, 0U);
    LoopHeadCache cache;
    REQUIRE(cache.beginStaging(key, 1U, LoopHeadBoundaryMode::OriginalEos) ==
            LoopHeadBeginStatus::Accepted);
    stageLane(cache, key, 0U, pcm.data(), LoopHeadCache::kCapacityFrames);
    REQUIRE(cache.commitStaging(key.loopRevision) == LoopHeadCommitStatus::Accepted);
    auto active = cache.acquireActive(key);
    REQUIRE(active.valid());
    REQUIRE(active.snapshot().boundaryMode == LoopHeadBoundaryMode::OriginalEos);
    REQUIRE(!active.snapshot().originalEndResolved);
    REQUIRE(active.snapshot().resolvedOriginalEndTimelineFrameExclusive == 0U);
    REQUIRE(!active.snapshot().cacheOnly);
    REQUIRE(active.snapshot().frameCount == LoopHeadCache::kCapacityFrames);
}

void retainedWindowReadsExactTenLanePcmBeyondTheRamHead() {
    constexpr std::size_t frames = 9U * LoopHeadCache::kSampleRateHz;
    auto key = makeKey(1U, 19U * 60U * LoopHeadCache::kSampleRateHz);
    key.endTimelineFrameExclusive = key.startTimelineFrame + frames;
    LoopHeadCache cache;
    REQUIRE(cache.beginStaging(key, 0x3ffU, LoopHeadBoundaryMode::RetainedWindow) ==
        LoopHeadBeginStatus::Accepted);
    std::vector<float> pcm(frames * LoopHeadCache::kChannelCount);
    std::array<std::vector<float>, 10U> expected;
    for (std::size_t lane = 0U; lane < expected.size(); ++lane) {
        fillLane(pcm, frames, lane);
        expected[lane] = pcm;
        REQUIRE(cache.writeStaging(key.loopRevision, lane, key.startTimelineFrame,
            pcm.data(), frames).status == LoopHeadWriteStatus::LaneComplete);
    }
    REQUIRE(cache.commitStaging(key.loopRevision) == LoopHeadCommitStatus::Accepted);
    auto active = cache.acquireActive(key);
    REQUIRE(active.valid() && active.snapshot().frameCount == frames);
    std::array<float, 480U * LoopHeadCache::kChannelCount> copied{};
    for (std::size_t lane = 0U; lane < expected.size(); ++lane) {
        for (std::size_t first = 0U; first < frames; first += 480U) {
            REQUIRE(active.copyLane(lane, key.startTimelineFrame + first,
                copied.data(), 480U, 480U));
            REQUIRE(std::equal(copied.begin(), copied.end(),
                expected[lane].begin() + first * LoopHeadCache::kChannelCount));
        }
    }
    REQUIRE(!active.copyLane(0U, key.endTimelineFrameExclusive, copied.data(), 480U, 1U));
}

}  // namespace

int main() {
    fixedCapacityAndBoundaryClassificationAreExact();
    oversizedWritesClampAtTheFixedHeadAndLeaveTheBodyUnconsumed();
    sevenCanonicalLanesRemainDistinctAndImmutable();
    newestRevisionSupersedesStagingAndAbortNeverMutatesActive();
    globalRevisionOrderingCrossesSongAndContentBoundaries();
    checksumDoesNotDependOnChunkingOrSafeRepeatedPrefixes();
    strictAppendRejectsGapsAndNonFiniteSamplesWithoutPartialMutation();
    commitWaitsForEveryRequiredLane();
    oldReaderDefersReuseWithoutBlockingNewPublication();
    concurrentPublicationNeverExposesAReusedSlot();
    tenThousandHeadBoundaryAccessesAllocateNothingAndKeepExactEndpoints();
    originalEosShrinksShortHeadsAndPadsOrClipsStemsToOriginal();
    originalEosFullRunwayCommitsUnresolvedForExactOrLongContent();
    retainedWindowReadsExactTenLanePcmBeyondTheRamHead();
    std::puts(
        "StemWise loop-head cache tests passed: fixed dual-slot memory, exact 4-second "
        "boundary, seven lanes, immutable revisions, explicit EOS, reader-safe publication, "
        "10,000 head-boundary accesses, decoded-Original short/exact/long runway semantics, "
        "and zero steady allocations.");
    return EXIT_SUCCESS;
}
