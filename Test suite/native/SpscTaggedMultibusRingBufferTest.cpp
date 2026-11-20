#include "AllocationProbeAttributes.h"
#include "SpscTaggedMultibusRingBuffer.h"

#include <array>
#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <new>
#include <stdexcept>
#include <thread>
#include <vector>

namespace allocation_probe {
std::atomic<bool> enabled{false};
std::atomic<std::uint64_t> count{0U}, bytes{0U};
const void *volatile escapedAllocation = nullptr;
std::atomic<std::size_t> failSize{0U};
}  // namespace allocation_probe

STEMWISE_ALLOCATION_HOOK void *operator new(std::size_t size) {
    if (allocation_probe::enabled.load(std::memory_order_relaxed)) {
        allocation_probe::count.fetch_add(1U, std::memory_order_relaxed);
        allocation_probe::bytes.fetch_add(size, std::memory_order_relaxed);
    }
    if (allocation_probe::failSize.load() == size && allocation_probe::failSize.exchange(0U) == size)
        throw std::bad_alloc();
    if (void *memory = std::malloc(size == 0U ? 1U : size)) return memory;
    throw std::bad_alloc();
}

STEMWISE_ALLOCATION_HOOK void *operator new[](std::size_t size) {
    return ::operator new(size);
}

// Keep GCC from mistaking the malloc/free allocation probe for a new/free mismatch.
#if defined(__GNUC__)
__attribute__((noinline))
#endif
STEMWISE_ALLOCATION_HOOK void operator delete(void *memory) noexcept {
    std::free(memory);
}

STEMWISE_ALLOCATION_HOOK void operator delete[](void *memory) noexcept {
    ::operator delete(memory);
}

STEMWISE_ALLOCATION_HOOK void operator delete(void *memory, std::size_t) noexcept {
    ::operator delete(memory);
}

STEMWISE_ALLOCATION_HOOK void operator delete[](void *memory, std::size_t) noexcept {
    ::operator delete(memory);
}

namespace {

using stemwise::audio::RenderBusFrameTag;
using stemwise::audio::RenderBusTagRun;
using stemwise::audio::LateStemMixControlSnapshot;
using stemwise::audio::SpscTaggedMultibusRingBuffer;
using stemwise::audio::TaggedMultibusReadLease;
using stemwise::audio::kStemwiseAllStemBusesReadyMask;

constexpr std::size_t kStride = SpscTaggedMultibusRingBuffer::kPayloadStride;

[[noreturn]] void failRequirement(
    const char *expression,
    const char *file,
    int line) noexcept {
    std::fprintf(
        stderr,
        "StemWise multibus ring test requirement failed: %s (%s:%d)\n",
        expression,
        file,
        line);
    std::fflush(stderr);
    std::_Exit(EXIT_FAILURE);
}

#define REQUIRE(expression) \
    ((expression) ? static_cast<void>(0) : failRequirement(#expression, __FILE__, __LINE__))

RenderBusFrameTag tag(
    std::uint64_t payloadSequence,
    std::uint64_t loopCycle = 1U) noexcept {
    RenderBusFrameTag result{};
    result.generation = 17U;
    result.selectionToken = 29U;
    result.payloadSequence = payloadSequence;
    result.payloadHash = payloadSequence * 101U;
    result.canonicalControlRevision = 31U;
    result.sourceIntentRevision = 37U;
    result.stemIntentRevision = 41U;
    result.stemBusReadyMask = kStemwiseAllStemBusesReadyMask;
    result.loopRevision = 43U;
    result.loopCycle = loopCycle;
    result.loopEnabled = true;
    return result;
}

LateStemMixControlSnapshot baseFor(const RenderBusFrameTag &renderTag) noexcept {
    LateStemMixControlSnapshot result;
    result.publicationSequence = renderTag.payloadSequence;
    result.generation = renderTag.generation;
    result.selectionToken = renderTag.selectionToken;
    result.canonicalControlRevision = renderTag.canonicalControlRevision;
    result.sourceIntentRevision = renderTag.sourceIntentRevision;
    result.stemIntentRevision = renderTag.stemIntentRevision;
    result.musicGain = .25F;
    return result;
}

template <std::size_t FrameCount>
std::array<float, FrameCount * kStride> payload(std::uint64_t firstFrame) noexcept {
    std::array<float, FrameCount * kStride> result{};
    for (std::size_t frame = 0U; frame < FrameCount; ++frame) {
        for (std::size_t channel = 0U; channel < kStride; ++channel) {
            result[frame * kStride + channel] = static_cast<float>(
                (firstFrame + frame) * 32U + channel);
        }
    }
    return result;
}

void requireLeasePayload(
    const TaggedMultibusReadLease &lease,
    std::uint64_t firstFrame) noexcept {
    std::size_t visited = 0U;
    for (std::size_t spanIndex = 0U; spanIndex < lease.spanCount; ++spanIndex) {
        const auto &span = lease.spans[spanIndex];
        REQUIRE(span.valid());
        REQUIRE(span.leaseFrameOffset == visited);
        REQUIRE(span.absoluteFirstFrame == lease.absoluteFirstFrame + visited);
        for (std::size_t frame = 0U; frame < span.frameCount; ++frame) {
            for (std::size_t channel = 0U; channel < kStride; ++channel) {
                const float expected = static_cast<float>(
                    (firstFrame + visited + frame) * 32U + channel);
                REQUIRE(span.interleavedPayload[frame * kStride + channel] == expected);
            }
        }
        visited += span.frameCount;
    }
    REQUIRE(visited == lease.frameCount);
}

void invalidCapacityIsRejected() {
    bool rejected = false;
    try {
        SpscTaggedMultibusRingBuffer invalid(0U);
        static_cast<void>(invalid);
    } catch (const std::invalid_argument &) {
        rejected = true;
    }
    REQUIRE(rejected);
}

template <typename Element>
STEMWISE_ALLOCATION_HOOK std::uint64_t vectorOverhead(std::size_t count) {
    const auto start = allocation_probe::bytes.load(std::memory_order_relaxed);
    std::vector<Element> calibration(count);
    // An observable address escape keeps optimized builds from eliding this calibration allocation.
    allocation_probe::escapedAllocation = calibration.data();
    REQUIRE(calibration.size() == count && calibration.data() != nullptr);
    const auto actual = allocation_probe::bytes.load(std::memory_order_relaxed) - start;
    if (actual < count * sizeof(Element))
        std::fprintf(stderr, "vector allocation probe element_bytes=%zu count=%zu start_bytes=%llu actual_bytes=%llu total_bytes=%llu pointer=%p\n",
            sizeof(Element), count, static_cast<unsigned long long>(start),
            static_cast<unsigned long long>(actual), static_cast<unsigned long long>(allocation_probe::bytes.load(std::memory_order_relaxed)),
            static_cast<void *>(calibration.data()));
    allocation_probe::escapedAllocation = nullptr;
    REQUIRE(actual >= count * sizeof(Element));
    return actual - count * sizeof(Element);
}

void commonControlRunsUseProportionalMemoryAndPeakGrowthPreservesEveryFrame() {
    constexpr std::size_t capacity = 16'384U;
    const auto samples = payload<512U>(0U);
    allocation_probe::enabled.store(true, std::memory_order_relaxed);
    // MSVC aligns large vector allocations with extra allocator storage. Measure each actual
    // element type/capacity independently; a one-frame calibration misses that size threshold.
    const auto containerOverhead = vectorOverhead<float>(capacity * kStride) +
        vectorOverhead<stemwise::audio::RenderBusFrameMapping>(capacity) +
        2U * vectorOverhead<std::uint32_t>(capacity) +
        vectorOverhead<std::unique_ptr<stemwise::audio::RenderBusControlRun[]>>((capacity + 63U) / 64U);
    const auto before = allocation_probe::bytes.load(std::memory_order_relaxed);
    SpscTaggedMultibusRingBuffer large(capacity);
    const auto construction = allocation_probe::bytes.load(std::memory_order_relaxed) - before;
    for (std::size_t start = 0U; start < capacity; start += 512U) {
        const auto base = baseFor(tag(start + 1U));
        REQUIRE(large.writeTaggedTimeline(samples.data(), 512U, tag(start + 1U),
            static_cast<double>(start), static_cast<double>(start + 512U), false, &base) == 512U);
    }
    allocation_probe::enabled.store(false, std::memory_order_relaxed);
    const auto metadata = construction - capacity * kStride * sizeof(float);
    std::printf("multibus metadata capacity=%zu allocated=%llu arrays=%zu measured-container-overhead=%llu\n", capacity,
        static_cast<unsigned long long>(metadata), large.metadataAllocatedBytes(),
        static_cast<unsigned long long>(containerOverhead));
    REQUIRE(metadata == large.metadataAllocatedBytes() + containerOverhead);
    REQUIRE(metadata < capacity * 64U);
    REQUIRE(allocation_probe::bytes.load(std::memory_order_relaxed) - before == construction);
    std::printf("multibus metadata capacity=%zu bytes=%llu old-tag-bytes=%zu\n", capacity,
        static_cast<unsigned long long>(metadata), capacity * sizeof(RenderBusFrameTag));

    SpscTaggedMultibusRingBuffer fine(128U);
    const auto one = payload<1U>(0U);
    for (std::size_t frame = 0U; frame < 64U; ++frame)
        REQUIRE(fine.writeTaggedTimeline(one.data(), 1U, tag(frame + 1U),
            static_cast<double>(frame), static_cast<double>(frame + 1U)) == 1U);
    allocation_probe::failSize.store(64U * sizeof(stemwise::audio::RenderBusControlRun));
    REQUIRE(fine.writeTaggedTimeline(one.data(), 1U, tag(65U), 64.0, 65.0) == 0U);
    REQUIRE(fine.queuedFrames() == 64U);
    const auto held = fine.acquireRead(64U);
    REQUIRE(held.spans[0U].tags[63U].payloadSequence == 64U);
    REQUIRE(fine.advanceRead(held, 1U));
    REQUIRE(fine.writeTaggedTimeline(one.data(), 1U, tag(65U), 64.0, 65.0) == 1U);
    for (std::size_t frame = 65U; frame < 129U; ++frame)
        REQUIRE(fine.writeTaggedTimeline(one.data(), 1U, tag(frame + 1U),
            static_cast<double>(frame), static_cast<double>(frame + 1U)) == 1U);
    REQUIRE(fine.queuedFrames() == 128U);
    auto lease = fine.acquireRead(128U);
    std::size_t frame = 1U;
    for (const auto &span : lease.spans) for (std::size_t index = 0U; index < span.frameCount; ++index, ++frame) {
        REQUIRE(span.tags[index].timelineFrame == static_cast<std::int64_t>(frame));
        REQUIRE(span.tags[index].payloadSequence == frame + 1U);
    }
    REQUIRE(frame == 129U && fine.advanceRead(lease));
}

void explicitAdvancePreservesPayloadAcrossWrapAndBackpressure() {
    SpscTaggedMultibusRingBuffer ring(4U);
    const auto first = payload<4U>(0U);
    const auto suffix = payload<2U>(4U);

    REQUIRE(ring.writeTagged(first.data(), 4U, tag(1U)) == 4U);
    TaggedMultibusReadLease lease = ring.acquireRead(3U);
    REQUIRE(lease.frameCount == 3U);
    REQUIRE(lease.spanCount == 1U);
    requireLeasePayload(lease, 0U);

    // Acquiring exposes data but deliberately does not publish free space to the producer.
    REQUIRE(ring.queuedFrames() == 4U);
    REQUIRE(ring.writeTagged(suffix.data(), 2U, tag(2U)) == 0U);
    TaggedMultibusReadLease forged = lease;
    forged.observedWritePosition += 100U;
    REQUIRE(!ring.advanceRead(forged));
    REQUIRE(ring.advanceRead(lease));
    REQUIRE(ring.queuedFrames() == 1U);
    REQUIRE(!ring.advanceRead(lease));

    REQUIRE(ring.writeTagged(suffix.data(), 2U, tag(2U)) == 2U);
    lease = ring.acquireRead(4U);
    REQUIRE(lease.frameCount == 3U);
    REQUIRE(lease.spanCount == 2U);
    REQUIRE(lease.spans[0].frameCount == 1U);
    REQUIRE(lease.spans[1].frameCount == 2U);
    requireLeasePayload(lease, 3U);

    REQUIRE(ring.advanceRead(lease, 1U));
    REQUIRE(!ring.advanceRead(lease, 2U));
    const TaggedMultibusReadLease remainder = ring.acquireRead(8U);
    REQUIRE(remainder.frameCount == 2U);
    requireLeasePayload(remainder, 4U);
    REQUIRE(ring.advanceRead(remainder));
    REQUIRE(ring.queuedFrames() == 0U);
    REQUIRE(ring.writableFrames() == ring.capacityFrames());
}

void timelineAndTerminalMarkerBelongOnlyToTheExactAcceptedSuffix() {
    SpscTaggedMultibusRingBuffer ring(4U);
    const auto all = payload<6U>(100U);
    RenderBusFrameTag base = tag(7U, 9U);
    base.terminalWriteBoundary = 999U;
    // INT64_MAX rounds up to 2^63 as a double; no tag boundary may narrow that value.
    REQUIRE(ring.writeTaggedTimeline(all.data(), 1U, base, 0.0,
        static_cast<double>(std::numeric_limits<std::int64_t>::max())) == 0U);

    REQUIRE(ring.writeTaggedTimeline(
        all.data(),
        6U,
        base,
        100.0,
        106.0,
        true) == 4U);
    TaggedMultibusReadLease lease = ring.acquireRead(8U);
    REQUIRE(lease.frameCount == 4U);
    for (std::size_t frame = 0U; frame < 4U; ++frame) {
        REQUIRE(lease.spans[0].tags[frame].timelineFrame == 100 +
            static_cast<std::int64_t>(frame));
        REQUIRE(lease.spans[0].tags[frame].nextTimelineFrame == 101 +
            static_cast<std::int64_t>(frame));
        REQUIRE(lease.spans[0].tags[frame].terminalWriteBoundary == 0U);
    }
    REQUIRE(ring.advanceRead(lease));

    REQUIRE(ring.writeTaggedTimeline(
        all.data() + 4U * kStride,
        2U,
        base,
        104.0,
        106.0,
        true) == 2U);
    lease = ring.acquireRead(8U);
    REQUIRE(lease.frameCount == 2U);
    REQUIRE(lease.spans[0].tags[0].timelineFrame == 104);
    REQUIRE(lease.spans[0].tags[1].timelineFrame == 105);
    REQUIRE(lease.spans[0].tags[1].nextTimelineFrame == 106);
    REQUIRE(lease.spans[0].tags[0].terminalWriteBoundary == 0U);
    REQUIRE(lease.spans[0].tags[1].terminalWriteBoundary == 6U);
    REQUIRE(lease.observedWritePosition == 6U);

    std::array<std::size_t, 2U> runFrames{};
    std::size_t runCount = 0U;
    ring.forEachTagRun(
        lease,
        [&](const RenderBusTagRun &run) noexcept {
            REQUIRE(runCount < runFrames.size());
            runFrames[runCount++] = run.frameCount;
        });
    REQUIRE(runCount == 2U);
    REQUIRE(runFrames[0] == 1U);
    REQUIRE(runFrames[1] == 1U);
    REQUIRE(ring.advanceRead(lease));
}

void irregularTimelinePreservesExactTerminalBoundary() {
    // 11 * (15.0 / 11) rounds down to 14.999999999999998 on the qualified host.
    const auto block = payload<11U>(0U);
    for (const std::size_t capacity : {5U, 16U}) {
        SpscTaggedMultibusRingBuffer ring(capacity);
        const auto base = tag(1U);
        if (capacity == 16U) {
            REQUIRE(ring.writeTagged(block.data(), 7U, base) == 7U);
            REQUIRE(ring.advanceRead(ring.acquireRead(7U)));
        }
        const std::size_t written = ring.writeTaggedTimeline(block.data(), 11U, base, 0.0, 15.0);
        REQUIRE(written == std::min<std::size_t>(capacity, 11U));
        const auto lease = ring.acquireRead(11U);
        const auto &lastSpan = lease.spans[lease.spanCount - 1U];
        REQUIRE(lastSpan.tags[lastSpan.frameCount - 1U].nextTimelineFrame ==
            (written == 11U ? 15 : 6));
        if (capacity == 16U) REQUIRE(lease.spanCount == 2U);
    }
}

void tagRunsExposeOwnershipLoopAndPhysicalWrapBoundaries() {
    SpscTaggedMultibusRingBuffer ring(4U);
    const auto first = payload<3U>(0U);
    const auto second = payload<3U>(3U);

    REQUIRE(ring.writeTagged(first.data(), 3U, tag(1U, 10U)) == 3U);
    TaggedMultibusReadLease lease = ring.acquireRead(2U);
    REQUIRE(ring.advanceRead(lease));
    REQUIRE(ring.writeTagged(second.data(), 3U, tag(2U, 11U)) == 3U);

    lease = ring.acquireRead(4U);
    REQUIRE(lease.spanCount == 2U);
    std::array<std::uint64_t, 3U> sequences{};
    std::array<std::uint64_t, 3U> cycles{};
    std::array<std::size_t, 3U> counts{};
    std::array<std::uint64_t, 3U> absoluteStarts{};
    std::size_t runCount = 0U;
    ring.forEachTagRun(
        lease,
        [&](const RenderBusTagRun &run) noexcept {
            REQUIRE(runCount < sequences.size());
            sequences[runCount] = run.tags[0].payloadSequence;
            cycles[runCount] = run.tags[0].loopCycle;
            counts[runCount] = run.frameCount;
            absoluteStarts[runCount] = run.absoluteFirstFrame;
            ++runCount;
        });

    // Logical B is split only because its direct payload wraps physically.
    REQUIRE(runCount == 3U);
    REQUIRE(sequences[0] == 1U && cycles[0] == 10U && counts[0] == 1U);
    REQUIRE(sequences[1] == 2U && cycles[1] == 11U && counts[1] == 1U);
    REQUIRE(sequences[2] == 2U && cycles[2] == 11U && counts[2] == 2U);
    REQUIRE(absoluteStarts[0] == 2U);
    REQUIRE(absoluteStarts[1] == 3U);
    REQUIRE(absoluteStarts[2] == 4U);
    REQUIRE(ring.advanceRead(lease));
}

void steadyStateReadWriteAndTagRunsAllocateNothing() {
    SpscTaggedMultibusRingBuffer ring(8U);
    const auto block = payload<8U>(0U);
    const RenderBusFrameTag base = tag(101U, 3U);
    std::size_t visitedRuns = 0U;

    const std::uint64_t baseline = allocation_probe::count.load(std::memory_order_relaxed);
    allocation_probe::enabled.store(true, std::memory_order_relaxed);
    for (std::size_t iteration = 0U; iteration < 128U; ++iteration) {
        REQUIRE(ring.writeTaggedTimeline(
            block.data(),
            8U,
            base,
            static_cast<double>(iteration * 8U),
            static_cast<double>((iteration + 1U) * 8U)) == 8U);
        const TaggedMultibusReadLease lease = ring.acquireRead(8U);
        ring.forEachTagRun(
            lease,
            [&](const RenderBusTagRun &run) noexcept {
                REQUIRE(run.frameCount == 8U);
                ++visitedRuns;
            });
        REQUIRE(ring.advanceRead(lease));
    }
    allocation_probe::enabled.store(false, std::memory_order_relaxed);

    REQUIRE(visitedRuns == 128U);
    REQUIRE(allocation_probe::count.load(std::memory_order_relaxed) == baseline);
}

void producerConsumerPressurePreservesEveryFrameAndTag() {
    constexpr std::uint64_t kFrameCount = 100'000U;
    constexpr std::size_t kProducerQuantum = 31U;
    SpscTaggedMultibusRingBuffer ring(127U);
    std::atomic<bool> producerFinished{false};

    std::jthread producer([&](std::stop_token stopToken) noexcept {
        std::array<float, kProducerQuantum * kStride> block{};
        std::uint64_t produced = 0U;
        while (produced < kFrameCount && !stopToken.stop_requested()) {
            const std::size_t requested = static_cast<std::size_t>(
                std::min<std::uint64_t>(kProducerQuantum, kFrameCount - produced));
            for (std::size_t frame = 0U; frame < requested; ++frame) {
                for (std::size_t channel = 0U; channel < kStride; ++channel) {
                    block[frame * kStride + channel] = static_cast<float>(
                        (produced + frame) * 32U + channel);
                }
            }
            const auto renderTag = tag(produced + 1U, 77U);
            const auto base = baseFor(renderTag);
            const std::size_t accepted = ring.writeTaggedTimeline(
                block.data(),
                requested,
                renderTag,
                static_cast<double>(produced),
                static_cast<double>(produced + requested), false, &base);
            if (accepted == 0U) {
                std::this_thread::yield();
                continue;
            }
            produced += accepted;
        }
        producerFinished.store(true, std::memory_order_release);
    });

    std::uint64_t consumed = 0U;
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(10);
    while (consumed < kFrameCount && std::chrono::steady_clock::now() < deadline) {
        TaggedMultibusReadLease lease = ring.acquireRead(29U);
        if (lease.empty() && producerFinished.load(std::memory_order_acquire)) {
            // Completion orders the final write; the earlier empty lease predates that proof.
            lease = ring.acquireRead(29U);
            REQUIRE(!lease.empty() || consumed == kFrameCount);
        }
        if (lease.empty()) {
            std::this_thread::yield();
            continue;
        }
        std::size_t leaseOffset = 0U;
        for (std::size_t spanIndex = 0U; spanIndex < lease.spanCount; ++spanIndex) {
            const auto &span = lease.spans[spanIndex];
            for (std::size_t frame = 0U; frame < span.frameCount; ++frame) {
                const std::uint64_t expectedFrame = consumed + leaseOffset + frame;
                REQUIRE(span.tags[frame].generation == 17U);
                REQUIRE(span.tags[frame].selectionToken == 29U);
                REQUIRE(span.tags[frame].timelineFrame ==
                    static_cast<std::int64_t>(expectedFrame));
                REQUIRE(span.tags[frame].nextTimelineFrame ==
                    static_cast<std::int64_t>(expectedFrame + 1U));
                for (std::size_t channel = 0U; channel < kStride; ++channel) {
                    REQUIRE(span.interleavedPayload[frame * kStride + channel] ==
                        static_cast<float>(expectedFrame * 32U + channel));
                }
            }
            leaseOffset += span.frameCount;
        }
        REQUIRE(leaseOffset == lease.frameCount);
        REQUIRE(ring.forEachTagRun(lease, [&](const RenderBusTagRun &run) noexcept {
            REQUIRE(run.baseControl != nullptr);
            REQUIRE(run.baseControl->publicationSequence == run.tags[0U].payloadSequence);
            REQUIRE(run.baseControl->generation == run.tags[0U].generation);
            REQUIRE(run.baseControl->selectionToken == run.tags[0U].selectionToken);
            REQUIRE(run.baseControl->musicGain == .25F);
        }));
        REQUIRE(ring.advanceRead(lease));
        consumed += lease.frameCount;
    }
    const bool completedBeforeWatchdog = consumed == kFrameCount;
    if (!completedBeforeWatchdog) producer.request_stop();
    producer.join();
    REQUIRE(completedBeforeWatchdog);
    REQUIRE(producerFinished.load(std::memory_order_acquire));
    REQUIRE(ring.queuedFrames() == 0U);
}

void blockBasesOutlivePartialReadsAndRetireAcrossBothRingWraps() {
    SpscTaggedMultibusRingBuffer ring(4U);
    const auto samples = payload<8U>(0U);
    auto base = baseFor(tag(1U));
    stemwise::audio::ManagedLoopRenderContext context{51U, 0U, 8U};
    static_assert(sizeof(context) == 24U);
    REQUIRE(ring.writeTaggedTimeline(samples.data(), 6U, tag(1U), 0.0, 6.0, false, &base, &context) == 4U);
    context.revision = 99U;
    base.musicGain = .75F;
    auto lease = ring.acquireRead(2U);
    REQUIRE(ring.forEachTagRun(lease, [&](const RenderBusTagRun &run) noexcept {
        REQUIRE(run.baseControl != nullptr && run.baseControl->musicGain == .25F);
        REQUIRE(run.managedLoopContext != nullptr && run.managedLoopContext->revision == 51U);
    }));
    REQUIRE(ring.advanceRead(lease, 1U));
    const auto second = baseFor(tag(2U));
    REQUIRE(ring.writeTaggedTimeline(samples.data() + 4U * kStride, 1U, tag(2U), 4.0, 5.0, false, &second) == 1U);
    lease = ring.acquireRead(4U);
    REQUIRE(lease.spanCount == 2U);
    REQUIRE(ring.forEachTagRun(lease, [&](const RenderBusTagRun &run) noexcept {
        REQUIRE(run.baseControl != nullptr);
        REQUIRE(run.baseControl->publicationSequence == (run.absoluteFirstFrame < 4U ? 1U : 2U));
        REQUIRE(run.baseControl->musicGain == .25F);
        REQUIRE(run.managedLoopContext != nullptr &&
            run.managedLoopContext->revision == (run.absoluteFirstFrame < 4U ? 51U : 0U));
    }));
    REQUIRE(ring.advanceRead(lease, 2U));
    const auto third = baseFor(tag(3U));
    REQUIRE(ring.writeTaggedTimeline(samples.data() + 5U * kStride, 2U, tag(3U), 5.0, 7.0, false, &third) == 2U);
    lease = ring.acquireRead(4U);
    REQUIRE(ring.forEachTagRun(lease, [&](const RenderBusTagRun &run) noexcept {
        const auto expected = run.absoluteFirstFrame < 4U ? 1U : run.absoluteFirstFrame < 5U ? 2U : 3U;
        REQUIRE(run.baseControl != nullptr && run.baseControl->publicationSequence == expected);
        REQUIRE(run.managedLoopContext != nullptr &&
            run.managedLoopContext->revision == (run.absoluteFirstFrame < 4U ? 51U : 0U));
    }));
    REQUIRE(ring.advanceRead(lease));
    allocation_probe::count.store(0U, std::memory_order_relaxed);
    allocation_probe::enabled.store(true, std::memory_order_relaxed);
    for (std::uint64_t sequence = 4U; sequence < 132U; ++sequence) {
        const auto endpoint = baseFor(tag(sequence));
        REQUIRE(ring.writeTagged(samples.data(), 1U, tag(sequence), &endpoint) == 1U);
        lease = ring.acquireRead(1U);
        REQUIRE(ring.forEachTagRun(lease, [&](const RenderBusTagRun &run) noexcept {
            REQUIRE(run.baseControl != nullptr && run.baseControl->publicationSequence == sequence);
            REQUIRE(run.managedLoopContext != nullptr && run.managedLoopContext->empty());
        }));
        REQUIRE(ring.advanceRead(lease));
    }
    allocation_probe::enabled.store(false, std::memory_order_relaxed);
    REQUIRE(allocation_probe::count.load(std::memory_order_relaxed) == 0U);
    for (std::uint64_t sequence = 1'000U; sequence < 1'004U; ++sequence) {
        const auto endpoint = baseFor(tag(sequence));
        REQUIRE(ring.writeTagged(samples.data(), 1U, tag(sequence), &endpoint) == 1U);
    }
    REQUIRE(ring.queuedFrames() == ring.capacityFrames());
    REQUIRE(ring.advanceRead(ring.acquireRead(1U)));
    const auto replacement = baseFor(tag(1'004U));
    REQUIRE(ring.writeTagged(samples.data(), 1U, tag(1'004U), &replacement) == 1U);
    lease = ring.acquireRead(4U);
    auto expected = 1'001U;
    REQUIRE(ring.forEachTagRun(lease, [&](const RenderBusTagRun &run) noexcept {
        REQUIRE(run.frameCount == 1U && run.baseControl != nullptr);
        REQUIRE(run.baseControl->publicationSequence == expected++);
    }));
    REQUIRE(expected == 1'005U && ring.advanceRead(lease));
}

void inactiveResetReclaimsTheCompleteRing() {
    SpscTaggedMultibusRingBuffer ring(4U);
    const auto block = payload<4U>(0U);
    REQUIRE(ring.writeTagged(block.data(), 4U, tag(1U)) == 4U);
    ring.resetInactive();
    REQUIRE(ring.readPosition() == 0U);
    REQUIRE(ring.writePosition() == 0U);
    REQUIRE(ring.queuedFrames() == 0U);
    REQUIRE(ring.writeTagged(block.data(), 4U, tag(2U)) == 4U);
}

void separateEosBindsTheExactWriteWithoutChangingAnAcquiredLease() {
    SpscTaggedMultibusRingBuffer ring(8U);
    const auto samples = payload<5U>(0U);
    const auto owner = tag(1U);
    REQUIRE(ring.writeTaggedTimeline(samples.data(), 4U, owner, 100.0, 104.0) == 4U);
    REQUIRE(!ring.publishTerminalBoundary(18U, owner.selectionToken, owner.loopPolicyRevision, 104.0));
    REQUIRE(!ring.publishTerminalBoundary(owner.generation, 30U, owner.loopPolicyRevision, 104.0));
    REQUIRE(!ring.publishTerminalBoundary(owner.generation, owner.selectionToken, owner.loopPolicyRevision + 1U, 104.0));
    REQUIRE(!ring.publishTerminalBoundary(owner.generation, owner.selectionToken, owner.loopPolicyRevision, 103.0));
    std::atomic<bool> acquired{false}, published{false}, valid{false};
    std::thread reader([&] {
        const auto lease = ring.acquireRead(4U);
        acquired.store(true, std::memory_order_release);
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(2);
        while (!published.load(std::memory_order_acquire) && std::chrono::steady_clock::now() < deadline) std::this_thread::yield();
        const auto end = ring.terminalBoundary();
        valid.store(published.load() && lease.frameCount == 4U && lease.spans[0].tags[3].terminalWriteBoundary == 0U &&
            end.generation == owner.generation && end.selectionToken == owner.selectionToken &&
            end.writePosition == 4U && end.sourceEnd == 104.0 && ring.advanceRead(lease), std::memory_order_release);
    });
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(2);
    while (!acquired.load(std::memory_order_acquire) && std::chrono::steady_clock::now() < deadline) std::this_thread::yield();
    const bool marked = acquired.load() && ring.publishTerminalBoundary(owner.generation, owner.selectionToken, owner.loopPolicyRevision, 104.0);
    published.store(true, std::memory_order_release);
    reader.join();
    REQUIRE(marked && valid.load());
    REQUIRE(ring.writeTaggedTimeline(samples.data(), 1U, tag(2U), 104.0, 105.0) == 1U);
    REQUIRE(ring.terminalBoundary().writePosition == 0U);
    REQUIRE(!ring.publishTerminalBoundary(owner.generation, owner.selectionToken, owner.loopPolicyRevision, 104.0));
    REQUIRE(ring.publishTerminalBoundary(owner.generation, owner.selectionToken, owner.loopPolicyRevision, 105.0));
    const auto epoch = ring.terminalBoundary().epoch;
    ring.resetInactive();
    REQUIRE(ring.terminalBoundary().writePosition == 0U && ring.epoch() != epoch);
    REQUIRE(!ring.publishTerminalBoundary(owner.generation, owner.selectionToken, owner.loopPolicyRevision, 105.0));
}

}  // namespace

int main() {
    invalidCapacityIsRejected();
    commonControlRunsUseProportionalMemoryAndPeakGrowthPreservesEveryFrame();
    explicitAdvancePreservesPayloadAcrossWrapAndBackpressure();
    timelineAndTerminalMarkerBelongOnlyToTheExactAcceptedSuffix();
    irregularTimelinePreservesExactTerminalBoundary();
    tagRunsExposeOwnershipLoopAndPhysicalWrapBoundaries();
    steadyStateReadWriteAndTagRunsAllocateNothing();
    producerConsumerPressurePreservesEveryFrameAndTag();
    blockBasesOutlivePartialReadsAndRetireAcrossBothRingWraps();
    inactiveResetReclaimsTheCompleteRing();
    separateEosBindsTheExactWriteWithoutChangingAnAcquiredLease();
    return 0;
}
