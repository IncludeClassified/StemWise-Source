#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>

namespace stemwise::audio::engine {

enum class TimelineAppendStatus : std::uint8_t {
    Accepted,
    Partial,
    Backpressured,
    GapRejected,
    StaleGeneration,
    InvalidArgument,
};

enum class TimelineCopyStatus : std::uint8_t {
    Complete,
    Partial,
    Unavailable,
    StaleGeneration,
    InvalidArgument,
};

struct TimelineAppendResult final {
    TimelineAppendStatus status{TimelineAppendStatus::InvalidArgument};
    std::size_t inputFramesAccepted{0};
    std::size_t framesQueued{0};
    std::size_t overlapFramesDiscarded{0};
    bool timelineReset{false};
};

struct TimelineCopyResult final {
    TimelineCopyStatus status{TimelineCopyStatus::Unavailable};
    std::size_t framesCopied{0};
};

struct TimelineStereoBufferDiagnostics final {
    std::size_t capacityFrames{0};
    std::size_t storedFrames{0};
    std::size_t highWaterFrames{0};
    std::uint64_t generation{0};
    std::uint64_t firstTimelineFrame{0};
    std::uint64_t endTimelineFrameExclusive{0};
    std::uint64_t appendCalls{0};
    std::uint64_t copyCalls{0};
    std::uint64_t resetCount{0};
    std::uint64_t generationResetCount{0};
    std::uint64_t discontinuityResetCount{0};
    std::uint64_t queuedFrames{0};
    std::uint64_t copiedFrames{0};
    std::uint64_t discardedFrames{0};
    std::uint64_t overlapFramesDiscarded{0};
    std::uint64_t staleFramesDiscarded{0};
    std::uint64_t partialAppendCount{0};
    std::uint64_t backpressureCount{0};
    std::uint64_t gapRejectionCount{0};
};

/**
 * Render-thread-owned reservoir of source-timeline stereo float PCM.
 *
 * LaneIngress performs the decoder-worker SPSC handoff and SRC. This second bounded stage retains
 * enough already-resampled source PCM for aligned stem mixing, loop heads, and TimePitch lookahead.
 * It deliberately supports non-consuming copyAt(): tuning can prime from future music while the
 * direct transition begins at the exact current source frame. discardBefore() is the sole commit.
 *
 * Construction is the only allocating operation. Every other method is lock-free by thread
 * confinement and performs no allocation, JNI, logging, or file I/O.
 */
class TimelineStereoBuffer final {
public:
    static constexpr std::size_t kChannelCount = 2;
    static constexpr std::size_t kMinimumCapacityFrames = 64;
    static constexpr std::size_t kMaximumCapacityFrames = 1U << 22U;

    explicit TimelineStereoBuffer(std::size_t capacityFrames);
    ~TimelineStereoBuffer();

    TimelineStereoBuffer(const TimelineStereoBuffer &) = delete;
    TimelineStereoBuffer &operator=(const TimelineStereoBuffer &) = delete;
    TimelineStereoBuffer(TimelineStereoBuffer &&) = delete;
    TimelineStereoBuffer &operator=(TimelineStereoBuffer &&) = delete;

    /**
     * Appends a contiguous suffix. Newer generations and explicit discontinuities clear retained
     * PCM atomically on this same render thread. An unexplained forward gap is rejected so callers
     * must consciously reset instead of manufacturing unbounded silence.
     */
    [[nodiscard]] TimelineAppendResult append(
        std::uint64_t generation,
        std::uint64_t firstTimelineFrame,
        const float *interleavedStereo,
        std::size_t frameCount,
        bool explicitDiscontinuity = false) noexcept;

    /** Copies retained PCM without advancing the committed source cursor. */
    [[nodiscard]] TimelineCopyResult copyAt(
        std::uint64_t generation,
        std::uint64_t firstTimelineFrame,
        float *interleavedStereoOutput,
        std::size_t outputCapacityFrames,
        std::size_t requestedFrames) const noexcept;

    /** Commits/discards every retained frame strictly before [timelineFrame]. */
    std::size_t discardBefore(std::uint64_t timelineFrame) noexcept;

    void reset(
        std::uint64_t generation,
        std::uint64_t firstTimelineFrame) noexcept;

    [[nodiscard]] std::size_t availableFrom(
        std::uint64_t generation,
        std::uint64_t firstTimelineFrame) const noexcept;

    [[nodiscard]] TimelineStereoBufferDiagnostics diagnostics() const noexcept;

private:
    class Impl;
    std::unique_ptr<Impl> mImpl;
};

}  // namespace stemwise::audio::engine
