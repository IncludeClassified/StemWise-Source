#pragma once

#include "LaneIngress.h"
#include "../render/LoopHeadCache.h"

#include <cstddef>
#include <cstdint>
#include <memory>

namespace stemwise::audio::engine {

struct LoopHeadPreparationConfiguration final {
    static constexpr std::size_t kDefaultQueueCapacityFrames = 16'384U;
    static constexpr std::size_t kDefaultMaximumInputQuantumFrames = 4'096U;
    static constexpr std::size_t kDefaultPullCapacityFrames = 4'096U;

    std::size_t queueCapacityFrames{kDefaultQueueCapacityFrames};
    std::size_t maximumInputQuantumFrames{kDefaultMaximumInputQuantumFrames};
    std::size_t pullCapacityFrames{kDefaultPullCapacityFrames};
    std::string cacheDirectory{};
};

enum class LoopHeadPreparationPhase : std::uint8_t {
    Idle,
    Staging,
    LaneConfigured,
    Committed,
    Failed,
    Aborted,
};

enum class LoopHeadPreparationBeginStatus : std::uint8_t {
    Accepted,
    InvalidKey,
    InvalidLaneMask,
    StaleRevision,
    ReadersActive,
    Stopped,
    AllocationFailure,
    InsufficientSpace,
};

enum class LoopHeadPreparationConfigureStatus : std::uint8_t {
    Accepted,
    AlreadyConfigured,
    NoPreparation,
    StaleRevision,
    InvalidLane,
    LaneNotRequested,
    LaneAlreadyCompleted,
    LaneBusy,
    InvalidConfiguration,
    AllocationFailure,
    Stopped,
};

enum class LoopHeadPreparationOfferStatus : std::uint8_t {
    Accepted,
    Partial,
    Backpressured,
    LaneCompleted,
    CacheCommitted,
    NoPreparation,
    StaleRevision,
    WrongLane,
    StaleGeneration,
    AfterEndOfStream,
    TimelineDiscontinuity,
    CacheRejected,
    ResamplerFailure,
    InvalidArgument,
    Stopped,
};

struct LoopHeadPreparationOfferResult final {
    LoopHeadPreparationOfferStatus status{LoopHeadPreparationOfferStatus::InvalidArgument};
    /** Exact decoder-block prefix consumed by LaneIngress. */
    std::size_t inputFramesAccepted{0};
    /** 48 kHz frames copied into the bounded immutable head staging slot by this call. */
    std::size_t cacheFramesWritten{0};
};

enum class LoopHeadPreparationEndStatus : std::uint8_t {
    LaneCompleted,
    CacheCommitted,
    AlreadyCompleted,
    NoPreparation,
    StaleRevision,
    WrongLane,
    StaleGeneration,
    BoundaryMismatch,
    TimelineDiscontinuity,
    CacheRejected,
    ResamplerFailure,
    InvalidArgument,
    Stopped,
    PaddingPending,
};

enum class LoopHeadPreparationAbortStatus : std::uint8_t {
    Accepted,
    NoPreparation,
    StaleRevision,
    Stopped,
};

struct LoopHeadPreparationSnapshot final {
    LoopHeadPreparationPhase phase{LoopHeadPreparationPhase::Idle};
    LoopHeadContentKey key{};
    std::uint32_t requiredLaneMask{0};
    std::uint32_t completedLaneMask{0};
    std::size_t activeLane{kLoopHeadLaneCount};
    std::uint32_t activeSourceRateHz{0};
    std::uint64_t activePreparationGeneration{0};
    std::uint64_t nextExpectedTimelineFrame{0};
    std::uint64_t beginCalls{0};
    std::uint64_t configuredLanes{0};
    std::uint64_t offerCalls{0};
    std::uint64_t inputFramesAccepted{0};
    std::uint64_t cacheFramesWritten{0};
    std::uint64_t lanesCompleted{0};
    std::uint64_t commits{0};
    std::uint64_t failures{0};
    std::uint64_t aborts{0};
};

/**
 * Serialized, non-real-time loop-head loader.
 *
 * Exactly one temporary decoder lane is configured at a time. Its PCM must pass through
 * LaneIngress normalization and Speex SRC before reaching LoopHeadCache, so cache preparation and
 * ordinary playback share one 48 kHz stereo interpretation. Completing a lane destroys that
 * temporary ingress before another lane may be configured; this is the native half of the
 * bounded shared decoder lease contract.
 *
 * begin/configure/offer/end/abort may be called only from one serialized preparation actor. The
 * implementation additionally guards accidental cross-thread calls because these methods are not
 * on the render or device-callback path. Render code may concurrently acquire immutable views from
 * cache() and never takes the preparation lock.
 */
class LoopHeadPreparationIngress final {
public:
    explicit LoopHeadPreparationIngress(
        const LoopHeadPreparationConfiguration &configuration = {});
    ~LoopHeadPreparationIngress();

    LoopHeadPreparationIngress(const LoopHeadPreparationIngress &) = delete;
    LoopHeadPreparationIngress &operator=(const LoopHeadPreparationIngress &) = delete;
    LoopHeadPreparationIngress(LoopHeadPreparationIngress &&) = delete;
    LoopHeadPreparationIngress &operator=(LoopHeadPreparationIngress &&) = delete;

    [[nodiscard]] LoopHeadPreparationBeginStatus begin(
        const LoopHeadContentKey &key,
        std::uint32_t requiredLaneMask,
        LoopHeadBoundaryMode boundaryMode = LoopHeadBoundaryMode::OriginalEos) noexcept;

    [[nodiscard]] LoopHeadPreparationConfigureStatus configureLane(
        std::uint64_t loopRevision,
        std::size_t laneIndex,
        std::uint32_t sourceRateHz,
        std::uint64_t preparationGeneration) noexcept;

    [[nodiscard]] LoopHeadPreparationOfferResult offer(
        std::uint64_t loopRevision,
        std::size_t laneIndex,
        const LaneInputBlock &block) noexcept;

    /** PaddingPending retains this exact lane/generation and advances at most one silence chunk. */
    [[nodiscard]] LoopHeadPreparationEndStatus markEndOfStream(
        std::uint64_t loopRevision,
        std::size_t laneIndex,
        std::uint64_t preparationGeneration,
        std::uint64_t finalSourceFrameExclusive) noexcept;

    [[nodiscard]] LoopHeadPreparationAbortStatus abort(
        std::uint64_t loopRevision) noexcept;

    /** Lock-free/RCU-safe immutable cache access for the native render worker. */
    [[nodiscard]] const LoopHeadCache &cache() const noexcept;
    [[nodiscard]] LoopHeadCache &cache() noexcept;

    /** Off-render diagnostics; may briefly wait for the serialized preparation actor. */
    [[nodiscard]] LoopHeadPreparationSnapshot snapshot() const noexcept;

private:
    class Impl;
    std::unique_ptr<Impl> mImpl;
};

}  // namespace stemwise::audio::engine
