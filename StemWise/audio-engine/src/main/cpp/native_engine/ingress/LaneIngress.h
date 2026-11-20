#pragma once

#include "../../StemwiseMultibus.h"

#include <cstddef>
#include <cstdint>
#include <memory>

namespace stemwise::audio::engine {

enum class LanePcmEncoding : std::uint8_t {
    Signed16 = 1,
    Float32 = 2,
};

enum class LaneOfferStatus : std::uint8_t {
    Accepted,
    Partial,
    Backpressured,
    StaleGeneration,
    AfterEndOfStream,
    InvalidArgument,
};

enum class LanePullStatus : std::uint8_t {
    Ok,
    Empty,
    EndOfStream,
    StaleGeneration,
    OutputCapacityExhausted,
    InvalidArgument,
    ResamplerFailure,
    EndOfStreamFlushFailure,
};

enum class LaneEndOfStreamStatus : std::uint8_t {
    Accepted,
    AlreadyMarked,
    StaleGeneration,
    BoundaryMismatch,
    InvalidArgument,
};

enum class LaneDiscontinuity : std::uint8_t {
    None = 0,
    Generation = 1U << 0U,
    TimestampGap = 1U << 1U,
    Explicit = 1U << 2U,
};

constexpr LaneDiscontinuity operator|(
    LaneDiscontinuity left,
    LaneDiscontinuity right) noexcept {
    return static_cast<LaneDiscontinuity>(
        static_cast<std::uint8_t>(left) | static_cast<std::uint8_t>(right));
}

constexpr LaneDiscontinuity &operator|=(
    LaneDiscontinuity &left,
    LaneDiscontinuity right) noexcept {
    left = left | right;
    return left;
}

[[nodiscard]] constexpr bool hasDiscontinuity(
    LaneDiscontinuity value,
    LaneDiscontinuity flag) noexcept {
    return (static_cast<std::uint8_t>(value) & static_cast<std::uint8_t>(flag)) != 0U;
}

struct LaneInputFormat final {
    std::uint32_t sampleRateHz{0};
    std::uint8_t channelCount{0};
    LanePcmEncoding encoding{LanePcmEncoding::Signed16};
};

/**
 * A synchronous decoder-worker view. The memory is copied/normalized before offer() returns.
 * presentationTimeUs is the first audible source frame on the decoder's normalized timeline.
 */
struct LaneInputBlock final {
    const void *data{nullptr};
    std::size_t frameCount{0};
    LaneInputFormat format{};
    std::uint64_t generation{0};
    std::uint64_t presentationTimeUs{0};
};

struct LaneOfferResult final {
    LaneOfferStatus status{LaneOfferStatus::InvalidArgument};
    // Prefix consumed from the decoder block. It includes safely discarded overlap/stale frames.
    std::size_t inputFramesAccepted{0};
    std::size_t sourceFramesQueued{0};
    std::size_t sourceFramesDiscarded{0};
    std::uint64_t firstSourceFrame{0};
};

struct LanePullResult final {
    LanePullStatus status{LanePullStatus::Empty};
    std::size_t sourceFramesConsumed{0};
    std::size_t outputFramesProduced{0};
    std::size_t staleSourceFramesDiscarded{0};
    std::uint64_t generation{0};
    std::uint64_t firstSourceFrame{0};
    std::uint64_t firstTimelineFrame48k{0};
    LaneDiscontinuity discontinuity{LaneDiscontinuity::None};
};

struct LaneEndOfStreamResult final {
    LaneEndOfStreamStatus status{LaneEndOfStreamStatus::InvalidArgument};
    std::uint64_t generation{0};
    std::uint64_t finalSourceFrameExclusive{0};
    std::uint64_t finalTimelineFrame48kExclusive{0};
};

struct LaneIngressDiagnostics final {
    std::uint32_t sourceRateHz{0};
    std::size_t queueCapacityFrames{0};
    std::size_t maximumInputQuantumFrames{0};
    std::size_t maximumOutputQuantumFrames{0};
    std::size_t readableSourceFrames{0};
    std::size_t highWaterSourceFrames{0};
    std::uint64_t activeGeneration{0};
    std::uint64_t producerGeneration{0};
    std::uint64_t consumerGeneration{0};
    std::uint64_t offerCalls{0};
    std::uint64_t acceptedInputFrames{0};
    std::uint64_t queuedSourceFrames{0};
    std::uint64_t overlapFramesDiscarded{0};
    std::uint64_t staleFramesDiscarded{0};
    std::uint64_t backpressureEvents{0};
    std::uint64_t partialOfferEvents{0};
    std::uint64_t timestampGapEvents{0};
    std::uint64_t explicitDiscontinuities{0};
    std::uint64_t generationAdvances{0};
    std::uint64_t pullCalls{0};
    std::uint64_t emptyPulls{0};
    std::uint64_t sourceFramesConsumed{0};
    std::uint64_t outputFramesProduced{0};
    std::uint64_t nonFiniteFloatSamples{0};
    std::uint64_t endOfStreamMarkCalls{0};
    std::uint64_t acceptedEndOfStreamMarks{0};
    std::uint64_t staleEndOfStreamMarks{0};
    std::uint64_t rejectedEndOfStreamMarks{0};
    std::uint64_t endOfStreamFlushCalls{0};
    std::uint64_t endOfStreamFlushInputFrames{0};
    std::uint64_t endOfStreamTailOutputFrames{0};
    std::uint64_t endOfStreamOvershootFramesDiscarded{0};
    std::uint64_t completedEndOfStreams{0};
    std::uint64_t endOfStreamFlushFailures{0};
    std::uint64_t endOfStreamGeneration{0};
    std::uint64_t finalSourceFrameExclusive{0};
    std::uint64_t finalTimelineFrame48kExclusive{0};
};

/**
 * One bounded SPSC decoder lane feeding the native render worker.
 *
 * Producer: exactly one MediaCodecTrackDecoder worker calls offer(). Any byte layout is normalized
 * immediately to interleaved stereo float. Consumer: exactly one native render worker calls
 * pull48k(). The future device callback calls neither method and sees only final post-DSP PCM.
 *
 * Construction allocates the queue, normalization scratch and Speex SRC state. After construction,
 * offer(), pull48k(), advanceGeneration(), markDiscontinuity() and diagnostics() allocate nothing,
 * acquire no lock, perform no JNI, log no message, and do no file I/O.
 *
 * A generation is positive and monotonically increasing. A newer offered generation atomically
 * becomes active; older queued/source blocks are discarded. Queue cursors are never reset across
 * threads, so a seek cannot race a producer write or discard newly published frames.
 */
class LaneIngress final {
public:
    static constexpr std::uint32_t kEngineSampleRateHz = 48'000;
    static constexpr std::size_t kChannelCount = 2;
    static constexpr std::size_t kMinimumQueueCapacityFrames = 64;
    static constexpr std::size_t kMaximumQueueCapacityFrames = 1U << 20U;

    LaneIngress(
        std::uint32_t sourceRateHz,
        std::size_t queueCapacityFrames,
        std::size_t maximumInputQuantumFrames,
        std::uint64_t initialGeneration);
    ~LaneIngress();

    LaneIngress(const LaneIngress &) = delete;
    LaneIngress &operator=(const LaneIngress &) = delete;
    LaneIngress(LaneIngress &&) = delete;
    LaneIngress &operator=(LaneIngress &&) = delete;

    [[nodiscard]] LaneOfferResult offer(const LaneInputBlock &block) noexcept;

    /**
     * Pulls one contiguous timeline span at 48 kHz. The caller supplies preallocated stereo float
     * storage. A timestamp gap starts a new span at firstTimelineFrame48k; the later renderer is
     * responsible for silence between spans instead of manufacturing unbounded gap data here.
     * Before appending the result downstream, the render worker rechecks activeGeneration() equals
     * result.generation; this is the final lock-free guard if ownership changes after pull returns.
     */
    [[nodiscard]] LanePullResult pull48k(
        float *interleavedStereoOutput,
        std::size_t outputCapacityFrames) noexcept;

    /**
     * Generation-gated render-runtime pull. If activeGeneration() is not exactly expectedGeneration
     * at entry, or a newer queued head wins before consumption, returns StaleGeneration without
     * consuming that future-generation frame or advancing SRC state into it. This closes the
     * check-then-pull race when decoders prebuffer a seek before the atomic bank intent arrives.
     */
    [[nodiscard]] LanePullResult pull48k(
        std::uint64_t expectedGeneration,
        float *interleavedStereoOutput,
        std::size_t outputCapacityFrames) noexcept;

    /** Advances ownership even if the decoder has not published its first post-seek block yet. */
    [[nodiscard]] bool advanceGeneration(std::uint64_t generation) noexcept;

    /** Marks the next block in the current generation as an explicit source discontinuity. */
    [[nodiscard]] bool markDiscontinuity(std::uint64_t generation) noexcept;

    /**
     * Publishes the exact exclusive audible source boundary after the final decoder block has been
     * fully accepted. The boundary must equal the next expected source frame; a non-zero boundary
     * without any accepted PCM is rejected. The render worker drains only the finite SRC filter
     * latency and publishes no frame at or beyond finalTimelineFrame48kExclusive.
     *
     * Producer contract: call from the same serialized decoder-worker lane as offer(). Do not call
     * while a final offer suffix is backpressured. Repeating the same marker is idempotent.
     */
    [[nodiscard]] LaneEndOfStreamResult markEndOfStream(
        std::uint64_t generation,
        std::uint64_t finalSourceFrameExclusive) noexcept;

    [[nodiscard]] std::uint32_t sourceRateHz() const noexcept;
    [[nodiscard]] std::uint64_t activeGeneration() const noexcept;
    [[nodiscard]] LaneIngressDiagnostics diagnostics() const noexcept;

private:
    class Impl;
    std::unique_ptr<Impl> mImpl;
};

/**
 * One resident song bank: Original + up to 17 declared parts. The engine owns two instances;
 * only configured lanes allocate buffers. Kotlin's separate decoder-lease gate bounds live
 * MediaCodec workers independently of native transport capacity.
 *
 * configureLane() fully constructs an owner before publishing its address with release semantics.
 * lane() is a lock-free acquire snapshot, so the render worker may safely discover a lane while a
 * decoder lazily configures its actual output format. A returned pointer is non-owning and may be
 * retained only while that lane remains published.
 *
 * Lifetime precondition: before releaseLane() or bank destruction, the coordinator must first
 * unassign the bank, quiesce the render worker, stop/join its decoder producers, and ensure no
 * caller retains an earlier lane() result. releaseLane() deliberately does not wait on the audio
 * path; violating this precondition is a lifetime error.
 */
class SongLaneIngressBank final {
public:
    static constexpr std::size_t kLaneCount = kStemwiseMusicBusCount;

    SongLaneIngressBank(
        std::size_t queueCapacityFrames,
        std::size_t maximumInputQuantumFrames);
    ~SongLaneIngressBank();

    SongLaneIngressBank(const SongLaneIngressBank &) = delete;
    SongLaneIngressBank &operator=(const SongLaneIngressBank &) = delete;
    SongLaneIngressBank(SongLaneIngressBank &&) = delete;
    SongLaneIngressBank &operator=(SongLaneIngressBank &&) = delete;

    [[nodiscard]] bool configureLane(
        std::size_t laneIndex,
        std::uint32_t sourceRateHz,
        std::uint64_t initialGeneration);

    /**
     * Unpublishes and destroys one lane. The bank lifetime precondition above is mandatory.
     */
    void releaseLane(std::size_t laneIndex) noexcept;

    /** Returns a non-owning acquire snapshot; it never locks or allocates. */
    [[nodiscard]] LaneIngress *lane(std::size_t laneIndex) noexcept;
    [[nodiscard]] const LaneIngress *lane(std::size_t laneIndex) const noexcept;

    /** Returns a race-free count of currently published lane owners. */
    [[nodiscard]] std::size_t configuredLaneCount() const noexcept;

private:
    class Impl;
    std::unique_ptr<Impl> mImpl;
};

}  // namespace stemwise::audio::engine
