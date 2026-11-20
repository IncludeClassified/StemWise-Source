#include "AllocationProbeAttributes.h"
#include "TimelineStereoBuffer.h"

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <new>
#include <vector>

namespace {

std::atomic<bool> gTrackAllocations{false};
std::atomic<std::uint64_t> gAllocationCount{0};

}  // namespace

STEMWISE_ALLOCATION_HOOK void *operator new(std::size_t size) {
    if (gTrackAllocations.load(std::memory_order_relaxed)) {
        gAllocationCount.fetch_add(1, std::memory_order_relaxed);
    }
    if (void *allocation = std::malloc(size)) return allocation;
    throw std::bad_alloc();
}

STEMWISE_ALLOCATION_HOOK void *operator new[](std::size_t size) {
    return ::operator new(size);
}

STEMWISE_ALLOCATION_HOOK void operator delete(void *allocation) noexcept { std::free(allocation); }
STEMWISE_ALLOCATION_HOOK void operator delete[](void *allocation) noexcept { std::free(allocation); }
STEMWISE_ALLOCATION_HOOK void operator delete(void *allocation, std::size_t) noexcept { std::free(allocation); }
STEMWISE_ALLOCATION_HOOK void operator delete[](void *allocation, std::size_t) noexcept { std::free(allocation); }

namespace {

using stemwise::audio::engine::TimelineAppendStatus;
using stemwise::audio::engine::TimelineCopyStatus;
using stemwise::audio::engine::TimelineStereoBuffer;

// Exit directly so a failed automated native test never opens MSVC's modal Debug Error dialog.
[[noreturn]] void fail() noexcept { std::_Exit(EXIT_FAILURE); }
void expect(bool condition) noexcept { if (!condition) fail(); }

std::vector<float> sequence(std::size_t frames, float first = 0.0F) {
    std::vector<float> result(frames * 2U);
    for (std::size_t frame = 0; frame < frames; ++frame) {
        result[frame * 2U] = first + static_cast<float>(frame);
        result[frame * 2U + 1U] = -(first + static_cast<float>(frame));
    }
    return result;
}

void nonConsumingCopyAndCommitSurvivePhysicalWrap() {
    TimelineStereoBuffer buffer(64);
    auto first = sequence(60, 0.0F);
    expect(buffer.append(1, 100, first.data(), 60).status == TimelineAppendStatus::Accepted);
    expect(buffer.discardBefore(154) == 54);

    auto second = sequence(40, 60.0F);
    expect(buffer.append(1, 160, second.data(), 40).status == TimelineAppendStatus::Accepted);
    std::array<float, 80> output{};
    const auto copied = buffer.copyAt(1, 160, output.data(), 40, 40);
    expect(copied.status == TimelineCopyStatus::Complete);
    expect(copied.framesCopied == 40);
    for (std::size_t frame = 0; frame < 40; ++frame) {
        expect(output[frame * 2U] == 60.0F + static_cast<float>(frame));
        expect(output[frame * 2U + 1U] == -output[frame * 2U]);
    }
    expect(buffer.availableFrom(1, 160) == 40);
    expect(buffer.availableFrom(1, 160) == 40);
    // Six older frames (154..160) were deliberately retained across the wrap too.
    expect(buffer.discardBefore(180) == 26);
    expect(buffer.availableFrom(1, 180) == 20);
}

void overlapGapGenerationAndDiscontinuityAreExplicit() {
    TimelineStereoBuffer buffer(64);
    auto input = sequence(32, 10.0F);
    expect(buffer.append(4, 1'000, input.data(), 16).status == TimelineAppendStatus::Accepted);

    const auto overlap = buffer.append(4, 1'008, input.data(), 16);
    expect(overlap.status == TimelineAppendStatus::Accepted);
    expect(overlap.overlapFramesDiscarded == 8);
    expect(overlap.framesQueued == 8);

    const auto gap = buffer.append(4, 1'030, input.data(), 2);
    expect(gap.status == TimelineAppendStatus::GapRejected);
    expect(buffer.diagnostics().endTimelineFrameExclusive == 1'024);

    const auto reset = buffer.append(4, 1'030, input.data(), 2, true);
    expect(reset.status == TimelineAppendStatus::Accepted);
    expect(reset.timelineReset);
    expect(buffer.diagnostics().firstTimelineFrame == 1'030);

    const auto stale = buffer.append(3, 0, input.data(), 4);
    expect(stale.status == TimelineAppendStatus::StaleGeneration);
    expect(stale.inputFramesAccepted == 4);

    const auto newer = buffer.append(5, 50, input.data(), 4);
    expect(newer.status == TimelineAppendStatus::Accepted);
    expect(newer.timelineReset);
    expect(buffer.diagnostics().generation == 5);
    expect(buffer.diagnostics().firstTimelineFrame == 50);
}

void boundedPressureAndFourteenBanksAllocateOnlyAtConstruction() {
    std::array<std::unique_ptr<TimelineStereoBuffer>, 14> buffers{};
    for (auto &buffer : buffers) {
        buffer = std::make_unique<TimelineStereoBuffer>(128);
    }
    auto input = sequence(64, 1.0F);
    std::array<float, 128> output{};

    const std::uint64_t allocationsBefore = gAllocationCount.load(std::memory_order_relaxed);
    gTrackAllocations.store(true, std::memory_order_release);
    for (std::uint64_t iteration = 0; iteration < 10'000; ++iteration) {
        TimelineStereoBuffer &buffer = *buffers[iteration % buffers.size()];
        const std::uint64_t generation = iteration / buffers.size() + 1U;
        const auto appended = buffer.append(
            generation,
            0,
            input.data(),
            64,
            true);
        expect(appended.status == TimelineAppendStatus::Accepted);
        const auto copied = buffer.copyAt(generation, 0, output.data(), 64, 64);
        expect(copied.status == TimelineCopyStatus::Complete);
        expect(buffer.discardBefore(64) == 64);
    }
    gTrackAllocations.store(false, std::memory_order_release);
    expect(gAllocationCount.load(std::memory_order_relaxed) == allocationsBefore);

    TimelineStereoBuffer full(64);
    expect(full.append(1, 0, input.data(), 64).status == TimelineAppendStatus::Accepted);
    expect(full.append(1, 64, input.data(), 1).status == TimelineAppendStatus::Backpressured);
}

}  // namespace

int main() {
    nonConsumingCopyAndCommitSurvivePhysicalWrap();
    overlapGapGenerationAndDiscontinuityAreExplicit();
    boundedPressureAndFourteenBanksAllocateOnlyAtConstruction();
    return 0;
}
