#pragma once

#include "../../StemwiseMultibus.h"
#include "CanonicalPcmStore.h"

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>

namespace stemwise::audio::engine {

inline constexpr std::size_t kCurrentSongCacheLaneCount = kStemwiseMusicBusCount;
inline constexpr std::size_t kCurrentSongCacheChannelCount = 2U;

struct CurrentSongCacheKey final {
    std::uint64_t selectionToken{0U};
    std::uint64_t generation{0U};
    std::uint64_t songIdentityHash{0U};
    std::uint64_t originalContentHash{0U};
    std::uint64_t stemPackageHash{0U};

    [[nodiscard]] constexpr bool operator==(const CurrentSongCacheKey &) const noexcept = default;
    [[nodiscard]] constexpr bool valid() const noexcept {
        return selectionToken != 0U && generation != 0U && songIdentityHash != 0U &&
            originalContentHash != 0U;
    }
};

/** Constructed with a pipeline. Async cache-owned references retire only on the I/O owner;
 * standalone synchronous I/O admission also reclaims retired readers. */
struct CurrentSongPcmReadConsumer final {
    static constexpr std::size_t kDomains = 4U; // Existing prime/input/transition/direct staging buffers.
    struct Range final { std::uint64_t first{}, end{}; };
    std::atomic<bool> active{false};
    std::atomic<bool> preparation{false};
    std::atomic<std::uint32_t> pendingDomains{0U};
    std::atomic<const void *> cache{nullptr};
};

enum class CurrentSongCacheBeginStatus : std::uint8_t {
    Accepted,
    AlreadyBegun,
    InvalidKey,
};

enum class CurrentSongCacheOfferStatus : std::uint8_t {
    Accepted,
    LaneComplete,
    NotBegun,
    Cancelled,
    StaleKey,
    InvalidLane,
    OriginalRangePending,
    GapRejected,
    NonFinitePcm,
    AllocationFailed,
    InvalidArgument,
    Pending,
};

struct CurrentSongCacheOfferResult final {
    CurrentSongCacheOfferStatus status{CurrentSongCacheOfferStatus::InvalidArgument};
    std::size_t inputFramesConsumed{0U};
    std::size_t framesStored{0U};
    std::uint64_t nextExpectedFrame{0U};
};

struct CurrentSongCacheUnpublishedPcm final {
    std::uint64_t firstFrame{0U};
    std::size_t frameCount{0U};
    const float *samples{nullptr};
};

enum class CurrentSongCacheSealStatus : std::uint8_t {
    Accepted,
    NotBegun,
    Cancelled,
    StaleKey,
    InvalidLane,
    EmptyOriginal,
    ConflictingEnd,
    AllocationFailed,
    AlreadyComplete,
    PaddingPending,
    OriginalPending,
    Pending,
};

struct CurrentSongCacheSnapshot final {
    bool begun{false};
    bool cancelled{false};
    bool allocationFailed{false};
    std::uint32_t readFailureLaneMask{0U};
    CurrentSongCacheKey key{};
    std::uint64_t originalEndFrameExclusive{0U};
    std::uint32_t completeLaneMask{0U};
    std::array<std::uint64_t, kCurrentSongCacheLaneCount> publishedFrames{};
    std::array<std::uint64_t, kCurrentSongCacheLaneCount> nextExpectedFrames{};
    std::uint64_t storedBytes{0U};
    std::uint64_t diskReadOperations{0U};
    std::uint64_t readCacheHits{0U};
    CurrentSongCacheStorageStatus storageStatus{CurrentSongCacheStorageStatus::NotRequested};
    CanonicalPcmIoDiagnostics io{};
    std::uint64_t fileBytes{};
};

/**
 * Exact post-SRC 48kHz PCM for one current song. A serialized builder commits immutable contiguous
 * chunks into the bounded canonical store. Required slices of the admitted pipeline readers retain
 * bounded RAM windows; speculative reads use only unprotected windows. Decoded extent and
 * complete recording admission remain distinct; failed writes retain the exact published prefix.
 */
class CurrentSongPcmCache final {
public:
    static constexpr std::size_t kMaximumReadConsumers = 3U; // Two bank pipelines and one continuation.
    static constexpr std::size_t kReadWindowsPerLane =
        kMaximumReadConsumers * CurrentSongPcmReadConsumer::kDomains * 2U;
    static constexpr std::uint32_t kSampleRateHz = 48'000U;
    static constexpr std::uint32_t kChunkFrames = 2'048U;
    static constexpr std::uint32_t kOriginalLaneMask = 1U;
    static constexpr std::uint64_t kFreeStorageReserveBytes = CanonicalPcmStorageBudget::freeSpaceFloorBytes;
    static constexpr std::uint32_t kAllLaneMask =
        (1U << static_cast<std::uint32_t>(kCurrentSongCacheLaneCount)) - 1U;

    explicit CurrentSongPcmCache(std::string cacheDirectory = {});
    ~CurrentSongPcmCache();

    CurrentSongPcmCache(const CurrentSongPcmCache &) = delete;
    CurrentSongPcmCache &operator=(const CurrentSongPcmCache &) = delete;
    CurrentSongPcmCache(CurrentSongPcmCache &&) = delete;
    CurrentSongPcmCache &operator=(CurrentSongPcmCache &&) = delete;

    [[nodiscard]] CurrentSongCacheBeginStatus begin(const CurrentSongCacheKey &key);

    /** Admits exact lane/frame accounting without allocating files or decoding absent ranges. */
    [[nodiscard]] CurrentSongCacheStorageStatus admitStorage(
        std::size_t laneCount, std::uint64_t maximumFramesPerLane) noexcept;

    [[nodiscard]] CurrentSongCacheOfferResult offer(
        const CurrentSongCacheKey &key,
        std::size_t lane,
        std::uint64_t firstFrame,
        const float *interleavedStereo,
        std::size_t frameCount) noexcept;

    /** Pads at most kChunkFrames per call; retry PaddingPending with the exact same decoded end. */
    [[nodiscard]] CurrentSongCacheSealStatus sealLane(
        const CurrentSongCacheKey &key,
        std::size_t lane,
        std::uint64_t decodedEndFrameExclusive) noexcept;

    [[nodiscard]] bool cancel(const CurrentSongCacheKey &key) noexcept;

    /** Re-owners immutable required lanes across a same-content seek/selection generation. */
    [[nodiscard]] bool rebindCompleteOwnership(
        const CurrentSongCacheKey &currentKey,
        const CurrentSongCacheKey &nextKey,
        std::uint32_t requiredLaneMask) noexcept;

    /** Re-owners a published Original prefix while its exact retained producer continues decoding. */
    [[nodiscard]] bool rebindStreamingOriginalOwnership(
        const CurrentSongCacheKey &currentKey,
        const CurrentSongCacheKey &nextKey) noexcept;

    /** Adds the first stem-package identity without discarding the same owner's cached Original. */
    [[nodiscard]] bool adoptStemPackageIdentity(
        const CurrentSongCacheKey &currentKey,
        const CurrentSongCacheKey &nextKey) noexcept;

    /** Clears retired stem PCM/readiness while retaining this owner's exact Original. */
    [[nodiscard]] bool retireStemPackageIdentity(
        const CurrentSongCacheKey &currentKey,
        const CurrentSongCacheKey &nextKey) noexcept;

    [[nodiscard]] bool copyLane(
        std::size_t lane,
        std::uint64_t firstFrame,
        float *interleavedStereoOutput,
        std::size_t outputCapacityFrames,
        std::size_t frameCount, bool requestMissing = true,
        CurrentSongPcmReadConsumer *consumer = nullptr, std::size_t domain = 0U) const noexcept;

    /**
     * Reads only release-published contiguous chunks and an observed short-stem silent suffix.
     * Range admission is independent of complete-recording admission.
     */
    [[nodiscard]] bool copyAvailableLane(
        std::size_t lane,
        std::uint64_t firstFrame,
        float *interleavedStereoOutput,
        std::size_t outputCapacityFrames,
        std::size_t frameCount, bool requestMissing = true,
        CurrentSongPcmReadConsumer *consumer = nullptr, std::size_t domain = 0U) const noexcept;

    /** Async admission never blocks, allocates or reclaims readers. Standalone synchronous I/O
     * admission may reclaim retired readers. A fourth live pipeline is explicitly rejected. */
    [[nodiscard]] bool admitReadConsumer(const std::shared_ptr<CurrentSongPcmReadConsumer> &,
        std::uint64_t token, std::uint64_t generation) const noexcept;
    /** Publishes one next unread slice, never an entire prime/song range. Zero mask retires it. */
    void requireReadRange(CurrentSongPcmReadConsumer &, std::size_t domain, std::uint32_t laneMask,
        std::uint64_t first, std::size_t frames) const noexcept;

    [[nodiscard]] std::uint64_t availableFrames(std::size_t lane) const noexcept;

    /** Advisory RAM-window coverage only; never requests or performs I/O. */
    [[nodiscard]] bool readWindowRangeReady(std::uint32_t laneMask,
        std::uint64_t firstFrame, std::size_t frames) const noexcept;

    /** Worker-only immutable replay view after builder failure; excludes every published frame. */
    [[nodiscard]] CurrentSongCacheUnpublishedPcm unpublishedOriginal(
        const CurrentSongCacheKey &key) const noexcept;

    [[nodiscard]] bool covers(
        std::uint32_t laneMask,
        std::uint64_t firstFrame,
        std::size_t frameCount) const noexcept;
    /** Required upcoming input reserve; never replaces a pending hard read miss. */
    void requestUpcoming(std::uint32_t laneMask, std::uint64_t firstFrame, std::size_t frameCount,
        CurrentSongPcmReadConsumer *consumer = nullptr) const noexcept;
    /** Bounded direct-I/O lookahead, independent of recording position or duration. */
    [[nodiscard]] std::size_t upcomingWindowFrames() const noexcept;
    [[nodiscard]] bool committedRange(std::uint32_t laneMask, std::uint64_t firstFrame,
        std::size_t frameCount) const noexcept;

    /** Observed decoded EOS, independent of current chunk residency. */
    [[nodiscard]] bool complete(std::uint32_t laneMask) const noexcept;
    [[nodiscard]] CurrentSongCacheSnapshot snapshot() const noexcept;
    using AsyncReadWake = void (*)(void *) noexcept;
    /** Setup owner enables bounded request-only reads before publishing this cache. */
    void enableAsyncReads(AsyncReadWake wake, void *context) noexcept;
    /** I/O owner fills the same bounded windows; never called by the render worker. */
    /** Returns actual read-window publication or a newly observed I/O failure. */
    bool serviceAsyncReads() noexcept;
    /** Serialized I/O-owner readback of exact committed PCM. */
    [[nodiscard]] bool readForAudit(const CurrentSongCacheKey &, std::size_t lane,
        std::uint64_t firstFrame, float *samples, std::size_t frames) noexcept;

private:
    class Impl;
    std::unique_ptr<Impl> mImpl;
};

static_assert(CurrentSongPcmCache::kChunkFrames % 256U == 0U);
static_assert(kCurrentSongCacheLaneCount < 32U);

} // namespace stemwise::audio::engine
