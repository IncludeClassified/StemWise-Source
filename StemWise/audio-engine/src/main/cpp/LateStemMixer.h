#pragma once

#include "StemwiseMultibus.h"

#include <array>
#include <cstddef>
#include <cstdint>

namespace stemwise::audio {

enum class LateStemSourceMode : std::uint8_t {
    Original = 0,
    Stems = 1,
};

/**
 * One complete callback-authoritative endpoint. Effective stem gains already include mute/solo;
 * the callback never reconstructs policy from independently published fields.
 */
struct LateStemMixControlSnapshot final {
    std::uint64_t publicationSequence{0U};
    std::uint64_t generation{0U};
    std::uint64_t selectionToken{0U};
    std::uint64_t canonicalControlRevision{0U};
    std::uint64_t sourceIntentRevision{0U};
    std::uint64_t stemIntentRevision{0U};
    /** Exact UI preview identity; independent from callback mailbox publicationSequence. */
    std::uint64_t stemGainPreviewPublicationSequence{0U};
    std::uint64_t stemGainPreviewCoversOfferSequence{0U};
    std::uint32_t stemGainPreviewActiveMask{0U};
    std::array<std::uint32_t, kStemwiseStemBusCount> stemGainPreviewGainBits{};
    LateStemSourceMode sourceMode{LateStemSourceMode::Original};
    std::uint32_t requiredStemMask{kStemwiseAllStemBusesReadyMask};
    std::array<float, kStemwiseStemBusCount> effectiveStemGains = stemwiseFilledArray<float, kStemwiseStemBusCount>(1.0F);
    float musicGain{1.0F};
    float mixHeadroom{0.9F};
    std::uint32_t sourceRampFrames{240U};
    std::uint32_t laneRampFrames{240U};
    std::uint32_t masterRampFrames{240U};

    [[nodiscard]] bool valid() const noexcept;
    [[nodiscard]] bool operator==(const LateStemMixControlSnapshot &) const noexcept = default;
};

struct LateStemMixerDiagnostics final {
    std::uint64_t controlSnapshotsApplied{0U};
    std::uint64_t controlSnapshotsRejected{0U};
    std::uint64_t ownerResets{0U};
    std::uint64_t renderedFrames{0U};
    std::uint64_t nonFiniteSamplesScrubbed{0U};
    std::uint64_t limiterActiveFrames{0U};
    std::uint64_t limiterSafetyClamps{0U};
    std::uint64_t safeOriginalFallbackFrames{0U};
};

/**
 * Callback-owned final mixer for one output-ring role.
 *
 * Construction performs every table calculation. applyControl() and mix() are bounded,
 * allocation-free, lock-free, noexcept operations suitable for the output consumer. The render
 * payload is fixed interleaved padded multibus PCM described by StemwiseMultibus.h.
 */
class LateStemMixer final {
public:
    static constexpr std::uint32_t kDefaultRampFrames = 240U;
    static constexpr float kLimiterCeiling = 0.98F;

    explicit LateStemMixer(std::uint32_t sampleRateHz = 48'000U);

    LateStemMixer(const LateStemMixer &) = delete;
    LateStemMixer &operator=(const LateStemMixer &) = delete;

    /**
     * Applies a newer exact-owner endpoint. A first endpoint for an owner is immediate; later
     * endpoints use their declared ramps except an authoritative all-muted Stems endpoint, which
     * hard-silences music immediately while retaining the queued metronome click.
     */
    [[nodiscard]] bool applyControl(
        const LateStemMixControlSnapshot &snapshot,
        std::uint32_t stemBusReadyMask = kStemwiseAllStemBusesReadyMask) noexcept;

    /** Explicit role/generation retirement; the next valid endpoint initializes immediately. */
    void reset() noexcept;

    /**
     * Mixes padded multibus render payload into interleaved stereo. Missing stem buses force the safe
     * Original endpoint; they never expose a partial or stale stem sum.
     */
    [[nodiscard]] bool mix(
        const float *interleavedMultibus,
        std::size_t frameCount,
        std::uint32_t stemBusReadyMask,
        float *interleavedStereo) noexcept;

    [[nodiscard]] const LateStemMixerDiagnostics &diagnostics() const noexcept;

#if defined(STEMWISE_MERGER_TEST_CAPTURE)
    // Test-owned observation before source blend, headroom, limiter and clamp; never changes PCM.
    void captureLinearStemsForTest(float *stereo) noexcept { mTestLinearCapture = stereo; }
#endif

private:
    class LinearRamp final {
    public:
        void setImmediate(float value) noexcept;
        void setTarget(float value, std::uint32_t frames) noexcept;
        [[nodiscard]] float next() noexcept;
        [[nodiscard]] float target() const noexcept;

    private:
        float mCurrent{0.0F};
        float mTarget{0.0F};
        float mStep{0.0F};
        std::uint32_t mRemaining{0U};
    };
#if defined(STEMWISE_MERGER_TEST_CAPTURE)
    float *mTestLinearCapture{nullptr};
#endif

    static constexpr std::size_t kLimiterTableSize = 4'097U;
    static constexpr float kLimiterTableMaximumPeak = 64.0F;

    [[nodiscard]] float limiterDesiredGain(float peak) const noexcept;
    void processLimiter(float &left, float &right) noexcept;
    void scrubFinite(float &sample) noexcept;
    void safetyClamp(float &sample) noexcept;
    void initializeForOwner(
        const LateStemMixControlSnapshot &snapshot,
        std::uint32_t stemBusReadyMask) noexcept;

    std::array<float, kLimiterTableSize> mLimiterGainTable{};
    float mLimiterAttackCoefficient{0.0F};
    float mLimiterReleaseCoefficient{0.0F};
    float mLimiterSmoothedGain{1.0F};
    std::uint64_t mGeneration{0U};
    std::uint64_t mSelectionToken{0U};
    std::uint64_t mPublicationSequence{0U};
    std::uint32_t mSourceRampFrames{kDefaultRampFrames};
    std::array<std::uint32_t, kStemwiseStemBusCount> mLaneRampFrames =
        stemwiseFilledArray<std::uint32_t, kStemwiseStemBusCount>(kDefaultRampFrames);
    std::uint32_t mMasterRampFrames{kDefaultRampFrames};
    LinearRamp mSourceRamp;
    std::array<LinearRamp, kStemwiseStemBusCount> mStemGainRamps{};
    LinearRamp mMasterRamp;
    LateStemSourceMode mRequestedSourceMode{LateStemSourceMode::Original};
    std::uint32_t mRequiredStemMask{kStemwiseAllStemBusesReadyMask};
    std::size_t mStemLaneCount{kStemwiseStemBusCount};
    bool mReadinessInitialized{false};
    bool mLastAllStemsReady{false};
    bool mHasLastMusicSample{false};
    float mLastMusicLeft{0.0F};
    float mLastMusicRight{0.0F};
    float mFallbackFromLeft{0.0F};
    float mFallbackFromRight{0.0F};
    std::uint32_t mFallbackTotalFrames{0U};
    std::uint32_t mFallbackAppliedFrames{0U};
    LateStemMixerDiagnostics mDiagnostics{};
};

}  // namespace stemwise::audio
