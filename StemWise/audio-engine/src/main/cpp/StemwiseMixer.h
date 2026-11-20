#pragma once
#include "native_engine/NativePlaybackLimits.h"

#include "StemwiseMultibus.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <memory>

namespace stemwise::audio {

inline constexpr std::size_t kStemLaneCount = kStemwiseStemBusCount;
inline constexpr std::size_t kMixerLaneCount = kStemLaneCount + 1;

enum class MixerSourceMode : std::uint8_t {
    Original,
    Stems,
};

enum class MetronomeMode : std::uint8_t {
    Off,
    FreeBpm,
    BeatGrid,
};

struct StereoPcmBlock {
    const float *samples = nullptr;
    std::uint32_t frameCount = 0;
};

/** One already-resampled, sample-aligned music block entering the pre-DSP mixer. */
struct PreDspRenderBlock {
    StereoPcmBlock original;
    std::array<StereoPcmBlock, kStemLaneCount> stems{};
    std::uint32_t frameCount = 0;
    std::int64_t timelineStartFrame = 0;
    std::uint64_t selectionToken = 0;
    std::uint64_t generation = 0;
};

/** Exact source interval represented by one post-DSP output block. */
struct SourceTimelineInterval {
    double startFrame = 0.0;
    double endFrameExclusive = 0.0;
};

/** Declared stereo music buses (up to kStemwiseMusicBusCount) entering the render-side auxiliary pass. */
struct PostDspMultibusRenderBlock {
    const float *processedMusicBuses = nullptr;
    std::uint32_t frameCount = 0;
    std::int64_t outputClockStartFrame = 0;
    SourceTimelineInterval sourceTimeline{};
    std::uint64_t selectionToken = 0;
    std::uint64_t generation = 0;
};

struct StemLaneControl {
    float gain = 1.0f;
    bool muted = false;
    bool soloed = false;
};

struct MetronomeControl {
    MetronomeMode mode = MetronomeMode::Off;
    float bpm = 120.0f;
    float gain = 0.35f;
    std::uint8_t subdivision = 1;
    std::uint8_t beatsPerBar = 4;
    /** Notated beat unit used by FreeBpm. Supported values are 1, 2, 4, 8, 16, and 32. */
    std::uint8_t beatUnitDenominator = 4;
};



/**
 * Immutable control snapshot. A revision is accepted only between render calls and only once.
 * Every audible Boolean/gain/source change reaches its target through a bounded sample ramp.
 */
struct MixerControlSnapshot {
    std::uint64_t revision = 1;
    MixerSourceMode sourceMode = MixerSourceMode::Original;
    std::array<StemLaneControl, kStemLaneCount> stems{};
    /** Declared lane count, independent from current decoder readiness. */
    std::uint32_t stemLaneCount = kStemLaneCount;
    float musicGain = 1.0f;
    float mixHeadroom = 0.9f;
    std::uint32_t sourceTransitionFrames = 240;
    std::uint32_t laneRampFrames = 240;
    std::uint32_t masterRampFrames = 240;
    std::uint32_t defaultDeclickFrames = 240;
    MetronomeControl metronome{};
};

struct MixerConfiguration {
    std::uint32_t sampleRateHz = 48'000;
    std::uint32_t maximumBlockFrames = 2'048;
    std::uint32_t maximumBeatGridPoints = engine::kMaximumNativeBeatGridPoints;
};



struct StemwiseMixerDiagnostics {
    std::uint64_t controlSnapshotsApplied = 0;
    std::uint64_t staleControlSnapshotsRejected = 0;
    std::uint64_t sanitizedControlValues = 0;
    std::uint64_t preDspRenderCalls = 0;
    std::uint64_t preDspRenderedFrames = 0;
    std::uint64_t postDspRenderCalls = 0;
    std::uint64_t postDspRenderedFrames = 0;
    std::uint64_t invalidPreDspRenderCalls = 0;
    std::uint64_t invalidPostDspRenderCalls = 0;
    std::uint64_t invalidOwnershipBlocks = 0;
    std::uint64_t ownershipChanges = 0;
    std::uint64_t timelineDiscontinuities = 0;
    std::uint64_t originalMissingBlocks = 0;
    std::uint64_t originalUnderrunFrames = 0;
    std::array<std::uint64_t, kStemLaneCount> stemMissingBlocks{};
    std::array<std::uint64_t, kStemLaneCount> stemUnderrunFrames{};
    std::uint64_t preDspNonFiniteSamplesScrubbed = 0;
    std::uint64_t postDspNonFiniteSamplesScrubbed = 0;
    std::uint64_t metronomeEvents = 0;
    std::uint64_t metronomeAccentEvents = 0;
    std::uint64_t metronomeBaseBeatEvents = 0;
    std::uint64_t metronomeSubdivisionEvents = 0;
    std::uint64_t metronomeVoiceSteals = 0;
    std::uint64_t invalidBeatGridsRejected = 0;
    std::uint64_t beatGridClears = 0;
    std::uint64_t loopFadeOutRequests = 0;
    std::uint64_t loopWraps = 0;
    std::uint64_t discontinuityResets = 0;
};

/**
 * Sanitized target endpoint admitted by the render-thread mixer.
 *
 * Effective stem gains already include canonical mute/solo policy and any gain overlay which the
 * caller merged into the submitted MixerControlSnapshot. This is a target snapshot, not evidence
 * that the render-thread ramps have settled; the production multibus path applies those ramps in
 * the audio callback.
 */
struct MixerLateControlEndpoint final {
    std::uint64_t mixerRevision{0U};
    MixerSourceMode sourceMode{MixerSourceMode::Original};
    std::array<float, kStemLaneCount> effectiveStemGains{};
    float musicGain{1.0F};
    float mixHeadroom{0.9F};
    std::uint32_t sourceRampFrames{240U};
    std::uint32_t laneRampFrames{240U};
    std::uint32_t masterRampFrames{240U};
};

/**
 * Allocation-free two-stage render-worker mixer. The mandatory order is:
 *
 * renderPreDspMultibus() -> external TimePitch -> renderPostDspMultibus() -> callback mix/device stage.
 *
 * Metronome/declick belong to the render payload; final mixing/limiting belongs to the callback. This class is
 * intentionally thread-confined and must never be called by the output consumer.
 */
class StemwiseMixer final {
public:
    explicit StemwiseMixer(const MixerConfiguration &configuration);
    ~StemwiseMixer();

    StemwiseMixer(const StemwiseMixer &) = delete;
    StemwiseMixer &operator=(const StemwiseMixer &) = delete;
    StemwiseMixer(StemwiseMixer &&) = delete;
    StemwiseMixer &operator=(StemwiseMixer &&) = delete;

    bool applyControls(const MixerControlSnapshot &snapshot) noexcept;

    /** Strictly increasing base beats with one explicit 0/1 song-authored accent per beat. */
    bool setBeatGrid(
        const std::int64_t *beatFrames,
        const std::uint8_t *accentFlags,
        std::size_t beatCount) noexcept;

    /** Explicitly removes all song-owned beat events and active grid-click voices. */
    void clearBeatGrid() noexcept;

    /** Ramps the current tail to zero before a known loop/seek boundary. */
    void beginPostDspLoopFadeOut(std::uint32_t fadeFrames = 0) noexcept;

    /** Cancels an obsolete loop fade without resetting audible DSP state. */
    void continuePostDspAfterLoopPolicyChange(std::uint32_t fadeFrames = 0) noexcept;

    /** Resets transport-owned state at a loop wrap and fades/crossfades the new head in. */
    void completePostDspLoopWrap(
        std::uint64_t selectionToken,
        std::uint64_t generation,
        std::uint32_t fadeFrames = 0) noexcept;

    /** Deterministic seek/song reset; the first new sample starts at the preceding final sample. */
    void resetPostDspForDiscontinuity(
        std::uint64_t selectionToken,
        std::uint64_t generation,
        std::uint32_t crossfadeFrames = 0) noexcept;

    /** Retags a retained, already-rendered loop head without restarting its tail envelope. */
    bool rebindPostDspOwner(std::uint64_t selectionToken, std::uint64_t previousGeneration,
        std::uint64_t nextGeneration) noexcept;


    /** Copies declared aligned stereo buses without applying source or stem gain policy. */
    bool renderPreDspMultibus(
        const PreDspRenderBlock &input,
        float *interleavedMusicBuses,
        std::uint32_t outputCapacityFrames) noexcept;


    /**
     * Writes bounded padded render payload: declared stereo buses, mono click, and a click-free envelope.
     * Stem/source/master gain, limiter, final clamp and final fingerprint remain callback-owned.
     */
    bool renderPostDspMultibus(
        const PostDspMultibusRenderBlock &input,
        float *interleavedRenderPayload,
        std::uint32_t outputCapacityFrames) noexcept;


    /** Exact sanitized target represented by the most recently accepted control snapshot. */
    [[nodiscard]] MixerLateControlEndpoint lateControlEndpoint() const noexcept;

    [[nodiscard]] const StemwiseMixerDiagnostics &diagnostics() const noexcept;
    void clearDiagnostics() noexcept;

private:
    class Impl;
    std::unique_ptr<Impl> mImpl;
};

}  // namespace stemwise::audio
