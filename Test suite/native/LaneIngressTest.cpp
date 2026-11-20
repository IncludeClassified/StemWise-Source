#include "AllocationProbeAttributes.h"
#include "LaneIngress.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <limits>
#include <new>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#if defined(_MSC_VER)
#include <malloc.h>
#endif

namespace {

std::atomic<bool> gTrackAllocations{false};
std::atomic<std::uint64_t> gTrackedAllocations{0};

void noteAllocation() noexcept {
    if (gTrackAllocations.load(std::memory_order_relaxed)) {
        gTrackedAllocations.fetch_add(1, std::memory_order_relaxed);
    }
}

void *allocateAligned(std::size_t size, std::size_t alignment) {
#if defined(_MSC_VER)
    void *memory = _aligned_malloc(size, alignment);
    if (memory == nullptr) throw std::bad_alloc();
    return memory;
#else
    void *memory = nullptr;
    if (posix_memalign(&memory, alignment, size) != 0) throw std::bad_alloc();
    return memory;
#endif
}

void freeAligned(void *memory) noexcept {
#if defined(_MSC_VER)
    _aligned_free(memory);
#else
    std::free(memory);
#endif
}

}  // namespace

STEMWISE_ALLOCATION_HOOK void *operator new(std::size_t size) {
    noteAllocation();
    if (void *memory = std::malloc(size)) return memory;
    throw std::bad_alloc();
}

STEMWISE_ALLOCATION_HOOK void *operator new[](std::size_t size) {
    noteAllocation();
    if (void *memory = std::malloc(size)) return memory;
    throw std::bad_alloc();
}

STEMWISE_ALLOCATION_HOOK void *operator new(std::size_t size, std::align_val_t alignment) {
    noteAllocation();
    return allocateAligned(size, static_cast<std::size_t>(alignment));
}

STEMWISE_ALLOCATION_HOOK void *operator new[](std::size_t size, std::align_val_t alignment) {
    noteAllocation();
    return allocateAligned(size, static_cast<std::size_t>(alignment));
}

STEMWISE_ALLOCATION_HOOK void operator delete(void *memory) noexcept { std::free(memory); }
STEMWISE_ALLOCATION_HOOK void operator delete[](void *memory) noexcept { std::free(memory); }
STEMWISE_ALLOCATION_HOOK void operator delete(void *memory, std::size_t) noexcept { std::free(memory); }
STEMWISE_ALLOCATION_HOOK void operator delete[](void *memory, std::size_t) noexcept { std::free(memory); }
STEMWISE_ALLOCATION_HOOK void operator delete(void *memory, std::align_val_t) noexcept { freeAligned(memory); }
STEMWISE_ALLOCATION_HOOK void operator delete[](void *memory, std::align_val_t) noexcept { freeAligned(memory); }
STEMWISE_ALLOCATION_HOOK void operator delete(void *memory, std::size_t, std::align_val_t) noexcept {
    freeAligned(memory);
}
STEMWISE_ALLOCATION_HOOK void operator delete[](void *memory, std::size_t, std::align_val_t) noexcept {
    freeAligned(memory);
}

namespace {

using stemwise::audio::engine::LaneDiscontinuity;
using stemwise::audio::engine::LaneEndOfStreamStatus;
using stemwise::audio::engine::LaneIngress;
using stemwise::audio::engine::SongLaneIngressBank;
using stemwise::audio::engine::LaneInputBlock;
using stemwise::audio::engine::LaneInputFormat;
using stemwise::audio::engine::LaneOfferStatus;
using stemwise::audio::engine::LanePcmEncoding;
using stemwise::audio::engine::LanePullStatus;
using stemwise::audio::engine::hasDiscontinuity;

void require(bool condition, const std::string &message) {
    if (!condition) throw std::runtime_error(message);
}

std::uint64_t framesToPresentationUs(std::uint64_t frame, std::uint32_t rate) {
    const std::uint64_t wholeSeconds = frame / rate;
    const std::uint64_t remainder = frame % rate;
    return wholeSeconds * 1'000'000U + remainder * 1'000'000U / rate;
}

std::vector<float> stereoSignal(std::size_t frames, std::uint32_t rate, float phase = 0.0F) {
    std::vector<float> result(frames * 2U, 0.0F);
    for (std::size_t index = 0; index < frames; ++index) {
        const float position = static_cast<float>(index) / static_cast<float>(rate);
        const float sample = std::sin(phase + position * 997.0F) * 0.65F;
        result[index * 2U] = sample;
        result[index * 2U + 1U] = sample * -0.75F;
    }
    return result;
}

LaneInputBlock floatBlock(
    const float *data,
    std::size_t frames,
    std::uint32_t rate,
    std::uint8_t channels,
    std::uint64_t generation,
    std::uint64_t firstFrame) {
    return {
        data,
        frames,
        {rate, channels, LanePcmEncoding::Float32},
        generation,
        framesToPresentationUs(firstFrame, rate),
    };
}

std::vector<float> drainAll(LaneIngress &lane, std::size_t outputCapacity = 4096) {
    const std::size_t boundedCapacity = std::min(
        outputCapacity,
        lane.diagnostics().maximumOutputQuantumFrames);
    std::vector<float> scratch(boundedCapacity * 2U, 0.0F);
    std::vector<float> result;
    std::size_t calls = 0;
    while (true) {
        const auto pulled = lane.pull48k(scratch.data(), boundedCapacity);
        require(
            pulled.status == LanePullStatus::Ok ||
                pulled.status == LanePullStatus::OutputCapacityExhausted ||
                pulled.status == LanePullStatus::Empty,
            "drain status");
        result.insert(
            result.end(),
            scratch.begin(),
            scratch.begin() + static_cast<std::ptrdiff_t>(pulled.outputFramesProduced * 2U));
        if (pulled.status == LanePullStatus::Empty) break;
        require(
            pulled.sourceFramesConsumed > 0 || pulled.outputFramesProduced > 0,
            "drain makes progress");
        require(++calls < 100'000, "drain terminates");
    }
    return result;
}

std::uint64_t expectedFinalTimelineFrame(
    std::uint64_t sourceFrames,
    std::uint32_t sourceRate) {
    const std::uint64_t whole = sourceFrames / sourceRate;
    const std::uint64_t remainder = sourceFrames % sourceRate;
    return whole * 48'000U +
        (remainder * 48'000U + sourceRate - 1U) / sourceRate;
}

std::vector<float> drainEndOfStream(
    LaneIngress &lane,
    std::size_t outputCapacity,
    std::uint64_t expectedGeneration) {
    const std::size_t boundedCapacity = std::min(
        outputCapacity,
        lane.diagnostics().maximumOutputQuantumFrames);
    std::vector<float> scratch(boundedCapacity * 2U, 0.0F);
    std::vector<float> result;
    std::size_t calls = 0;
    while (true) {
        const auto pulled = lane.pull48k(scratch.data(), boundedCapacity);
        require(
            pulled.status == LanePullStatus::Ok ||
                pulled.status == LanePullStatus::OutputCapacityExhausted ||
                pulled.status == LanePullStatus::EndOfStream,
            "EOS drain status");
        require(pulled.generation == expectedGeneration, "EOS drain generation");
        result.insert(
            result.end(),
            scratch.begin(),
            scratch.begin() + static_cast<std::ptrdiff_t>(pulled.outputFramesProduced * 2U));
        if (pulled.status == LanePullStatus::EndOfStream) break;
        require(
            pulled.sourceFramesConsumed > 0 || pulled.outputFramesProduced > 0,
            "EOS drain makes progress status=" +
                std::to_string(static_cast<int>(pulled.status)) +
                " generation=" + std::to_string(pulled.generation) +
                " timeline=" + std::to_string(pulled.firstTimelineFrame48k));
        require(++calls < 100'000, "EOS drain terminates");
    }
    return result;
}

void formatMatrixNormalizesToStereoFloat() {
    {
        LaneIngress lane(48'000, 64, 64, 1);
        const std::int16_t mono[] = {
            std::numeric_limits<std::int16_t>::min(), -16'384, 0, 16'384,
            std::numeric_limits<std::int16_t>::max(),
        };
        const LaneInputBlock block{
            mono,
            std::size(mono),
            {48'000, 1, LanePcmEncoding::Signed16},
            1,
            0,
        };
        require(lane.offer(block).status == LaneOfferStatus::Accepted, "s16 mono offer");
        float output[10]{};
        const auto pull = lane.pull48k(output, std::size(mono));
        require(pull.outputFramesProduced == std::size(mono), "s16 mono frames");
        for (std::size_t index = 0; index < std::size(mono); ++index) {
            const float expected = static_cast<float>(mono[index]) / 32768.0F;
            require(output[index * 2U] == expected, "s16 mono left");
            require(output[index * 2U + 1U] == expected, "s16 mono duplicate");
        }
    }
    {
        LaneIngress lane(48'000, 64, 64, 2);
        const std::int16_t stereo[] = {-32'768, 32'767, -12'345, 23'456, 0, -1};
        const LaneInputBlock block{
            stereo,
            3,
            {48'000, 2, LanePcmEncoding::Signed16},
            2,
            0,
        };
        require(lane.offer(block).status == LaneOfferStatus::Accepted, "s16 stereo offer");
        float output[6]{};
        require(lane.pull48k(output, 3).outputFramesProduced == 3, "s16 stereo frames");
        for (std::size_t index = 0; index < 6; ++index) {
            require(
                output[index] == static_cast<float>(stereo[index]) / 32768.0F,
                "s16 stereo sample");
        }
    }
    {
        LaneIngress lane(48'000, 64, 64, 3);
        const float mono[] = {
            -0.25F,
            std::numeric_limits<float>::quiet_NaN(),
            10.0F,
            -10.0F,
        };
        const auto block = floatBlock(mono, 4, 48'000, 1, 3, 0);
        require(lane.offer(block).status == LaneOfferStatus::Accepted, "float mono offer");
        float output[8]{};
        require(lane.pull48k(output, 4).outputFramesProduced == 4, "float mono frames");
        const float expected[] = {-0.25F, 0.0F, 10.0F, -10.0F};
        for (std::size_t index = 0; index < 4; ++index) {
            require(output[index * 2U] == expected[index], "float mono left");
            require(output[index * 2U + 1U] == expected[index], "float mono duplicate");
        }
        const auto diagnostics = lane.diagnostics();
        require(diagnostics.nonFiniteFloatSamples == 1, "nonfinite diagnostic");
    }
    {
        LaneIngress lane(48'000, 64, 64, 4);
        const float stereo[] = {0.1F, -0.2F, 0.3F, -0.4F};
        const auto block = floatBlock(stereo, 2, 48'000, 2, 4, 0);
        require(lane.offer(block).status == LaneOfferStatus::Accepted, "float stereo offer");
        float output[4]{};
        require(lane.pull48k(output, 2).outputFramesProduced == 2, "float stereo frames");
        require(std::memcmp(output, stereo, sizeof(stereo)) == 0, "float stereo exact bypass");
    }
}

std::vector<float> convertWithOfferChunks(
    std::uint32_t sourceRate,
    const std::vector<float> &input,
    const std::vector<std::size_t> &chunks,
    std::size_t pullCapacity) {
    const std::size_t frames = input.size() / 2U;
    LaneIngress lane(sourceRate, frames + 64U, frames, 10);
    std::size_t offset = 0;
    std::size_t chunkIndex = 0;
    while (offset < frames) {
        const std::size_t requested = std::min(
            chunks[chunkIndex % chunks.size()],
            frames - offset);
        const auto offered = lane.offer(floatBlock(
            input.data() + offset * 2U,
            requested,
            sourceRate,
            2,
            10,
            offset));
        require(offered.inputFramesAccepted == requested, "chunk accepted");
        offset += requested;
        ++chunkIndex;
    }
    return drainAll(lane, pullCapacity);
}

void ratesAndArbitraryChunkingAreDeterministic() {
    for (const std::uint32_t rate : {
             8'000U, 44'100U, 48'000U, 96'000U, 192'000U, 384'000U}) {
        const std::size_t frames = rate == 8'000U ? 1'337U : 4'097U;
        const auto input = stereoSignal(frames, rate);
        const auto baseline = convertWithOfferChunks(rate, input, {frames}, 16'384);
        const auto fragmented = convertWithOfferChunks(
            rate,
            input,
            {1, 17, 3, 251, 2, 509, 31},
            17);
        require(fragmented.size() == baseline.size(), "chunked output count");
        require(
            std::memcmp(
                fragmented.data(),
                baseline.data(),
                baseline.size() * sizeof(float)) == 0,
            "chunked output is byte deterministic");
        const std::uint64_t producedFrames = baseline.size() / 2U;
        const std::uint64_t expectedFrames =
            static_cast<std::uint64_t>(frames) * 48'000U / rate;
        require(
            producedFrames <= expectedFrames && expectedFrames - producedFrames <= 512U,
            "rate conversion remains timeline bounded");
    }
}

void nonzeroPhaseAlignedHistoryMatchesTheZeroOriginSamples() {
    for (const std::uint32_t rate : {8'000U, 8'001U, 44'100U, 48'000U, 96'000U}) {
        const auto input = stereoSignal(rate * 3U, rate, 0.17F);
        const auto read = [&](std::size_t firstSourceFrame, std::size_t partition) {
            LaneIngress lane(rate, input.size() / 2U + 64U, 1024U, 10U);
            std::size_t offset = firstSourceFrame;
            while (offset < input.size() / 2U) {
                const auto count = std::min(partition, input.size() / 2U - offset);
                require(lane.offer(floatBlock(input.data() + offset * 2U, count,
                    rate, 2U, 10U, offset)).inputFramesAccepted == count, "history input accepted");
                offset += count;
            }
            const auto output = drainAll(lane, partition);
            const auto firstTag = static_cast<std::uint64_t>(firstSourceFrame) * 48'000U / rate;
            constexpr std::size_t target = 2'002U * 48U;
            require(firstTag <= target && output.size() / 2U > target - firstTag + 1024U,
                "same-tag window exists after real preroll");
            const auto start = output.begin() + static_cast<std::ptrdiff_t>((target - firstTag) * 2U);
            return std::vector<float>(start, start + 2048);
        };
        const auto baseline = read(0U, 1024U);
        for (const auto partition : {17U, 193U, 1024U}) {
            const auto anchored = read(rate, partition);
            require(anchored == baseline, "phase-aligned real filter history preserves same-tag PCM");
        }
        if (rate == 44'100U) {
            const auto oldMillisecondPrefix = read(2'001U * rate / 1'000U + 1U, 193U);
            require(oldMillisecondPrefix != baseline,
                "negative control detects the old fractional-phase error despite matching integer tags");
        }
    }
}

void sourcePtsMapsToExactRational48kTimeline() {
    {
        LaneIngress lane(44'100, 128, 64, 11);
        const auto input = stereoSignal(64, 44'100);
        require(
            lane.offer(floatBlock(input.data(), 64, 44'100, 2, 11, 441))
                    .inputFramesAccepted == 64,
            "44.1k offset offer");
        std::vector<float> output(lane.diagnostics().maximumOutputQuantumFrames * 2U);
        const auto pulled = lane.pull48k(
            output.data(),
            lane.diagnostics().maximumOutputQuantumFrames);
        require(pulled.firstSourceFrame == 441, "44.1k source descriptor");
        require(pulled.firstTimelineFrame48k == 480, "44.1k exact rational timeline");
    }
    {
        LaneIngress lane(8'000, 128, 64, 12);
        const auto input = stereoSignal(64, 8'000);
        require(
            lane.offer(floatBlock(input.data(), 64, 8'000, 2, 12, 2'000))
                    .inputFramesAccepted == 64,
            "8k offset offer");
        std::vector<float> output(lane.diagnostics().maximumOutputQuantumFrames * 2U);
        const auto pulled = lane.pull48k(
            output.data(),
            lane.diagnostics().maximumOutputQuantumFrames);
        require(pulled.firstSourceFrame == 2'000, "8k source descriptor");
        require(pulled.firstTimelineFrame48k == 12'000, "8k exact rational timeline");
    }
}

void eosFlushHasExactFiniteLengthAcrossRatesAndShortStreams() {
    for (const std::uint32_t rate : {
             8'000U, 44'100U, 48'000U, 96'000U, 192'000U, 384'000U}) {
        for (const std::size_t frameCount : {0U, 1U, 2U, 7U, 127U, 1'001U}) {
            const std::size_t capacity = std::max<std::size_t>(64, frameCount + 1U);
            const std::size_t quantum = std::max<std::size_t>(1, frameCount);
            const std::uint64_t generation =
                1'000U + static_cast<std::uint64_t>(rate) + frameCount;
            LaneIngress lane(rate, capacity, quantum, generation);
            std::vector<float> input;
            if (frameCount > 0) {
                input = stereoSignal(frameCount, rate, 0.7F);
                const auto offered = lane.offer(floatBlock(
                    input.data(), frameCount, rate, 2, generation, 0));
                require(offered.inputFramesAccepted == frameCount, "EOS complete input offer");
            }
            const auto marked = lane.markEndOfStream(generation, frameCount);
            require(marked.status == LaneEndOfStreamStatus::Accepted, "EOS marker accepted");
            require(
                marked.finalTimelineFrame48kExclusive ==
                    expectedFinalTimelineFrame(frameCount, rate),
                "EOS rational endpoint");
            const auto idempotent = lane.markEndOfStream(generation, frameCount);
            require(
                idempotent.status == LaneEndOfStreamStatus::AlreadyMarked,
                "EOS marker idempotent");

            const auto output = drainEndOfStream(lane, 17, generation);
            const std::size_t expectedFrames = static_cast<std::size_t>(
                expectedFinalTimelineFrame(frameCount, rate));
            require(output.size() == expectedFrames * 2U, "EOS exact final output length");
            require(
                std::all_of(output.begin(), output.end(), [](float sample) {
                    return std::isfinite(sample);
                }),
                "EOS tail remains finite");
            if (rate == 48'000U) {
                require(
                    output.size() == input.size() &&
                        std::memcmp(
                            output.data(),
                            input.data(),
                            input.size() * sizeof(float)) == 0,
                    "48k EOS bypass neither pads nor truncates PCM");
            }
            const auto diagnostics = lane.diagnostics();
            require(diagnostics.completedEndOfStreams == 1, "EOS completion diagnostic");
            require(diagnostics.endOfStreamFlushFailures == 0, "EOS no flush failure");
            require(
                diagnostics.finalTimelineFrame48kExclusive == expectedFrames,
                "EOS diagnostic endpoint");

            float extra[2]{};
            const auto afterEos = lane.offer(floatBlock(
                extra, 1, rate, 2, generation, frameCount));
            require(
                afterEos.status == LaneOfferStatus::AfterEndOfStream,
                "post-EOS input rejected");
        }
    }
}

void eosRequiresTheFullyAcceptedExclusiveBoundary() {
    LaneIngress lane(48'000, 64, 64, 70);
    const auto input = stereoSignal(100, 48'000);
    const auto partial = lane.offer(floatBlock(input.data(), 100, 48'000, 2, 70, 0));
    require(partial.status == LaneOfferStatus::Partial, "EOS boundary partial offer");
    require(partial.inputFramesAccepted == 64, "EOS boundary accepted prefix");
    require(
        lane.markEndOfStream(70, 100).status == LaneEndOfStreamStatus::BoundaryMismatch,
        "EOS ahead of accepted PCM rejected");
    require(
        lane.markEndOfStream(70, 63).status == LaneEndOfStreamStatus::BoundaryMismatch,
        "EOS behind accepted PCM rejected");

    LaneIngress empty(44'100, 64, 64, 71);
    require(
        empty.markEndOfStream(71, 1).status == LaneEndOfStreamStatus::BoundaryMismatch,
        "non-zero empty EOS rejected");
    require(
        empty.markEndOfStream(71, 0).status == LaneEndOfStreamStatus::Accepted,
        "zero-length empty EOS accepted");
    const auto output = drainEndOfStream(empty, 8, 71);
    require(output.empty(), "empty EOS emits no invented silence");
}

void eosAtANonZeroSeekPositionUsesAbsoluteTimelineBoundaries() {
    constexpr std::uint32_t kRate = 44'100;
    constexpr std::uint64_t kStartFrame = 441;
    constexpr std::size_t kFrames = 441;
    constexpr std::uint64_t kEndFrame = kStartFrame + kFrames;
    LaneIngress lane(kRate, 512, 441, 75);
    const auto input = stereoSignal(kFrames, kRate, 0.4F);
    require(
        lane.offer(floatBlock(input.data(), kFrames, kRate, 2, 75, kStartFrame))
                .inputFramesAccepted == kFrames,
        "seek-offset EOS input");
    const auto marker = lane.markEndOfStream(75, kEndFrame);
    require(marker.status == LaneEndOfStreamStatus::Accepted, "seek-offset EOS marker");
    require(marker.finalTimelineFrame48kExclusive == 960, "seek-offset final timeline");
    const auto output = drainEndOfStream(lane, 19, 75);
    require(output.size() == 480U * 2U, "seek-offset EOS duration only");
}

void eosAfterPartialQueueBackpressureStillEndsExactlyOnce() {
    constexpr std::size_t kFrames = 1'337;
    constexpr std::uint32_t kRate = 44'100;
    LaneIngress lane(kRate, 64, 64, 80);
    const auto input = stereoSignal(kFrames, kRate);
    std::vector<float> rendered;
    std::vector<float> scratch(lane.diagnostics().maximumOutputQuantumFrames * 2U, 0.0F);
    std::size_t sourceOffset = 0;
    while (sourceOffset < kFrames) {
        const auto offered = lane.offer(floatBlock(
            input.data() + sourceOffset * 2U,
            kFrames - sourceOffset,
            kRate,
            2,
            80,
            sourceOffset));
        sourceOffset += offered.inputFramesAccepted;
        if (sourceOffset < kFrames) {
            const auto pulled = lane.pull48k(scratch.data(), 17);
            require(
                pulled.status == LanePullStatus::Ok ||
                    pulled.status == LanePullStatus::OutputCapacityExhausted,
                "EOS backpressure pre-drain status");
            rendered.insert(
                rendered.end(),
                scratch.begin(),
                scratch.begin() +
                    static_cast<std::ptrdiff_t>(pulled.outputFramesProduced * 2U));
        }
    }
    require(
        lane.markEndOfStream(80, kFrames).status == LaneEndOfStreamStatus::Accepted,
        "EOS after full partial retry accepted");
    auto tail = drainEndOfStream(lane, 7, 80);
    rendered.insert(rendered.end(), tail.begin(), tail.end());
    const std::size_t expectedFrames = static_cast<std::size_t>(
        expectedFinalTimelineFrame(kFrames, kRate));
    require(rendered.size() == expectedFrames * 2U, "EOS backpressure exact endpoint");
    const auto diagnostics = lane.diagnostics();
    require(diagnostics.partialOfferEvents > 0, "EOS exercised partial input offers");
    require(diagnostics.endOfStreamFlushFailures == 0, "EOS partial flush healthy");
}

void seekInvalidatesPublishedAndLateStaleEos() {
    LaneIngress lane(44'100, 512, 512, 90);
    const auto oldInput = stereoSignal(512, 44'100);
    require(
        lane.offer(floatBlock(oldInput.data(), 512, 44'100, 2, 90, 0))
                .inputFramesAccepted == 512,
        "old generation queued");
    require(
        lane.markEndOfStream(90, 512).status == LaneEndOfStreamStatus::Accepted,
        "old EOS published");
    require(lane.advanceGeneration(91), "seek advances generation");
    require(
        lane.markEndOfStream(90, 512).status == LaneEndOfStreamStatus::StaleGeneration,
        "late old EOS rejected");

    const auto freshInput = stereoSignal(257, 44'100, 1.0F);
    require(
        lane.offer(floatBlock(freshInput.data(), 257, 44'100, 2, 91, 0))
                .inputFramesAccepted == 0,
        "old queued frames apply bounded backpressure to fresh generation");
    std::vector<float> scratch(lane.diagnostics().maximumOutputQuantumFrames * 2U);
    const auto staleDrain = lane.pull48k(
        scratch.data(),
        lane.diagnostics().maximumOutputQuantumFrames);
    require(staleDrain.status == LanePullStatus::Empty, "old queued generation discarded");
    require(
        lane.offer(floatBlock(freshInput.data(), 257, 44'100, 2, 91, 0))
                .inputFramesAccepted == 257,
        "fresh generation queued");
    require(
        lane.markEndOfStream(91, 257).status == LaneEndOfStreamStatus::Accepted,
        "fresh EOS accepted");
    const auto fresh = drainEndOfStream(lane, 13, 91);
    require(
        fresh.size() == expectedFinalTimelineFrame(257, 44'100) * 2U,
        "seek EOS exact fresh length");
    const auto diagnostics = lane.diagnostics();
    require(diagnostics.staleEndOfStreamMarks == 1, "stale EOS diagnostic");
    require(diagnostics.completedEndOfStreams == 1, "only fresh EOS completed");
}

void partialCapacityRetryNeverDuplicates() {
    LaneIngress lane(48'000, 64, 64, 20);
    auto input = stereoSignal(137, 48'000);
    auto first = lane.offer(floatBlock(input.data(), 137, 48'000, 2, 20, 0));
    require(first.status == LaneOfferStatus::Partial, "queue partial status");
    require(first.inputFramesAccepted == 64, "queue partial accepted prefix");
    auto output = drainAll(lane, 11);

    std::size_t offset = first.inputFramesAccepted;
    while (offset < 137) {
        const auto offered = lane.offer(floatBlock(
            input.data() + offset * 2U,
            137 - offset,
            48'000,
            2,
            20,
            offset));
        offset += offered.inputFramesAccepted;
        auto suffix = drainAll(lane, 7);
        output.insert(output.end(), suffix.begin(), suffix.end());
    }
    require(output.size() == input.size(), "partial retry output count");
    require(
        std::memcmp(output.data(), input.data(), input.size() * sizeof(float)) == 0,
        "partial retry exact without duplicate");
    const auto diagnostics = lane.diagnostics();
    require(diagnostics.acceptedInputFrames == 137, "partial retry accepted exactly once");
    require(diagnostics.queuedSourceFrames == 137, "partial retry queued exactly once");
}

void gapsOverlapsAndExplicitDiscontinuitiesAreUnambiguous() {
    LaneIngress lane(48'000, 128, 64, 30);
    std::vector<float> first(20, 0.1F);       // frames 0..9
    std::vector<float> overlapAndNew(20, 0.2F);  // frames 5..14
    std::vector<float> afterGap(10, 0.3F);    // frames 25..29
    require(
        lane.offer(floatBlock(first.data(), 10, 48'000, 2, 30, 0)).inputFramesAccepted == 10,
        "initial span");
    const auto overlap = lane.offer(floatBlock(
        overlapAndNew.data(), 10, 48'000, 2, 30, 5));
    require(overlap.inputFramesAccepted == 10, "overlap consumed");
    require(overlap.sourceFramesDiscarded == 5, "overlap prefix trimmed");
    require(overlap.sourceFramesQueued == 5, "overlap suffix queued");
    require(
        lane.offer(floatBlock(afterGap.data(), 5, 48'000, 2, 30, 25)).inputFramesAccepted == 5,
        "gap span queued");

    float output[128]{};
    const auto contiguous = lane.pull48k(output, 64);
    require(contiguous.outputFramesProduced == 15, "overlap remains contiguous");
    require(contiguous.firstTimelineFrame48k == 0, "initial timeline");
    require(
        hasDiscontinuity(contiguous.discontinuity, LaneDiscontinuity::Generation),
        "initial generation boundary");
    for (std::size_t index = 0; index < 10; ++index) {
        require(output[index * 2U] == 0.1F, "initial samples retained");
    }
    for (std::size_t index = 10; index < 15; ++index) {
        require(output[index * 2U] == 0.2F, "only new overlap suffix retained");
    }
    const auto gap = lane.pull48k(output, 64);
    require(gap.outputFramesProduced == 5, "gap suffix output");
    require(gap.firstTimelineFrame48k == 25, "gap exact timeline jump");
    require(
        hasDiscontinuity(gap.discontinuity, LaneDiscontinuity::TimestampGap),
        "gap explicitly reported");

    require(lane.markDiscontinuity(30), "explicit mark accepted");
    std::vector<float> explicitData(8, 0.4F);
    require(
        lane.offer(floatBlock(explicitData.data(), 4, 48'000, 2, 30, 40))
                .inputFramesAccepted == 4,
        "explicit span queued");
    const auto explicitPull = lane.pull48k(output, 64);
    require(explicitPull.firstTimelineFrame48k == 40, "explicit exact timeline");
    require(
        hasDiscontinuity(explicitPull.discontinuity, LaneDiscontinuity::Explicit),
        "explicit flag reported");
    const auto diagnostics = lane.diagnostics();
    require(diagnostics.overlapFramesDiscarded == 5, "overlap diagnostic");
    require(diagnostics.timestampGapEvents == 1, "producer gap diagnostic");
}

void staleGenerationAndSeekFlushAreDeterministic() {
    constexpr std::uint32_t kRate = 44'100;
    constexpr std::size_t kFrames = 1'024;
    LaneIngress lane(kRate, 2'048, kFrames, 40);
    const auto input = stereoSignal(kFrames, kRate);
    require(
        lane.offer(floatBlock(input.data(), kFrames, kRate, 2, 40, 0)).inputFramesAccepted ==
            kFrames,
        "generation one offered");
    const auto first = drainAll(lane);

    require(lane.advanceGeneration(41), "generation advances");
    const auto stale = lane.offer(floatBlock(input.data(), 100, kRate, 2, 40, 0));
    require(stale.status == LaneOfferStatus::StaleGeneration, "stale rejected");
    require(stale.inputFramesAccepted == 100, "stale decoder may release buffer");
    require(stale.sourceFramesQueued == 0, "stale never queued");
    require(
        lane.offer(floatBlock(input.data(), kFrames, kRate, 2, 41, 0)).inputFramesAccepted ==
            kFrames,
        "post-seek offered");
    const auto replay = drainAll(lane);
    require(replay.size() == first.size(), "seek replay frame count");
    require(
        std::memcmp(replay.data(), first.data(), first.size() * sizeof(float)) == 0,
        "seek reset byte deterministic");
    const auto diagnostics = lane.diagnostics();
    require(diagnostics.activeGeneration == 41, "active generation");
    require(diagnostics.consumerGeneration == 41, "consumer generation");
    require(diagnostics.staleFramesDiscarded >= 100, "stale diagnostic");
}

void queuedOldGenerationCannotBlockOrLeakAfterFlush() {
    LaneIngress lane(48'000, 64, 64, 50);
    std::vector<float> oldData(128, -0.75F);
    std::vector<float> newData(128, 0.75F);
    require(
        lane.offer(floatBlock(oldData.data(), 64, 48'000, 2, 50, 0)).inputFramesAccepted == 64,
        "old queue filled");
    require(lane.advanceGeneration(51), "flush ownership advance");
    const auto initiallyFull = lane.offer(floatBlock(newData.data(), 64, 48'000, 2, 51, 0));
    require(
        initiallyFull.status == LaneOfferStatus::Backpressured,
        "bounded queue does not rewrite consumer cursor");
    float output[128]{};
    const auto discard = lane.pull48k(output, 64);
    require(discard.status == LanePullStatus::Empty, "old queue drained as stale");
    require(discard.staleSourceFramesDiscarded == 64, "all old frames discarded");
    require(
        lane.offer(floatBlock(newData.data(), 64, 48'000, 2, 51, 0)).inputFramesAccepted == 64,
        "new owner queues after stale drain");
    const auto fresh = lane.pull48k(output, 64);
    require(fresh.outputFramesProduced == 64, "fresh output produced");
    for (std::size_t index = 0; index < 128; ++index) {
        require(output[index] == 0.75F, "no old generation leaked");
    }
}

void expectedGenerationGatePreservesFutureHead() {
    LaneIngress lane(48'000, 128, 64, 70);
    std::vector<float> future(128, 0.625F);
    require(
        lane.offer(floatBlock(future.data(), 64, 48'000, 2, 71, 0))
                .inputFramesAccepted == 64,
        "future generation prebuffered before bank intent");
    const std::size_t readableBefore = lane.diagnostics().readableSourceFrames;
    float output[128]{};
    const auto deferred = lane.pull48k(70, output, 64);
    require(deferred.status == LanePullStatus::StaleGeneration, "future pull deferred");
    require(deferred.outputFramesProduced == 0, "deferred future produced nothing");
    require(deferred.sourceFramesConsumed == 0, "deferred future consumed nothing");
    require(
        lane.diagnostics().readableSourceFrames == readableBefore,
        "future head remains byte-for-byte queued");

    const auto admitted = lane.pull48k(71, output, 64);
    require(admitted.status == LanePullStatus::Ok, "future owner admits its head");
    require(admitted.generation == 71, "future owner generation exact");
    require(admitted.firstTimelineFrame48k == 0, "future head timeline not clipped");
    require(admitted.outputFramesProduced == 64, "whole future head retained");
    for (float sample : output) {
        require(sample == 0.625F, "future head sample preserved");
    }
}

void gatedCurrentOwnerDrainsItsStalePrefixSoFutureIngressCanPrime() {
    LaneIngress lane(48'000, 64, 64, 80);
    std::vector<float> oldData(128, -0.5F);
    std::vector<float> futureData(128, 0.5F);
    require(
        lane.offer(floatBlock(oldData.data(), 64, 48'000, 2, 80, 0))
                .inputFramesAccepted == 64,
        "current owner fills bounded queue");
    require(lane.advanceGeneration(81), "future ingress epoch authorized");
    require(
        lane.offer(floatBlock(futureData.data(), 64, 48'000, 2, 81, 100))
                .status == LaneOfferStatus::Backpressured,
        "future body initially waits behind current prefix");

    float output[128]{};
    const auto retired = lane.pull48k(80, output, 64);
    require(retired.status == LanePullStatus::StaleGeneration, "retired owner is fenced");
    require(retired.outputFramesProduced == 0, "retired owner never leaks PCM");
    require(retired.staleSourceFramesDiscarded == 64, "retired prefix releases capacity");

    require(
        lane.offer(floatBlock(futureData.data(), 64, 48'000, 2, 81, 100))
                .inputFramesAccepted == 64,
        "future body primes after bounded stale drain");
    const auto stillCurrent = lane.pull48k(80, output, 64);
    require(stillCurrent.status == LanePullStatus::StaleGeneration, "future head remains gated");
    require(stillCurrent.sourceFramesConsumed == 0, "future head remains intact");

    const auto admitted = lane.pull48k(81, output, 64);
    require(admitted.status == LanePullStatus::Ok, "future owner admits primed body");
    require(admitted.outputFramesProduced == 64, "future body preserved in full");
    for (float sample : output) {
        require(sample == 0.5F, "future body sample preserved");
    }
}

void explicitBoundarySurvivesACompletelyFullQueue() {
    LaneIngress lane(48'000, 64, 64, 60);
    std::vector<float> initial(128, 0.1F);
    std::vector<float> later(16, 0.2F);
    require(
        lane.offer(floatBlock(initial.data(), 64, 48'000, 2, 60, 0))
                .inputFramesAccepted == 64,
        "explicit-pressure initial fill");
    require(lane.markDiscontinuity(60), "explicit-pressure mark");
    const auto full = lane.offer(floatBlock(later.data(), 8, 48'000, 2, 60, 100));
    require(full.status == LaneOfferStatus::Backpressured, "explicit-pressure backpressure");
    float output[128]{};
    require(lane.pull48k(output, 64).outputFramesProduced == 64, "initial queue drained");
    require(
        lane.offer(floatBlock(later.data(), 8, 48'000, 2, 60, 100))
                .inputFramesAccepted == 8,
        "explicit-pressure retry accepted");
    const auto retried = lane.pull48k(output, 64);
    require(retried.firstTimelineFrame48k == 100, "explicit-pressure timeline retained");
    require(
        hasDiscontinuity(retried.discontinuity, LaneDiscontinuity::Explicit),
        "explicit boundary retained across zero acceptance");
}

void twoSevenLaneBanksSurvivePressure() {
    SongLaneIngressBank activeBank(256, 127);
    SongLaneIngressBank candidateBank(256, 127);
    require(activeBank.configuredLaneCount() == 0, "empty active bank");
    require(candidateBank.configuredLaneCount() == 0, "empty candidate bank");
    for (std::size_t bankIndex = 0; bankIndex < 2; ++bankIndex) {
        SongLaneIngressBank &bank = bankIndex == 0 ? activeBank : candidateBank;
        for (std::size_t laneIndex = 0; laneIndex < SongLaneIngressBank::kLaneCount; ++laneIndex) {
            const std::uint32_t rate = laneIndex % 3U == 0U
                ? 8'000U
                : (laneIndex % 3U == 1U ? 44'100U : 48'000U);
            const std::uint64_t generation = 100U + bankIndex * 10U + laneIndex;
            require(bank.configureLane(laneIndex, rate, generation), "configure song lane");
            require(!bank.configureLane(laneIndex, rate, generation), "no duplicate lane");
        }
    }
    require(activeBank.configuredLaneCount() == SongLaneIngressBank::kLaneCount,
        "every active buffer configured");
    require(candidateBank.configuredLaneCount() == SongLaneIngressBank::kLaneCount,
        "every candidate buffer configured");
    require(!activeBank.configureLane(SongLaneIngressBank::kLaneCount, 48'000, 999),
        "lane beyond the bounded capacity rejected");

    for (std::size_t cycle = 0; cycle < 1'000; ++cycle) {
        for (std::size_t bankIndex = 0; bankIndex < 2; ++bankIndex) {
            SongLaneIngressBank &bank = bankIndex == 0 ? activeBank : candidateBank;
            for (std::size_t laneIndex = 0; laneIndex < SongLaneIngressBank::kLaneCount; ++laneIndex) {
                LaneIngress *lane = bank.lane(laneIndex);
                require(lane != nullptr, "configured lane address");
                const std::uint32_t rate = lane->diagnostics().sourceRateHz;
                const auto input = stereoSignal(17, rate, static_cast<float>(cycle));
                const std::uint64_t generation = 100U + bankIndex * 10U + laneIndex;
                const auto offered = lane->offer(floatBlock(
                    input.data(),
                    17,
                    rate,
                    2,
                    generation,
                    cycle * 17U));
                require(offered.inputFramesAccepted == 17, "two-bank pressure offer");
                std::vector<float> output(lane->diagnostics().maximumOutputQuantumFrames * 2U);
                const auto pulled = lane->pull48k(
                    output.data(),
                    lane->diagnostics().maximumOutputQuantumFrames);
                require(pulled.status == LanePullStatus::Ok, "two-bank pressure pull");
            }
        }
    }
    candidateBank.releaseLane(SongLaneIngressBank::kLaneCount - 1U);
    require(candidateBank.configuredLaneCount() == SongLaneIngressBank::kLaneCount - 1U,
        "candidate lane released independently");
    require(activeBank.configuredLaneCount() == SongLaneIngressBank::kLaneCount,
        "active bank remains resident");
}

void concurrentLazyLanePublicationIsRaceFree(int holdAfterPublications = -1) {
    constexpr std::size_t kReaderCount = 4;
    constexpr std::array<std::uint32_t, 7U> kRateCycle{
        8'000U, 22'050U, 44'100U, 48'000U, 96'000U, 192'000U, 384'000U,
    };
    constexpr std::uint64_t kFirstGeneration = 10'000U;

    SongLaneIngressBank bank(256, 127);
    const SongLaneIngressBank &constBank = bank;
    std::atomic<std::size_t> readersReady{0};
    std::atomic<bool> start{false};
    std::atomic<bool> producerDone{false};
    std::atomic<std::size_t> producerProgress{0U};
    std::atomic<std::size_t> producerAttempt{SongLaneIngressBank::kLaneCount};
    std::atomic<const char *> producerPhase{"start-barrier"};
    std::array<std::atomic<std::size_t>, kReaderCount> readerObservedPublications{};
    std::array<std::uint64_t, SongLaneIngressBank::kLaneCount> configurationStartMicros{};
    std::array<std::uint64_t, SongLaneIngressBank::kLaneCount> publicationMicros{};
    std::array<std::uint64_t, kReaderCount> readerScans{};
    const auto began = std::chrono::steady_clock::now();
    std::atomic<bool> readerDeadline{false};
    std::atomic<bool> controlledDeadlineElapsed{false};
    std::atomic<const char *> firstInvariant{nullptr};
    std::size_t firstLane = SongLaneIngressBank::kLaneCount;
    std::size_t firstCount = 0U;
    std::size_t firstVisible = 0U;
    const auto recordFailure = [&](const char *reason, std::size_t lane, std::size_t count,
                                   std::size_t visible = 0U) {
        const char *empty = nullptr;
        if (firstInvariant.compare_exchange_strong(empty, reason, std::memory_order_relaxed)) {
            firstLane = lane;
            firstCount = count;
            firstVisible = visible;
        }
    };
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(15'000);

    auto reader = [&](std::stop_token stopToken, std::size_t readerIndex) {
        std::array<const LaneIngress *, SongLaneIngressBank::kLaneCount> observed{};
        readersReady.fetch_add(1U, std::memory_order_release);
        while (!start.load(std::memory_order_acquire) && !stopToken.stop_requested() &&
               std::chrono::steady_clock::now() < deadline) {
            std::this_thread::yield();
        }

        std::size_t previousCount = 0;
        std::size_t postPublicationScans = 0;
        while ((!producerDone.load(std::memory_order_acquire) || postPublicationScans < 2'000U) &&
               !stopToken.stop_requested() &&
               !controlledDeadlineElapsed.load(std::memory_order_acquire) &&
               std::chrono::steady_clock::now() < deadline) {
            const std::size_t publishedCount = constBank.configuredLaneCount();
            ++readerScans[readerIndex];
            if (publishedCount < previousCount ||
                publishedCount > SongLaneIngressBank::kLaneCount) {
                recordFailure(publishedCount < previousCount ? "count-regressed" : "count-exceeds-capacity",
                    SongLaneIngressBank::kLaneCount, publishedCount);
            }
            previousCount = publishedCount;

            std::size_t visibleCount = 0;
            for (std::size_t laneIndex = 0;
                 laneIndex < SongLaneIngressBank::kLaneCount;
                 ++laneIndex) {
                const LaneIngress *const published = constBank.lane(laneIndex);
                if (published == nullptr) continue;
                ++visibleCount;
                if (observed[laneIndex] == nullptr) {
                    observed[laneIndex] = published;
                } else if (observed[laneIndex] != published) {
                    recordFailure("pointer-changed", laneIndex, publishedCount);
                }

                const auto diagnostics = published->diagnostics();
                if (published->sourceRateHz() != kRateCycle[laneIndex % kRateCycle.size()] ||
                    diagnostics.sourceRateHz != kRateCycle[laneIndex % kRateCycle.size()] ||
                    diagnostics.activeGeneration != kFirstGeneration + laneIndex) {
                    recordFailure(published->sourceRateHz() != kRateCycle[laneIndex % kRateCycle.size()]
                        ? "source-rate" : diagnostics.sourceRateHz != kRateCycle[laneIndex % kRateCycle.size()]
                        ? "diagnostic-rate" : "generation", laneIndex, publishedCount);
                }
            }

            // The count publication is sequenced after every corresponding pointer publication.
            // An acquire count therefore cannot expose more lanes than subsequent acquire loads.
            if (visibleCount < publishedCount) {
                recordFailure("visible-count", SongLaneIngressBank::kLaneCount, publishedCount, visibleCount);
            }
            readerObservedPublications[readerIndex].store(publishedCount, std::memory_order_release);
            if (producerDone.load(std::memory_order_acquire)) ++postPublicationScans;
        }

        if (std::chrono::steady_clock::now() >= deadline ||
            controlledDeadlineElapsed.load(std::memory_order_acquire)) {
            readerDeadline.store(true, std::memory_order_relaxed);
        } else {
            for (std::size_t lane = 0U; lane < observed.size(); ++lane)
                if (observed[lane] == nullptr) recordFailure("missing-lane", lane, constBank.configuredLaneCount());
        }
    };

    std::array<std::jthread, kReaderCount> readers{
        std::jthread(reader, 0U),
        std::jthread(reader, 1U),
        std::jthread(reader, 2U),
        std::jthread(reader, 3U),
    };
    std::jthread producer([&](std::stop_token stopToken) {
        while (!start.load(std::memory_order_acquire) && !stopToken.stop_requested() &&
               std::chrono::steady_clock::now() < deadline) {
            std::this_thread::yield();
        }
        for (std::size_t laneIndex = 0;
             laneIndex < SongLaneIngressBank::kLaneCount && !stopToken.stop_requested() &&
                 std::chrono::steady_clock::now() < deadline;
             ++laneIndex) {
            if (static_cast<int>(laneIndex) == holdAfterPublications) {
                producerPhase.store("controlled-delay", std::memory_order_relaxed);
                const auto heldDeadline = std::min(deadline,
                    std::chrono::steady_clock::now() + std::chrono::milliseconds(50));
                while (!stopToken.stop_requested() && std::chrono::steady_clock::now() < heldDeadline)
                    std::this_thread::yield();
                controlledDeadlineElapsed.store(true, std::memory_order_release);
                break;
            }
            producerAttempt.store(laneIndex, std::memory_order_relaxed);
            producerPhase.store("configure", std::memory_order_relaxed);
            configurationStartMicros[laneIndex] = static_cast<std::uint64_t>(
                std::chrono::duration_cast<std::chrono::microseconds>(
                    std::chrono::steady_clock::now() - began).count());
            if (!bank.configureLane(
                    laneIndex,
                    kRateCycle[laneIndex % kRateCycle.size()],
                    kFirstGeneration + laneIndex)) {
                recordFailure("producer-configure-rejected", laneIndex, constBank.configuredLaneCount());
            } else {
                producerProgress.store(laneIndex + 1U, std::memory_order_release);
                publicationMicros[laneIndex] = static_cast<std::uint64_t>(
                    std::chrono::duration_cast<std::chrono::microseconds>(
                        std::chrono::steady_clock::now() - began).count());
            }
            // Require real reader overlap instead of spending hundreds of Windows scheduling quanta.
            producerPhase.store("reader-observation", std::memory_order_relaxed);
            while (!stopToken.stop_requested() && std::chrono::steady_clock::now() < deadline &&
                   std::any_of(readerObservedPublications.begin(), readerObservedPublications.end(),
                       [laneIndex](const auto &observed) {
                           return observed.load(std::memory_order_acquire) < laneIndex + 1U;
                       })) {
                std::this_thread::yield();
            }
        }
        producerDone.store(true, std::memory_order_release);
    });

    while (readersReady.load(std::memory_order_acquire) != kReaderCount &&
           std::chrono::steady_clock::now() < deadline) {
        std::this_thread::yield();
    }
    const bool readersStarted = readersReady.load(std::memory_order_acquire) == kReaderCount;
    start.store(true, std::memory_order_release);
    producer.join();
    for (std::jthread &thread : readers) thread.join();

    const char *const invariant = firstInvariant.load(std::memory_order_relaxed);
    const bool elapsed = std::chrono::steady_clock::now() >= deadline ||
        controlledDeadlineElapsed.load(std::memory_order_acquire);
    const char *const disposition = !readersStarted ? "start-barrier-timeout" : invariant != nullptr
        ? "incoherent-observation" : producerProgress.load(std::memory_order_acquire) != SongLaneIngressBank::kLaneCount
        ? "incomplete-producer" : readerDeadline.load(std::memory_order_relaxed) ? "reader-timeout" : "coherent";
    std::string diagnostic = std::string("lazy-lane-publication disposition=") + disposition +
        " invariant=" + (invariant ? invariant : "none") + " lane=" + std::to_string(firstLane) +
        " observedCount=" + std::to_string(firstCount) + " visibleCount=" + std::to_string(firstVisible) +
        " configuredCount=" + std::to_string(constBank.configuredLaneCount()) +
        " producerProgress=" + std::to_string(producerProgress.load(std::memory_order_acquire)) +
        " readersReady=" + std::to_string(readersReady.load(std::memory_order_acquire)) +
        " producerPhase=" + producerPhase.load(std::memory_order_relaxed) +
        " producerAttempt=" + std::to_string(producerAttempt.load(std::memory_order_relaxed)) +
        " elapsedUs=" + std::to_string(std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now() - began).count()) +
        " deadlineElapsed=" + (elapsed ? "true" : "false");
    for (std::size_t lane = 0U; lane < publicationMicros.size(); ++lane) {
        diagnostic += " configureStartUs[" + std::to_string(lane) + "]=" + std::to_string(configurationStartMicros[lane]);
        diagnostic += " publicationUs[" + std::to_string(lane) + "]=" + std::to_string(publicationMicros[lane]);
    }
    for (std::size_t readerIndex = 0U; readerIndex < readerScans.size(); ++readerIndex) {
        diagnostic += " readerScans[" + std::to_string(readerIndex) + "]=" + std::to_string(readerScans[readerIndex]);
        diagnostic += " readerObserved[" + std::to_string(readerIndex) + "]=" +
            std::to_string(readerObservedPublications[readerIndex].load(std::memory_order_acquire));
    }
    if (holdAfterPublications >= 0) {
        require(readersStarted && invariant == nullptr && elapsed &&
            producerProgress.load(std::memory_order_acquire) == static_cast<std::size_t>(holdAfterPublications) &&
            constBank.configuredLaneCount() == static_cast<std::size_t>(holdAfterPublications) &&
            std::string(disposition) == "incomplete-producer", diagnostic);
        std::cout << diagnostic << '\n';
        return;
    }
    require(std::string(disposition) == "coherent", diagnostic);
    require(
        bank.configuredLaneCount() == SongLaneIngressBank::kLaneCount,
        "all lazy lanes published");

    // Joining every reader satisfies releaseLane's explicit quiescence/lifetime precondition.
    for (std::size_t laneIndex = 0;
         laneIndex < SongLaneIngressBank::kLaneCount;
         ++laneIndex) {
        bank.releaseLane(laneIndex);
        require(bank.lane(laneIndex) == nullptr, "released lane is unpublished");
        require(
            bank.configuredLaneCount() == SongLaneIngressBank::kLaneCount - laneIndex - 1U,
            "published lane count decrements exactly");
    }
    require(
        bank.configureLane(3, 32'000U, 20'000U),
        "quiesced lane slot may be configured again");
    require(bank.lane(3) != nullptr, "reconfigured lane is republished");
    require(bank.lane(3)->sourceRateHz() == 32'000U, "republished lane is fully initialized");
}

void allSevenSongLanesRemainSampleAligned() {
    constexpr std::uint32_t kRate = 44'100;
    constexpr std::size_t kFrames = 4'097;
    SongLaneIngressBank bank(kFrames + 64U, kFrames);
    const auto input = stereoSignal(kFrames, kRate);
    std::vector<float> baseline;
    for (std::size_t laneIndex = 0; laneIndex < SongLaneIngressBank::kLaneCount; ++laneIndex) {
        const std::uint64_t generation = 500U + laneIndex;
        require(bank.configureLane(laneIndex, kRate, generation), "aligned lane configured");
        LaneIngress *lane = bank.lane(laneIndex);
        require(lane != nullptr, "aligned lane exists");
        std::size_t offset = 0;
        const std::size_t chunks[] = {1, 31, 7, 509, 3, 127};
        std::size_t chunkIndex = laneIndex;
        while (offset < kFrames) {
            const std::size_t requested = std::min(
                chunks[chunkIndex % std::size(chunks)],
                kFrames - offset);
            const auto offered = lane->offer(floatBlock(
                input.data() + offset * 2U,
                requested,
                kRate,
                2,
                generation,
                offset));
            require(offered.inputFramesAccepted == requested, "aligned lane offer");
            offset += requested;
            ++chunkIndex;
        }
        const auto output = drainAll(*lane, 17 + laneIndex * 11U);
        if (laneIndex == 0) {
            baseline = output;
        } else {
            require(output.size() == baseline.size(), "aligned lane frame count");
            require(
                std::memcmp(
                    output.data(),
                    baseline.data(),
                    baseline.size() * sizeof(float)) == 0,
                "all seven lanes remain byte-aligned across arbitrary JNI block sizes");
        }
    }
}

void bothSevenLaneBanksFinishIndependentlyAtExactEndpoints() {
    SongLaneIngressBank active(256, 127);
    SongLaneIngressBank candidate(256, 127);
    for (std::size_t bankIndex = 0; bankIndex < 2; ++bankIndex) {
        SongLaneIngressBank &bank = bankIndex == 0 ? active : candidate;
        for (std::size_t laneIndex = 0; laneIndex < 7; ++laneIndex) {
            const std::uint32_t rate = laneIndex % 4U == 0U
                ? 8'000U
                : (laneIndex % 4U == 1U
                    ? 44'100U
                    : (laneIndex % 4U == 2U ? 48'000U : 96'000U));
            const std::size_t frames = 1U + laneIndex * 13U + bankIndex * 7U;
            const std::uint64_t generation = 2'000U + bankIndex * 100U + laneIndex;
            require(bank.configureLane(laneIndex, rate, generation), "EOS bank lane config");
            LaneIngress *lane = bank.lane(laneIndex);
            require(lane != nullptr, "EOS bank lane exists");
            const auto input = stereoSignal(frames, rate);
            require(
                lane->offer(floatBlock(input.data(), frames, rate, 2, generation, 0))
                        .inputFramesAccepted == frames,
                "EOS bank lane offer");
            require(
                lane->markEndOfStream(generation, frames).status ==
                    LaneEndOfStreamStatus::Accepted,
                "EOS bank lane marker");
            const auto output = drainEndOfStream(*lane, 11, generation);
            require(
                output.size() == expectedFinalTimelineFrame(frames, rate) * 2U,
                "EOS bank lane exact endpoint");
        }
    }
}

void emptyGenerationAfterNonemptyStreamEndsAtZero() {
    for (const std::uint32_t rate : {48'000U, 44'100U, 96'000U}) {
        LaneIngress lane(rate, 256, 127, 4'000U);
        const auto input = stereoSignal(64U, rate);
        require(lane.offer(floatBlock(input.data(), 64U, rate, 2, 4'000U, 0U))
            .inputFramesAccepted == 64U, "old generation accepted");
        require(lane.markEndOfStream(4'000U, 64U).status == LaneEndOfStreamStatus::Accepted,
            "old generation EOS accepted");
        static_cast<void>(drainEndOfStream(lane, 17U, 4'000U));
        require(lane.advanceGeneration(4'001U), "empty generation advanced");
        require(lane.markEndOfStream(4'001U, 0U).status == LaneEndOfStreamStatus::Accepted,
            "empty generation EOS accepted");
        const auto empty = drainEndOfStream(lane, 17U, 4'001U);
        require(empty.empty(), "empty generation retained old output timeline");
    }
}

void eosMarkAndFiniteTailFlushAllocateNothingAfterWarmup() {
    LaneIngress lane(44'100, 256, 127, 3'000);
    const auto input = stereoSignal(127, 44'100);
    std::vector<float> output(lane.diagnostics().maximumOutputQuantumFrames * 2U);

    require(
        lane.offer(floatBlock(input.data(), 127, 44'100, 2, 3'000, 0))
                .inputFramesAccepted == 127,
        "EOS allocation warm offer");
    require(
        lane.markEndOfStream(3'000, 127).status == LaneEndOfStreamStatus::Accepted,
        "EOS allocation warm mark");
    std::size_t warmupGuard = 0U;
    while (lane.pull48k(output.data(), 17).status != LanePullStatus::EndOfStream) {
        require(++warmupGuard <= 1'000U, "EOS allocation warm-up terminates");
    }

    gTrackedAllocations.store(0, std::memory_order_relaxed);
    gTrackAllocations.store(true, std::memory_order_release);
    bool valid = true;
    for (std::uint64_t generation = 3'001; generation < 3'101; ++generation) {
        valid = valid && lane.advanceGeneration(generation);
        const auto offered = lane.offer(floatBlock(
            input.data(), 127, 44'100, 2, generation, 0));
        valid = valid && offered.inputFramesAccepted == 127;
        valid = valid && lane.markEndOfStream(generation, 127).status ==
            LaneEndOfStreamStatus::Accepted;
        std::size_t guard = 0;
        while (true) {
            const auto pulled = lane.pull48k(output.data(), 17);
            valid = valid && pulled.status != LanePullStatus::EndOfStreamFlushFailure;
            if (pulled.status == LanePullStatus::EndOfStream) break;
            if (++guard > 1'000) {
                valid = false;
                break;
            }
        }
    }
    gTrackAllocations.store(false, std::memory_order_release);
    require(valid, "EOS allocation pressure valid");
    require(
        gTrackedAllocations.load(std::memory_order_relaxed) == 0,
        "EOS marker and finite tail flush allocate nothing after warm-up");
}

void concurrentSpscPressurePreservesEveryFrame() {
    constexpr std::size_t kFrames = 200'000;
    constexpr std::size_t kChunk = 193;
    LaneIngress lane(48'000, 1'024, 512, 200);
    std::vector<float> input(kFrames * 2U);
    for (std::size_t index = 0; index < kFrames; ++index) {
        const float value = static_cast<float>(index % 997U) / 997.0F;
        input[index * 2U] = value;
        input[index * 2U + 1U] = -value;
    }
    std::vector<float> output(kFrames * 2U, 9.0F);
    std::atomic<bool> producerDone{false};
    std::atomic<bool> failed{false};
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(15);
    std::jthread producer([&](std::stop_token stopToken) {
        std::size_t offset = 0;
        while (offset < kFrames && !stopToken.stop_requested() &&
               std::chrono::steady_clock::now() < deadline) {
            const std::size_t requested = std::min(kChunk, kFrames - offset);
            const auto offered = lane.offer(floatBlock(
                input.data() + offset * 2U,
                requested,
                48'000,
                2,
                200,
                offset));
            if (offered.status == LaneOfferStatus::InvalidArgument ||
                offered.status == LaneOfferStatus::StaleGeneration) {
                failed.store(true, std::memory_order_relaxed);
                break;
            }
            offset += offered.inputFramesAccepted;
            if (offered.inputFramesAccepted == 0) std::this_thread::yield();
        }
        if (offset != kFrames) failed.store(true, std::memory_order_relaxed);
        producerDone.store(true, std::memory_order_release);
    });
    std::jthread consumer([&](std::stop_token stopToken) {
        std::size_t offset = 0;
        float scratch[257U * 2U]{};
        while ((!producerDone.load(std::memory_order_acquire) ||
                lane.diagnostics().readableSourceFrames > 0) &&
               !stopToken.stop_requested() &&
               std::chrono::steady_clock::now() < deadline) {
            const auto pulled = lane.pull48k(scratch, 257);
            if (pulled.status == LanePullStatus::InvalidArgument ||
                pulled.status == LanePullStatus::ResamplerFailure ||
                offset + pulled.outputFramesProduced > kFrames) {
                failed.store(true, std::memory_order_relaxed);
                break;
            }
            if (pulled.outputFramesProduced > 0) {
                std::memcpy(
                    output.data() + offset * 2U,
                    scratch,
                    pulled.outputFramesProduced * 2U * sizeof(float));
                offset += pulled.outputFramesProduced;
            } else {
                std::this_thread::yield();
            }
        }
        if (offset != kFrames) failed.store(true, std::memory_order_relaxed);
    });
    producer.join();
    consumer.join();
    require(!failed.load(std::memory_order_relaxed), "concurrent SPSC completed");
    require(
        std::memcmp(input.data(), output.data(), input.size() * sizeof(float)) == 0,
        "concurrent SPSC has no loss, duplicate, or reordering");
}

void steadyStateAndSeekResetAllocateNothing() {
    LaneIngress lane(44'100, 1'024, 256, 300);
    const auto input = stereoSignal(256, 44'100);
    std::vector<float> output(lane.diagnostics().maximumOutputQuantumFrames * 2U, 0.0F);
    require(
        lane.offer(floatBlock(input.data(), 256, 44'100, 2, 300, 0)).inputFramesAccepted == 256,
        "allocation warm offer");
    require(
        lane.pull48k(output.data(), lane.diagnostics().maximumOutputQuantumFrames).status ==
            LanePullStatus::Ok,
        "allocation warm pull");

    gTrackedAllocations.store(0, std::memory_order_relaxed);
    gTrackAllocations.store(true, std::memory_order_release);
    bool valid = true;
    std::uint64_t generation = 300;
    std::uint64_t firstFrame = 256;
    for (std::size_t cycle = 0; cycle < 2'000; ++cycle) {
        if (cycle % 200U == 0U) {
            ++generation;
            valid = valid && lane.advanceGeneration(generation);
            firstFrame = 0;
        }
        const auto offered = lane.offer(floatBlock(
            input.data(),
            256,
            44'100,
            2,
            generation,
            firstFrame));
        valid = valid && offered.inputFramesAccepted == 256;
        const auto pulled = lane.pull48k(
            output.data(),
            lane.diagnostics().maximumOutputQuantumFrames);
        valid = valid && pulled.status == LanePullStatus::Ok;
        firstFrame += 256;
    }
    gTrackAllocations.store(false, std::memory_order_release);
    require(valid, "allocation pressure operations valid");
    require(
        gTrackedAllocations.load(std::memory_order_relaxed) == 0,
        "zero allocation after warm-up including deterministic seek resets");
}

void invalidInputsAreRejectedWithoutMutation() {
    LaneIngress lane(48'000, 64, 32, 1);
    float sample[2]{};
    require(
        lane.offer(floatBlock(sample, 1, 44'100, 2, 1, 0)).status ==
            LaneOfferStatus::InvalidArgument,
        "wrong source rate rejected");
    LaneInputBlock invalid = floatBlock(sample, 1, 48'000, 2, 1, 0);
    invalid.format.channelCount = 3;
    require(lane.offer(invalid).status == LaneOfferStatus::InvalidArgument, "channels rejected");
    invalid = floatBlock(sample, 1, 48'000, 2, 0, 0);
    require(lane.offer(invalid).status == LaneOfferStatus::InvalidArgument, "zero gen rejected");
    require(lane.pull48k(nullptr, 1).status == LanePullStatus::InvalidArgument, "null pull");
    require(!lane.advanceGeneration(0), "zero generation advance rejected");
    require(lane.advanceGeneration(1), "same generation activation is idempotent");
    require(!lane.markDiscontinuity(2), "wrong generation mark rejected");
    require(lane.diagnostics().readableSourceFrames == 0, "invalid offers mutate nothing");
}

}  // namespace

int main() {
    try {
        formatMatrixNormalizesToStereoFloat();
        ratesAndArbitraryChunkingAreDeterministic();
        nonzeroPhaseAlignedHistoryMatchesTheZeroOriginSamples();
        sourcePtsMapsToExactRational48kTimeline();
        eosFlushHasExactFiniteLengthAcrossRatesAndShortStreams();
        eosRequiresTheFullyAcceptedExclusiveBoundary();
        eosAtANonZeroSeekPositionUsesAbsoluteTimelineBoundaries();
        eosAfterPartialQueueBackpressureStillEndsExactlyOnce();
        seekInvalidatesPublishedAndLateStaleEos();
        partialCapacityRetryNeverDuplicates();
        gapsOverlapsAndExplicitDiscontinuitiesAreUnambiguous();
        staleGenerationAndSeekFlushAreDeterministic();
        queuedOldGenerationCannotBlockOrLeakAfterFlush();
        expectedGenerationGatePreservesFutureHead();
        gatedCurrentOwnerDrainsItsStalePrefixSoFutureIngressCanPrime();
        explicitBoundarySurvivesACompletelyFullQueue();
        twoSevenLaneBanksSurvivePressure();
        concurrentLazyLanePublicationIsRaceFree();
        concurrentLazyLanePublicationIsRaceFree(0);
        concurrentLazyLanePublicationIsRaceFree(2);
        allSevenSongLanesRemainSampleAligned();
        bothSevenLaneBanksFinishIndependentlyAtExactEndpoints();
        concurrentSpscPressurePreservesEveryFrame();
        steadyStateAndSeekResetAllocateNothing();
        emptyGenerationAfterNonemptyStreamEndsAtZero();
        eosMarkAndFiniteTailFlushAllocateNothingAfterWarmup();
        invalidInputsAreRejectedWithoutMutation();
        std::cout << "LaneIngress deterministic/pressure tests passed\n";
        return 0;
    } catch (const std::exception &error) {
        gTrackAllocations.store(false, std::memory_order_release);
        std::cerr << "LaneIngress test failed: " << error.what() << '\n';
        return 1;
    }
}
