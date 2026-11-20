#pragma once

#include "GuardedAtomicSnapshot.h"

#include "StemwiseMultibus.h"
#include "LateStemMixer.h"
#include "FinalPcmFrameTag.h"
#include "ImmutablePcmRunPool.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <bit>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
#include <stdexcept>
#include <type_traits>
#include <vector>

namespace stemwise::audio {

/**
 * Immutable render-bus ownership attached before the multibus payload enters the device queue.
 *
 * This deliberately does not claim to describe final PCM. Stem/source gain, limiting, and final
 * discontinuity treatment happen only after the callback consumes these buses. `payloadHash` is
 * therefore render-payload integrity evidence, while final audible evidence belongs downstream.
 */
struct RenderBusFrameTag final {
    std::uint64_t generation{0};
    std::uint64_t selectionToken{0};
    std::uint64_t payloadSequence{0};
    std::uint64_t payloadHash{0};
    std::uint64_t canonicalControlRevision{0};
    std::uint64_t sourceIntentRevision{0};
    std::uint64_t stemIntentRevision{0};
    std::uint32_t stemBusReadyMask{0};
    /** Numerical DSP tuple represented by this payload; zero tempo means transition/unknown. */
    float actualTempo{0.0F};
    float actualPitchSemitones{0.0F};

    /** Source mapping provenance; integer widths are bounded by the render block limit. */
    double renderSourceStartFrame{0.0}, renderSourceEndFrameExclusive{0.0};
    std::uint32_t renderFrameCount{0U}, renderFrameOffset{0U}, mappingFirstRenderFrameOffset{0U};
    /** Latest fixed-loop policy represented by this PCM, including disabled/dormant policies. */
    std::uint64_t loopPolicyRevision{0};
    std::uint64_t loopRevision{0};
    std::uint64_t loopCycle{0};
    bool loopEnabled{false};
    /** Exact absolute write boundary after the last frame of a real stream end, or zero. */
    std::uint64_t terminalWriteBoundary{0};
    /** Integer source-timeline frame represented by this output frame. */
    std::int64_t timelineFrame{0};
    /** Next source-frame boundary from the producer's tempo mapping; -1 for unmapped tags. */
    std::int64_t nextTimelineFrame{-1};

    [[nodiscard]] bool operator==(const RenderBusFrameTag &) const noexcept = default;
};

[[nodiscard]] inline bool managedLoopContextCoversRenderTag(
    const ManagedLoopRenderContext &context, const RenderBusFrameTag &tag) noexcept {
    if (context.valid()) return tag.loopEnabled && context.revision == tag.loopRevision;
    return context.empty() && (!tag.loopEnabled ||
        (tag.loopPolicyRevision != 0U && tag.loopRevision == tag.loopPolicyRevision));
}

[[nodiscard]] inline bool lateControlCoversRenderTag(
    const LateStemMixControlSnapshot &control, const RenderBusFrameTag &tag) noexcept {
    return control.valid() && control.generation == tag.generation &&
        control.selectionToken == tag.selectionToken &&
        control.canonicalControlRevision >= tag.canonicalControlRevision &&
        control.sourceIntentRevision >= tag.sourceIntentRevision &&
        control.stemIntentRevision >= tag.stemIntentRevision;
}

/**
 * Tags belong to one callback processing run when every non-timeline field is identical.
 *
 * Both timeline fields normally change on every frame and are intentionally excluded. The terminal
 * boundary is included, so the exact terminal frame is always visible as its own run.
 */
[[nodiscard]] inline bool sameRenderBusTagRun(
    const RenderBusFrameTag &left,
    const RenderBusFrameTag &right) noexcept {
    return left.generation == right.generation &&
        left.selectionToken == right.selectionToken &&
        left.payloadSequence == right.payloadSequence &&
        left.payloadHash == right.payloadHash &&
        left.canonicalControlRevision == right.canonicalControlRevision &&
        left.sourceIntentRevision == right.sourceIntentRevision &&
        left.stemIntentRevision == right.stemIntentRevision &&
        left.stemBusReadyMask == right.stemBusReadyMask &&
        left.actualTempo == right.actualTempo &&
        left.actualPitchSemitones == right.actualPitchSemitones &&
        left.renderSourceStartFrame == right.renderSourceStartFrame &&
        left.renderSourceEndFrameExclusive == right.renderSourceEndFrameExclusive &&
        left.renderFrameCount == right.renderFrameCount &&
        left.mappingFirstRenderFrameOffset == right.mappingFirstRenderFrameOffset &&
        left.loopPolicyRevision == right.loopPolicyRevision &&
        left.loopRevision == right.loopRevision &&
        left.loopCycle == right.loopCycle &&
        left.loopEnabled == right.loopEnabled &&
        left.terminalWriteBoundary == right.terminalWriteBoundary;
}

struct RenderBusControlRun final {
    std::uint64_t firstFrame{0U}, endFrame{0U};
    RenderBusFrameTag tag{};
    LateStemMixControlSnapshot control{};
    ManagedLoopRenderContext managedLoopContext{};
    bool hasBaseControl{false};
};

/** Only sample-varying data belongs to each frame. Control/owner tuples live in immutable runs. */
struct RenderBusFrameMapping final {
    std::int64_t timelineFrame{}, nextTimelineFrame{};
    std::uint64_t terminalWriteBoundary{};
    std::uint32_t renderFrameOffset{}, runIndex{};
};

class RenderBusFrameTags final {
public:
    RenderBusFrameTags() = default;
    RenderBusFrameTags(const RenderBusFrameMapping *frames,
        const ImmutablePcmRunPool<RenderBusControlRun> *runs) : mFrames(frames), mRuns(runs) {}
    [[nodiscard]] constexpr bool valid() const noexcept { return mFrames != nullptr && mRuns != nullptr; }
    [[nodiscard]] RenderBusFrameTag operator[](std::size_t index) const noexcept {
        const auto &frame = mFrames[index];
        auto tag = (*mRuns)[frame.runIndex & kRunMask].tag;
        tag.timelineFrame = frame.timelineFrame;
        tag.nextTimelineFrame = frame.nextTimelineFrame;
        tag.terminalWriteBoundary = frame.terminalWriteBoundary;
        tag.renderFrameOffset = frame.renderFrameOffset;
        if ((frame.runIndex & kUnmapped) != 0U) tag.renderFrameCount = 0U;
        return tag;
    }
    [[nodiscard]] RenderBusFrameTags operator+(std::size_t offset) const noexcept {
        return {mFrames + offset, mRuns};
    }
    [[nodiscard]] bool sameRun(std::size_t left, std::size_t right) const noexcept {
        return mFrames[left].runIndex == mFrames[right].runIndex &&
            mFrames[left].terminalWriteBoundary == mFrames[right].terminalWriteBoundary;
    }
    static constexpr std::uint32_t kUnmapped = 0x8000'0000U, kRunMask = ~kUnmapped;
private:
    const RenderBusFrameMapping *mFrames{nullptr};
    const ImmutablePcmRunPool<RenderBusControlRun> *mRuns{nullptr};
};

/** One physically contiguous part of a callback read lease. */
struct TaggedMultibusReadSpan final {
    const float *interleavedPayload{nullptr};
    RenderBusFrameTags tags{};
    std::size_t frameCount{0};
    std::uint64_t absoluteFirstFrame{0};
    std::size_t leaseFrameOffset{0};

    [[nodiscard]] constexpr bool valid() const noexcept {
        return interleavedPayload != nullptr && tags.valid() && frameCount != 0U;
    }
};

/**
 * Bounded zero-copy callback lease. A ring wrap produces exactly two spans; otherwise one.
 * Acquiring a lease never advances the consumer. The callback must finish mixing before calling
 * advanceRead(), because that release permits the producer to overwrite the consumed frames.
 */
struct TaggedMultibusReadLease final {
    std::array<TaggedMultibusReadSpan, 2U> spans{};
    std::size_t spanCount{0};
    std::size_t frameCount{0};
    std::uint64_t absoluteFirstFrame{0};
    std::uint64_t observedWritePosition{0};

    [[nodiscard]] constexpr bool empty() const noexcept { return frameCount == 0U; }
};

/** One physically contiguous run with an identical non-timeline render-bus tag tuple. */
struct RenderBusTagRun final {
    const float *interleavedPayload{nullptr};
    RenderBusFrameTags tags{};
    std::size_t frameCount{0};
    std::uint64_t absoluteFirstFrame{0};
    std::size_t leaseFrameOffset{0};
    /** Borrowed immutable block endpoint; valid only until the PCM lease advances. */
    const LateStemMixControlSnapshot *baseControl{nullptr};
    const ManagedLoopRenderContext *managedLoopContext{nullptr};
};

/**
 * Bounded SPSC queue for StemWise's kStemwiseMultibusStride-float render-bus payload.
 *
 * One render worker owns writes and one audio callback owns read leases. Frame storage is fixed;
 * immutable run storage grows only at new producer occupancy peaks. Consumer work never allocates.
 * Monotonic frame positions make terminal boundaries and partial writes exact across ring wrap.
 */
class SpscTaggedMultibusRingBuffer final {
public:
    struct TerminalBoundary final {
        std::uint64_t generation{}, selectionToken{}, physicalRevision{}, writePosition{}, epoch{};
        double sourceEnd{};
    };
    static constexpr std::size_t kPayloadStride = kStemwiseMultibusStride;
    static constexpr std::size_t kMaximumCapacityFrames =
        static_cast<std::size_t>(1U) << 24U;

    [[nodiscard]] static std::size_t storageBytes(std::size_t frames, bool peak) {
        checkedCapacity(frames);
        return addAudioMemoryBytes(audioMemoryBytes(frames,
            kPayloadStride * sizeof(float) + sizeof(RenderBusFrameMapping) + sizeof(std::uint32_t)),
            ImmutablePcmRunPool<RenderBusControlRun>::storageBytes(frames, peak));
    }

    explicit SpscTaggedMultibusRingBuffer(std::size_t capacityFrames)
        : mCapacityFrames(checkedCapacity(capacityFrames)),
          mPayload(checkedPayloadSampleCount(capacityFrames), 0.0F),
          mMappings(capacityFrames), mRunQueue(capacityFrames), mControlRuns(capacityFrames) {}

    [[nodiscard]] std::size_t metadataAllocatedBytes() const noexcept {
        return mMappings.size() * sizeof(RenderBusFrameMapping) +
            mRunQueue.size() * sizeof(std::uint32_t) + mControlRuns.allocatedBytes();
    }

    SpscTaggedMultibusRingBuffer(const SpscTaggedMultibusRingBuffer &) = delete;
    SpscTaggedMultibusRingBuffer &operator=(const SpscTaggedMultibusRingBuffer &) = delete;

    [[nodiscard]] std::size_t capacityFrames() const noexcept { return mCapacityFrames; }

    /** Producer only. Every accepted frame receives the same render-bus tag. */
    std::size_t writeTagged(
        const float *interleavedPayload,
        std::size_t frameCount,
        const RenderBusFrameTag &tag,
        const LateStemMixControlSnapshot *baseControl = nullptr,
        const ManagedLoopRenderContext *managedLoopContext = nullptr) noexcept {
        if (interleavedPayload == nullptr || frameCount == 0U) return 0U;

        const std::uint64_t writePosition = mWritePosition.load(std::memory_order_relaxed);
        const std::uint64_t readPosition = mReadPosition.load(std::memory_order_acquire);
        const std::size_t writable = writablePrefix(writePosition, readPosition, frameCount);
        if (writable == 0U || !canCommitControlBlock()) return 0U;
        const auto run = commitControlBlock(writePosition, writable, tag, baseControl, managedLoopContext);
        if (!run) return 0U;
        const RenderBusFrameMapping mapping{tag.timelineFrame, tag.nextTimelineFrame,
            tag.terminalWriteBoundary, tag.renderFrameOffset, *run};

        const std::size_t ringFrame =
            static_cast<std::size_t>(writePosition % mCapacityFrames);
        const std::size_t firstFrames = std::min(writable, mCapacityFrames - ringFrame);
        copyPayloadFrames(
            mPayload.data() + ringFrame * kPayloadStride,
            interleavedPayload,
            firstFrames);
        std::fill_n(mMappings.data() + ringFrame, firstFrames, mapping);

        const std::size_t secondFrames = writable - firstFrames;
        if (secondFrames != 0U) {
            copyPayloadFrames(
                mPayload.data(),
                interleavedPayload + firstFrames * kPayloadStride,
                secondFrames);
            std::fill_n(mMappings.data(), secondFrames, mapping);
        }

        mProducerTail = {};
        mWritePosition.store(writePosition + writable, std::memory_order_release);
        return writable;
    }

    /**
     * Producer only. Maps the accepted prefix onto one source-timeline interval.
     *
     * A partial write never publishes the terminal marker. The caller retries its suffix with the
     * corresponding remaining source interval; only the exact final accepted suffix can mark EOS.
     */
    std::size_t writeTaggedTimeline(
        const float *interleavedPayload,
        std::size_t frameCount,
        const RenderBusFrameTag &baseTag,
        double sourceStartFrame,
        double sourceEndFrameExclusive,
        bool endOfStreamAfterInput = false,
        const LateStemMixControlSnapshot *baseControl = nullptr,
        const ManagedLoopRenderContext *managedLoopContext = nullptr) noexcept {
        if (interleavedPayload == nullptr || frameCount == 0U ||
            !std::isfinite(sourceStartFrame) || !std::isfinite(sourceEndFrameExclusive) ||
            sourceStartFrame < 0.0 || sourceEndFrameExclusive < sourceStartFrame ||
            sourceEndFrameExclusive >=
                static_cast<double>(std::numeric_limits<std::int64_t>::max())) {
            return 0U;
        }

        const std::uint64_t writePosition = mWritePosition.load(std::memory_order_relaxed);
        const std::uint64_t readPosition = mReadPosition.load(std::memory_order_acquire);
        const std::size_t writable = writablePrefix(writePosition, readPosition, frameCount);
        if (writable == 0U || !canCommitControlBlock()) return 0U;

        RenderBusFrameTag mappingTag = baseTag;
        if (mappingTag.renderFrameCount == 0U && frameCount <= std::numeric_limits<std::uint32_t>::max()) {
            mappingTag.renderSourceStartFrame = sourceStartFrame;
            mappingTag.renderSourceEndFrameExclusive = sourceEndFrameExclusive;
            mappingTag.renderFrameCount = static_cast<std::uint32_t>(frameCount);
            mappingTag.renderFrameOffset = 0U;
            mappingTag.mappingFirstRenderFrameOffset = 0U;
        }
        const auto run = commitControlBlock(writePosition, writable, mappingTag, baseControl, managedLoopContext);
        if (!run) return 0U;
        const double sourceStep =
            (sourceEndFrameExclusive - sourceStartFrame) / static_cast<double>(frameCount);
        const std::size_t ringFrame =
            static_cast<std::size_t>(writePosition % mCapacityFrames);
        const std::size_t firstFrames = std::min(writable, mCapacityFrames - ringFrame);
        copyPayloadFrames(
            mPayload.data() + ringFrame * kPayloadStride,
            interleavedPayload,
            firstFrames);
        writeTimelineTags(
            mMappings.data() + ringFrame,
            firstFrames,
            0U,
            mappingTag, *run,
            sourceStartFrame,
            sourceStep,
            sourceEndFrameExclusive);

        const std::size_t secondFrames = writable - firstFrames;
        if (secondFrames != 0U) {
            copyPayloadFrames(
                mPayload.data(),
                interleavedPayload + firstFrames * kPayloadStride,
                secondFrames);
            writeTimelineTags(
                mMappings.data(),
                secondFrames,
                firstFrames,
                mappingTag, *run,
                sourceStartFrame,
                sourceStep,
                sourceEndFrameExclusive);
        }

        if (writable == frameCount) {
            const std::uint64_t terminalBoundary = writePosition + writable;
            const std::size_t terminalRingFrame = static_cast<std::size_t>(
                (terminalBoundary - 1U) % mCapacityFrames);
            // Reconstructing end as start + N * step may round below an exact integer boundary.
            mMappings[terminalRingFrame].nextTimelineFrame =
                static_cast<std::int64_t>(sourceEndFrameExclusive);
            if (endOfStreamAfterInput) {
                mMappings[terminalRingFrame].terminalWriteBoundary = terminalBoundary;
            }
        }

        mProducerTail = frameTags()[static_cast<std::size_t>((writePosition + writable - 1U) % mCapacityFrames)];
        mProducerSourceEnd = writable == frameCount ? sourceEndFrameExclusive :
            sourceStartFrame + static_cast<double>(writable) * sourceStep;
        mWritePosition.store(writePosition + writable, std::memory_order_release);
        return writable;
    }

    /** Sole producer: EOF may arrive separately after the last PCM write was released. */
    [[nodiscard]] bool publishTerminalBoundary(std::uint64_t generation, std::uint64_t token,
        std::uint64_t physicalRevision, double sourceEnd) noexcept {
        const auto boundary = writePosition();
        if (boundary == 0U || generation == 0U || token == 0U || !std::isfinite(sourceEnd) || sourceEnd < 0.0 ||
            sourceEnd >= static_cast<double>(std::numeric_limits<std::int64_t>::max()) ||
            mProducerTail.generation != generation || mProducerTail.selectionToken != token ||
            mProducerTail.loopPolicyRevision != physicalRevision || mProducerSourceEnd != sourceEnd ||
            mProducerTail.nextTimelineFrame != static_cast<std::int64_t>(sourceEnd)) return false;
        const auto publication = mTerminalGuard.publish();
        mTerminalGeneration.store(generation, std::memory_order_release);
        mTerminalToken.store(token, std::memory_order_release);
        mTerminalPhysicalRevision.store(physicalRevision, std::memory_order_release);
        mTerminalSourceEnd.store(std::bit_cast<std::uint64_t>(sourceEnd), std::memory_order_release);
        mTerminalWritePosition.store(boundary, std::memory_order_release);
        mTerminalEpoch.store(epoch(), std::memory_order_release);
        return true;
    }

    [[nodiscard]] TerminalBoundary terminalBoundary() const noexcept {
        const auto terminal = mTerminalGuard.read([this](std::uint64_t) noexcept {
            return TerminalBoundary{mTerminalGeneration.load(std::memory_order_acquire),
                mTerminalToken.load(std::memory_order_acquire),
                mTerminalPhysicalRevision.load(std::memory_order_acquire),
                mTerminalWritePosition.load(std::memory_order_acquire),
                mTerminalEpoch.load(std::memory_order_acquire),
                std::bit_cast<double>(mTerminalSourceEnd.load(std::memory_order_acquire))};
        });
        return terminal.writePosition != 0U && terminal.writePosition == writePosition() && terminal.epoch == epoch()
            ? terminal : TerminalBoundary{};
    }
    [[nodiscard]] std::uint64_t epoch() const noexcept { return mEpoch.load(std::memory_order_acquire); }

    /**
     * Callback only. Returns at most two direct spans and does not release any frame to the writer.
     * The pointers remain valid until advanceRead() publishes consumption.
     */
    [[nodiscard]] TaggedMultibusReadLease acquireRead(std::size_t maximumFrames) const noexcept {
        TaggedMultibusReadLease lease{};
        if (maximumFrames == 0U) return lease;

        const std::uint64_t readPosition = mReadPosition.load(std::memory_order_relaxed);
        const std::uint64_t writePosition = mWritePosition.load(std::memory_order_acquire);
        if (writePosition <= readPosition) return lease;
        const std::uint64_t queued = writePosition - readPosition;
        const std::size_t readable = static_cast<std::size_t>(
            std::min<std::uint64_t>({queued, mCapacityFrames, maximumFrames}));
        if (readable == 0U) return lease;

        lease.frameCount = readable;
        lease.absoluteFirstFrame = readPosition;
        lease.observedWritePosition = writePosition;

        const std::size_t ringFrame =
            static_cast<std::size_t>(readPosition % mCapacityFrames);
        const std::size_t firstFrames = std::min(readable, mCapacityFrames - ringFrame);
        lease.spans[0] = {
            .interleavedPayload = mPayload.data() + ringFrame * kPayloadStride,
            .tags = frameTags() + ringFrame,
            .frameCount = firstFrames,
            .absoluteFirstFrame = readPosition,
            .leaseFrameOffset = 0U,
        };
        lease.spanCount = 1U;

        const std::size_t secondFrames = readable - firstFrames;
        if (secondFrames != 0U) {
            lease.spans[1] = {
                .interleavedPayload = mPayload.data(),
                .tags = frameTags(),
                .frameCount = secondFrames,
                .absoluteFirstFrame = readPosition + firstFrames,
                .leaseFrameOffset = firstFrames,
            };
            lease.spanCount = 2U;
        }
        return lease;
    }

    /**
     * Callback only. Releases an already-mixed prefix of one current lease.
     *
     * Partial advancement is supported, but the remaining suffix must be reacquired. A stale or
     * repeated lease is rejected so consumer position can never rewind.
     */
    bool advanceRead(
        const TaggedMultibusReadLease &lease,
        std::size_t consumedFrames) noexcept {
        if (consumedFrames == 0U) return true;
        if (consumedFrames > lease.frameCount || lease.frameCount == 0U) return false;
        const std::uint64_t readPosition = mReadPosition.load(std::memory_order_relaxed);
        const std::uint64_t writePosition = mWritePosition.load(std::memory_order_acquire);
        if (readPosition != lease.absoluteFirstFrame ||
            lease.observedWritePosition < lease.absoluteFirstFrame ||
            lease.frameCount > lease.observedWritePosition - lease.absoluteFirstFrame ||
            lease.observedWritePosition > writePosition ||
            writePosition < readPosition ||
            consumedFrames > writePosition - readPosition) {
            return false;
        }
        const auto nextRead = readPosition + consumedFrames;
        auto blockPosition = mReadBlockPosition.load(std::memory_order_relaxed);
        auto frame = readPosition;
        while (frame < nextRead) {
            const auto &block = mControlRuns[mRunQueue[blockPosition % mCapacityFrames]];
            if (block.firstFrame > frame || block.endFrame <= frame ||
                block.endFrame > lease.observedWritePosition) return false;
            if (block.endFrame > nextRead) break;
            frame = block.endFrame;
            ++blockPosition;
        }
        // Retire a descriptor only after its final PCM frame was consumed. This release permits
        // descriptor reuse; the later frame-position release permits payload/tag reuse.
        for (auto retired = mReadBlockPosition.load(std::memory_order_relaxed); retired < blockPosition; ++retired)
            mControlRuns.release(mRunQueue[retired % mCapacityFrames]);
        mReadBlockPosition.store(blockPosition, std::memory_order_release);
        mReadPosition.store(nextRead, std::memory_order_release);
        return true;
    }

    /** Callback-only shorthand which releases the complete lease. */
    bool advanceRead(const TaggedMultibusReadLease &lease) noexcept {
        return advanceRead(lease, lease.frameCount);
    }

    /**
     * Visits every physically contiguous identical-tag run before the lease is advanced.
     * A logical run crossing ring wrap is deliberately delivered as two physical runs.
     */
    template <typename Visitor>
    bool forEachTagRun(const TaggedMultibusReadLease &lease, Visitor &&visitor) const noexcept {
        static_assert(
            std::is_nothrow_invocable_v<Visitor &, const RenderBusTagRun &>,
            "The audio-callback tag-run visitor must be noexcept");
        auto blockPosition = mReadBlockPosition.load(std::memory_order_relaxed);
        for (std::size_t spanIndex = 0U; spanIndex < lease.spanCount; ++spanIndex) {
            const TaggedMultibusReadSpan &span = lease.spans[spanIndex];
            if (!span.valid()) continue;
            std::size_t runStart = 0U;
            while (runStart < span.frameCount) {
                const auto &block = mControlRuns[mRunQueue[blockPosition % mCapacityFrames]];
                const auto absolute = span.absoluteFirstFrame + runStart;
                if (block.firstFrame > absolute || block.endFrame <= absolute ||
                    block.endFrame > lease.observedWritePosition) return false;
                const auto blockEnd = runStart + static_cast<std::size_t>(
                    std::min<std::uint64_t>(block.endFrame - absolute, span.frameCount - runStart));
                auto frame = runStart + 1U;
                while (frame < blockEnd && span.tags.sameRun(runStart, frame)) ++frame;
                visitor(RenderBusTagRun{
                    .interleavedPayload =
                        span.interleavedPayload + runStart * kPayloadStride,
                    .tags = span.tags + runStart,
                    .frameCount = frame - runStart,
                    .absoluteFirstFrame = span.absoluteFirstFrame + runStart,
                    .leaseFrameOffset = span.leaseFrameOffset + runStart,
                    .baseControl = block.hasBaseControl ? &block.control : nullptr,
                    .managedLoopContext = &block.managedLoopContext,
                });
                runStart = frame;
                if (span.absoluteFirstFrame + frame == block.endFrame) ++blockPosition;
            }
        }
        return true;
    }

    [[nodiscard]] std::uint64_t writePosition() const noexcept {
        return mWritePosition.load(std::memory_order_acquire);
    }

    [[nodiscard]] std::uint64_t readPosition() const noexcept {
        return mReadPosition.load(std::memory_order_acquire);
    }

    [[nodiscard]] std::size_t queuedFrames() const noexcept {
        const std::uint64_t readPosition = mReadPosition.load(std::memory_order_acquire);
        const std::uint64_t writePosition = mWritePosition.load(std::memory_order_acquire);
        if (writePosition <= readPosition) return 0U;
        return static_cast<std::size_t>(
            std::min<std::uint64_t>(writePosition - readPosition, mCapacityFrames));
    }

    [[nodiscard]] std::size_t writableFrames() const noexcept {
        return mCapacityFrames - queuedFrames();
    }

    /** Sole producer, inactive ring only; reads one immutable staged head without advancing it. */
    [[nodiscard]] bool peekInactiveHead(RenderBusFrameTag &tag) const noexcept {
        const std::uint64_t readPosition = mReadPosition.load(std::memory_order_relaxed);
        const std::uint64_t writePosition = mWritePosition.load(std::memory_order_acquire);
        if (writePosition <= readPosition || writePosition - readPosition > mCapacityFrames) {
            return false;
        }
        tag = frameTags()[static_cast<std::size_t>(readPosition % mCapacityFrames)];
        return true;
    }

    /** Sole producer, inactive ring only; proves a short continuation ends at its final frame. */
    [[nodiscard]] bool peekInactiveTail(RenderBusFrameTag &tag) const noexcept {
        const std::uint64_t readPosition = mReadPosition.load(std::memory_order_relaxed);
        const std::uint64_t writePosition = mWritePosition.load(std::memory_order_acquire);
        if (writePosition <= readPosition || writePosition - readPosition > mCapacityFrames) {
            return false;
        }
        tag = frameTags()[static_cast<std::size_t>((writePosition - 1U) % mCapacityFrames)];
        return true;
    }

    /** Sole producer, inactive ring only; no callback may retain a lease for this ring. */
    void resetInactive() noexcept {
        mEpoch.fetch_add(1U, std::memory_order_acq_rel);
        { const auto publication = mTerminalGuard.publish();
          mTerminalWritePosition.store(0U, std::memory_order_release); }
        mProducerTail = {};
        mProducerSourceEnd = 0.0;
        mControlRuns.resetInactive();
        mReadBlockPosition.store(0U, std::memory_order_relaxed);
        mWriteBlockPosition = 0U;
        mReadPosition.store(0U, std::memory_order_relaxed);
        mWritePosition.store(0U, std::memory_order_release);
    }

private:
    [[nodiscard]] RenderBusFrameTags frameTags() const noexcept { return {mMappings.data(), &mControlRuns}; }

    [[nodiscard]] bool canCommitControlBlock() const noexcept {
        const auto read = mReadBlockPosition.load(std::memory_order_acquire);
        return mWriteBlockPosition != std::numeric_limits<std::uint64_t>::max() &&
            read <= mWriteBlockPosition && mWriteBlockPosition - read < mCapacityFrames;
    }

    [[nodiscard]] std::optional<std::uint32_t> commitControlBlock(std::uint64_t firstFrame, std::size_t frames,
        const RenderBusFrameTag &tag,
        const LateStemMixControlSnapshot *baseControl,
        const ManagedLoopRenderContext *managedLoopContext) noexcept {
        const auto slot = mControlRuns.acquire();
        if (!slot) return std::nullopt;
        auto &block = mControlRuns[*slot];
        block.firstFrame = firstFrame;
        block.endFrame = firstFrame + frames;
        block.tag = tag;
        block.hasBaseControl = baseControl != nullptr;
        if (baseControl != nullptr) block.control = *baseControl;
        block.managedLoopContext = managedLoopContext != nullptr ? *managedLoopContext : ManagedLoopRenderContext{};
        mRunQueue[mWriteBlockPosition % mCapacityFrames] = *slot;
        ++mWriteBlockPosition;
        return slot;
    }

    static std::size_t checkedCapacity(std::size_t capacityFrames) {
        if (capacityFrames == 0U || capacityFrames > kMaximumCapacityFrames) {
            throw std::invalid_argument("Invalid multibus ring-buffer capacity");
        }
        return capacityFrames;
    }

    static std::size_t checkedPayloadSampleCount(std::size_t capacityFrames) {
        static_cast<void>(checkedCapacity(capacityFrames));
        if (capacityFrames > std::numeric_limits<std::size_t>::max() / kPayloadStride) {
            throw std::invalid_argument("Multibus ring-buffer payload size overflows");
        }
        return capacityFrames * kPayloadStride;
    }

    [[nodiscard]] std::size_t writablePrefix(
        std::uint64_t writePosition,
        std::uint64_t readPosition,
        std::size_t requestedFrames) const noexcept {
        if (writePosition < readPosition) return 0U;
        const std::uint64_t queued = writePosition - readPosition;
        if (queued >= mCapacityFrames) return 0U;
        return std::min(
            requestedFrames,
            mCapacityFrames - static_cast<std::size_t>(queued));
    }

    static void copyPayloadFrames(
        float *destination,
        const float *source,
        std::size_t frameCount) noexcept {
        std::memcpy(
            destination,
            source,
            frameCount * kPayloadStride * sizeof(float));
    }

    static void writeTimelineTags(
        RenderBusFrameMapping *destination,
        std::size_t frameCount,
        std::size_t sourceOffsetFrames,
        const RenderBusFrameTag &baseTag,
        std::uint32_t runIndex,
        double sourceStartFrame,
        double sourceStep,
        double sourceEndFrameExclusive) noexcept {
        for (std::size_t frame = 0U; frame < frameCount; ++frame) {
            RenderBusFrameMapping tag{};
            tag.runIndex = runIndex;
            const auto offset = static_cast<std::uint64_t>(baseTag.renderFrameOffset) + sourceOffsetFrames + frame;
            if (offset <= std::numeric_limits<std::uint32_t>::max()) {
                tag.renderFrameOffset = static_cast<std::uint32_t>(offset);
            } else {
                tag.runIndex |= RenderBusFrameTags::kUnmapped;
                tag.renderFrameOffset = baseTag.renderFrameOffset;
            }
            tag.terminalWriteBoundary = 0U;
            const double sourceFrame = sourceStartFrame +
                static_cast<double>(sourceOffsetFrames + frame) * sourceStep;
            tag.timelineFrame = static_cast<std::int64_t>(sourceFrame);
            tag.nextTimelineFrame = static_cast<std::int64_t>(std::min(
                sourceStartFrame + static_cast<double>(sourceOffsetFrames + frame + 1U) * sourceStep,
                sourceEndFrameExclusive));
            destination[frame] = tag;
        }
    }

    const std::size_t mCapacityFrames;
    std::vector<float> mPayload;
    std::vector<RenderBusFrameMapping> mMappings;
    // At most one descriptor per queued frame, including legal one-frame render blocks.
    // Endpoints are copied once per accepted write, never into per-frame tags.
    std::vector<std::uint32_t> mRunQueue;
    ImmutablePcmRunPool<RenderBusControlRun> mControlRuns;
    std::uint64_t mWriteBlockPosition{0U};
    RenderBusFrameTag mProducerTail{};
    double mProducerSourceEnd{};
    GuardedAtomicSnapshot mTerminalGuard;
    std::atomic<std::uint64_t> mTerminalGeneration{}, mTerminalToken{}, mTerminalPhysicalRevision{},
        mTerminalWritePosition{}, mTerminalSourceEnd{}, mTerminalEpoch{};
    std::atomic<std::uint64_t> mEpoch{1U};
    alignas(64) std::atomic<std::uint64_t> mReadBlockPosition{0U};
    alignas(64) std::atomic<std::uint64_t> mWritePosition{0U};
    alignas(64) std::atomic<std::uint64_t> mReadPosition{0U};
};

static_assert(SpscTaggedMultibusRingBuffer::kPayloadStride == kStemwiseMultibusStride);
static_assert(std::atomic<std::uint64_t>::is_always_lock_free);

}  // namespace stemwise::audio
