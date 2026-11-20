#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <thread>
#include <vector>

namespace stemwise::audio {

/** One exact non-empty, valid-owner span emitted by the device callback. */
struct NativeAudioPresentationSpan final {
    std::uint64_t sequence{0};
    std::uint64_t generation{0};
    std::uint64_t selectionToken{0};
    std::uint64_t firstFingerprintSequence{0};
    std::uint64_t lastFingerprintSequence{0};
    std::uint64_t callbackSequence{0};
    std::uint64_t pcmFrames{0};
    std::uint64_t firstLoopPolicyRevision{0};
    std::uint64_t lastLoopPolicyRevision{0};
    std::int64_t firstTimelineFrame{-1};
    std::int64_t lastTimelineFrame{-1};
    std::uint64_t continuityViolationCount{0};
    double renderSourceStartFrame{0.0}, renderSourceEndFrameExclusive{0.0};
    std::uint32_t renderFrameCount{0U}, firstRenderFrameOffset{0U}, mappingFirstRenderFrameOffset{0U};
    float actualTempo{0.0F};
    std::int64_t lastNextTimelineFrame{-1};
    std::uint64_t callbackPcmFrames{0U};

    [[nodiscard]] constexpr bool valid() const noexcept {
        return sequence != 0U && generation != 0U && selectionToken != 0U &&
            firstFingerprintSequence != 0U && lastFingerprintSequence != 0U &&
            lastFingerprintSequence >= firstFingerprintSequence &&
            callbackSequence != 0U && pcmFrames != 0U &&
            firstTimelineFrame >= 0 && lastTimelineFrame >= 0 &&
            continuityViolationCount <= pcmFrames - 1U;
    }
};

struct NativeAudioPresentationJournalSnapshot final {
    std::uint64_t requestedAfterSequence{0};
    std::uint64_t writeSequence{0};
    std::uint64_t oldestAvailableSequence{0};
    /** Entries overwritten before this reader could observe them. */
    std::uint64_t overflowCount{0};
    /** Callback publications dropped because an off-callback reader owned the target slot. */
    std::uint64_t writerCollisionCount{0};
    /** Invalid owner/fingerprint tuples rejected before any slot mutation. */
    std::uint64_t invalidPublicationCount{0};
    bool sequenceExhausted{false};
    bool coherent{true};
    std::vector<NativeAudioPresentationSpan> entries{};
};

/**
 * Fixed-capacity single-writer presentation journal.
 *
 * Each slot owns a standard-guaranteed lock-free [std::atomic_flag]. The callback attempts that
 * latch once and never spins; collision drops the diagnostic entry and increments a hard-failure
 * counter without advancing sequence. Off-callback readers may spin/yield, then copy the plain
 * immutable payload while holding the same latch. The writer's release clear and reader's acquire
 * test-and-set establish the happens-before edge, and mutual exclusion makes same-slot wrap free
 * of payload data races. There are no allocations or blocking waits in [publish].
 */
template <std::size_t Capacity>
class PresentationSpanJournal final {
public:
    static_assert(Capacity > 0U);

    void recordUnavailableCapture() noexcept { incrementSaturating(mInvalidPublicationCount); }

    bool publish(NativeAudioPresentationSpan span) noexcept {
        span.sequence = 1U;
        if (!span.valid()) {
            incrementSaturating(mInvalidPublicationCount);
            return false;
        }
        const std::uint64_t previous = mWriteSequence.load(std::memory_order_relaxed);
        if (previous == std::numeric_limits<std::uint64_t>::max()) {
            mSequenceExhausted.store(true, std::memory_order_release);
            return false;
        }
        const std::uint64_t sequence = previous + 1U;
        Slot &slot = mSlots[indexFor(sequence)];
        if (slot.latch.test_and_set(std::memory_order_acquire)) {
            incrementSaturating(mWriterCollisionCount);
            return false;
        }
        span.sequence = sequence;
        slot.payload = span;
        slot.latch.clear(std::memory_order_release);
        mWriteSequence.store(sequence, std::memory_order_release);
        return true;
    }

    [[nodiscard]] NativeAudioPresentationJournalSnapshot readAfter(
        std::uint64_t afterSequence) const {
        constexpr std::size_t kMaximumCaptureAttempts = 8U;
        for (std::size_t attempt = 0U; attempt < kMaximumCaptureAttempts; ++attempt) {
            const std::uint64_t writeSequence =
                mWriteSequence.load(std::memory_order_acquire);
            NativeAudioPresentationJournalSnapshot result = headerFor(
                afterSequence,
                writeSequence);
            if (writeSequence == 0U || afterSequence >= writeSequence) {
                refreshCollisionState(result);
                return result;
            }

            const std::uint64_t requestedStart = afterSequence + 1U;
            const std::uint64_t captureStart =
                requestedStart < result.oldestAvailableSequence
                    ? result.oldestAvailableSequence
                    : requestedStart;
            result.entries.reserve(static_cast<std::size_t>(writeSequence - captureStart + 1U));
            bool coherent = true;
            for (std::uint64_t sequence = captureStart; sequence <= writeSequence; ++sequence) {
                NativeAudioPresentationSpan span{};
                if (!readSlot(sequence, span)) {
                    coherent = false;
                    break;
                }
                result.entries.push_back(span);
                if (sequence == std::numeric_limits<std::uint64_t>::max()) break;
            }
            if (coherent) {
                refreshCollisionState(result);
                return result;
            }
        }

        const std::uint64_t writeSequence = mWriteSequence.load(std::memory_order_acquire);
        NativeAudioPresentationJournalSnapshot failed = headerFor(afterSequence, writeSequence);
        failed.coherent = false;
        refreshCollisionState(failed);
        return failed;
    }

    [[nodiscard]] std::uint64_t writeSequence() const noexcept {
        return mWriteSequence.load(std::memory_order_acquire);
    }

private:
    struct Slot final {
        mutable std::atomic_flag latch = ATOMIC_FLAG_INIT;
        NativeAudioPresentationSpan payload{};
    };

    static void incrementSaturating(std::atomic<std::uint64_t> &counter) noexcept {
        std::uint64_t previous = counter.load(std::memory_order_relaxed);
        while (previous != std::numeric_limits<std::uint64_t>::max() &&
               !counter.compare_exchange_weak(
                   previous,
                   previous + 1U,
                   std::memory_order_relaxed,
                   std::memory_order_relaxed)) {
        }
    }

    static void lockForRead(Slot &slot) noexcept {
        while (slot.latch.test_and_set(std::memory_order_acquire)) {
            std::this_thread::yield();
        }
    }

    [[nodiscard]] static constexpr std::size_t indexFor(
        std::uint64_t sequence) noexcept {
        return static_cast<std::size_t>((sequence - 1U) % Capacity);
    }

    [[nodiscard]] NativeAudioPresentationJournalSnapshot headerFor(
        std::uint64_t afterSequence,
        std::uint64_t writeSequence) const noexcept {
        const std::uint64_t oldest = writeSequence == 0U
            ? 0U
            : (writeSequence > Capacity ? writeSequence - Capacity + 1U : 1U);
        std::uint64_t overflow = 0U;
        if (writeSequence != 0U && afterSequence < writeSequence) {
            const std::uint64_t requestedStart = afterSequence + 1U;
            if (requestedStart < oldest) overflow = oldest - requestedStart;
        }
        return NativeAudioPresentationJournalSnapshot{
            .requestedAfterSequence = afterSequence,
            .writeSequence = writeSequence,
            .oldestAvailableSequence = oldest,
            .overflowCount = overflow,
            .writerCollisionCount =
                mWriterCollisionCount.load(std::memory_order_acquire),
            .invalidPublicationCount =
                mInvalidPublicationCount.load(std::memory_order_acquire),
            .sequenceExhausted = mSequenceExhausted.load(std::memory_order_acquire),
            .coherent = mWriterCollisionCount.load(std::memory_order_acquire) == 0U,
            .entries = {},
        };
    }

    void refreshCollisionState(
        NativeAudioPresentationJournalSnapshot &snapshot) const noexcept {
        snapshot.writerCollisionCount =
            mWriterCollisionCount.load(std::memory_order_acquire);
        snapshot.invalidPublicationCount =
            mInvalidPublicationCount.load(std::memory_order_acquire);
        if (snapshot.writerCollisionCount != 0U || snapshot.invalidPublicationCount != 0U) {
            snapshot.coherent = false;
        }
    }

    [[nodiscard]] bool readSlot(
        std::uint64_t expectedSequence,
        NativeAudioPresentationSpan &destination) const noexcept {
        Slot &slot = mSlots[indexFor(expectedSequence)];
        lockForRead(slot);
        destination = slot.payload;
        slot.latch.clear(std::memory_order_release);
        return destination.sequence == expectedSequence && destination.valid();
    }

    mutable std::array<Slot, Capacity> mSlots{};
    std::atomic<std::uint64_t> mWriteSequence{0};
    std::atomic<std::uint64_t> mWriterCollisionCount{0};
    std::atomic<std::uint64_t> mInvalidPublicationCount{0};
    std::atomic<bool> mSequenceExhausted{false};
};

}  // namespace stemwise::audio
