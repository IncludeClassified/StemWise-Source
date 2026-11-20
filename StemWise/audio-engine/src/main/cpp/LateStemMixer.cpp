#include "LateStemMixer.h"

#include <algorithm>
#include <bit>
#include <cmath>
#include <limits>
#include <stdexcept>

namespace stemwise::audio {
namespace {

constexpr float kAudibleEpsilon = 1.0e-6F;
constexpr float kLimiterKneeDb = 6.0F;
constexpr float kLimiterAttackMs = 2.0F;
constexpr float kLimiterReleaseMs = 120.0F;

[[nodiscard]] bool validRamp(std::uint32_t frames, std::uint32_t sampleRateHz) noexcept {
    return frames != 0U && frames <= sampleRateHz * 2U;
}

[[nodiscard]] float desiredLimiterGain(float peak) noexcept {
    if (!(peak > 1.0e-12F)) return 1.0F;
    const float inputDb = 20.0F * std::log10(peak);
    const float ceilingDb = 20.0F * std::log10(LateStemMixer::kLimiterCeiling);
    const float overDb = inputDb - ceilingDb;
    const float halfKnee = kLimiterKneeDb * 0.5F;
    float gainDb = 0.0F;
    if (overDb >= halfKnee) {
        gainDb = -overDb;
    } else if (overDb > -halfKnee) {
        const float kneePosition = overDb + halfKnee;
        gainDb = -(kneePosition * kneePosition) / (2.0F * kLimiterKneeDb);
    }
    return std::pow(10.0F, gainDb / 20.0F);
}

}  // namespace

bool LateStemMixControlSnapshot::valid() const noexcept {
    constexpr std::uint32_t kAllStemLanes =
        (1U << static_cast<std::uint32_t>(kStemwiseStemBusCount)) - 1U;
    if (publicationSequence == 0U || generation == 0U || selectionToken == 0U ||
        (sourceMode != LateStemSourceMode::Original && sourceMode != LateStemSourceMode::Stems) ||
        canonicalControlRevision == 0U || sourceIntentRevision == 0U ||
        stemIntentRevision == 0U ||
        (stemGainPreviewPublicationSequence == 0U) !=
            (stemGainPreviewCoversOfferSequence == 0U) ||
        (stemGainPreviewActiveMask & ~kAllStemLanes) != 0U ||
        (requiredStemMask & ~kAllStemLanes) != 0U ||
        (requiredStemMask & (requiredStemMask + 1U)) != 0U ||
        (sourceMode == LateStemSourceMode::Stems && requiredStemMask == 0U) ||
        (stemGainPreviewActiveMask & ~requiredStemMask) != 0U ||
        (stemGainPreviewPublicationSequence == 0U && stemGainPreviewActiveMask != 0U) ||
        !std::isfinite(musicGain) ||
        !std::isfinite(mixHeadroom) || musicGain < 0.0F || musicGain > 2.0F ||
        mixHeadroom < 0.0F || mixHeadroom > 1.0F ||
        !validRamp(sourceRampFrames, 384'000U) ||
        !validRamp(laneRampFrames, 384'000U) ||
        !validRamp(masterRampFrames, 384'000U)) {
        return false;
    }
    const bool effectiveGainsValid = std::all_of(
        effectiveStemGains.begin(),
        effectiveStemGains.end(),
        [](float gain) noexcept {
            return std::isfinite(gain) && gain >= 0.0F && gain <= 2.0F;
        });
    const bool previewGainsValid = std::all_of(
        stemGainPreviewGainBits.begin(),
        stemGainPreviewGainBits.end(),
        [this](std::uint32_t bits) noexcept {
            if (stemGainPreviewPublicationSequence == 0U) return bits == 0U;
            const float gain = std::bit_cast<float>(bits);
            return std::isfinite(gain) && gain >= 0.0F && gain <= 2.0F;
        });
    return effectiveGainsValid && previewGainsValid;
}

void LateStemMixer::LinearRamp::setImmediate(float value) noexcept {
    mCurrent = value;
    mTarget = value;
    mStep = 0.0F;
    mRemaining = 0U;
}

void LateStemMixer::LinearRamp::setTarget(float value, std::uint32_t frames) noexcept {
    // A publication for another control must not restart this in-flight ramp.
    if (value == mTarget && frames > 1U) return;
    mTarget = value;
    if (frames <= 1U || std::abs(mTarget - mCurrent) <= kAudibleEpsilon) {
        setImmediate(value);
        return;
    }
    mRemaining = frames;
    mStep = (mTarget - mCurrent) / static_cast<float>(frames - 1U);
}

float LateStemMixer::LinearRamp::next() noexcept {
    if (mRemaining == 0U) return mCurrent;
    const float result = mCurrent;
    if (mRemaining == 1U) {
        setImmediate(mTarget);
    } else {
        mCurrent += mStep;
        --mRemaining;
    }
    return result;
}

float LateStemMixer::LinearRamp::target() const noexcept {
    return mTarget;
}

LateStemMixer::LateStemMixer(std::uint32_t sampleRateHz)
{
    if (sampleRateHz < 8'000U || sampleRateHz > 384'000U) {
        throw std::invalid_argument("LateStemMixer sample rate is unsupported");
    }
    for (std::size_t index = 0U; index < mLimiterGainTable.size(); ++index) {
        const float peak = kLimiterTableMaximumPeak * static_cast<float>(index) /
            static_cast<float>(mLimiterGainTable.size() - 1U);
        mLimiterGainTable[index] = desiredLimiterGain(peak);
    }
    const float sampleRate = static_cast<float>(sampleRateHz);
    mLimiterAttackCoefficient = std::exp(
        -1.0F / std::max(kLimiterAttackMs * 0.001F * sampleRate, 1.0F));
    mLimiterReleaseCoefficient = std::exp(
        -1.0F / std::max(kLimiterReleaseMs * 0.001F * sampleRate, 1.0F));
    mSourceRamp.setImmediate(0.0F);
    for (LinearRamp &ramp : mStemGainRamps) ramp.setImmediate(1.0F);
    mMasterRamp.setImmediate(0.9F);
}

void LateStemMixer::initializeForOwner(
    const LateStemMixControlSnapshot &snapshot,
    std::uint32_t stemBusReadyMask) noexcept {
    mGeneration = snapshot.generation;
    mSelectionToken = snapshot.selectionToken;
    mPublicationSequence = snapshot.publicationSequence;
    mRequestedSourceMode = snapshot.sourceMode;
    mRequiredStemMask = snapshot.requiredStemMask;
    mStemLaneCount = static_cast<std::size_t>(std::countr_one(mRequiredStemMask));
    mSourceRampFrames = snapshot.sourceRampFrames;
    mLaneRampFrames.fill(snapshot.laneRampFrames);
    mMasterRampFrames = snapshot.masterRampFrames;
    const bool stemsEndpoint = snapshot.sourceMode == LateStemSourceMode::Stems &&
        (mRequiredStemMask != 0U && (stemBusReadyMask & mRequiredStemMask) == mRequiredStemMask);
    mSourceRamp.setImmediate(stemsEndpoint ? 1.0F : 0.0F);
    for (std::size_t lane = 0U; lane < mStemGainRamps.size(); ++lane) {
        mStemGainRamps[lane].setImmediate(snapshot.effectiveStemGains[lane]);
    }
    const bool allMutedStems = snapshot.sourceMode == LateStemSourceMode::Stems &&
        std::all_of(snapshot.effectiveStemGains.begin(),
            snapshot.effectiveStemGains.begin() + mStemLaneCount,
            [](float gain) noexcept { return gain <= kAudibleEpsilon; });
    mMasterRamp.setImmediate(allMutedStems ? 0.0F : snapshot.musicGain * snapshot.mixHeadroom);
    mLimiterSmoothedGain = 1.0F;
    mReadinessInitialized = true;
    mLastAllStemsReady = (mRequiredStemMask != 0U && (stemBusReadyMask & mRequiredStemMask) == mRequiredStemMask);
    mHasLastMusicSample = false;
    mLastMusicLeft = 0.0F;
    mLastMusicRight = 0.0F;
    mFallbackFromLeft = 0.0F;
    mFallbackFromRight = 0.0F;
    mFallbackTotalFrames = 0U;
    mFallbackAppliedFrames = 0U;
    if (mDiagnostics.ownerResets != std::numeric_limits<std::uint64_t>::max()) {
        ++mDiagnostics.ownerResets;
    }
}

bool LateStemMixer::applyControl(
    const LateStemMixControlSnapshot &snapshot,
    std::uint32_t stemBusReadyMask) noexcept {
    if (!snapshot.valid() ||
        (stemBusReadyMask & ~kStemwiseAllStemBusesReadyMask) != 0U ||
        (mGeneration != 0U &&
         (snapshot.generation != mGeneration || snapshot.selectionToken != mSelectionToken)) ||
        (snapshot.generation == mGeneration && snapshot.selectionToken == mSelectionToken &&
         snapshot.publicationSequence <= mPublicationSequence)) {
        if (mDiagnostics.controlSnapshotsRejected !=
            std::numeric_limits<std::uint64_t>::max()) {
            ++mDiagnostics.controlSnapshotsRejected;
        }
        return false;
    }
    if (mGeneration == 0U) {
        initializeForOwner(snapshot, stemBusReadyMask);
    } else {
        mPublicationSequence = snapshot.publicationSequence;
        mRequestedSourceMode = snapshot.sourceMode;
        mRequiredStemMask = snapshot.requiredStemMask;
        mStemLaneCount = static_cast<std::size_t>(std::countr_one(mRequiredStemMask));
        mSourceRampFrames = snapshot.sourceRampFrames;
        mLaneRampFrames.fill(snapshot.laneRampFrames);
        mMasterRampFrames = snapshot.masterRampFrames;
        const bool stemsEndpoint = snapshot.sourceMode == LateStemSourceMode::Stems &&
            (mRequiredStemMask != 0U && (stemBusReadyMask & mRequiredStemMask) == mRequiredStemMask);
        mSourceRamp.setTarget(stemsEndpoint ? 1.0F : 0.0F, mSourceRampFrames);
        const bool allMutedStems = snapshot.sourceMode == LateStemSourceMode::Stems &&
            std::all_of(
                snapshot.effectiveStemGains.begin(),
                snapshot.effectiveStemGains.begin() + mStemLaneCount,
                [](float gain) noexcept { return gain <= kAudibleEpsilon; });
        for (std::size_t lane = 0U; lane < mStemGainRamps.size(); ++lane) {
            mStemGainRamps[lane].setTarget(
                snapshot.effectiveStemGains[lane], mLaneRampFrames[lane]);
        }
        mMasterRamp.setTarget(
            allMutedStems ? 0.0F : snapshot.musicGain * snapshot.mixHeadroom,
            mMasterRampFrames);
    }
    if (mDiagnostics.controlSnapshotsApplied != std::numeric_limits<std::uint64_t>::max()) {
        ++mDiagnostics.controlSnapshotsApplied;
    }
    return true;
}

void LateStemMixer::reset() noexcept {
    mGeneration = 0U;
    mSelectionToken = 0U;
    mPublicationSequence = 0U;
    mRequestedSourceMode = LateStemSourceMode::Original;
    mSourceRamp.setImmediate(0.0F);
    for (LinearRamp &ramp : mStemGainRamps) ramp.setImmediate(1.0F);
    mMasterRamp.setImmediate(0.9F);
    mLimiterSmoothedGain = 1.0F;
    mReadinessInitialized = false;
    mLastAllStemsReady = false;
    mHasLastMusicSample = false;
    mLastMusicLeft = 0.0F;
    mLastMusicRight = 0.0F;
    mFallbackFromLeft = 0.0F;
    mFallbackFromRight = 0.0F;
    mFallbackTotalFrames = 0U;
    mFallbackAppliedFrames = 0U;
}

float LateStemMixer::limiterDesiredGain(float peak) const noexcept {
    if (!(peak > 0.0F)) return 1.0F;
    if (peak >= kLimiterTableMaximumPeak) return kLimiterCeiling / peak;
    const float scaled = peak *
        static_cast<float>(mLimiterGainTable.size() - 1U) /
        kLimiterTableMaximumPeak;
    const auto lower = static_cast<std::size_t>(scaled);
    const std::size_t upper = std::min(lower + 1U, mLimiterGainTable.size() - 1U);
    const float fraction = scaled - static_cast<float>(lower);
    return mLimiterGainTable[lower] +
        (mLimiterGainTable[upper] - mLimiterGainTable[lower]) * fraction;
}

void LateStemMixer::processLimiter(float &left, float &right) noexcept {
    const float peak = std::max(std::abs(left), std::abs(right));
    const float desiredGain = limiterDesiredGain(peak);
    const float coefficient = desiredGain < mLimiterSmoothedGain
        ? mLimiterAttackCoefficient
        : mLimiterReleaseCoefficient;
    mLimiterSmoothedGain = coefficient * mLimiterSmoothedGain +
        (1.0F - coefficient) * desiredGain;
    const float instantaneousSafetyGain = peak > kLimiterCeiling
        ? kLimiterCeiling / peak
        : 1.0F;
    const float appliedGain = std::min(mLimiterSmoothedGain, instantaneousSafetyGain);
    if (appliedGain < 0.999999F &&
        mDiagnostics.limiterActiveFrames != std::numeric_limits<std::uint64_t>::max()) {
        ++mDiagnostics.limiterActiveFrames;
    }
    left *= appliedGain;
    right *= appliedGain;
}

void LateStemMixer::scrubFinite(float &sample) noexcept {
    if (std::isfinite(sample)) return;
    sample = 0.0F;
    if (mDiagnostics.nonFiniteSamplesScrubbed !=
        std::numeric_limits<std::uint64_t>::max()) {
        ++mDiagnostics.nonFiniteSamplesScrubbed;
    }
}

void LateStemMixer::safetyClamp(float &sample) noexcept {
    const float clamped = std::clamp(sample, -kLimiterCeiling, kLimiterCeiling);
    if (clamped != sample &&
        mDiagnostics.limiterSafetyClamps != std::numeric_limits<std::uint64_t>::max()) {
        ++mDiagnostics.limiterSafetyClamps;
    }
    sample = clamped;
}

bool LateStemMixer::mix(
    const float *interleavedMultibus,
    std::size_t frameCount,
    std::uint32_t stemBusReadyMask,
    float *interleavedStereo) noexcept {
    if (mGeneration == 0U || interleavedMultibus == nullptr ||
        interleavedStereo == nullptr || frameCount == 0U ||
        (stemBusReadyMask & ~kStemwiseAllStemBusesReadyMask) != 0U) {
        return false;
    }
    const bool allStemsReady = (mRequiredStemMask != 0U && (stemBusReadyMask & mRequiredStemMask) == mRequiredStemMask);
    const bool readinessChanged = mReadinessInitialized &&
        allStemsReady != mLastAllStemsReady;
    if (readinessChanged && !allStemsReady &&
        mRequestedSourceMode == LateStemSourceMode::Stems && mHasLastMusicSample) {
        mFallbackFromLeft = mLastMusicLeft;
        mFallbackFromRight = mLastMusicRight;
        mFallbackTotalFrames = std::max(mSourceRampFrames, 1U);
        mFallbackAppliedFrames = 0U;
    }
    if (!allStemsReady) {
        // An incomplete set is one unavailable endpoint, never a partial mix. The bounded
        // fallback below owns continuity; source weighting must already represent Original.
        mSourceRamp.setImmediate(0.0F);
    }
    mReadinessInitialized = true;
    mLastAllStemsReady = allStemsReady;
    const float safeSourceTarget =
        mRequestedSourceMode == LateStemSourceMode::Stems && allStemsReady ? 1.0F : 0.0F;
    if (mSourceRamp.target() != safeSourceTarget) {
        mSourceRamp.setTarget(safeSourceTarget, mSourceRampFrames);
    }

    for (std::size_t frame = 0U; frame < frameCount; ++frame) {
        const float *payload = interleavedMultibus + frame * kStemwiseMultibusStride;
        float originalLeft = payload[stemwiseOriginalChannel(0U)];
        float originalRight = payload[stemwiseOriginalChannel(1U)];
        scrubFinite(originalLeft);
        scrubFinite(originalRight);

        float stemsLeft = 0.0F;
        float stemsRight = 0.0F;
        for (std::size_t lane = 0U; lane < mStemLaneCount; ++lane) {
            const float gain = mStemGainRamps[lane].next();
            if (allStemsReady) {
                float left = payload[stemwiseStemChannel(lane, 0U)];
                float right = payload[stemwiseStemChannel(lane, 1U)];
                scrubFinite(left);
                scrubFinite(right);
                stemsLeft += left * gain;
                stemsRight += right * gain;
            }
        }

#if defined(STEMWISE_MERGER_TEST_CAPTURE)
        if (mTestLinearCapture != nullptr) {
            mTestLinearCapture[frame * 2U] = stemsLeft;
            mTestLinearCapture[frame * 2U + 1U] = stemsRight;
        }
#endif
        const float sourceWeight = mSourceRamp.next();
        const float originalWeight = 1.0F - sourceWeight;
        const float masterGain = mMasterRamp.next();
        float click = payload[kStemwiseMultibusClickChannel];
        float envelope = payload[kStemwiseMultibusEnvelopeChannel];
        scrubFinite(click);
        scrubFinite(envelope);
        envelope = std::clamp(envelope, 0.0F, 1.0F);
        float musicLeft =
            (originalLeft * originalWeight + stemsLeft * sourceWeight) * masterGain;
        float musicRight =
            (originalRight * originalWeight + stemsRight * sourceWeight) * masterGain;
        // A brief decoder/readiness recovery must not tear down an in-flight safety fallback.
        // Continue the same cached-music transition while the newly complete Stems endpoint
        // begins its normal source ramp; otherwise the first ready frame can jump directly from
        // a partially blended fallback to live Original/Stems PCM.
        if (mFallbackAppliedFrames < mFallbackTotalFrames) {
            const float newWeight = mFallbackTotalFrames <= 1U
                ? 1.0F
                : static_cast<float>(mFallbackAppliedFrames) /
                    static_cast<float>(mFallbackTotalFrames - 1U);
            const float cachedWeight = 1.0F - newWeight;
            musicLeft = mFallbackFromLeft * cachedWeight + musicLeft * newWeight;
            musicRight = mFallbackFromRight * cachedWeight + musicRight * newWeight;
            ++mFallbackAppliedFrames;
        }
        mLastMusicLeft = musicLeft;
        mLastMusicRight = musicRight;
        mHasLastMusicSample = true;
        float left = (musicLeft + click) * envelope;
        float right = (musicRight + click) * envelope;
        scrubFinite(left);
        scrubFinite(right);
        processLimiter(left, right);
        safetyClamp(left);
        safetyClamp(right);
        interleavedStereo[frame * 2U] = left;
        interleavedStereo[frame * 2U + 1U] = right;
        if (!allStemsReady && mRequestedSourceMode == LateStemSourceMode::Stems &&
            mDiagnostics.safeOriginalFallbackFrames !=
                std::numeric_limits<std::uint64_t>::max()) {
            ++mDiagnostics.safeOriginalFallbackFrames;
        }
    }
    const auto maximum = std::numeric_limits<std::uint64_t>::max();
    const auto frames = static_cast<std::uint64_t>(frameCount);
    mDiagnostics.renderedFrames = frames > maximum - mDiagnostics.renderedFrames
        ? maximum
        : mDiagnostics.renderedFrames + frames;
    return true;
}

const LateStemMixerDiagnostics &LateStemMixer::diagnostics() const noexcept {
    return mDiagnostics;
}

}  // namespace stemwise::audio
