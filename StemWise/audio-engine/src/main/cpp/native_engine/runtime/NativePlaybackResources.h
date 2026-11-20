#pragma once

#include "../render/CurrentSongPcmCache.h"
#include "../render/SongRenderPipeline.h"
#include "../ingress/LaneIngress.h"
#include <memory>
#include <string>
#include <utility>

namespace stemwise::audio::engine {

struct NativePlaybackResourceDiagnostics final {
    std::uint64_t operationsCompleted{};
    std::uint64_t lastOperationMicros{};
    std::uint64_t maximumOperationMicros{};
    std::uint64_t maximumQueueAgeMicros{};
    CurrentSongCacheKey cacheKey{};
    CanonicalPcmIoDiagnostics pcmIo{};
};

enum class NativePlaybackCacheRetirementStatus { Pending, Retired, Rejected };
enum class NativePlaybackResourceStatus { Pending, Succeeded, Rejected };
enum class NativePlaybackCacheDiscardStatus { Unchanged, Pending, Discarded };

/** One canonical I/O owner and one single-flight DSP preparation owner; admission never waits. */
class NativePlaybackResources final {
public:
    /** Called by either fixed owner; the notification must be thread-safe. */
    using Wake = void (*)(void *) noexcept;
    NativePlaybackResources(SongRenderPipelineConfiguration configuration, std::string cacheDirectory,
        Wake wake, void *context);
    ~NativePlaybackResources();
    NativePlaybackResources(const NativePlaybackResources &) = delete;
    NativePlaybackResources &operator=(const NativePlaybackResources &) = delete;

    /** Constructs a cold pipeline off the I/O/render workers; adopts only its exact result. */
    [[nodiscard]] bool preparePipeline(std::size_t target, std::uint64_t revision,
        std::size_t count, std::unique_ptr<SongRenderPipeline> &pipeline, bool *failed = nullptr) noexcept;
    [[nodiscard]] bool prepareLanes(std::size_t target, std::uint64_t revision,
        std::size_t count, SongRenderPipeline &pipeline, bool *failed = nullptr) noexcept;
    /** Only the target's authoritative revision may retire its pending/completed DSP result. */
    void discardSupersededPreparation(std::size_t target, std::uint64_t revision) noexcept;
    [[nodiscard]] CurrentSongPcmCache *createCache(const CurrentSongCacheKey &key,
        std::size_t laneCount, std::uint64_t maximumFrames, bool &finished) noexcept;
    void retireCache() noexcept;
    /** A superseded operation must finish before its receipt and actual capacity can reconcile. */
    [[nodiscard]] NativePlaybackCacheDiscardStatus discardSupersededCacheResult(
        const CurrentSongCacheKey &expected, std::uint64_t intentRevision) noexcept;
    [[nodiscard]] CurrentSongCacheStorageStatus storageStatus() const noexcept;
    /** Sole render/coordinator owner; preserves its last snapshot when publication is busy. */
    [[nodiscard]] CurrentSongCacheSnapshot snapshot() const noexcept;
    [[nodiscard]] NativePlaybackResourceStatus admitStorage(const CurrentSongCacheKey &owner, std::uint64_t intentRevision,
        std::size_t lanes, std::uint64_t maximumFrames) noexcept;
    [[nodiscard]] CurrentSongCacheOfferResult offer(const CurrentSongCacheKey &key, std::size_t lane,
        std::uint64_t firstFrame, const float *samples, std::size_t count) noexcept;
    [[nodiscard]] CurrentSongCacheSealStatus seal(const CurrentSongCacheKey &key, std::size_t lane,
        std::uint64_t endFrame) noexcept;
    [[nodiscard]] bool rebind(const CurrentSongCacheKey &key, const CurrentSongCacheKey &next,
        std::uint32_t mask, bool streamingOriginal = false) noexcept;
    [[nodiscard]] std::uint32_t pendingRebindLaneMask(const CurrentSongCacheKey &key) const noexcept;
    [[nodiscard]] NativePlaybackResourceStatus adoptStems(const CurrentSongCacheKey &key, const CurrentSongCacheKey &next) noexcept;
    [[nodiscard]] NativePlaybackCacheRetirementStatus retireStems(const CurrentSongCacheKey &key, const CurrentSongCacheKey &next) noexcept;
    [[nodiscard]] std::uint64_t waitPcmCacheProgress(std::uint64_t after) noexcept;
    void stopPcmCacheProgressWaiters() noexcept;
    /** Thread-safe control/setup read; never mutates the render owner fallback. */
    [[nodiscard]] NativePlaybackResourceDiagnostics diagnostics() const noexcept;
    /** Control/setup only after both render workers are stopped; joins both owners before moving bytes. */
    [[nodiscard]] bool inheritCacheFrom(NativePlaybackResources &previous) noexcept;
    [[nodiscard]] std::uint64_t beginPcmWindowRead(const CurrentSongCacheKey &, std::size_t lane,
        std::uint64_t firstFrame, std::size_t frames) noexcept;
    /** 0 pending, positive copied prefix frames, -1 uncovered, -2 corrupt/read failure, -3 stale/cancelled. */
    [[nodiscard]] int pollPcmWindowRead(std::uint64_t ticket, float *samples, std::size_t frames) noexcept;
    void cancelPcmWindowRead(std::uint64_t ticket) noexcept;
    [[nodiscard]] bool readPcmCacheForAudit(const CurrentSongCacheKey &, std::size_t lane,
        std::uint64_t firstFrame, float *samples, std::size_t frames) noexcept;
    [[nodiscard]] bool pcmCacheRangeReady(const CurrentSongCacheKey &, std::uint32_t laneMask,
        std::uint64_t firstFrame, std::uint64_t endFrame) noexcept;
    /** Setup only, before preparation/render start; this worker owns retained-window reads too. */
    void attachLoopWindowCache(LoopHeadCache &) noexcept;

private:
    class Impl;
    std::unique_ptr<Impl> mImpl;
};
}
