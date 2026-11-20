#if defined(STEMWISE_CACHE_IO_TEST_HOOKS)
#include "CurrentSongPcmCacheTestHooks.h"
#endif
#include "CurrentSongPcmCache.h"
#include "../../GuardedAtomicSnapshot.h"
#if defined(STEMWISE_CACHE_IO_PROFILING)
#include <android/trace.h>
#endif

#include <algorithm>
#include <array>
#include <cerrno>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <limits>
#include <mutex>
#include <new>
#include <stdexcept>
#include <utility>

namespace stemwise::audio::engine {
namespace {


constexpr std::size_t kSamplesPerChunk =
    static_cast<std::size_t>(CurrentSongPcmCache::kChunkFrames) *
    kCurrentSongCacheChannelCount;
constexpr std::size_t kBytesPerFrame =
    kCurrentSongCacheChannelCount * sizeof(float);

[[nodiscard]] constexpr std::uint32_t laneBit(std::size_t lane) noexcept {
    return 1U << static_cast<std::uint32_t>(lane);
}

[[nodiscard]] bool byteRange(
    std::uint64_t firstFrame,
    std::size_t frameCount,
    std::uint64_t &offset,
    std::size_t &byteCount) noexcept {
    if (frameCount > std::numeric_limits<std::size_t>::max() / kBytesPerFrame ||
        firstFrame > static_cast<std::uint64_t>(
            std::numeric_limits<std::int64_t>::max()) / kBytesPerFrame) {
        return false;
    }
    byteCount = frameCount * kBytesPerFrame;
    offset = firstFrame * kBytesPerFrame;
    return byteCount <= static_cast<std::uint64_t>(
        std::numeric_limits<std::int64_t>::max()) - offset;
}

struct LaneStorage final {
    std::array<float, kSamplesPerChunk> fillingSamples{};
    bool hasFillingChunk{false};
    std::uint64_t fillingChunkIndex{0U};
};

// Each admitted raw staging domain can straddle two windows. Reads use only published
// immutable PCM, so seeks/owner rebinds cannot make retained samples stale.
struct LaneReadWindow final {
    static constexpr std::size_t kFrames = 4'096U;
    std::array<float, kFrames * kCurrentSongCacheChannelCount> samples{};
    std::uint64_t firstFrame{0U};
    std::size_t frames{0U};
    std::uint64_t use{0U};
};

struct LaneReadCache final {
    std::array<LaneReadWindow, CurrentSongPcmCache::kReadWindowsPerLane> windows{};
    std::size_t mostRecent{0U};

    [[nodiscard]] std::size_t loadSlot(std::uint64_t first) const noexcept {
        for (std::size_t slot = 0U; slot < windows.size(); ++slot)
            if (windows[slot].frames != 0U && windows[slot].firstFrame == first) return slot;
        return 1U - mostRecent;
    }
};

} // namespace


class CurrentSongPcmCache::Impl final {
    friend class CurrentSongPcmCache;
public:
    explicit Impl(std::string configuredDirectory)
        : store(std::move(configuredDirectory)) {
        for (auto &value : publishedFrames) value.store(0U, std::memory_order_relaxed);
        for (auto &value : nextExpected) value.store(0U, std::memory_order_relaxed);
    }

    [[nodiscard]] CurrentSongCacheStorageStatus admitStorage(
        std::size_t laneCount, std::uint64_t maximumFramesPerLane) noexcept {
        return store.admit(laneCount, maximumFramesPerLane);
    }

    [[nodiscard]] CurrentSongCacheBeginStatus begin(const CurrentSongCacheKey &nextKey) {
        if (!nextKey.valid()) return CurrentSongCacheBeginStatus::InvalidKey;
        if (begun.load(std::memory_order_acquire)) {
            return CurrentSongCacheBeginStatus::AlreadyBegun;
        }
        assignKey(nextKey);
        begun.store(true, std::memory_order_release);
        return CurrentSongCacheBeginStatus::Accepted;
    }

    [[nodiscard]] CurrentSongCacheOfferResult offer(
        const CurrentSongCacheKey &offeredKey,
        std::size_t lane,
        std::uint64_t firstFrame,
        const float *source,
        std::size_t frameCount) noexcept {
        const auto result = [this](
                                CurrentSongCacheOfferStatus status,
                                std::size_t consumed = 0U,
                                std::size_t stored = 0U,
                                std::size_t laneIndex = 0U) {
            return CurrentSongCacheOfferResult{
                status,
                consumed,
                stored,
                laneIndex < kCurrentSongCacheLaneCount
                    ? (status == CurrentSongCacheOfferStatus::AllocationFailed
                        ? publishedFrames[laneIndex].load(std::memory_order_acquire)
                        : nextExpected[laneIndex].load(std::memory_order_relaxed))
                    : 0U,
            };
        };
        if (!begun.load(std::memory_order_acquire)) {
            return result(CurrentSongCacheOfferStatus::NotBegun);
        }
        if (cancelled.load(std::memory_order_acquire)) {
            return result(CurrentSongCacheOfferStatus::Cancelled);
        }
        if (storageFailed.load(std::memory_order_acquire)) {
            return result(CurrentSongCacheOfferStatus::AllocationFailed);
        }
        if (offeredKey != key) return result(CurrentSongCacheOfferStatus::StaleKey);
        if (lane >= kCurrentSongCacheLaneCount) {
            return result(CurrentSongCacheOfferStatus::InvalidLane);
        }
        if (lanes[lane].hasFillingChunk && nextExpected[lane].load() % kChunkFrames == 0U) {
            try { publishPartialChunk(lane); }
            catch (...) { storageFailed.store(true); return result(CurrentSongCacheOfferStatus::AllocationFailed, 0U, 0U, lane); }
        }
        if (source == nullptr || frameCount == 0U ||
            firstFrame > std::numeric_limits<std::uint64_t>::max() - frameCount) {
            return result(CurrentSongCacheOfferStatus::InvalidArgument, 0U, 0U, lane);
        }
        std::uint64_t ignoredOffset = 0U;
        std::size_t ignoredBytes = 0U;
        if (!byteRange(firstFrame, frameCount, ignoredOffset, ignoredBytes)) {
            return result(CurrentSongCacheOfferStatus::InvalidArgument, 0U, 0U, lane);
        }
        if ((completeLaneMask.load(std::memory_order_acquire) & laneBit(lane)) != 0U) {
            return result(CurrentSongCacheOfferStatus::LaneComplete, frameCount, 0U, lane);
        }

        const std::uint64_t expected = nextExpected[lane].load(std::memory_order_relaxed);
        if (firstFrame > expected) {
            return result(CurrentSongCacheOfferStatus::GapRejected, 0U, 0U, lane);
        }
        const std::uint64_t offeredEnd = firstFrame + static_cast<std::uint64_t>(frameCount);
        if (offeredEnd <= expected) {
            return result(CurrentSongCacheOfferStatus::Accepted, frameCount, 0U, lane);
        }
        const std::size_t prefix = static_cast<std::size_t>(expected - firstFrame);
        std::size_t framesToStore = frameCount - prefix;
        const bool originalComplete =
            (completeLaneMask.load(std::memory_order_acquire) & kOriginalLaneMask) != 0U;
        if (lane != 0U) {
            const std::uint64_t end = originalComplete ? originalEnd.load(std::memory_order_acquire)
                : publishedFrames[0U].load(std::memory_order_acquire);
            if (expected >= end) {
                return result(originalComplete ? CurrentSongCacheOfferStatus::Accepted :
                    CurrentSongCacheOfferStatus::OriginalRangePending,
                    originalComplete ? frameCount : prefix, 0U, lane);
            }
            framesToStore = static_cast<std::size_t>(std::min<std::uint64_t>(framesToStore, end - expected));
        }
        for (std::size_t sample = prefix * kCurrentSongCacheChannelCount;
             sample < (prefix + framesToStore) * kCurrentSongCacheChannelCount;
             ++sample) {
            if (!std::isfinite(source[sample])) {
                return result(CurrentSongCacheOfferStatus::NonFinitePcm, 0U, 0U, lane);
            }
        }

        try {
            append(lane, source + prefix * kCurrentSongCacheChannelCount, framesToStore);
        } catch (...) {
            storageFailed.store(true, std::memory_order_release);
            const std::uint64_t published =
                publishedFrames[lane].load(std::memory_order_acquire);
            const auto accepted = nextExpected[lane].load(std::memory_order_relaxed);
            const std::size_t consumed = static_cast<std::size_t>(
                accepted > firstFrame
                    ? std::min<std::uint64_t>(frameCount, accepted - firstFrame)
                    : 0U);
            const std::size_t stored = static_cast<std::size_t>(
                published > expected ? published - expected : 0U);
            return result(
                CurrentSongCacheOfferStatus::AllocationFailed,
                consumed,
                stored,
                lane);
        }
        return result(CurrentSongCacheOfferStatus::Accepted,
            lane == 0U || originalComplete ? frameCount : prefix + framesToStore, framesToStore, lane);
    }

    [[nodiscard]] CurrentSongCacheSealStatus sealLane(
        const CurrentSongCacheKey &sealedKey,
        std::size_t lane,
        std::uint64_t decodedEnd) noexcept {
        if (!begun.load(std::memory_order_acquire)) {
            return CurrentSongCacheSealStatus::NotBegun;
        }
        if (cancelled.load(std::memory_order_acquire)) {
            return CurrentSongCacheSealStatus::Cancelled;
        }
        if (storageFailed.load(std::memory_order_acquire)) {
            return CurrentSongCacheSealStatus::AllocationFailed;
        }
        if (sealedKey != key) return CurrentSongCacheSealStatus::StaleKey;
        if (lane >= kCurrentSongCacheLaneCount) {
            return CurrentSongCacheSealStatus::InvalidLane;
        }
        const std::uint32_t bit = laneBit(lane);
        if ((completeLaneMask.load(std::memory_order_acquire) & bit) != 0U) {
            return CurrentSongCacheSealStatus::AlreadyComplete;
        }
        const std::uint64_t expected = nextExpected[lane].load(std::memory_order_relaxed);
        if (lane == 0U) {
            if (decodedEnd == 0U) return CurrentSongCacheSealStatus::EmptyOriginal;
            if (decodedEnd != expected) return CurrentSongCacheSealStatus::ConflictingEnd;
            originalEnd.store(decodedEnd, std::memory_order_release);
        } else {
            const bool originalComplete =
                (completeLaneMask.load(std::memory_order_acquire) & kOriginalLaneMask) != 0U;
            const std::uint64_t originalLimit = originalComplete
                ? originalEnd.load(std::memory_order_acquire) : publishedFrames[0U].load(std::memory_order_acquire);
            if ((paddingLaneMask & bit) == 0U ?
                    expected != (originalComplete ? std::min(decodedEnd, originalLimit) : decodedEnd)
                    : decodedEnd != paddingDecodedEnds[lane]) {
                return CurrentSongCacheSealStatus::ConflictingEnd;
            }
            paddingLaneMask |= bit;
            paddingDecodedEnds[lane] = decodedEnd;
            try {
                // Publish the last real partial chunk once, padded only after the observed stem EOS.
                // Later short-stem reads synthesize zero inside Original's immutable prefix; final
                // whole-recording padding remains bounded and waits for its actual endpoint.
                const std::uint64_t target = originalComplete ? originalLimit :
                    std::min(originalLimit, expected + (kChunkFrames - expected % kChunkFrames) % kChunkFrames);
                const std::uint64_t missing = target - expected;
                appendSilence(lane, std::min<std::uint64_t>(missing, kChunkFrames));
                if (!originalComplete) {
                    silentSuffixLaneMask.fetch_or(bit, std::memory_order_release);
                    return CurrentSongCacheSealStatus::OriginalPending;
                }
                if (missing > kChunkFrames) return CurrentSongCacheSealStatus::PaddingPending;
            } catch (...) {
                storageFailed.store(true, std::memory_order_release);
                return CurrentSongCacheSealStatus::AllocationFailed;
            }
        }
        try {
            publishPartialChunk(lane);
        } catch (...) {
            storageFailed.store(true, std::memory_order_release);
            return CurrentSongCacheSealStatus::AllocationFailed;
        }
        completeLaneMask.fetch_or(bit, std::memory_order_release);
        return CurrentSongCacheSealStatus::Accepted;
    }

    [[nodiscard]] bool cancel(const CurrentSongCacheKey &cancelledKey) noexcept {
        if (!begun.load(std::memory_order_acquire) || cancelledKey != key) return false;
        residencySequence.fetch_add(1U, std::memory_order_acq_rel);
        cancelled.store(true, std::memory_order_release);
        store.retireLanes(0U);
        completeLaneMask.store(0U, std::memory_order_release);
        silentSuffixLaneMask.store(0U, std::memory_order_release);
        std::lock_guard lock(readCacheMutex);
        for (std::size_t lane = 0U; lane < kCurrentSongCacheLaneCount; ++lane) {
            lanes[lane].hasFillingChunk = false;
            for (auto &window : readCaches[lane].windows) window.frames = 0U;
            publishedFrames[lane].store(0U, std::memory_order_relaxed);
        }
        storedBytes.store(0U, std::memory_order_release);
        residencySequence.fetch_add(1U, std::memory_order_release);
        return true;
    }

    [[nodiscard]] bool rebindOwnership(
        const CurrentSongCacheKey &currentKey,
        const CurrentSongCacheKey &nextKey,
        std::uint32_t requiredLaneMask,
        bool streamingOriginal) noexcept {
        if (!nextKey.valid() || !begun.load(std::memory_order_acquire) ||
            cancelled.load(std::memory_order_acquire) ||
            storageFailed.load(std::memory_order_acquire) ||
            (readFailureLaneMask.load(std::memory_order_acquire) & requiredLaneMask) != 0U || currentKey != key ||
            currentKey.songIdentityHash != nextKey.songIdentityHash ||
            currentKey.originalContentHash != nextKey.originalContentHash ||
            currentKey.stemPackageHash != nextKey.stemPackageHash ||
            requiredLaneMask == 0U || (requiredLaneMask & ~kAllLaneMask) != 0U ||
            (!complete(requiredLaneMask) &&
             (!streamingOriginal || requiredLaneMask != kOriginalLaneMask ||
              publishedFrames[0U].load(std::memory_order_acquire) == 0U))) {
            return false;
        }
        assignKey(nextKey);
        return true;
    }

    [[nodiscard]] bool adoptStemPackageIdentity(
        const CurrentSongCacheKey &currentKey,
        const CurrentSongCacheKey &nextKey) noexcept {
        if (!nextKey.valid() || !begun.load(std::memory_order_acquire) ||
            cancelled.load(std::memory_order_acquire) ||
            (readFailureLaneMask.load(std::memory_order_acquire) & kOriginalLaneMask) != 0U || currentKey != key ||
            currentKey.selectionToken != nextKey.selectionToken ||
            currentKey.generation != nextKey.generation ||
            currentKey.songIdentityHash != nextKey.songIdentityHash ||
            currentKey.originalContentHash != nextKey.originalContentHash ||
            currentKey.stemPackageHash != 0U || nextKey.stemPackageHash == 0U ||
            (storageFailed.load(std::memory_order_acquire) &&
             publishedFrames[0U].load(std::memory_order_acquire) == 0U)) {
            return false;
        }
        assignKey(nextKey);
        return true;
    }

    [[nodiscard]] bool complete(std::uint32_t laneMask) const noexcept {
        return begun.load(std::memory_order_acquire) &&
            !cancelled.load(std::memory_order_acquire) &&
            !storageFailed.load(std::memory_order_acquire) &&
            (readFailureLaneMask.load(std::memory_order_acquire) & laneMask) == 0U && laneMask != 0U &&
            (laneMask & ~kAllLaneMask) == 0U &&
            (completeLaneMask.load(std::memory_order_acquire) & laneMask) == laneMask;
    }

    [[nodiscard]] bool retireStemPackageIdentity(
        const CurrentSongCacheKey &currentKey, const CurrentSongCacheKey &nextKey) noexcept {
        if (!nextKey.valid() || !begun.load(std::memory_order_acquire) ||
            cancelled.load(std::memory_order_acquire) ||
            (readFailureLaneMask.load(std::memory_order_acquire) & kOriginalLaneMask) != 0U || currentKey != key ||
            currentKey.selectionToken != nextKey.selectionToken || currentKey.generation != nextKey.generation ||
            currentKey.songIdentityHash != nextKey.songIdentityHash ||
            currentKey.originalContentHash != nextKey.originalContentHash ||
            nextKey.stemPackageHash != 0U ||
            (storageFailed.load(std::memory_order_acquire) && publishedFrames[0U].load(std::memory_order_acquire) == 0U)) return false;
        completeLaneMask.fetch_and(kOriginalLaneMask, std::memory_order_release);
        residencySequence.fetch_add(1U, std::memory_order_acq_rel);
        silentSuffixLaneMask.store(0U, std::memory_order_release);
        {
        std::lock_guard lock(readCacheMutex);
        for (std::size_t lane = 1U; lane < kCurrentSongCacheLaneCount; ++lane) {
            lanes[lane].hasFillingChunk = false;
            lanes[lane].fillingChunkIndex = 0U;
            for (auto &window : readCaches[lane].windows) window.frames = 0U;
            readCaches[lane].mostRecent = 0U;
            publishedFrames[lane].store(0U, std::memory_order_relaxed);
            nextExpected[lane].store(0U, std::memory_order_relaxed);
            readRequests[lane].store(0U, std::memory_order_relaxed);
            readAheadRequests[lane].store(0U, std::memory_order_relaxed);
            paddingDecodedEnds[lane] = 0U;
        }
        readFailureLaneMask.fetch_and(kOriginalLaneMask, std::memory_order_release);
        paddingLaneMask = 0U;
        assignKey(nextKey);
        residencySequence.fetch_add(1U, std::memory_order_release);
        }
        // Original's committed residency does not depend on optional descriptor destruction.
        store.retireLanes(1U);
        storedBytes.store(store.diagnostics().fileBytes, std::memory_order_release);
        return true;
    }

    [[nodiscard]] std::uint64_t availableFrames(std::size_t lane) const noexcept {
        if (lane >= kCurrentSongCacheLaneCount ||
            !begun.load(std::memory_order_acquire) ||
            cancelled.load(std::memory_order_acquire) ||
            (readFailureLaneMask.load(std::memory_order_acquire) & (laneBit(lane) | kOriginalLaneMask)) != 0U) {
            return 0U;
        }
        const auto published = publishedFrames[lane].load(std::memory_order_acquire);
        if (lane == 0U) return published;
        const auto original = publishedFrames[0U].load(std::memory_order_acquire);
        return (silentSuffixLaneMask.load(std::memory_order_acquire) & laneBit(lane)) != 0U
            ? original : std::min(published, original);
    }

    [[nodiscard]] bool covers(
        std::uint32_t laneMask,
        std::uint64_t firstFrame,
        std::size_t frameCount) const noexcept {
        if (laneMask == 0U || (laneMask & ~kAllLaneMask) != 0U || frameCount == 0U) return false;
        for (std::size_t lane = 0U; lane < kCurrentSongCacheLaneCount; ++lane) {
            if ((laneMask & laneBit(lane)) == 0U) continue;
            const auto available = availableFrames(lane);
            if (firstFrame > available || frameCount > available - firstFrame) return false;
        }
        return true;
    }

    [[nodiscard]] bool copyLane(
        std::size_t lane,
        std::uint64_t firstFrame,
        float *output,
        std::size_t outputCapacityFrames,
        std::size_t frameCount, bool requestMissing = true,
        CurrentSongPcmReadConsumer *consumer = nullptr, std::size_t domain = 0U) const noexcept {
        return lane < kCurrentSongCacheLaneCount &&
            covers(laneBit(lane), firstFrame, frameCount) &&
            copyAvailableLane(lane, firstFrame, output, outputCapacityFrames, frameCount, requestMissing, consumer, domain);
    }

    [[nodiscard]] bool copyAvailableLane(
        std::size_t lane,
        std::uint64_t firstFrame,
        float *output,
        std::size_t outputCapacityFrames,
        std::size_t frameCount, bool requestMissing = true,
        CurrentSongPcmReadConsumer *consumer = nullptr, std::size_t domain = 0U) const noexcept {
        if (lane >= kCurrentSongCacheLaneCount || output == nullptr || frameCount == 0U ||
            outputCapacityFrames < frameCount) return false;
        const std::uint64_t available = availableFrames(lane);
        if (firstFrame > available || frameCount > available - firstFrame) return false;
        const auto stored = publishedFrames[lane].load(std::memory_order_acquire);
        const auto real = static_cast<std::size_t>(firstFrame < stored
            ? std::min<std::uint64_t>(frameCount, stored - firstFrame) : 0U);
        if (real != 0U && !copyPublished(lane, firstFrame, output, outputCapacityFrames, real, requestMissing, consumer, domain)) return false;
        if (real != frameCount) {
            if ((silentSuffixLaneMask.load(std::memory_order_acquire) & laneBit(lane)) == 0U) return false;
            std::fill_n(output + real * kCurrentSongCacheChannelCount,
                (frameCount - real) * kCurrentSongCacheChannelCount, 0.0F);
        }
        return true;
    }

    [[nodiscard]] CurrentSongCacheSnapshot snapshot() const noexcept {
        const auto unreadable = readFailureLaneMask.load(std::memory_order_acquire);
        const bool failed = storageFailed.load(std::memory_order_acquire) ||
            (unreadable & kOriginalLaneMask) != 0U;
        CurrentSongCacheSnapshot value{
            .begun = begun.load(std::memory_order_acquire),
            .cancelled = cancelled.load(std::memory_order_acquire),
            .allocationFailed = failed,
            .readFailureLaneMask = unreadable,
            .key = snapshotKey(),
            .originalEndFrameExclusive = originalEnd.load(std::memory_order_acquire),
            .completeLaneMask = failed
                ? 0U
                : completeLaneMask.load(std::memory_order_acquire) & ~unreadable,
        };
        for (std::size_t lane = 0U; lane < kCurrentSongCacheLaneCount; ++lane) {
            value.publishedFrames[lane] =
                publishedFrames[lane].load(std::memory_order_acquire);
            value.nextExpectedFrames[lane] =
                nextExpected[lane].load(std::memory_order_acquire);
        }
        value.storedBytes = storedBytes.load(std::memory_order_acquire);
        value.diskReadOperations = diskReadOperations.load(std::memory_order_relaxed);
        value.readCacheHits = readCacheHits.load(std::memory_order_relaxed);
        value.storageStatus = store.status();
        value.io = store.diagnostics();
        value.fileBytes = value.io.fileBytes;
        if (failed && (value.storageStatus == CurrentSongCacheStorageStatus::Admitted ||
                value.storageStatus == CurrentSongCacheStorageStatus::NotRequested)) {
            value.storageStatus = CurrentSongCacheStorageStatus::IoFailure;
        }
        return value;
    }

    [[nodiscard]] CurrentSongCacheUnpublishedPcm unpublishedOriginal(
        const CurrentSongCacheKey &expectedKey) const noexcept {
        if (!storageFailed.load(std::memory_order_acquire) || cancelled.load(std::memory_order_acquire) ||
            expectedKey != key || !lanes[0U].hasFillingChunk) return {};
        const auto first = publishedFrames[0U].load(std::memory_order_acquire);
        const auto end = nextExpected[0U].load(std::memory_order_relaxed);
        if (end <= first || end - first > kChunkFrames ||
            lanes[0U].fillingChunkIndex * kChunkFrames != first) return {};
        return {first, static_cast<std::size_t>(end - first), lanes[0U].fillingSamples.data()};
    }

private:
    void append(std::size_t lane, const float *source, std::size_t frames) {
        LaneStorage &storage = lanes[lane];
        std::uint64_t next = nextExpected[lane].load(std::memory_order_relaxed);
        const auto end = frames > std::numeric_limits<std::uint64_t>::max() - next
            ? std::numeric_limits<std::uint64_t>::max() : next + frames;
        if (!store.validateExtent(lane, end)) {
            throw std::bad_alloc();
        }
        std::size_t sourceOffset = 0U;
        while (frames != 0U) {
            const std::uint64_t chunkIndex = next / kChunkFrames;
            const std::size_t offset = static_cast<std::size_t>(next % kChunkFrames);
            if (storage.hasFillingChunk && storage.fillingChunkIndex != chunkIndex) throw std::bad_alloc();
            if (!storage.hasFillingChunk) {
                storage.hasFillingChunk = true;
                storage.fillingChunkIndex = chunkIndex;
            }
            const std::size_t copied = std::min<std::size_t>(frames, kChunkFrames - offset);
            std::copy_n(
                source + sourceOffset * kCurrentSongCacheChannelCount,
                copied * kCurrentSongCacheChannelCount,
                storage.fillingSamples.data() + offset * kCurrentSongCacheChannelCount);
            next += copied;
            sourceOffset += copied;
            frames -= copied;
            nextExpected[lane].store(next, std::memory_order_relaxed);
            if (next % kChunkFrames == 0U) publishPartialChunk(lane);
        }
    }

    void appendSilence(std::size_t lane, std::uint64_t frames) {
        static constexpr std::array<float, kSamplesPerChunk> kSilence{};
        while (frames != 0U) {
            const std::size_t written = static_cast<std::size_t>(std::min<std::uint64_t>(
                frames,
                kChunkFrames));
            append(lane, kSilence.data(), written);
            frames -= written;
        }
    }

    void publishPartialChunk(std::size_t lane) {
        LaneStorage &storage = lanes[lane];
        if (!storage.hasFillingChunk) return;
        const std::uint64_t firstFrame = storage.fillingChunkIndex * kChunkFrames;
        const std::uint64_t next = nextExpected[lane].load(std::memory_order_relaxed);
        if (next <= firstFrame || next - firstFrame > kChunkFrames) throw std::bad_alloc();
        const std::size_t frames = static_cast<std::size_t>(next - firstFrame);
        std::uint64_t byteOffset = 0U;
        std::size_t byteCount = 0U;
        if (!byteRange(firstFrame, frames, byteOffset, byteCount)) throw std::bad_alloc();
        const auto published = publishedFrames[lane].load(std::memory_order_acquire);
        if (published > next || published < firstFrame ||
            !store.append(lane, published, storage.fillingSamples.data() + (published - firstFrame) * kCurrentSongCacheChannelCount,
                static_cast<std::size_t>(next - published))) throw std::bad_alloc();
        storedBytes.store(store.diagnostics().fileBytes, std::memory_order_release);
        storage.hasFillingChunk = false;
        publishedFrames[lane].store(next, std::memory_order_release);
    }

    bool readResident(std::size_t lane, std::uint64_t first, float *samples, std::size_t frames) const noexcept {
        if (!store.read(lane, first, samples, frames)) {
            readFailureLaneMask.fetch_or(laneBit(lane), std::memory_order_release);
            return false;
        }
        return true;
    }

    struct ResidentReadWindow final {
        std::uint64_t first;
        std::size_t frames;
    };

    [[nodiscard]] ResidentReadWindow residentReadWindow(
        std::uint64_t requested, std::uint64_t available) const noexcept {
        const auto canonical = requested / LaneReadWindow::kFrames * LaneReadWindow::kFrames;
        const auto canonicalEnd = canonical + std::min<std::uint64_t>(
            LaneReadWindow::kFrames, available - canonical);
        return {canonical, static_cast<std::size_t>(canonicalEnd - canonical)};
    }

    [[nodiscard]] bool copyPublished(
        std::size_t lane,
        std::uint64_t firstFrame,
        float *output,
        std::size_t outputCapacityFrames,
        std::size_t frameCount, bool requestMissing = true,
        CurrentSongPcmReadConsumer *consumer = nullptr, std::size_t domain = 0U) const noexcept {
        if (output == nullptr || outputCapacityFrames < frameCount || frameCount == 0U) {
            return false;
        }
        if (asyncReadWake) return copyReadWindows(lane, firstFrame, output, frameCount, requestMissing, consumer, domain);
        if (consumer) {
            std::unique_lock lock(readCacheMutex, std::try_to_lock);
            if (!lock.owns_lock() || !validConsumer(*consumer)) return false;
        }
        const std::uint64_t available = publishedFrames[lane].load(std::memory_order_acquire);
        if (firstFrame > available || frameCount > available - firstFrame) {
            return false;
        }
        auto &cache = readCaches[lane];
        while (frameCount != 0U) {
            std::size_t slot = cache.windows.size();
            for (std::size_t index = 0U; index < cache.windows.size(); ++index) {
                const auto &window = cache.windows[index];
                if (firstFrame >= window.firstFrame && firstFrame - window.firstFrame < window.frames) {
                    slot = index;
                    break;
                }
            }
            if (slot == cache.windows.size()) {
                const auto range = residentReadWindow(firstFrame, available);
                slot = cache.loadSlot(range.first);
                auto &window = cache.windows[slot];
                window.frames = 0U;
                window.firstFrame = range.first;
                const auto count = range.frames;
                if (count == 0U) return false;
                if (!readResident(lane, window.firstFrame, window.samples.data(), count)) {
                    return false;
                }
                diskReadOperations.fetch_add(1U, std::memory_order_relaxed);
                window.frames = count;
            } else {
                readCacheHits.fetch_add(1U, std::memory_order_relaxed);
            }
            cache.mostRecent = slot;
            const auto &window = cache.windows[slot];
            const auto offset = static_cast<std::size_t>(firstFrame - window.firstFrame);
            const auto count = std::min(frameCount, window.frames - offset);
            std::copy_n(window.samples.data() + offset * kCurrentSongCacheChannelCount,
                count * kCurrentSongCacheChannelCount, output);
            firstFrame += count;
            frameCount -= count;
            output += count * kCurrentSongCacheChannelCount;
        }
        return true;
    }

    struct ReadRegistration final {
        std::shared_ptr<CurrentSongPcmReadConsumer> reader{};
        CurrentSongCacheKey key{};
        std::array<std::array<CurrentSongPcmReadConsumer::Range, kCurrentSongCacheLaneCount>, CurrentSongPcmReadConsumer::kDomains> required{};
        std::array<std::uint64_t, kCurrentSongCacheLaneCount> ahead{};
    };

    bool retireInactiveReaders() const noexcept {
        bool retired = false;
        for (auto &slot : consumers)
            if (slot.reader && (!slot.reader->active.load(std::memory_order_acquire) ||
                slot.reader->cache.load(std::memory_order_acquire) != this)) {
                slot.reader.reset();
                retired = true;
            }
        return retired;
    }

    ReadRegistration *validConsumer(CurrentSongPcmReadConsumer &reader) const noexcept {
        const auto owner = snapshotKey();
        if (!reader.active.load(std::memory_order_acquire) || reader.cache.load(std::memory_order_acquire) != this ||
            !owner.valid()) return nullptr;
        ReadRegistration *registration = nullptr;
        for (auto &slot : consumers) if (slot.reader.get() == &reader) registration = &slot;
        if (!registration || registration->key.selectionToken != owner.selectionToken ||
            registration->key.generation != owner.generation || registration->key.songIdentityHash != owner.songIdentityHash ||
            registration->key.originalContentHash != owner.originalContentHash) return nullptr;
        if (registration->key.stemPackageHash != owner.stemPackageHash) {
            for (auto &domain : registration->required)
                for (std::size_t lane = 1U; lane < domain.size(); ++lane) domain[lane] = {};
            for (std::size_t lane = 1U; lane < registration->ahead.size(); ++lane) registration->ahead[lane] = 0U;
            registration->key = owner;
        }
        return registration;
    }

    bool protectedWindow(std::size_t lane, const LaneReadWindow &window) const noexcept {
        const auto owner = snapshotKey();
        for (const auto &slot : consumers) {
            if (!slot.reader || !slot.reader->active.load(std::memory_order_acquire) || slot.reader->cache.load(std::memory_order_acquire) != this ||
                slot.key.selectionToken != owner.selectionToken || slot.key.generation != owner.generation ||
                slot.key.songIdentityHash != owner.songIdentityHash ||
                slot.key.originalContentHash != owner.originalContentHash ||
                (lane != 0U && slot.key.stemPackageHash != owner.stemPackageHash)) continue;
            // An unpublished replacement cannot revoke the accepted pin or reserve unrelated
            // windows. Its own copies remain blocked until its publication is retried.
            for (const auto &domain : slot.required) {
                const auto range = domain[lane];
                if (range.end > range.first && range.first < window.firstFrame + window.frames &&
                    window.firstFrame < range.end) return true;
            }
        }
        return false;
    }

    // Caller owns readCacheMutex. Residency is separate from the committed file extent.
    bool readWindowsCover(std::size_t lane, std::uint64_t cursor, std::size_t frames) const noexcept {
        while (frames != 0U) {
            std::size_t covered{};
            for (const auto &window : readCaches[lane].windows)
                if (cursor >= window.firstFrame && cursor - window.firstFrame < window.frames)
                    covered = std::max(covered, std::min(frames,
                        window.frames - static_cast<std::size_t>(cursor - window.firstFrame)));
            if (covered == 0U) return false;
            cursor += covered;
            frames -= covered;
        }
        return true;
    }

    bool requiredReadCanProgress(std::size_t lane, std::uint64_t first, std::size_t frames) const noexcept {
        if (cancelled.load(std::memory_order_acquire) ||
            (readFailureLaneMask.load(std::memory_order_acquire) & (laneBit(lane) | kOriginalLaneMask)) != 0U) return false;
        const auto available = publishedFrames[lane].load(std::memory_order_acquire);
        const auto count = static_cast<std::size_t>(first < available ?
            std::min<std::uint64_t>(frames, available - first) : 0U);
        return count != 0U && !readWindowsCover(lane, first, count);
    }

    bool copyReadWindows(std::size_t lane, std::uint64_t firstFrame, float *output, std::size_t frames,
        bool requestMissing, CurrentSongPcmReadConsumer *reader = nullptr, std::size_t domain = 0U) const noexcept {
        std::unique_lock lock(readCacheMutex, std::defer_lock);
#if defined(STEMWISE_CACHE_IO_TEST_HOOKS)
        const auto defer = cache_test::deferReadCopy.load();
        if (!defer || !defer(reader, lane, firstFrame, frames, domain))
#endif
            static_cast<void>(lock.try_lock());
        if (!lock.owns_lock()) {
            // The bytes may already be resident. Ask the I/O owner for a coordinator retry
            // rather than waiting for a file read or producer append that may never occur.
            if (asyncReadWake) {
                readerRetry.store(true, std::memory_order_release);
                asyncReadWake(asyncReadContext);
            }
            return false;
        }
        auto *registration = reader ? validConsumer(*reader) : nullptr;
        if (reader) {
            if (domain >= CurrentSongPcmReadConsumer::kDomains || frames > LaneReadWindow::kFrames || !registration ||
                reader->pendingDomains.load(std::memory_order_acquire) != 0U) {
                return false;
            }
            if (requestMissing) registration->required[domain][lane] = {firstFrame, firstFrame + frames};
        }
        auto &cache = readCaches[lane];
        const auto slots = reader ? cache.windows.size() : 2U;
        while (frames != 0U) {
            std::size_t slot = cache.windows.size();
            for (std::size_t index = 0U; index < slots; ++index) {
                const auto &window = cache.windows[index];
                if (firstFrame >= window.firstFrame && firstFrame - window.firstFrame < window.frames) { slot = index; break; }
            }
            if (slot == cache.windows.size()) {
                // Optional buses may warm a resident window, but cannot authorize codec replay.
                if (!reader) {
                    auto &requests = requestMissing ? readRequests : readAheadRequests;
                    requests[lane].store(firstFrame / kChunkFrames * kChunkFrames + 1U, std::memory_order_release);
                } else if (!requestMissing) {
                    registration->ahead[lane] = firstFrame / kChunkFrames * kChunkFrames + 1U;
                }
                asyncReadWake(asyncReadContext);
                return false;
            }
            if (!reader) cache.mostRecent = slot;
            if (readUse != std::numeric_limits<std::uint64_t>::max()) ++readUse;
            cache.windows[slot].use = readUse;
            const auto &window = cache.windows[slot];
            const auto offset = static_cast<std::size_t>(firstFrame - window.firstFrame);
            const auto count = std::min(frames, window.frames - offset);
            std::copy_n(window.samples.data() + offset * kCurrentSongCacheChannelCount,
                count * kCurrentSongCacheChannelCount, output);
            readCacheHits.fetch_add(1U, std::memory_order_relaxed);
            firstFrame += count; frames -= count; output += count * kCurrentSongCacheChannelCount;
        }
        // Speculate beyond the copied range without changing PCM authority.
        const auto next = firstFrame / LaneReadWindow::kFrames * LaneReadWindow::kFrames + LaneReadWindow::kFrames;
        if (next < publishedFrames[lane].load(std::memory_order_acquire)) {
            // Missing PCM is authoritative demand. Another pipeline can still read a resident
            // window while a handoff is staged; its speculative read ahead cannot erase that
            // pending miss and leave both readers repeatedly waking the I/O worker without PCM.
            if (reader) {
                if (registration->ahead[lane] == 0U) registration->ahead[lane] = next + 1U;
                asyncReadWake(asyncReadContext);
            } else {
                std::uint64_t empty = 0U;
                if (readAheadRequests[lane].compare_exchange_strong(empty, next + 1U,
                        std::memory_order_release, std::memory_order_relaxed)) asyncReadWake(asyncReadContext);
            }
        }
        return true;
    }

    bool admitReadConsumer(const std::shared_ptr<CurrentSongPcmReadConsumer> &reader,
        std::uint64_t token, std::uint64_t generation) const noexcept {
        if (!reader) return false;
        std::unique_lock lock(readCacheMutex, std::try_to_lock);
        if (!lock.owns_lock()) {
            if (asyncReadWake) {
                readerRetry.store(true, std::memory_order_release);
                asyncReadWake(asyncReadContext);
            }
            return false;
        }
        const auto owner = snapshotKey();
        if (!owner.valid() || owner.selectionToken != token || owner.generation != generation ||
            cancelled.load(std::memory_order_acquire)) return false;
#if defined(STEMWISE_CACHE_IO_TEST_HOOKS)
        if (cache_test::denyReadConsumerGeneration.load() == generation) {
            cache_test::readConsumerAdmissionDenials.fetch_add(1U);
            return false;
        }
#endif
        // Standalone synchronous callers already own direct file I/O and reclamation.
        // Runtime enables async reads before publication; its render caller never drops readers.
        if (!asyncReadWake) retireInactiveReaders();
        auto *target = &consumers[0U];
        bool found = false;
        for (auto &slot : consumers) {
            if (slot.reader.get() == reader.get()) { target = &slot; found = true; break; }
            if (!slot.reader) { target = &slot; found = true; }
        }
        if (!found) { if (asyncReadWake) asyncReadWake(asyncReadContext); return false; }
        if (target->reader && target->key == owner && reader->active.load(std::memory_order_acquire) &&
            reader->cache.load(std::memory_order_acquire) == this) return true;
        if (!target->reader) target->reader = reader;
        target->key = owner; target->required = {}; target->ahead = {};
        reader->cache.store(this, std::memory_order_release);
        reader->pendingDomains.store(0U, std::memory_order_release);
        reader->active.store(true, std::memory_order_release);
        return true;
    }

    void requireReadRange(CurrentSongPcmReadConsumer &reader, std::size_t domain,
        std::uint32_t mask, std::uint64_t first, std::size_t frames) const noexcept {
        if (domain >= CurrentSongPcmReadConsumer::kDomains || frames > LaneReadWindow::kFrames ||
            first > std::numeric_limits<std::uint64_t>::max() - frames) return;
        reader.pendingDomains.fetch_or(1U << domain, std::memory_order_release);
#if defined(STEMWISE_CACHE_IO_TEST_HOOKS)
        if (auto defer = cache_test::deferReadRequirement.load(); defer && defer(reader, domain, first, frames)) {
            if (asyncReadWake) asyncReadWake(asyncReadContext);
            return;
        }
#endif
        std::unique_lock lock(readCacheMutex, std::try_to_lock);
        auto *registration = lock.owns_lock() ? validConsumer(reader) : nullptr;
        if (!registration) {
            if (asyncReadWake) asyncReadWake(asyncReadContext);
            return;
        }
        bool needsIo = false;
        for (std::size_t lane = 0U; lane < kCurrentSongCacheLaneCount; ++lane) {
            registration->required[domain][lane] = (mask & laneBit(lane)) != 0U
                ? CurrentSongPcmReadConsumer::Range{first, first + frames} : CurrentSongPcmReadConsumer::Range{};
            if ((mask & laneBit(lane)) != 0U && requiredReadCanProgress(lane, first, frames)) needsIo = true;
        }
        reader.pendingDomains.fetch_and(~(1U << domain), std::memory_order_release);
        // Append completion wakes render when more file bytes become readable. Repeated
        // publication of resident or not-yet-committed input must not poll the I/O owner.
        if (needsIo && asyncReadWake) asyncReadWake(asyncReadContext);
    }

    bool serviceAsyncReads() noexcept {
        const auto failedBefore = readFailureLaneMask.load(std::memory_order_acquire);
        bool progressed = readerRetry.exchange(false, std::memory_order_acq_rel);
        std::array<float, LaneReadWindow::kFrames * kCurrentSongCacheChannelCount> samples{};
        std::array<ReadRegistration *, kMaximumReadConsumers> readers{};
        {
            std::lock_guard lock(readCacheMutex);
            // Only this I/O owner may drop the cache's reference and destroy a retired reader.
            progressed = retireInactiveReaders() || progressed;
            for (std::size_t i = 0U; i < consumers.size(); ++i) {
                readers[i] = &consumers[i];
                // A nonblocking publisher that lost this mutex needs another coordinator turn,
                // even when every previously published required window is already resident.
                if (auto &reader = consumers[i].reader; reader && validConsumer(*reader) &&
                    reader->pendingDomains.load(std::memory_order_acquire) != 0U) progressed = true;
            }
        }
        const auto load = [&](std::size_t lane, std::uint64_t requested, bool legacy) {
            if (cancelled.load(std::memory_order_acquire) ||
                (readFailureLaneMask.load(std::memory_order_acquire) & (laneBit(lane) | kOriginalLaneMask)) != 0U) return false;
            const auto available = publishedFrames[lane].load(std::memory_order_acquire);
            if (requested >= available) return false;
            const auto range = residentReadWindow(requested, available);
            const auto first = range.first;
            const auto frames = range.frames;
            if (frames == 0U) return false;
            const auto victim = [&]() {
                auto &cache = readCaches[lane];
                const auto slots = legacy ? 2U : cache.windows.size();
                std::size_t selected = cache.windows.size();
                for (std::size_t i = 0U; i < slots; ++i) {
                    const auto &window = cache.windows[i];
                    if (window.firstFrame == first && window.frames != 0U) return i;
                    if (protectedWindow(lane, window)) continue;
                    if (selected == cache.windows.size() || window.frames == 0U ||
                        (legacy ? i == 1U - cache.mostRecent : window.use < cache.windows[selected].use)) selected = i;
                    if (window.frames == 0U) break;
                }
                return selected;
            };
            {
                std::lock_guard lock(readCacheMutex);
                const auto &windows = readCaches[lane].windows;
                if (std::any_of(windows.begin(), windows.begin() + (legacy ? 2U : windows.size()), [&](const auto &window) {
                        return window.firstFrame == first && window.frames >= frames;
                    }) || victim() == windows.size()) return false;
            }
            if (!readResident(lane, first, samples.data(), frames)) return false;
            diskReadOperations.fetch_add(1U, std::memory_order_relaxed);
            std::lock_guard lock(readCacheMutex);
            auto &cache = readCaches[lane];
            const auto selected = victim();
            if (selected == cache.windows.size()) return false;
            auto &window = cache.windows[selected];
            std::copy_n(samples.data(), frames * kCurrentSongCacheChannelCount, window.samples.data());
            window.firstFrame = first;
            window.frames = frames;
            if (readUse != std::numeric_limits<std::uint64_t>::max()) ++readUse;
            window.use = readUse;
            return true;
        };
        // Each registered-reader service unit performs at most one 32 KiB read.
        for (const bool preparation : {false, true}) for (auto *slot : readers) {
            { std::lock_guard lock(readCacheMutex);
                if (!slot->reader || slot->reader->preparation.load(std::memory_order_relaxed) != preparation) continue; }
            for (std::size_t domain = 0U; domain < CurrentSongPcmReadConsumer::kDomains; ++domain)
                for (std::size_t lane = 0U; lane < kCurrentSongCacheLaneCount; ++lane) {
                    CurrentSongPcmReadConsumer::Range required{};
                    { std::lock_guard lock(readCacheMutex);
                        if (slot->reader) if (auto *valid = validConsumer(*slot->reader)) required = valid->required[domain][lane]; }
                    for (auto first = required.first; first < required.end;) {
                        if (load(lane, first, false)) { asyncReadWake(asyncReadContext); return true; }
                        const auto next = first / LaneReadWindow::kFrames * LaneReadWindow::kFrames + LaneReadWindow::kFrames;
                        if (next <= first) break;
                        first = next;
                    }
                }
        }
        for (std::size_t lane = 0U; lane < kCurrentSongCacheLaneCount; ++lane) {
            const auto demand = readRequests[lane].exchange(0U, std::memory_order_acq_rel);
            const auto ahead = readAheadRequests[lane].exchange(0U, std::memory_order_acq_rel);
            const auto encoded = demand != 0U ? demand : ahead;
            if (encoded != 0U && load(lane, encoded - 1U, true)) {
                if (asyncReadWake) asyncReadWake(asyncReadContext);
                return true;
            }
        }
        for (auto *slot : readers) {
            for (std::size_t lane = 0U; lane < kCurrentSongCacheLaneCount; ++lane) {
                std::uint64_t ahead{};
                { std::lock_guard lock(readCacheMutex);
                    if (slot->reader) if (auto *valid = validConsumer(*slot->reader)) ahead = std::exchange(valid->ahead[lane], 0U); }
                if (ahead != 0U && load(lane, ahead - 1U, false)) { asyncReadWake(asyncReadContext); return true; }
            }
        }
        return progressed || (failedBefore != readFailureLaneMask.load(std::memory_order_acquire));
    }

    void assignKey(const CurrentSongCacheKey &next) noexcept {
        key = next; // Serialized I/O owner; const render snapshots read only the atomic projection.
        beginGuardedPublication(keySequence);
        const std::array values{next.selectionToken, next.generation, next.songIdentityHash,
            next.originalContentHash, next.stemPackageHash};
        for (std::size_t index = 0U; index < values.size(); ++index)
            publishedKey[index].store(values[index], std::memory_order_relaxed);
        keySequence.fetch_add(1U, std::memory_order_release);
    }
    CurrentSongCacheKey snapshotKey() const noexcept {
        const auto sequence = keySequence.load(std::memory_order_acquire);
        if ((sequence & 1U) != 0U) return {};
        CurrentSongCacheKey result{publishedKey[0U].load(std::memory_order_relaxed),
            publishedKey[1U].load(std::memory_order_relaxed), publishedKey[2U].load(std::memory_order_relaxed),
            publishedKey[3U].load(std::memory_order_relaxed), publishedKey[4U].load(std::memory_order_relaxed)};
        // A concurrent identity mutation yields no ownership claim; the render worker retries.
        std::atomic_thread_fence(std::memory_order_acquire);
        return keySequence.load(std::memory_order_acquire) == sequence ? result : CurrentSongCacheKey{};
    }

    CanonicalPcmStore store;
    std::atomic<bool> begun{false};
    CurrentSongCacheKey key{};
    std::atomic<std::uint64_t> keySequence{};
    std::array<std::atomic<std::uint64_t>, 5U> publishedKey{};
    std::atomic<bool> cancelled{false};
    std::atomic<bool> storageFailed{false};
    mutable std::atomic<std::uint32_t> readFailureLaneMask{0U};
    std::atomic<std::uint32_t> completeLaneMask{0U};
    std::atomic<std::uint64_t> originalEnd{0U};
    std::array<std::atomic<std::uint64_t>, kCurrentSongCacheLaneCount> publishedFrames{};
    std::array<std::atomic<std::uint64_t>, kCurrentSongCacheLaneCount> nextExpected{};
    std::atomic<std::uint32_t> silentSuffixLaneMask{0U};
    std::uint32_t paddingLaneMask{0U};
    std::array<std::uint64_t, kCurrentSongCacheLaneCount> paddingDecodedEnds{};
    std::atomic<std::uint64_t> storedBytes{0U};
    std::array<LaneStorage, kCurrentSongCacheLaneCount> lanes{};
    mutable std::array<LaneReadCache, kCurrentSongCacheLaneCount> readCaches{};
    mutable std::array<ReadRegistration, kMaximumReadConsumers> consumers{};
    mutable std::atomic<bool> readerRetry{false};
    mutable std::uint64_t readUse{};
    mutable std::mutex readCacheMutex;
    mutable std::array<std::atomic<std::uint64_t>, kCurrentSongCacheLaneCount> readRequests{};
    mutable std::array<std::atomic<std::uint64_t>, kCurrentSongCacheLaneCount> readAheadRequests{};
    AsyncReadWake asyncReadWake{};
    void *asyncReadContext{};
    mutable std::atomic<std::uint64_t> diskReadOperations{0U};
    mutable std::atomic<std::uint64_t> readCacheHits{0U};
    std::atomic<std::uint64_t> residencySequence{};
};

CurrentSongPcmCache::CurrentSongPcmCache(std::string cacheDirectory)
    : mImpl(std::make_unique<Impl>(std::move(cacheDirectory))) {}

CurrentSongPcmCache::~CurrentSongPcmCache() = default;

void CurrentSongPcmCache::enableAsyncReads(AsyncReadWake wake, void *context) noexcept {
    mImpl->asyncReadWake = wake;
    mImpl->asyncReadContext = context;
}

bool CurrentSongPcmCache::readWindowRangeReady(std::uint32_t mask,
    std::uint64_t first, std::size_t count) const noexcept {
    if (!committedRange(mask, first, count)) return false;
    if (!mImpl->asyncReadWake) return true; // Standalone synchronous consumer has direct I/O authority.
    std::unique_lock lock(mImpl->readCacheMutex, std::try_to_lock);
    if (!lock.owns_lock()) return false;
    for (std::size_t lane = 0U; lane < kCurrentSongCacheLaneCount; ++lane) {
        if ((mask & laneBit(lane)) == 0U) continue;
        const auto published = mImpl->publishedFrames[lane].load(std::memory_order_acquire);
        const auto physicalFrames = static_cast<std::size_t>(first < published ?
            std::min<std::uint64_t>(count, published - first) : 0U);
        if (!mImpl->readWindowsCover(lane, first, physicalFrames)) return false;
    }
    return true;
}

bool CurrentSongPcmCache::serviceAsyncReads() noexcept { return mImpl->serviceAsyncReads(); }

bool CurrentSongPcmCache::readForAudit(const CurrentSongCacheKey &key, std::size_t lane,
    std::uint64_t firstFrame, float *samples, std::size_t frames) noexcept {
    if (key != mImpl->key || lane >= kCurrentSongCacheLaneCount || !samples || frames == 0U || frames > LaneReadWindow::kFrames ||
        firstFrame > mImpl->availableFrames(lane) || frames > mImpl->availableFrames(lane) - firstFrame) return false;
    const auto stored = mImpl->publishedFrames[lane].load(std::memory_order_acquire);
    const auto real = static_cast<std::size_t>(firstFrame < stored ? std::min<std::uint64_t>(frames, stored - firstFrame) : 0U);
    if (real == 0U || mImpl->readResident(lane, firstFrame, samples, real)) {
        if (real < frames) {
            if ((mImpl->silentSuffixLaneMask.load(std::memory_order_acquire) & laneBit(lane)) == 0U) return false;
            std::fill_n(samples + real * 2U, (frames - real) * 2U, 0.0F);
        }
        return true;
    }
    return false;
}

CurrentSongCacheBeginStatus CurrentSongPcmCache::begin(const CurrentSongCacheKey &key) {
    return mImpl->begin(key);
}

CurrentSongCacheStorageStatus CurrentSongPcmCache::admitStorage(
    std::size_t laneCount, std::uint64_t maximumFramesPerLane) noexcept {
    return mImpl->admitStorage(laneCount, maximumFramesPerLane);
}

CurrentSongCacheOfferResult CurrentSongPcmCache::offer(
    const CurrentSongCacheKey &key,
    std::size_t lane,
    std::uint64_t firstFrame,
    const float *interleavedStereo,
    std::size_t frameCount) noexcept {
    return mImpl->offer(key, lane, firstFrame, interleavedStereo, frameCount);
}

CurrentSongCacheSealStatus CurrentSongPcmCache::sealLane(
    const CurrentSongCacheKey &key,
    std::size_t lane,
    std::uint64_t decodedEndFrameExclusive) noexcept {
    return mImpl->sealLane(key, lane, decodedEndFrameExclusive);
}

bool CurrentSongPcmCache::cancel(const CurrentSongCacheKey &key) noexcept {
    return mImpl->cancel(key);
}

bool CurrentSongPcmCache::rebindCompleteOwnership(
    const CurrentSongCacheKey &currentKey,
    const CurrentSongCacheKey &nextKey,
    std::uint32_t requiredLaneMask) noexcept {
    return mImpl->rebindOwnership(currentKey, nextKey, requiredLaneMask, false);
}

bool CurrentSongPcmCache::rebindStreamingOriginalOwnership(
    const CurrentSongCacheKey &currentKey,
    const CurrentSongCacheKey &nextKey) noexcept {
    return mImpl->rebindOwnership(currentKey, nextKey, kOriginalLaneMask, true);
}

bool CurrentSongPcmCache::adoptStemPackageIdentity(
    const CurrentSongCacheKey &currentKey,
    const CurrentSongCacheKey &nextKey) noexcept {
    return mImpl->adoptStemPackageIdentity(currentKey, nextKey);
}

bool CurrentSongPcmCache::retireStemPackageIdentity(
    const CurrentSongCacheKey &currentKey, const CurrentSongCacheKey &nextKey) noexcept {
    return mImpl->retireStemPackageIdentity(currentKey, nextKey);
}

bool CurrentSongPcmCache::copyLane(
    std::size_t lane,
    std::uint64_t firstFrame,
    float *interleavedStereoOutput,
    std::size_t outputCapacityFrames,
    std::size_t frameCount, bool requestMissing,
    CurrentSongPcmReadConsumer *consumer, std::size_t domain) const noexcept {
    const bool copied = mImpl->copyLane(
        lane,
        firstFrame,
        interleavedStereoOutput,
        outputCapacityFrames,
        frameCount, requestMissing, consumer, domain);
#if defined(STEMWISE_CACHE_IO_TEST_HOOKS)
    if (auto observe = cache_test::afterReadCopy.load()) observe(consumer, copied);
#endif
    return copied;
}

bool CurrentSongPcmCache::copyAvailableLane(
    std::size_t lane,
    std::uint64_t firstFrame,
    float *interleavedStereoOutput,
    std::size_t outputCapacityFrames,
    std::size_t frameCount, bool requestMissing,
    CurrentSongPcmReadConsumer *consumer, std::size_t domain) const noexcept {
    return mImpl->copyAvailableLane(
        lane,
        firstFrame,
        interleavedStereoOutput,
        outputCapacityFrames,
        frameCount, requestMissing, consumer, domain);
}

std::uint64_t CurrentSongPcmCache::availableFrames(std::size_t lane) const noexcept {
    return mImpl->availableFrames(lane);
}

bool CurrentSongPcmCache::covers(
    std::uint32_t laneMask,
    std::uint64_t firstFrame,
    std::size_t frameCount) const noexcept {
    return mImpl->covers(laneMask, firstFrame, frameCount);
}

bool CurrentSongPcmCache::admitReadConsumer(const std::shared_ptr<CurrentSongPcmReadConsumer> &reader,
    std::uint64_t token, std::uint64_t generation) const noexcept {
    return mImpl->admitReadConsumer(reader, token, generation);
}

void CurrentSongPcmCache::requireReadRange(CurrentSongPcmReadConsumer &reader, std::size_t domain,
    std::uint32_t mask, std::uint64_t first, std::size_t frames) const noexcept {
    mImpl->requireReadRange(reader, domain, mask, first, frames);
}

void CurrentSongPcmCache::requestUpcoming(std::uint32_t mask, std::uint64_t first, std::size_t frames,
    CurrentSongPcmReadConsumer *consumer) const noexcept {
    if (!mImpl->asyncReadWake || !mImpl->covers(mask, first, frames)) return;
    if (consumer) {
        std::unique_lock lock(mImpl->readCacheMutex, std::try_to_lock);
        auto *registration = lock.owns_lock() ? mImpl->validConsumer(*consumer) : nullptr;
        if (!registration) return;
        for (std::size_t lane = 0U; lane < kCurrentSongCacheLaneCount; ++lane)
            if ((mask & laneBit(lane)) != 0U && registration->ahead[lane] == 0U)
                registration->ahead[lane] = first / kChunkFrames * kChunkFrames + 1U;
        mImpl->asyncReadWake(mImpl->asyncReadContext);
        return;
    }
    for (std::size_t lane = 0U; lane < kCurrentSongCacheLaneCount; ++lane) {
        if ((mask & laneBit(lane)) == 0U) continue;
        std::uint64_t empty{};
        if (mImpl->readAheadRequests[lane].compare_exchange_strong(empty,
                first / kChunkFrames * kChunkFrames + 1U, std::memory_order_release, std::memory_order_relaxed))
            mImpl->asyncReadWake(mImpl->asyncReadContext);
    }
}
std::size_t CurrentSongPcmCache::upcomingWindowFrames() const noexcept { return LaneReadWindow::kFrames; }

bool CurrentSongPcmCache::committedRange(std::uint32_t mask, std::uint64_t first,
    std::size_t frames) const noexcept {
    const auto sequence = mImpl->residencySequence.load(std::memory_order_acquire);
    return (sequence & 1U) == 0U && mImpl->covers(mask, first, frames) &&
        mImpl->residencySequence.load(std::memory_order_acquire) == sequence;
}

bool CurrentSongPcmCache::complete(std::uint32_t laneMask) const noexcept {
    return mImpl->complete(laneMask);
}

CurrentSongCacheSnapshot CurrentSongPcmCache::snapshot() const noexcept {
    return mImpl->snapshot();
}

CurrentSongCacheUnpublishedPcm CurrentSongPcmCache::unpublishedOriginal(
    const CurrentSongCacheKey &key) const noexcept {
    return mImpl->unpublishedOriginal(key);
}

} // namespace stemwise::audio::engine
