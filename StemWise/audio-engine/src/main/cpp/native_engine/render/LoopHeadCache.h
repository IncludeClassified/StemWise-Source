#pragma once

#include "../../StemwiseMultibus.h"

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>

namespace stemwise::audio::engine {

inline constexpr std::size_t kLoopHeadLaneCount = kStemwiseMusicBusCount;
inline constexpr std::uint32_t kLoopHeadAllLanesMask =
    (1U << static_cast<std::uint32_t>(kLoopHeadLaneCount)) - 1U;

/** OriginalEos uses the key end as a bounded preparation runway, not an authored boundary. */
enum class LoopHeadBoundaryMode : std::uint8_t {
    Unresolved = 0,
    OriginalEos = 1,
    RetainedWindow = 2,
};

/**
 * Allocation-free identity for one immutable raw loop head.
 *
 * Hashes are produced by the serialized Kotlin controller from the complete local audio identity;
 * no path or variable-size string is retained in the native real-time graph. A cache may be rebound
 * to a newer output generation only when the required lanes' source identity still matches.
 */
struct LoopHeadContentKey final {
    std::uint64_t songIdentityHash{0};
    std::uint64_t originalContentHash{0};
    std::uint64_t stemPackageHash{0};
    std::uint64_t contentRevision{0};
    /** Globally monotonic for the lifetime of this cache, including across song/content changes. */
    std::uint64_t loopRevision{0};
    std::uint64_t startTimelineFrame{0};
    std::uint64_t endTimelineFrameExclusive{0};

    [[nodiscard]] constexpr bool operator==(const LoopHeadContentKey &) const noexcept = default;

    [[nodiscard]] constexpr bool matchesSource(std::uint64_t songHash, std::uint64_t originalHash,
        std::uint64_t packageHash, std::uint32_t laneMask) const noexcept {
        return laneMask != 0U && songIdentityHash == songHash && originalContentHash == originalHash &&
            ((laneMask & ~1U) == 0U || stemPackageHash == packageHash);
    }

    [[nodiscard]] constexpr bool valid() const noexcept {
        return songIdentityHash != 0U && originalContentHash != 0U &&
            contentRevision != 0U && loopRevision != 0U &&
            endTimelineFrameExclusive > startTimelineFrame;
    }
};

enum class LoopHeadBeginStatus : std::uint8_t {
    Accepted,
    InvalidKey,
    InvalidLaneMask,
    StaleRevision,
    ReadersActive,
    AllocationFailed,
    InsufficientSpace,
};

enum class LoopHeadWriteStatus : std::uint8_t {
    Accepted,
    LaneComplete,
    NoStagingSlot,
    StaleRevision,
    InvalidLane,
    LaneNotRequested,
    GapRejected,
    NonFinitePcm,
    InvalidArgument,
};

struct LoopHeadWriteResult final {
    LoopHeadWriteStatus status{LoopHeadWriteStatus::InvalidArgument};
    std::size_t inputFramesConsumed{0};
    std::size_t framesWritten{0};
    std::uint64_t nextExpectedTimelineFrame{0};
};

enum class LoopHeadSealStatus : std::uint8_t {
    Accepted,
    NoStagingSlot,
    StaleRevision,
    InvalidLane,
    LaneNotRequested,
    AlreadyComplete,
    InvalidBoundary,
    PaddingPending,
};

enum class LoopHeadCommitStatus : std::uint8_t {
    Accepted,
    NoStagingSlot,
    StaleRevision,
    MissingRequiredLanes,
};

enum class LoopHeadAbortStatus : std::uint8_t {
    Accepted,
    NoStagingSlot,
    StaleRevision,
};

enum class LoopHeadCopyStatus : std::uint8_t {
    Complete,
    Pending,
    Unavailable,
    ReadFailure,
};

struct LoopHeadCacheSnapshot final {
    bool valid{false};
    std::uint32_t slotIndex{0};
    LoopHeadContentKey key{};
    std::uint32_t requiredLaneMask{0};
    std::uint32_t completedLaneMask{0};
    std::uint32_t frameCount{0};
    bool cacheOnly{false};
    LoopHeadBoundaryMode boundaryMode{LoopHeadBoundaryMode::Unresolved};
    bool originalEndResolved{false};
    std::uint64_t resolvedOriginalEndTimelineFrameExclusive{0};
    std::array<std::uint64_t, kLoopHeadLaneCount> laneChecksums{};
    std::uint64_t combinedChecksum{0};
};

struct LoopHeadCacheDiagnostics final {
    std::uint64_t beginCalls{0};
    std::uint64_t beginAccepted{0};
    std::uint64_t staleBegins{0};
    std::uint64_t readerDeferrals{0};
    std::uint64_t writeCalls{0};
    std::uint64_t writtenFrames{0};
    std::uint64_t overlappedFramesDiscarded{0};
    std::uint64_t gapRejections{0};
    std::uint64_t nonFiniteRejections{0};
    std::uint64_t silenceFramesWritten{0};
    std::uint64_t lanesCompleted{0};
    std::uint64_t commits{0};
    std::uint64_t rejectedCommits{0};
    std::uint64_t aborts{0};
    std::uint64_t activeViewAcquisitions{0};
    std::uint64_t activeViewMisses{0};
    std::array<std::uint32_t, 2> activeReaders{};
};

/**
 * Two-slot fixed-capacity raw 48 kHz stereo cache.
 *
 * A serialized control/loader owner allocates only requested lanes in an inactive staging slot.
 * Warm staging reuses its storage. The native render worker obtains immutable ReadView objects;
 * reads never allocate, lock, perform JNI, log, or perform file/device I/O.
 *
 * A committed slot is never modified. After a newer slot commits, the old slot may be reused only
 * after its reader count reaches zero. This small RCU fence lets a render block safely finish while
 * the latest loop revision becomes active without a mutex or use-after-overwrite race.
 *
 * Lifetime contract: the cache owner must quiesce and destroy every ReadView before destroying the
 * cache. A ReadView deliberately borrows the fixed PCM allocation instead of retaining ownership.
 */
class LoopHeadCache final {
public:
    static constexpr std::uint32_t kSampleRateHz = 48'000U;
    static constexpr std::size_t kChannelCount = 2U;
    static constexpr std::uint32_t kCapacityFrames = 192'000U;
    static constexpr std::uint32_t kSlotCount = 2U;
    static constexpr std::uint32_t kNoSlot = kSlotCount;
    static constexpr std::size_t kPcmBytesPerSlot =
        static_cast<std::size_t>(kCapacityFrames) * kLoopHeadLaneCount *
        kChannelCount * sizeof(float);
    static constexpr std::size_t kTotalPcmBytes = kPcmBytesPerSlot * kSlotCount;

    class ReadView final {
    public:
        ReadView() noexcept = default;
        ~ReadView();

        ReadView(const ReadView &) = delete;
        ReadView &operator=(const ReadView &) = delete;
        ReadView(ReadView &&other) noexcept;
        ReadView &operator=(ReadView &&other) noexcept;

        [[nodiscard]] bool valid() const noexcept;
        [[nodiscard]] const LoopHeadCacheSnapshot &snapshot() const noexcept;
        /** Pins this same immutable slot even after a different head becomes active. */
        [[nodiscard]] ReadView retain() const noexcept;
        [[nodiscard]] std::uint32_t readFailureLaneMask() const noexcept;

        /** Copies an exact source span; optional status distinguishes cold async windows from failed/unavailable reads. */
        [[nodiscard]] bool copyLane(
            std::size_t laneIndex,
            std::uint64_t firstTimelineFrame,
            float *interleavedStereoOutput,
            std::size_t outputCapacityFrames,
            std::size_t requestedFrames,
            LoopHeadCopyStatus *status = nullptr) const noexcept;

    private:
        friend class LoopHeadCache;
        ReadView(
            const LoopHeadCache *owner,
            std::uint32_t slot,
            LoopHeadCacheSnapshot snapshot) noexcept;
        void release() noexcept;

        const LoopHeadCache *mOwner{nullptr};
        std::uint32_t mSlot{kNoSlot};
        LoopHeadCacheSnapshot mSnapshot{};
    };

    explicit LoopHeadCache(std::string cacheDirectory = {});
    ~LoopHeadCache();

    LoopHeadCache(const LoopHeadCache &) = delete;
    LoopHeadCache &operator=(const LoopHeadCache &) = delete;
    LoopHeadCache(LoopHeadCache &&) = delete;
    LoopHeadCache &operator=(LoopHeadCache &&) = delete;

    [[nodiscard]] LoopHeadBeginStatus beginStaging(
        const LoopHeadContentKey &key,
        std::uint32_t requiredLaneMask,
        LoopHeadBoundaryMode boundaryMode = LoopHeadBoundaryMode::OriginalEos) noexcept;

    /** Strict contiguous append with safe repeated-prefix discard and exact head-end clamping. */
    [[nodiscard]] LoopHeadWriteResult writeStaging(
        std::uint64_t loopRevision,
        std::size_t laneIndex,
        std::uint64_t firstTimelineFrame,
        const float *interleavedStereo,
        std::size_t frameCount) noexcept;

    /** Retained windows pad one canonical chunk per call; retry PaddingPending with the same EOS. */
    [[nodiscard]] LoopHeadSealStatus sealStagingLaneWithSilence(
        std::uint64_t loopRevision,
        std::size_t laneIndex,
        std::uint64_t actualEndTimelineFrameExclusive) noexcept;

    [[nodiscard]] LoopHeadCommitStatus commitStaging(
        std::uint64_t loopRevision) noexcept;

    /** Aborting never makes the same or an older revision writable again. */
    [[nodiscard]] LoopHeadAbortStatus abortStaging(
        std::uint64_t loopRevision) noexcept;

    /** Empty view on key/revision mismatch. */
    [[nodiscard]] ReadView acquireActive(
        const LoopHeadContentKey &expectedKey) const noexcept;

    /** Any currently committed key; intended for diagnostics and controlled rebind checks. */
    [[nodiscard]] ReadView acquireActive() const noexcept;

    /** Serialized staging-owner inspection only; it is not safe for concurrent polling. */
    [[nodiscard]] LoopHeadCacheSnapshot stagingSnapshot() const noexcept;
    [[nodiscard]] LoopHeadCacheDiagnostics diagnostics() const noexcept;
    using AsyncReadWake = void (*)(void *) noexcept;
    void enableAsyncReads(AsyncReadWake wake, void *context) noexcept;
    [[nodiscard]] bool serviceAsyncReads() const noexcept;
    [[nodiscard]] static constexpr std::size_t maximumPcmBytes() noexcept {
        return kTotalPcmBytes;
    }

private:
    class Impl;
    std::unique_ptr<Impl> mImpl;
};

static_assert(LoopHeadCache::kCapacityFrames == LoopHeadCache::kSampleRateHz * 4U);
static_assert(LoopHeadCache::kSlotCount == 2U);
static_assert(kLoopHeadLaneCount < 32U);
static_assert(std::atomic<std::uint32_t>::is_always_lock_free);
static_assert(std::atomic<std::uint64_t>::is_always_lock_free);

}  // namespace stemwise::audio::engine
