#pragma once

#include "../../StemwiseMultibus.h"
#include <cstddef>
#include <cstdint>
#include <memory>

namespace stemwise::audio::engine {

/** Why the render worker discarded the processor's spectral and ratio state. */
enum class TimePitchResetReason : std::uint8_t {
    Explicit,
    Discontinuity,
    Seek,
    ControlModeChange,
};

enum class TimePitchProcessStatus : std::uint8_t {
    Ok,
    PrimeRequired,
    InvalidBuffer,
    OutputCapacityExceeded,
    InsufficientInput,
    ProcessingFailure,
};

enum class TimePitchTimelineState : std::uint8_t {
    DirectVirgin,
    PrimeRequired,
    Processed,
};

enum class TimePitchPrimeStatus : std::uint8_t {
    Ok,
    InProgress,
    NotRequired,
    InvalidBuffer,
    InsufficientLookahead,
    ProcessingFailure,
};

struct TimePitchPrimeRequirements final {
    std::size_t lookaheadInputFrames{0};
    std::size_t nextInputOffsetFrames{0};
    std::size_t preparedOutputLatencyFrames{0};
    float tempo{1.0F};
    float pitchSemitones{0.0F};
    bool inProgress{false};
};

struct TimePitchPrimeResult final {
    TimePitchPrimeStatus status{TimePitchPrimeStatus::Ok};
    std::size_t lookaheadInputFramesConsumed{0};
    std::size_t nextInputOffsetFrames{0};
};

struct TimePitchProcessResult final {
    TimePitchProcessStatus status{TimePitchProcessStatus::Ok};
    std::size_t inputFramesConsumed{0};
    std::size_t outputFramesProduced{0};
};

struct TimePitchDiagnostics final {
    std::uint32_t sampleRateHz{0};
    std::size_t maximumOutputFrames{0};
    std::size_t maximumInputFrames{0};
    std::size_t activeChannelCount{0};
    std::size_t channelCapacity{0};

    float tempo{1.0F};
    float pitchSemitones{0.0F};
    float transposeTonalityLimitHz{0.0F};
    bool exactBypass{true};
    TimePitchTimelineState timelineState{TimePitchTimelineState::DirectVirgin};
    bool processedTimelineActivated{false};

    // Signalsmith's configured spectral latency. Active bypass latency is exactly zero.
    std::size_t configuredAnalysisBlockFrames{0};
    std::size_t configuredAnalysisIntervalFrames{0};
    std::size_t configuredInputLatencyFrames{0};
    std::size_t configuredOutputLatencyFrames{0};
    std::size_t activeInputLatencyFrames{0};
    std::size_t activeOutputLatencyFrames{0};

    std::uint64_t generation{0};
    std::uint64_t processCalls{0};
    std::uint64_t bypassCalls{0};
    std::uint64_t inputFramesConsumed{0};
    std::uint64_t outputFramesProduced{0};
    std::uint64_t exactBypassFrames{0};
    std::uint64_t primeCount{0};
    std::uint64_t primeLookaheadFramesConsumed{0};
    std::uint64_t resetCount{0};
    std::uint64_t discontinuityCount{0};
    std::uint64_t seekCount{0};
    std::uint64_t controlUpdateCount{0};
    std::uint64_t clampedControlUpdateCount{0};
    std::uint64_t rejectedControlUpdateCount{0};
    std::uint64_t rejectedProcessCount{0};
    std::uint64_t nonFiniteInputSampleCount{0};
    std::uint64_t nonFiniteOutputSampleCount{0};
};

/**
 * Project-owned stereo time/pitch boundary for StemWise's native render engine.
 *
 * Ownership/threading contract:
 * - Construct, control, reset, query and process on one render-worker thread.
 * - Do not call this object from an audio-device callback.
 * - Configuration allocates all project-owned PCM scratch storage. Steady-state process(),
 *   setControls(), requiredInputFrames() and diagnostics() take no locks and perform no
 *   project-owned allocation, logging, JNI, or file I/O.
 * - input/output are interleaved float PCM; active width changes retain existing spectral/timeline history.
 *   They may alias; non-bypass processing snapshots all consumed input before writing output.
 *
 * The tempo control is playback tempo: 2.0 consumes two input frames per output frame,
 * while 0.5 consumes one input frame per two output frames on average. The caller asks for an
 * output quantum, calls requiredInputFrames(), and supplies at least that many input frames.
 */
class TimePitchProcessor final {
public:
    /** Legacy/default stereo width retained for existing callers and fixtures. */
    static constexpr std::size_t kChannelCount = 2;
    /** Bounded source capacity; each instance processes only its configured active width. */
    static constexpr std::size_t kMaximumChannelCount = kStemwiseMusicChannelCount;
    /** Spectral input domain; neutral bypass remains bit-exact without an amplitude clamp. */
    static constexpr float kMaximumSpectralInputMagnitude = 65'536.0F;
    // Direct native callers are clamped to the same product range as every public control layer.
    static constexpr float kMinimumTempo = 0.5F;
    static constexpr float kMaximumTempo = 2.0F;
    static constexpr float kMinimumPitchSemitones = -12.0F;
    static constexpr float kMaximumPitchSemitones = 12.0F;
    // Signalsmith's recommended musical-content tonality limit. Supplying this on every
    // transpose update preserves more upper-frequency timbre than the unbounded frequency map.
    static constexpr float kTransposeTonalityLimitHz = 8'000.0F;
    // Keep the interaction window qualified by the complete seek/automation suite. A longer
    // window increased every processed transition's lookahead without benefiting the supported
    // musical range.
    static constexpr std::uint32_t kAnalysisBlockMilliseconds = 120U;
    static constexpr std::uint32_t kAnalysisIntervalMilliseconds = 20U;

    /**
     * Configures Signalsmith and preallocates planar scratch buffers.
     * This is a render-worker setup operation and may allocate or throw std::invalid_argument.
     */
    TimePitchProcessor(
        std::uint32_t sampleRateHz,
        std::size_t maximumOutputFrames,
        std::size_t channelCount = kChannelCount,
        std::size_t interleavedStride = 0U);
    ~TimePitchProcessor();

    TimePitchProcessor(const TimePitchProcessor &) = delete;
    TimePitchProcessor &operator=(const TimePitchProcessor &) = delete;
    TimePitchProcessor(TimePitchProcessor &&) = delete;
    TimePitchProcessor &operator=(TimePitchProcessor &&) = delete;

    /**
     * Applies finite controls. Values outside the supported range are clamped and return false;
     * finite values in range return true. Non-finite requests are rejected without changing state.
     * preserveProcessedTimeline primes a fresh processor when inheriting an already processed
     * song, including when that song's current controls are neutral.
     */
    [[nodiscard]] bool setControls(
        float tempo,
        float pitchSemitones,
        bool preserveProcessedTimeline = false) noexcept;

    /** Input needed for the next successful process call; does not advance ratio state. */
    [[nodiscard]] std::size_t requiredInputFrames(
        std::size_t requestedOutputFrames) const noexcept;

    /** Exact bounded future-source window required at the desired transition timeline frame. */
    [[nodiscard]] TimePitchPrimeRequirements primeRequirements() const noexcept;

    /**
     * Primes Signalsmith with future PCM beginning exactly at the transition source frame.
     * On success, the next process input begins at nextInputOffsetFrames into that same source
     * timeline. outputSeek() precomputes the spectral pre-roll; no silence is emitted or inserted.
     * A nonzero work limit yields InProgress. Subsequent calls reuse the copied lookahead;
     * no timeline offset is consumed until completion. Reset or a control change cancels it.
     */
    [[nodiscard]] TimePitchPrimeResult prime(
        const float *interleavedLookahead,
        std::size_t availableLookaheadFrames,
        std::size_t maximumPrerollOutputFrames = 0U) noexcept;

    /**
     * Produces exactly requestedOutputFrames or no frames. availableInputFrames may be larger than
     * requiredInputFrames(); only the required prefix is consumed. The output capacity is expressed
     * in stereo frames, not samples.
     */
    [[nodiscard]] TimePitchProcessResult process(
        const float *interleavedInput,
        std::size_t availableInputFrames,
        float *interleavedOutput,
        std::size_t outputCapacityFrames,
        std::size_t requestedOutputFrames) noexcept;

    /**
     * Clears spectral history and the fractional input scheduler. Seek and discontinuity therefore
     * never reuse audio from the preceding timeline. Cumulative diagnostics remain available.
     */
    void reset(TimePitchResetReason reason = TimePitchResetReason::Explicit) noexcept;

    /** Named timeline boundaries for call sites which should not traffic in reset reasons. */
    void onDiscontinuity() noexcept;
    void onSeek() noexcept;

    [[nodiscard]] TimePitchDiagnostics diagnostics() const noexcept;

    /** Admits bounded channels without resetting existing channels or the ratio scheduler. */
    [[nodiscard]] bool setActiveChannelCount(std::size_t count) noexcept;

    /** Current admitted interleaved width; safe on the render worker. */
    [[nodiscard]] std::size_t channelCount() const noexcept;

private:
    class Impl;
    std::unique_ptr<Impl> mImpl;
};

}  // namespace stemwise::audio::engine
