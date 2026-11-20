#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>

namespace stemwise::audio::engine {

enum class SampleRateResetReason : std::uint8_t {
    Explicit,
    Discontinuity,
    Seek,
};

enum class SampleRateProcessStatus : std::uint8_t {
    Ok,
    OutputCapacityExhausted,
    InvalidBuffer,
    InputQuantumExceeded,
    OutputQuantumExceeded,
    BackendFailure,
};

struct SampleRateProcessResult final {
    SampleRateProcessStatus status{SampleRateProcessStatus::Ok};
    std::size_t inputFramesConsumed{0};
    std::size_t outputFramesProduced{0};
    int backendErrorCode{0};
};

struct SampleRateDiagnostics final {
    std::uint32_t sourceRateHz{0};
    std::uint32_t destinationRateHz{0};
    std::uint32_t reducedRatioNumerator{0};
    std::uint32_t reducedRatioDenominator{0};
    int quality{0};
    bool exactBypass{false};
    bool initialZerosSkipped{false};
    std::size_t maximumInputFrames{0};
    std::size_t maximumOutputFrames{0};

    // Speex's filter delay remains observable. skip_zeros() compensates initial stream alignment,
    // so the externally visible startup delay is zero for both processed and bypass streams.
    std::size_t filterInputLatencyFrames{0};
    std::size_t filterOutputLatencyFrames{0};
    std::size_t compensatedOutputLatencyFrames{0};

    std::uint64_t generation{0};
    std::uint64_t processCalls{0};
    std::uint64_t partialProcessCalls{0};
    std::uint64_t inputFramesConsumed{0};
    std::uint64_t outputFramesProduced{0};
    std::uint64_t generationInputFramesConsumed{0};
    std::uint64_t generationOutputFramesProduced{0};

    // Exact rational progress for drift monitoring within the current reset/seek generation.
    std::uint64_t generationExpectedOutputFramesFloor{0};
    std::uint32_t generationExpectedOutputRemainderNumerator{0};
    std::int64_t generationRawOutputFrameError{0};
    // Adds the known pending filter tail to produced frames; steady streams should remain <=1.
    std::int64_t generationOutputFrameError{0};

    std::uint64_t resetCount{0};
    std::uint64_t discontinuityCount{0};
    std::uint64_t seekCount{0};
    std::uint64_t rejectedProcessCount{0};
    std::uint64_t backendFailureCount{0};
    int lastBackendErrorCode{0};
    std::uint64_t nonFiniteInputSampleCount{0};
    std::uint64_t nonFiniteOutputSampleCount{0};
};

/**
 * Streaming stereo float sample-rate conversion owned by StemWise's native render engine.
 *
 * The API is push-oriented: process() may consume a prefix of the supplied input when output
 * capacity is exhausted, and always reports exact whole stereo frames consumed and produced.
 * Retry with the unconsumed input suffix. Neither path writes beyond outputCapacityFrames.
 *
 * Threading contract:
 * - Construct, process, reset and query from one decoder/render-worker thread.
 * - Never call this object from the audio-device callback.
 * - Construction may allocate. Steady-state process(), reset(), onSeek(), onDiscontinuity() and
 *   diagnostics() perform no project-owned allocation, locks, logging, JNI, or file I/O.
 * - Rate and quality are immutable controls. Reconfigure by constructing a replacement on the
 *   setup worker before handing it to the render worker; no allocating live-control path exists.
 *
 * When rates match, process() is a zero-latency bit-exact memmove. Differing rates use a private
 * Speex resampler initialized with skip_zeros(). Reset/seek/discontinuity repeat that policy so
 * separately constructed stem lanes begin at the same compensated timeline frame.
 */
class SampleRateConverter final {
public:
    static constexpr std::size_t kChannelCount = 2;
    static constexpr std::uint32_t kMinimumRateHz = 8'000;
    static constexpr std::uint32_t kMaximumRateHz = 384'000;
    static constexpr int kMinimumQuality = 0;
    static constexpr int kMaximumQuality = 10;
    static constexpr int kDefaultQuality = 5;

    SampleRateConverter(
        std::uint32_t sourceRateHz,
        std::uint32_t destinationRateHz,
        std::size_t maximumInputFrames,
        int quality = kDefaultQuality);
    ~SampleRateConverter();

    SampleRateConverter(const SampleRateConverter &) = delete;
    SampleRateConverter &operator=(const SampleRateConverter &) = delete;
    SampleRateConverter(SampleRateConverter &&) = delete;
    SampleRateConverter &operator=(SampleRateConverter &&) = delete;

    [[nodiscard]] SampleRateProcessResult process(
        const float *interleavedInput,
        std::size_t inputFrames,
        float *interleavedOutput,
        std::size_t outputCapacityFrames) noexcept;

    void reset(SampleRateResetReason reason = SampleRateResetReason::Explicit) noexcept;
    void onDiscontinuity() noexcept;
    void onSeek() noexcept;

    [[nodiscard]] SampleRateDiagnostics diagnostics() const noexcept;

private:
    class Impl;
    std::unique_ptr<Impl> mImpl;
};

}  // namespace stemwise::audio::engine
