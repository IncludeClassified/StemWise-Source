#include "LaneIngress.h"

#include "../dsp/SampleRateConverter.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstring>
#include <limits>
#include <mutex>
#include <stdexcept>
#include <vector>

namespace stemwise::audio::engine {
namespace {

constexpr std::uint64_t kMicrosPerSecond = 1'000'000U;
constexpr std::uint64_t kNoCursor = std::numeric_limits<std::uint64_t>::max();

void saturatingAdd(std::atomic<std::uint64_t> &counter, std::uint64_t amount) noexcept {
    std::uint64_t previous = counter.load(std::memory_order_relaxed);
    while (true) {
        const std::uint64_t maximum = std::numeric_limits<std::uint64_t>::max();
        const std::uint64_t updated = amount > maximum - previous ? maximum : previous + amount;
        if (counter.compare_exchange_weak(
                previous,
                updated,
                std::memory_order_relaxed,
                std::memory_order_relaxed)) {
            return;
        }
    }
}

void saturatingIncrement(std::atomic<std::uint64_t> &counter) noexcept {
    saturatingAdd(counter, 1U);
}

void updateMaximum(std::atomic<std::size_t> &target, std::size_t candidate) noexcept {
    std::size_t previous = target.load(std::memory_order_relaxed);
    while (candidate > previous && !target.compare_exchange_weak(
        previous,
        candidate,
        std::memory_order_relaxed,
        std::memory_order_relaxed)) {}
}

std::uint64_t saturatingMultiplyAdd(
    std::uint64_t left,
    std::uint64_t multiplier,
    std::uint64_t addend) noexcept {
    const std::uint64_t maximum = std::numeric_limits<std::uint64_t>::max();
    if (left > (maximum - addend) / multiplier) return maximum;
    return left * multiplier + addend;
}

std::uint64_t presentationUsToSourceFrame(
    std::uint64_t presentationTimeUs,
    std::uint32_t sampleRateHz) noexcept {
    const std::uint64_t wholeSeconds = presentationTimeUs / kMicrosPerSecond;
    const std::uint64_t remainderUs = presentationTimeUs % kMicrosPerSecond;
    const std::uint64_t roundedRemainderFrames =
        (remainderUs * sampleRateHz + kMicrosPerSecond / 2U) / kMicrosPerSecond;
    return saturatingMultiplyAdd(wholeSeconds, sampleRateHz, roundedRemainderFrames);
}

std::uint64_t sourceFrameTo48kTimeline(
    std::uint64_t sourceFrame,
    std::uint32_t sourceRateHz) noexcept {
    const std::uint64_t wholeSeconds = sourceFrame / sourceRateHz;
    const std::uint64_t remainderFrames = sourceFrame % sourceRateHz;
    const std::uint64_t outputRemainder =
        (remainderFrames * LaneIngress::kEngineSampleRateHz) / sourceRateHz;
    return saturatingMultiplyAdd(
        wholeSeconds,
        LaneIngress::kEngineSampleRateHz,
        outputRemainder);
}

std::uint64_t sourceBoundaryTo48kTimelineCeil(
    std::uint64_t sourceFrameExclusive,
    std::uint32_t sourceRateHz) noexcept {
    const std::uint64_t wholeSeconds = sourceFrameExclusive / sourceRateHz;
    const std::uint64_t remainderFrames = sourceFrameExclusive % sourceRateHz;
    const std::uint64_t scaledRemainder =
        remainderFrames * LaneIngress::kEngineSampleRateHz;
    const std::uint64_t outputRemainder =
        (scaledRemainder + sourceRateHz - 1U) / sourceRateHz;
    return saturatingMultiplyAdd(
        wholeSeconds,
        LaneIngress::kEngineSampleRateHz,
        outputRemainder);
}

std::uint64_t saturatingFrameAdd(std::uint64_t frame, std::size_t count) noexcept {
    const auto amount = static_cast<std::uint64_t>(count);
    const std::uint64_t maximum = std::numeric_limits<std::uint64_t>::max();
    return amount > maximum - frame ? maximum : frame + amount;
}

std::size_t bytesPerSample(LanePcmEncoding encoding) noexcept {
    switch (encoding) {
        case LanePcmEncoding::Signed16:
            return sizeof(std::int16_t);
        case LanePcmEncoding::Float32:
            return sizeof(float);
    }
    return 0;
}

struct alignas(64) RingCursor final {
    std::atomic<std::uint64_t> value{0};
};

struct SourceFrameSlot final {
    float left{0.0F};
    float right{0.0F};
    std::uint64_t generation{0};
    std::uint64_t sourceFrame{0};
    LaneDiscontinuity discontinuity{LaneDiscontinuity::None};
};

struct EndOfStreamSnapshot final {
    bool valid{false};
    std::uint64_t generation{0};
    std::uint64_t finalSourceFrameExclusive{0};
    std::uint64_t finalTimelineFrame48kExclusive{0};
};

}  // namespace

class LaneIngress::Impl final {
public:
    Impl(
        std::uint32_t configuredSourceRateHz,
        std::size_t configuredQueueCapacityFrames,
        std::size_t configuredMaximumInputQuantumFrames,
        std::uint64_t configuredInitialGeneration)
        : sourceRateHz(configuredSourceRateHz),
          queueCapacityFrames(configuredQueueCapacityFrames),
          maximumInputQuantumFrames(configuredMaximumInputQuantumFrames),
          queue(checkedQueueCapacity(configuredQueueCapacityFrames)),
          converter(
              checkedSourceRate(configuredSourceRateHz),
              LaneIngress::kEngineSampleRateHz,
              checkedInputQuantum(
                  configuredMaximumInputQuantumFrames,
                  configuredQueueCapacityFrames)),
          inputScratch(configuredMaximumInputQuantumFrames * LaneIngress::kChannelCount, 0.0F),
          zeroScratch(configuredMaximumInputQuantumFrames * LaneIngress::kChannelCount, 0.0F),
          activeGeneration(checkedGeneration(configuredInitialGeneration)) {
        const SampleRateDiagnostics diagnostics = converter.diagnostics();
        maximumOutputQuantumFrames = diagnostics.maximumOutputFrames;
        const std::size_t latency = diagnostics.filterInputLatencyFrames;
        maximumEndOfStreamFlushInputFrames = latency >
                (std::numeric_limits<std::size_t>::max() - 256U) / 2U
            ? std::numeric_limits<std::size_t>::max()
            : latency * 2U + 256U;
    }

    static std::uint32_t checkedSourceRate(std::uint32_t rate) {
        if (rate < SampleRateConverter::kMinimumRateHz ||
            rate > SampleRateConverter::kMaximumRateHz) {
            throw std::invalid_argument("LaneIngress source rate is unsupported");
        }
        return rate;
    }

    static std::size_t checkedQueueCapacity(std::size_t capacity) {
        if (capacity < LaneIngress::kMinimumQueueCapacityFrames ||
            capacity > LaneIngress::kMaximumQueueCapacityFrames) {
            throw std::invalid_argument("LaneIngress queue capacity is invalid");
        }
        return capacity;
    }

    static std::size_t checkedInputQuantum(std::size_t quantum, std::size_t capacity) {
        if (quantum == 0 || quantum > capacity) {
            throw std::invalid_argument("LaneIngress input quantum is invalid");
        }
        return quantum;
    }

    static std::uint64_t checkedGeneration(std::uint64_t generation) {
        if (generation == 0) {
            throw std::invalid_argument("LaneIngress generation must be positive");
        }
        return generation;
    }

    [[nodiscard]] std::size_t readableFrames() const noexcept {
        const std::uint64_t write = writeCursor.value.load(std::memory_order_acquire);
        const std::uint64_t read = readCursor.value.load(std::memory_order_acquire);
        if (write < read) return 0;
        const std::uint64_t difference = write - read;
        return static_cast<std::size_t>(std::min<std::uint64_t>(
            difference,
            queueCapacityFrames));
    }

    [[nodiscard]] bool promoteGeneration(std::uint64_t generation) noexcept {
        if (generation == 0) return false;
        std::uint64_t active = activeGeneration.load(std::memory_order_acquire);
        while (generation > active) {
            if (activeGeneration.compare_exchange_weak(
                    active,
                    generation,
                    std::memory_order_acq_rel,
                    std::memory_order_acquire)) {
                saturatingIncrement(generationAdvances);
                return true;
            }
        }
        return generation == active;
    }

    [[nodiscard]] float readFloatSample(const std::byte *address) noexcept {
        float sample = 0.0F;
        std::memcpy(&sample, address, sizeof(sample));
        if (!std::isfinite(sample)) {
            saturatingIncrement(nonFiniteFloatSamples);
            return 0.0F;
        }
        return sample;
    }

    [[nodiscard]] static float readSigned16Sample(const std::byte *address) noexcept {
        std::int16_t sample = 0;
        std::memcpy(&sample, address, sizeof(sample));
        return static_cast<float>(sample) / 32768.0F;
    }

    void readNormalizedFrame(
        const LaneInputBlock &block,
        std::size_t inputFrameIndex,
        float &left,
        float &right) noexcept {
        const std::size_t sampleBytes = bytesPerSample(block.format.encoding);
        const std::size_t frameBytes = sampleBytes * block.format.channelCount;
        const auto *frameAddress = static_cast<const std::byte *>(block.data) +
            inputFrameIndex * frameBytes;
        if (block.format.encoding == LanePcmEncoding::Signed16) {
            left = readSigned16Sample(frameAddress);
            right = block.format.channelCount == 1
                ? left
                : readSigned16Sample(frameAddress + sampleBytes);
            return;
        }
        left = readFloatSample(frameAddress);
        right = block.format.channelCount == 1
            ? left
            : readFloatSample(frameAddress + sampleBytes);
    }

    [[nodiscard]] LaneOfferResult offer(const LaneInputBlock &block) noexcept {
        saturatingIncrement(offerCalls);
        const std::size_t sampleBytes = bytesPerSample(block.format.encoding);
        if (block.data == nullptr || block.frameCount == 0 || block.generation == 0 ||
            block.format.sampleRateHz != sourceRateHz ||
            (block.format.channelCount != 1 && block.format.channelCount != 2) ||
            sampleBytes == 0 ||
            block.frameCount > std::numeric_limits<std::size_t>::max() /
                (sampleBytes * block.format.channelCount)) {
            return {LaneOfferStatus::InvalidArgument};
        }

        if (!promoteGeneration(block.generation)) {
            saturatingAdd(staleFramesDiscarded, block.frameCount);
            saturatingAdd(acceptedInputFrames, block.frameCount);
            return {
                LaneOfferStatus::StaleGeneration,
                block.frameCount,
                0,
                block.frameCount,
                presentationUsToSourceFrame(block.presentationTimeUs, sourceRateHz),
            };
        }

        // A control thread may advance ownership after the check above. Publishing the old tag is
        // still safe: the consumer discards it. It is intentionally not relabelled as newer audio.
        const std::uint64_t firstSourceFrame =
            presentationUsToSourceFrame(block.presentationTimeUs, sourceRateHz);
        if (producerGeneration != block.generation) {
            producerGeneration = block.generation;
            publishedProducerGeneration.store(block.generation, std::memory_order_release);
            producerHasExpectedFrame = false;
            producerPendingDiscontinuity |= LaneDiscontinuity::Generation;
            producerEndOfStreamMarked = false;
            producerFinalSourceFrameExclusive = 0;
        } else if (producerEndOfStreamMarked) {
            return {
                LaneOfferStatus::AfterEndOfStream,
                0,
                0,
                0,
                presentationUsToSourceFrame(block.presentationTimeUs, sourceRateHz),
            };
        }

        std::uint64_t pendingExplicit =
            explicitDiscontinuityGeneration.load(std::memory_order_acquire);
        if (pendingExplicit == block.generation &&
            explicitDiscontinuityGeneration.compare_exchange_strong(
                pendingExplicit,
                0,
                std::memory_order_acq_rel,
                std::memory_order_acquire)) {
            producerHasExpectedFrame = false;
            producerPendingDiscontinuity |= LaneDiscontinuity::Explicit;
        }

        LaneDiscontinuity firstQueuedDiscontinuity = producerPendingDiscontinuity;

        std::size_t overlapFrames = 0;
        if (producerHasExpectedFrame && firstSourceFrame < producerExpectedSourceFrame) {
            const std::uint64_t overlapDistance = producerExpectedSourceFrame - firstSourceFrame;
            overlapFrames = static_cast<std::size_t>(std::min<std::uint64_t>(
                overlapDistance,
                block.frameCount));
            saturatingAdd(overlapFramesDiscarded, overlapFrames);
        }

        if (overlapFrames == block.frameCount) {
            saturatingAdd(acceptedInputFrames, block.frameCount);
            return {
                LaneOfferStatus::Accepted,
                block.frameCount,
                0,
                block.frameCount,
                firstSourceFrame,
            };
        }

        const std::uint64_t queuedFirstSourceFrame =
            saturatingFrameAdd(firstSourceFrame, overlapFrames);
        if (producerHasExpectedFrame && queuedFirstSourceFrame > producerExpectedSourceFrame) {
            firstQueuedDiscontinuity |= LaneDiscontinuity::TimestampGap;
        }

        const std::uint64_t write = writeCursor.value.load(std::memory_order_relaxed);
        const std::uint64_t read = readCursor.value.load(std::memory_order_acquire);
        const std::uint64_t occupied = write >= read ? write - read : queueCapacityFrames;
        const std::size_t available = occupied >= queueCapacityFrames
            ? 0
            : queueCapacityFrames - static_cast<std::size_t>(occupied);
        const std::size_t remainingAfterOverlap = block.frameCount - overlapFrames;
        const std::size_t queuedFrames = std::min(remainingAfterOverlap, available);

        for (std::size_t index = 0; index < queuedFrames; ++index) {
            SourceFrameSlot &slot = queue[static_cast<std::size_t>(
                (write + index) % queueCapacityFrames)];
            readNormalizedFrame(block, overlapFrames + index, slot.left, slot.right);
            slot.generation = block.generation;
            slot.sourceFrame = saturatingFrameAdd(queuedFirstSourceFrame, index);
            slot.discontinuity = index == 0
                ? firstQueuedDiscontinuity
                : LaneDiscontinuity::None;
        }
        if (queuedFrames > 0) {
            writeCursor.value.store(write + queuedFrames, std::memory_order_release);
            if (hasDiscontinuity(
                    firstQueuedDiscontinuity,
                    LaneDiscontinuity::TimestampGap)) {
                saturatingIncrement(timestampGapEvents);
            }
            producerPendingDiscontinuity = LaneDiscontinuity::None;
            producerHasExpectedFrame = true;
            producerExpectedSourceFrame =
                saturatingFrameAdd(queuedFirstSourceFrame, queuedFrames);
            saturatingAdd(queuedSourceFrames, queuedFrames);
            updateMaximum(highWaterSourceFrames, static_cast<std::size_t>(occupied) + queuedFrames);
        }

        const std::size_t accepted = overlapFrames + queuedFrames;
        saturatingAdd(acceptedInputFrames, accepted);
        if (accepted == block.frameCount) {
            return {
                LaneOfferStatus::Accepted,
                accepted,
                queuedFrames,
                overlapFrames,
                firstSourceFrame,
            };
        }
        if (accepted == 0) {
            saturatingIncrement(backpressureEvents);
            return {
                LaneOfferStatus::Backpressured,
                0,
                0,
                0,
                firstSourceFrame,
            };
        }
        saturatingIncrement(partialOfferEvents);
        return {
            LaneOfferStatus::Partial,
            accepted,
            queuedFrames,
            overlapFrames,
            firstSourceFrame,
        };
    }

    [[nodiscard]] LaneEndOfStreamResult markEndOfStream(
        std::uint64_t generation,
        std::uint64_t finalSourceFrameExclusive) noexcept {
        saturatingIncrement(endOfStreamMarkCalls);
        if (generation == 0) {
            saturatingIncrement(rejectedEndOfStreamMarks);
            return {LaneEndOfStreamStatus::InvalidArgument};
        }
        const std::uint64_t active = activeGeneration.load(std::memory_order_acquire);
        if (generation != active) {
            saturatingIncrement(staleEndOfStreamMarks);
            return {
                LaneEndOfStreamStatus::StaleGeneration,
                generation,
                finalSourceFrameExclusive,
                sourceBoundaryTo48kTimelineCeil(finalSourceFrameExclusive, sourceRateHz),
            };
        }

        // An empty decoder generation may publish EOS without ever calling offer(). This mutation
        // remains producer-owned because markEndOfStream() shares offer()'s serialized lane.
        if (producerGeneration != generation) {
            if (producerGeneration > generation) {
                saturatingIncrement(staleEndOfStreamMarks);
                return {
                    LaneEndOfStreamStatus::StaleGeneration,
                    generation,
                    finalSourceFrameExclusive,
                    sourceBoundaryTo48kTimelineCeil(finalSourceFrameExclusive, sourceRateHz),
                };
            }
            producerGeneration = generation;
            publishedProducerGeneration.store(generation, std::memory_order_release);
            producerHasExpectedFrame = false;
            producerPendingDiscontinuity |= LaneDiscontinuity::Generation;
            producerEndOfStreamMarked = false;
            producerFinalSourceFrameExclusive = 0;
        }

        const std::uint64_t finalTimelineFrame48kExclusive =
            sourceBoundaryTo48kTimelineCeil(finalSourceFrameExclusive, sourceRateHz);
        if (producerEndOfStreamMarked) {
            if (producerFinalSourceFrameExclusive == finalSourceFrameExclusive) {
                return {
                    LaneEndOfStreamStatus::AlreadyMarked,
                    generation,
                    finalSourceFrameExclusive,
                    finalTimelineFrame48kExclusive,
                };
            }
            saturatingIncrement(rejectedEndOfStreamMarks);
            return {
                LaneEndOfStreamStatus::BoundaryMismatch,
                generation,
                finalSourceFrameExclusive,
                finalTimelineFrame48kExclusive,
            };
        }

        const bool boundaryMatches = producerHasExpectedFrame
            ? finalSourceFrameExclusive == producerExpectedSourceFrame
            : finalSourceFrameExclusive == 0;
        if (!boundaryMatches) {
            saturatingIncrement(rejectedEndOfStreamMarks);
            return {
                LaneEndOfStreamStatus::BoundaryMismatch,
                generation,
                finalSourceFrameExclusive,
                finalTimelineFrame48kExclusive,
            };
        }

        std::uint64_t sequence = endOfStreamPublishSequence.load(std::memory_order_relaxed);
        if (sequence > std::numeric_limits<std::uint64_t>::max() - 2U) {
            saturatingIncrement(rejectedEndOfStreamMarks);
            return {
                LaneEndOfStreamStatus::InvalidArgument,
                generation,
                finalSourceFrameExclusive,
                finalTimelineFrame48kExclusive,
            };
        }
        endOfStreamPublishSequence.store(sequence + 1U, std::memory_order_relaxed);
        std::atomic_thread_fence(std::memory_order_release);
        publishedEndOfStreamGeneration.store(generation, std::memory_order_relaxed);
        publishedFinalSourceFrameExclusive.store(
            finalSourceFrameExclusive,
            std::memory_order_relaxed);
        publishedFinalTimelineFrame48kExclusive.store(
            finalTimelineFrame48kExclusive,
            std::memory_order_relaxed);
        endOfStreamPublishSequence.store(sequence + 2U, std::memory_order_release);

        producerEndOfStreamMarked = true;
        producerFinalSourceFrameExclusive = finalSourceFrameExclusive;
        saturatingIncrement(acceptedEndOfStreamMarks);
        return {
            LaneEndOfStreamStatus::Accepted,
            generation,
            finalSourceFrameExclusive,
            finalTimelineFrame48kExclusive,
        };
    }

    [[nodiscard]] EndOfStreamSnapshot readEndOfStreamSnapshot() const noexcept {
        // Atomics make every read race-free; the even sequence provides a coherent three-field
        // snapshot. A concurrent producer publication simply defers observation to the next pull.
        for (int attempt = 0; attempt < 4; ++attempt) {
            const std::uint64_t before =
                endOfStreamPublishSequence.load(std::memory_order_acquire);
            if ((before & 1U) != 0U) continue;
            const std::uint64_t generation =
                publishedEndOfStreamGeneration.load(std::memory_order_relaxed);
            const std::uint64_t finalSource =
                publishedFinalSourceFrameExclusive.load(std::memory_order_relaxed);
            const std::uint64_t finalTimeline =
                publishedFinalTimelineFrame48kExclusive.load(std::memory_order_relaxed);
            std::atomic_thread_fence(std::memory_order_acquire);
            const std::uint64_t after =
                endOfStreamPublishSequence.load(std::memory_order_acquire);
            if (before == after && (after & 1U) == 0U) {
                return {generation != 0, generation, finalSource, finalTimeline};
            }
        }
        return {};
    }

    void refreshConsumerEndOfStream() noexcept {
        const EndOfStreamSnapshot snapshot = readEndOfStreamSnapshot();
        if (!snapshot.valid || snapshot.generation != consumerGeneration ||
            consumerEndOfStreamObserved) {
            return;
        }
        consumerEndOfStreamObserved = true;
        consumerEndOfStreamCompleted = false;
        consumerFinalSourceFrameExclusive = snapshot.finalSourceFrameExclusive;
        consumerFinalTimelineFrame48kExclusive = snapshot.finalTimelineFrame48kExclusive;
        consumerEndOfStreamFlushInputFramesRemaining = maximumEndOfStreamFlushInputFrames;
    }

    void synchronizeConsumerGenerationTo(std::uint64_t generation) noexcept {
        if (consumerGeneration == generation) return;
        converter.onSeek();
        consumerGeneration = generation;
        publishedConsumerGeneration.store(generation, std::memory_order_release);
        consumerHasTimeline = false;
        nextOutputTimelineFrame = 0U;
        pendingFirstSourceFrame = 0U;
        consumerExpectedSourceFrame = 0U;
        consumerHasExpectedSourceFrame = false;
        boundaryAppliedCursor = kNoCursor;
        pendingOutputDiscontinuity = LaneDiscontinuity::Generation;
        consumerEndOfStreamObserved = false;
        consumerEndOfStreamCompleted = false;
        consumerFinalSourceFrameExclusive = 0;
        consumerFinalTimelineFrame48kExclusive = 0;
        consumerEndOfStreamFlushInputFramesRemaining = 0;
    }

    void synchronizeConsumerGeneration() noexcept {
        synchronizeConsumerGenerationTo(
            activeGeneration.load(std::memory_order_acquire));
    }

    [[nodiscard]] LanePullResult pullEndOfStream(
        float *output,
        std::size_t outputCapacityFrames,
        std::size_t staleDiscardedThisCall) noexcept {
        if (!consumerEndOfStreamObserved) {
            saturatingIncrement(emptyPulls);
            return {
                LanePullStatus::Empty,
                0,
                0,
                staleDiscardedThisCall,
                consumerGeneration,
            };
        }
        if (consumerEndOfStreamCompleted) {
            return {
                LanePullStatus::EndOfStream,
                0,
                0,
                staleDiscardedThisCall,
                consumerGeneration,
                consumerFinalSourceFrameExclusive,
                consumerFinalTimelineFrame48kExclusive,
                pendingOutputDiscontinuity,
            };
        }
        if (consumerHasExpectedSourceFrame &&
            consumerExpectedSourceFrame != consumerFinalSourceFrameExclusive) {
            saturatingIncrement(endOfStreamFlushFailures);
            return {
                LanePullStatus::EndOfStreamFlushFailure,
                0,
                0,
                staleDiscardedThisCall,
                consumerGeneration,
                consumerExpectedSourceFrame,
                nextOutputTimelineFrame,
                pendingOutputDiscontinuity,
            };
        }
        if (!consumerHasExpectedSourceFrame && consumerFinalSourceFrameExclusive != 0) {
            saturatingIncrement(endOfStreamFlushFailures);
            return {
                LanePullStatus::EndOfStreamFlushFailure,
                0,
                0,
                staleDiscardedThisCall,
                consumerGeneration,
                consumerFinalSourceFrameExclusive,
                nextOutputTimelineFrame,
                pendingOutputDiscontinuity,
            };
        }
        if (nextOutputTimelineFrame > consumerFinalTimelineFrame48kExclusive) {
            saturatingIncrement(endOfStreamFlushFailures);
            return {
                LanePullStatus::EndOfStreamFlushFailure,
                0,
                0,
                staleDiscardedThisCall,
                consumerGeneration,
                consumerFinalSourceFrameExclusive,
                nextOutputTimelineFrame,
                pendingOutputDiscontinuity,
            };
        }

        const std::uint64_t remainingOutputFrames =
            consumerFinalTimelineFrame48kExclusive - nextOutputTimelineFrame;
        if (remainingOutputFrames == 0) {
            consumerEndOfStreamCompleted = true;
            saturatingIncrement(completedEndOfStreams);
            return {
                LanePullStatus::EndOfStream,
                0,
                0,
                staleDiscardedThisCall,
                consumerGeneration,
                consumerFinalSourceFrameExclusive,
                nextOutputTimelineFrame,
                pendingOutputDiscontinuity,
            };
        }
        if (consumerEndOfStreamFlushInputFramesRemaining == 0) {
            saturatingIncrement(endOfStreamFlushFailures);
            return {
                LanePullStatus::EndOfStreamFlushFailure,
                0,
                0,
                staleDiscardedThisCall,
                consumerGeneration,
                consumerFinalSourceFrameExclusive,
                nextOutputTimelineFrame,
                pendingOutputDiscontinuity,
            };
        }

        const std::uint64_t firstTimelineFrame = nextOutputTimelineFrame;
        while (consumerEndOfStreamFlushInputFramesRemaining > 0) {
            const std::size_t flushInputFrames = std::min(
                maximumInputQuantumFrames,
                consumerEndOfStreamFlushInputFramesRemaining);
            const std::size_t boundedOutputCapacity = static_cast<std::size_t>(
                std::min<std::uint64_t>(
                    consumerFinalTimelineFrame48kExclusive - nextOutputTimelineFrame,
                    outputCapacityFrames));
            saturatingIncrement(endOfStreamFlushCalls);
            const SampleRateProcessResult converted = converter.process(
                zeroScratch.data(),
                flushInputFrames,
                output,
                boundedOutputCapacity);
            if (converted.status == SampleRateProcessStatus::InvalidBuffer ||
                converted.status == SampleRateProcessStatus::InputQuantumExceeded ||
                converted.status == SampleRateProcessStatus::OutputQuantumExceeded ||
                converted.status == SampleRateProcessStatus::BackendFailure ||
                (converted.inputFramesConsumed == 0 && converted.outputFramesProduced == 0)) {
                saturatingIncrement(endOfStreamFlushFailures);
                return {
                    converted.status == SampleRateProcessStatus::BackendFailure
                        ? LanePullStatus::ResamplerFailure
                        : LanePullStatus::EndOfStreamFlushFailure,
                    0,
                    0,
                    staleDiscardedThisCall,
                    consumerGeneration,
                    consumerFinalSourceFrameExclusive,
                    firstTimelineFrame,
                    pendingOutputDiscontinuity,
                };
            }

            consumerEndOfStreamFlushInputFramesRemaining -= converted.inputFramesConsumed;
            saturatingAdd(endOfStreamFlushInputFrames, converted.inputFramesConsumed);
            if (converted.outputFramesProduced == 0) continue;

            nextOutputTimelineFrame = saturatingFrameAdd(
                nextOutputTimelineFrame,
                converted.outputFramesProduced);
            saturatingAdd(endOfStreamTailOutputFrames, converted.outputFramesProduced);
            const LaneDiscontinuity reportedDiscontinuity = pendingOutputDiscontinuity;
            pendingOutputDiscontinuity = LaneDiscontinuity::None;
            const bool completed =
                nextOutputTimelineFrame == consumerFinalTimelineFrame48kExclusive;
            if (completed) {
                consumerEndOfStreamCompleted = true;
                saturatingIncrement(completedEndOfStreams);
            }
            return {
                completed ? LanePullStatus::EndOfStream :
                    (converted.status == SampleRateProcessStatus::OutputCapacityExhausted
                        ? LanePullStatus::OutputCapacityExhausted
                        : LanePullStatus::Ok),
                0,
                converted.outputFramesProduced,
                staleDiscardedThisCall,
                consumerGeneration,
                consumerFinalSourceFrameExclusive,
                firstTimelineFrame,
                reportedDiscontinuity,
            };
        }
        saturatingIncrement(endOfStreamFlushFailures);
        return {
            LanePullStatus::EndOfStreamFlushFailure,
            0,
            0,
            staleDiscardedThisCall,
            consumerGeneration,
            consumerFinalSourceFrameExclusive,
            firstTimelineFrame,
            pendingOutputDiscontinuity,
        };
    }

    [[nodiscard]] LanePullResult pull48k(
        std::uint64_t expectedGeneration,
        float *output,
        std::size_t outputCapacityFrames) noexcept {
        saturatingIncrement(pullCalls);
        if (output == nullptr || outputCapacityFrames == 0 ||
            outputCapacityFrames > maximumOutputQuantumFrames) {
            return {LanePullStatus::InvalidArgument};
        }

        const std::uint64_t activeAtEntry =
            activeGeneration.load(std::memory_order_acquire);
        if (expectedGeneration != 0U && activeAtEntry < expectedGeneration) {
            return {
                LanePullStatus::StaleGeneration,
                0,
                0,
                0,
                activeAtEntry,
            };
        }

        // A future decoder epoch may be authorized while the current render owner is still
        // draining. Do not reject that current owner at entry: it must advance the consumer cursor
        // across its now-stale queued prefix (the post-SRC generation fence below publishes no old
        // audio) so the bounded SPSC ring has room for the future epoch. Once the first future slot
        // reaches the head, the loop below parks it byte-for-byte until expectedGeneration catches
        // up. Rejecting whenever activeAtEntry was merely newer left a full old queue permanently
        // wedged: the producer could not enqueue the requested loop body and the consumer could not
        // free the obsolete prefix.

        if (expectedGeneration == 0U) {
            synchronizeConsumerGeneration();
        } else {
            synchronizeConsumerGenerationTo(expectedGeneration);
        }
        refreshConsumerEndOfStream();
        std::size_t staleDiscardedThisCall = 0;
        std::uint64_t read = readCursor.value.load(std::memory_order_relaxed);
        while (true) {
            const std::uint64_t write = writeCursor.value.load(std::memory_order_acquire);
            while (read < write) {
                const SourceFrameSlot &slot =
                    queue[static_cast<std::size_t>(read % queueCapacityFrames)];
                if (slot.generation == consumerGeneration) break;
                if (slot.generation > consumerGeneration) {
                    if (expectedGeneration != 0U) {
                        // read points at the first future-generation slot. Publish only discarded
                        // older-prefix progress; leave this head and the SRC untouched for the
                        // command that assigns expectedGeneration=slot.generation.
                        readCursor.value.store(read, std::memory_order_release);
                        if (staleDiscardedThisCall > 0) {
                            saturatingAdd(staleFramesDiscarded, staleDiscardedThisCall);
                        }
                        return {
                            LanePullStatus::StaleGeneration,
                            0,
                            0,
                            staleDiscardedThisCall,
                            slot.generation,
                        };
                    }
                    synchronizeConsumerGeneration();
                    continue;
                }
                ++read;
                ++staleDiscardedThisCall;
            }
            readCursor.value.store(read, std::memory_order_release);
            if (read == write) {
                if (staleDiscardedThisCall > 0) {
                    saturatingAdd(staleFramesDiscarded, staleDiscardedThisCall);
                }
                refreshConsumerEndOfStream();
                return pullEndOfStream(
                    output,
                    outputCapacityFrames,
                    staleDiscardedThisCall);
            }
            const SourceFrameSlot &first =
                queue[static_cast<std::size_t>(read % queueCapacityFrames)];
            if (first.generation != consumerGeneration) continue;
            // Copy descriptor fields before releasing any consumed cursor. Once readCursor moves,
            // the producer is allowed to overwrite this physical slot immediately.
            const std::uint64_t firstSourceFrame = first.sourceFrame;
            const LaneDiscontinuity firstDiscontinuity = first.discontinuity;

            LaneDiscontinuity boundary = firstDiscontinuity;
            if (consumerHasExpectedSourceFrame &&
                firstSourceFrame != consumerExpectedSourceFrame) {
                boundary |= LaneDiscontinuity::TimestampGap;
            }
            if (boundaryAppliedCursor != read) {
                if (hasDiscontinuity(boundary, LaneDiscontinuity::TimestampGap) ||
                    hasDiscontinuity(boundary, LaneDiscontinuity::Explicit)) {
                    converter.onDiscontinuity();
                }
                if (!consumerHasTimeline || boundary != LaneDiscontinuity::None) {
                    nextOutputTimelineFrame =
                        sourceFrameTo48kTimeline(firstSourceFrame, sourceRateHz);
                    consumerHasTimeline = true;
                }
                pendingOutputDiscontinuity |= boundary;
                pendingFirstSourceFrame = firstSourceFrame;
                boundaryAppliedCursor = read;
            }

            const std::size_t available = static_cast<std::size_t>(write - read);
            const std::size_t gatherLimit = std::min(available, maximumInputQuantumFrames);
            std::size_t gathered = 0;
            for (; gathered < gatherLimit; ++gathered) {
                const SourceFrameSlot &slot = queue[static_cast<std::size_t>(
                    (read + gathered) % queueCapacityFrames)];
                if (slot.generation != consumerGeneration) break;
                if (gathered > 0 &&
                    (slot.discontinuity != LaneDiscontinuity::None ||
                     slot.sourceFrame != saturatingFrameAdd(firstSourceFrame, gathered))) {
                    break;
                }
                inputScratch[gathered * LaneIngress::kChannelCount] = slot.left;
                inputScratch[gathered * LaneIngress::kChannelCount + 1U] = slot.right;
            }

            const std::uint64_t firstTimelineFrame = nextOutputTimelineFrame;
            const SampleRateProcessResult converted = converter.process(
                inputScratch.data(),
                gathered,
                output,
                outputCapacityFrames);
            if (converted.status == SampleRateProcessStatus::InvalidBuffer ||
                converted.status == SampleRateProcessStatus::InputQuantumExceeded ||
                converted.status == SampleRateProcessStatus::OutputQuantumExceeded) {
                return {
                    LanePullStatus::InvalidArgument,
                    0,
                    0,
                    staleDiscardedThisCall,
                    consumerGeneration,
                    firstSourceFrame,
                    firstTimelineFrame,
                    pendingOutputDiscontinuity,
                };
            }
            if (converted.status == SampleRateProcessStatus::BackendFailure) {
                return {
                    LanePullStatus::ResamplerFailure,
                    0,
                    0,
                    staleDiscardedThisCall,
                    consumerGeneration,
                    firstSourceFrame,
                    firstTimelineFrame,
                    pendingOutputDiscontinuity,
                };
            }

            // A seek/selection can win while SRC is processing this old span. Never authorize the
            // already-written scratch as output in that case. Consume only the prefix the SRC
            // actually read, publish zero produced frames, and reset to the winning generation.
            if (activeGeneration.load(std::memory_order_acquire) != consumerGeneration) {
                if (converted.inputFramesConsumed > 0) {
                    read += converted.inputFramesConsumed;
                    readCursor.value.store(read, std::memory_order_release);
                    staleDiscardedThisCall += converted.inputFramesConsumed;
                }
                saturatingAdd(staleFramesDiscarded, staleDiscardedThisCall);
                synchronizeConsumerGeneration();
                // This is stale work, not an underrun/empty queue.
                return {
                    LanePullStatus::StaleGeneration,
                    converted.inputFramesConsumed,
                    0,
                    staleDiscardedThisCall,
                    consumerGeneration,
                    firstSourceFrame,
                    firstTimelineFrame,
                    LaneDiscontinuity::Generation,
                };
            }

            refreshConsumerEndOfStream();
            std::size_t authorizedOutputFrames = converted.outputFramesProduced;
            if (consumerEndOfStreamObserved) {
                const std::uint64_t remainingAuthorized = firstTimelineFrame >=
                        consumerFinalTimelineFrame48kExclusive
                    ? 0
                    : consumerFinalTimelineFrame48kExclusive - firstTimelineFrame;
                authorizedOutputFrames = static_cast<std::size_t>(std::min<std::uint64_t>(
                    authorizedOutputFrames,
                    remainingAuthorized));
                if (authorizedOutputFrames < converted.outputFramesProduced) {
                    saturatingAdd(
                        endOfStreamOvershootFramesDiscarded,
                        converted.outputFramesProduced - authorizedOutputFrames);
                }
            }

            if (converted.inputFramesConsumed > 0) {
                read += converted.inputFramesConsumed;
                readCursor.value.store(read, std::memory_order_release);
                consumerHasExpectedSourceFrame = true;
                consumerExpectedSourceFrame = saturatingFrameAdd(
                    firstSourceFrame,
                    converted.inputFramesConsumed);
                boundaryAppliedCursor = kNoCursor;
                saturatingAdd(sourceFramesConsumed, converted.inputFramesConsumed);
            }
            if (authorizedOutputFrames > 0) {
                nextOutputTimelineFrame = saturatingFrameAdd(
                    nextOutputTimelineFrame,
                    authorizedOutputFrames);
                saturatingAdd(outputFramesProduced, authorizedOutputFrames);
            }
            if (staleDiscardedThisCall > 0) {
                saturatingAdd(staleFramesDiscarded, staleDiscardedThisCall);
            }

            const LaneDiscontinuity reportedDiscontinuity = pendingOutputDiscontinuity;
            const std::uint64_t reportedFirstSource = pendingOutputDiscontinuity ==
                    LaneDiscontinuity::None
                ? firstSourceFrame
                : pendingFirstSourceFrame;
            if (authorizedOutputFrames > 0) {
                pendingOutputDiscontinuity = LaneDiscontinuity::None;
            }
            return {
                converted.status == SampleRateProcessStatus::OutputCapacityExhausted
                    ? LanePullStatus::OutputCapacityExhausted
                    : LanePullStatus::Ok,
                converted.inputFramesConsumed,
                authorizedOutputFrames,
                staleDiscardedThisCall,
                consumerGeneration,
                reportedFirstSource,
                firstTimelineFrame,
                reportedDiscontinuity,
            };
        }
    }

    [[nodiscard]] bool markDiscontinuity(std::uint64_t generation) noexcept {
        if (generation == 0 ||
            activeGeneration.load(std::memory_order_acquire) != generation) {
            return false;
        }
        explicitDiscontinuityGeneration.store(generation, std::memory_order_release);
        saturatingIncrement(explicitDiscontinuities);
        return true;
    }

    std::uint32_t sourceRateHz;
    std::size_t queueCapacityFrames;
    std::size_t maximumInputQuantumFrames;
    std::size_t maximumOutputQuantumFrames{0};
    std::vector<SourceFrameSlot> queue;
    SampleRateConverter converter;
    std::vector<float> inputScratch;
    std::vector<float> zeroScratch;
    std::size_t maximumEndOfStreamFlushInputFrames{0};
    RingCursor readCursor;
    RingCursor writeCursor;
    std::atomic<std::uint64_t> activeGeneration;
    std::atomic<std::uint64_t> publishedProducerGeneration{0};
    std::atomic<std::uint64_t> publishedConsumerGeneration{0};
    std::atomic<std::uint64_t> explicitDiscontinuityGeneration{0};
    std::atomic<std::uint64_t> endOfStreamPublishSequence{0};
    std::atomic<std::uint64_t> publishedEndOfStreamGeneration{0};
    std::atomic<std::uint64_t> publishedFinalSourceFrameExclusive{0};
    std::atomic<std::uint64_t> publishedFinalTimelineFrame48kExclusive{0};

    // Producer-owned state.
    std::uint64_t producerGeneration{0};
    std::uint64_t producerExpectedSourceFrame{0};
    bool producerHasExpectedFrame{false};
    LaneDiscontinuity producerPendingDiscontinuity{LaneDiscontinuity::None};
    bool producerEndOfStreamMarked{false};
    std::uint64_t producerFinalSourceFrameExclusive{0};

    // Consumer-owned state.
    std::uint64_t consumerGeneration{0};
    std::uint64_t consumerExpectedSourceFrame{0};
    std::uint64_t nextOutputTimelineFrame{0};
    std::uint64_t boundaryAppliedCursor{kNoCursor};
    std::uint64_t pendingFirstSourceFrame{0};
    bool consumerHasExpectedSourceFrame{false};
    bool consumerHasTimeline{false};
    LaneDiscontinuity pendingOutputDiscontinuity{LaneDiscontinuity::None};
    bool consumerEndOfStreamObserved{false};
    bool consumerEndOfStreamCompleted{false};
    std::uint64_t consumerFinalSourceFrameExclusive{0};
    std::uint64_t consumerFinalTimelineFrame48kExclusive{0};
    std::size_t consumerEndOfStreamFlushInputFramesRemaining{0};

    std::atomic<std::size_t> highWaterSourceFrames{0};
    std::atomic<std::uint64_t> offerCalls{0};
    std::atomic<std::uint64_t> acceptedInputFrames{0};
    std::atomic<std::uint64_t> queuedSourceFrames{0};
    std::atomic<std::uint64_t> overlapFramesDiscarded{0};
    std::atomic<std::uint64_t> staleFramesDiscarded{0};
    std::atomic<std::uint64_t> backpressureEvents{0};
    std::atomic<std::uint64_t> partialOfferEvents{0};
    std::atomic<std::uint64_t> timestampGapEvents{0};
    std::atomic<std::uint64_t> explicitDiscontinuities{0};
    std::atomic<std::uint64_t> generationAdvances{0};
    std::atomic<std::uint64_t> pullCalls{0};
    std::atomic<std::uint64_t> emptyPulls{0};
    std::atomic<std::uint64_t> sourceFramesConsumed{0};
    std::atomic<std::uint64_t> outputFramesProduced{0};
    std::atomic<std::uint64_t> nonFiniteFloatSamples{0};
    std::atomic<std::uint64_t> endOfStreamMarkCalls{0};
    std::atomic<std::uint64_t> acceptedEndOfStreamMarks{0};
    std::atomic<std::uint64_t> staleEndOfStreamMarks{0};
    std::atomic<std::uint64_t> rejectedEndOfStreamMarks{0};
    std::atomic<std::uint64_t> endOfStreamFlushCalls{0};
    std::atomic<std::uint64_t> endOfStreamFlushInputFrames{0};
    std::atomic<std::uint64_t> endOfStreamTailOutputFrames{0};
    std::atomic<std::uint64_t> endOfStreamOvershootFramesDiscarded{0};
    std::atomic<std::uint64_t> completedEndOfStreams{0};
    std::atomic<std::uint64_t> endOfStreamFlushFailures{0};
};

LaneIngress::LaneIngress(
    std::uint32_t sourceRateHz,
    std::size_t queueCapacityFrames,
    std::size_t maximumInputQuantumFrames,
    std::uint64_t initialGeneration)
    : mImpl(std::make_unique<Impl>(
          sourceRateHz,
          queueCapacityFrames,
          maximumInputQuantumFrames,
          initialGeneration)) {}

LaneIngress::~LaneIngress() = default;

LaneOfferResult LaneIngress::offer(const LaneInputBlock &block) noexcept {
    return mImpl->offer(block);
}

LanePullResult LaneIngress::pull48k(
    float *interleavedStereoOutput,
    std::size_t outputCapacityFrames) noexcept {
    return mImpl->pull48k(0U, interleavedStereoOutput, outputCapacityFrames);
}

LanePullResult LaneIngress::pull48k(
    std::uint64_t expectedGeneration,
    float *interleavedStereoOutput,
    std::size_t outputCapacityFrames) noexcept {
    if (expectedGeneration == 0U) return {LanePullStatus::InvalidArgument};
    return mImpl->pull48k(
        expectedGeneration,
        interleavedStereoOutput,
        outputCapacityFrames);
}

bool LaneIngress::advanceGeneration(std::uint64_t generation) noexcept {
    return mImpl->promoteGeneration(generation);
}

bool LaneIngress::markDiscontinuity(std::uint64_t generation) noexcept {
    return mImpl->markDiscontinuity(generation);
}

LaneEndOfStreamResult LaneIngress::markEndOfStream(
    std::uint64_t generation,
    std::uint64_t finalSourceFrameExclusive) noexcept {
    return mImpl->markEndOfStream(generation, finalSourceFrameExclusive);
}

std::uint32_t LaneIngress::sourceRateHz() const noexcept {
    return mImpl->sourceRateHz;
}

std::uint64_t LaneIngress::activeGeneration() const noexcept {
    return mImpl->activeGeneration.load(std::memory_order_acquire);
}

LaneIngressDiagnostics LaneIngress::diagnostics() const noexcept {
    const EndOfStreamSnapshot endOfStream = mImpl->readEndOfStreamSnapshot();
    return {
        mImpl->sourceRateHz,
        mImpl->queueCapacityFrames,
        mImpl->maximumInputQuantumFrames,
        mImpl->maximumOutputQuantumFrames,
        mImpl->readableFrames(),
        mImpl->highWaterSourceFrames.load(std::memory_order_relaxed),
        mImpl->activeGeneration.load(std::memory_order_acquire),
        mImpl->publishedProducerGeneration.load(std::memory_order_acquire),
        mImpl->publishedConsumerGeneration.load(std::memory_order_acquire),
        mImpl->offerCalls.load(std::memory_order_relaxed),
        mImpl->acceptedInputFrames.load(std::memory_order_relaxed),
        mImpl->queuedSourceFrames.load(std::memory_order_relaxed),
        mImpl->overlapFramesDiscarded.load(std::memory_order_relaxed),
        mImpl->staleFramesDiscarded.load(std::memory_order_relaxed),
        mImpl->backpressureEvents.load(std::memory_order_relaxed),
        mImpl->partialOfferEvents.load(std::memory_order_relaxed),
        mImpl->timestampGapEvents.load(std::memory_order_relaxed),
        mImpl->explicitDiscontinuities.load(std::memory_order_relaxed),
        mImpl->generationAdvances.load(std::memory_order_relaxed),
        mImpl->pullCalls.load(std::memory_order_relaxed),
        mImpl->emptyPulls.load(std::memory_order_relaxed),
        mImpl->sourceFramesConsumed.load(std::memory_order_relaxed),
        mImpl->outputFramesProduced.load(std::memory_order_relaxed),
        mImpl->nonFiniteFloatSamples.load(std::memory_order_relaxed),
        mImpl->endOfStreamMarkCalls.load(std::memory_order_relaxed),
        mImpl->acceptedEndOfStreamMarks.load(std::memory_order_relaxed),
        mImpl->staleEndOfStreamMarks.load(std::memory_order_relaxed),
        mImpl->rejectedEndOfStreamMarks.load(std::memory_order_relaxed),
        mImpl->endOfStreamFlushCalls.load(std::memory_order_relaxed),
        mImpl->endOfStreamFlushInputFrames.load(std::memory_order_relaxed),
        mImpl->endOfStreamTailOutputFrames.load(std::memory_order_relaxed),
        mImpl->endOfStreamOvershootFramesDiscarded.load(std::memory_order_relaxed),
        mImpl->completedEndOfStreams.load(std::memory_order_relaxed),
        mImpl->endOfStreamFlushFailures.load(std::memory_order_relaxed),
        endOfStream.generation,
        endOfStream.finalSourceFrameExclusive,
        endOfStream.finalTimelineFrame48kExclusive,
    };
}

class SongLaneIngressBank::Impl final {
public:
    Impl(std::size_t capacity, std::size_t quantum)
        : queueCapacityFrames(capacity), maximumInputQuantumFrames(quantum) {
        if (capacity < LaneIngress::kMinimumQueueCapacityFrames ||
            capacity > LaneIngress::kMaximumQueueCapacityFrames ||
            quantum == 0 || quantum > capacity) {
            throw std::invalid_argument("SongLaneIngressBank bounds are invalid");
        }
        for (auto &publishedLane : publishedLanes) {
            publishedLane.store(nullptr, std::memory_order_relaxed);
        }
    }

    std::size_t queueCapacityFrames;
    std::size_t maximumInputQuantumFrames;
    std::mutex ownerMutex;
    std::array<std::unique_ptr<LaneIngress>, SongLaneIngressBank::kLaneCount> laneOwners{};
    std::array<std::atomic<LaneIngress *>, SongLaneIngressBank::kLaneCount> publishedLanes{};
    std::atomic<std::size_t> publishedLaneCount{0};
};

SongLaneIngressBank::SongLaneIngressBank(
    std::size_t queueCapacityFrames,
    std::size_t maximumInputQuantumFrames)
    : mImpl(std::make_unique<Impl>(
          queueCapacityFrames,
          maximumInputQuantumFrames)) {}

SongLaneIngressBank::~SongLaneIngressBank() = default;

bool SongLaneIngressBank::configureLane(
    std::size_t laneIndex,
    std::uint32_t sourceRateHz,
    std::uint64_t initialGeneration) {
    if (laneIndex >= kLaneCount) return false;

    std::unique_ptr<LaneIngress> candidate;
    try {
        candidate = std::make_unique<LaneIngress>(
            sourceRateHz,
            mImpl->queueCapacityFrames,
            mImpl->maximumInputQuantumFrames,
            initialGeneration);
    } catch (const std::invalid_argument &) {
        return false;
    }

    const std::lock_guard<std::mutex> lock(mImpl->ownerMutex);
    std::unique_ptr<LaneIngress> &owner = mImpl->laneOwners[laneIndex];
    if (owner != nullptr) return false;

    LaneIngress *const publishedAddress = candidate.get();
    owner = std::move(candidate);
    mImpl->publishedLanes[laneIndex].store(
        publishedAddress,
        std::memory_order_release);
    mImpl->publishedLaneCount.fetch_add(1U, std::memory_order_release);
    return true;
}

void SongLaneIngressBank::releaseLane(std::size_t laneIndex) noexcept {
    if (laneIndex >= kLaneCount) return;

    const std::lock_guard<std::mutex> lock(mImpl->ownerMutex);
    std::unique_ptr<LaneIngress> &owner = mImpl->laneOwners[laneIndex];
    if (owner == nullptr) return;

    // Caller has already quiesced every borrower. Unpublish before owner destruction so a future
    // acquire snapshot cannot discover the object during teardown.
    mImpl->publishedLanes[laneIndex].store(nullptr, std::memory_order_release);
    mImpl->publishedLaneCount.fetch_sub(1U, std::memory_order_release);
    owner.reset();
}

LaneIngress *SongLaneIngressBank::lane(std::size_t laneIndex) noexcept {
    return laneIndex < kLaneCount
        ? mImpl->publishedLanes[laneIndex].load(std::memory_order_acquire)
        : nullptr;
}

const LaneIngress *SongLaneIngressBank::lane(std::size_t laneIndex) const noexcept {
    return laneIndex < kLaneCount
        ? mImpl->publishedLanes[laneIndex].load(std::memory_order_acquire)
        : nullptr;
}

std::size_t SongLaneIngressBank::configuredLaneCount() const noexcept {
    return mImpl->publishedLaneCount.load(std::memory_order_acquire);
}

}  // namespace stemwise::audio::engine
