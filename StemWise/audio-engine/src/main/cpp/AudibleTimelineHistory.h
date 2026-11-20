#include "GuardedAtomicSnapshot.h"
#include "AudioMemoryAdmission.h"
#pragma once

#include "FinalPcmFrameTag.h"
#include "NativeAudioOutputBridge.h"

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>

namespace stemwise::audio {

/** Fixed callback-written history used only to resolve the output's presented output-frame timestamp. */
class AudibleTimelineHistory final {
public:
    [[nodiscard]] static std::size_t storageBytes(std::size_t frames) { return audioMemoryBytes(frames, sizeof(Slot)); }
    explicit AudibleTimelineHistory(std::size_t capacityFrames)
        : mCapacityFrames(capacityFrames),
          mSlots(std::make_unique<Slot[]>(capacityFrames)) {}

    AudibleTimelineHistory(const AudibleTimelineHistory &) = delete;
    AudibleTimelineHistory &operator=(const AudibleTimelineHistory &) = delete;
    [[nodiscard]] std::size_t allocatedBytes() const noexcept { return mCapacityFrames * sizeof(Slot); }

    void publish(
        std::uint64_t firstOutputFrame,
        const OutputTimelineFrameTag *tags,
        std::size_t frameCount) noexcept {
        if (tags == nullptr || mCapacityFrames == 0U) return;
        for (std::size_t frame = 0U; frame < frameCount; ++frame) {
            const std::uint64_t outputFrame = firstOutputFrame + frame;
            Slot &slot = mSlots[outputFrame % mCapacityFrames];
            beginGuardedPublication(slot.guard);
            slot.generation.store(tags[frame].generation, std::memory_order_relaxed);
            slot.selectionToken.store(tags[frame].selectionToken, std::memory_order_relaxed);
            slot.timelineFrame.store(tags[frame].timelineFrame, std::memory_order_relaxed);
            slot.outputFrame.store(outputFrame, std::memory_order_relaxed);
            slot.guard.fetch_add(1U, std::memory_order_release);
        }
    }

    [[nodiscard]] NativeAudioAudiblePosition position(
        std::uint64_t outputFrame) const noexcept {
        if (mCapacityFrames == 0U) return {};
        const Slot &slot = mSlots[outputFrame % mCapacityFrames];
        for (std::uint32_t attempt = 0U; attempt < 3U; ++attempt) {
            const std::uint64_t before = slot.guard.load(std::memory_order_acquire);
            if ((before & 1U) != 0U) continue;
            NativeAudioAudiblePosition result{
                .generation = slot.generation.load(std::memory_order_relaxed),
                .selectionToken = slot.selectionToken.load(std::memory_order_relaxed),
                .timelineFrame = slot.timelineFrame.load(std::memory_order_relaxed),
                .outputFrame = slot.outputFrame.load(std::memory_order_relaxed),
            };
            std::atomic_thread_fence(std::memory_order_acquire);
            const std::uint64_t after = slot.guard.load(std::memory_order_relaxed);
            if (before == after && (after & 1U) == 0U &&
                result.outputFrame == outputFrame && result.valid()) {
                return result;
            }
        }
        return {};
    }

private:
    struct Slot final {
        std::atomic<std::uint64_t> guard{0U};
        std::atomic<std::uint64_t> outputFrame{0U};
        std::atomic<std::uint64_t> generation{0U};
        std::atomic<std::uint64_t> selectionToken{0U};
        std::atomic<std::int64_t> timelineFrame{-1};
    };

    const std::size_t mCapacityFrames;
    std::unique_ptr<Slot[]> mSlots;
};

static_assert(std::atomic<std::uint64_t>::is_always_lock_free);
static_assert(std::atomic<std::int64_t>::is_always_lock_free);

}  // namespace stemwise::audio
