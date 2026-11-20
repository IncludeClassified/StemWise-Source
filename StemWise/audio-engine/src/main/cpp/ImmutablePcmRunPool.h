#pragma once

#include "AudioMemoryAdmission.h"

#include <algorithm>
#include <atomic>
#include <cassert>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <memory>
#include <optional>
#include <vector>

namespace stemwise::audio {

/** One producer publishes immutable runs; one consumer returns them after its last frame.
 * Pages never move. New peak occupancy grows storage on the producer, never on the consumer;
 * even one distinct control per queued frame retains the queue's complete frame capacity. */
template <typename Run>
class ImmutablePcmRunPool final {
public:
    [[nodiscard]] static std::size_t storageBytes(std::size_t maximumRuns, bool peak) {
        const auto pages = maximumRuns / kPageRuns + (maximumRuns % kPageRuns != 0U ? 1U : 0U);
        return addAudioMemoryBytes(addAudioMemoryBytes(audioMemoryBytes(maximumRuns, sizeof(std::uint32_t)),
            audioMemoryBytes(pages, sizeof(std::unique_ptr<Run[]>))),
            audioMemoryBytes(peak ? maximumRuns : std::min(maximumRuns, kPageRuns), sizeof(Run)));
    }
    explicit ImmutablePcmRunPool(std::size_t maximumRuns)
        : mMaximumRuns(maximumRuns), mPages((maximumRuns + kPageRuns - 1U) / kPageRuns),
          mReturned(maximumRuns) {
        assert(maximumRuns != 0U && maximumRuns <= std::numeric_limits<std::uint32_t>::max());
        allocatePage(0U);
    }

    [[nodiscard]] std::optional<std::uint32_t> acquire() noexcept {
        const auto returned = mReturnedWrite.load(std::memory_order_acquire);
        const auto read = mReturnedRead.load(std::memory_order_relaxed);
        if (read < returned) {
            const auto index = mReturned[read % mMaximumRuns];
            mReturnedRead.store(read + 1U, std::memory_order_release);
            return index;
        }
        if (mNextUnused == mMaximumRuns) return std::nullopt;
        const auto page = mNextUnused / kPageRuns;
        if (!mPages[page]) {
            try { allocatePage(page); } catch (...) { return std::nullopt; }
        }
        return static_cast<std::uint32_t>(mNextUnused++);
    }

    void release(std::uint32_t index) noexcept {
        const auto write = mReturnedWrite.load(std::memory_order_relaxed);
        assert(index < mMaximumRuns && write - mReturnedRead.load(std::memory_order_acquire) < mMaximumRuns);
        mReturned[write % mMaximumRuns] = index;
        mReturnedWrite.store(write + 1U, std::memory_order_release);
    }

    [[nodiscard]] Run &operator[](std::uint32_t index) noexcept {
        return mPages[index / kPageRuns][index % kPageRuns];
    }
    [[nodiscard]] const Run &operator[](std::uint32_t index) const noexcept {
        return mPages[index / kPageRuns][index % kPageRuns];
    }

    /** No reader/writer may retain an outstanding run during reset. */
    void resetInactive() noexcept {
        mNextUnused = 0U;
        mReturnedRead.store(0U, std::memory_order_relaxed);
        mReturnedWrite.store(0U, std::memory_order_relaxed);
    }

    [[nodiscard]] std::size_t allocatedBytes() const noexcept {
        return mReturned.size() * sizeof(std::uint32_t) + mPages.size() * sizeof(mPages[0]) +
            mAllocatedRuns.load(std::memory_order_relaxed) * sizeof(Run);
    }

private:
    void allocatePage(std::size_t page) {
        const auto count = std::min(kPageRuns, mMaximumRuns - page * kPageRuns);
        mPages[page] = std::make_unique<Run[]>(count);
        mAllocatedRuns.fetch_add(count, std::memory_order_relaxed);
    }
    static constexpr std::size_t kPageRuns = 64U;
    const std::size_t mMaximumRuns;
    std::vector<std::unique_ptr<Run[]>> mPages;
    std::vector<std::uint32_t> mReturned;
    std::size_t mNextUnused{0U};
    std::atomic<std::size_t> mAllocatedRuns{0U};
    alignas(64) std::atomic<std::uint64_t> mReturnedRead{0U};
    alignas(64) std::atomic<std::uint64_t> mReturnedWrite{0U};
};
} // namespace stemwise::audio
