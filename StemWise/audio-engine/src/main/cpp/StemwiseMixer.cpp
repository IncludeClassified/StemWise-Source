#include "StemwiseMixer.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <stdexcept>
#include <utility>
#include <vector>

namespace stemwise::audio {

namespace {

constexpr float kPi = 3.14159265358979323846f;
constexpr float kTwoPi = 2.0f * kPi;
constexpr float kAudibleEpsilon = 1.0e-6f;
constexpr std::size_t kClickVoiceCount = 12;

[[nodiscard]] constexpr bool validBeatUnitDenominator(std::uint8_t value) noexcept {
    return value == 1U || value == 2U || value == 4U || value == 8U || value == 16U ||
        value == 32U;
}

MixerConfiguration validateConfiguration(const MixerConfiguration &configuration) {
    if (configuration.sampleRateHz < 8'000 || configuration.sampleRateHz > 384'000) {
        throw std::invalid_argument("Mixer sample rate must be in 8000..384000 Hz");
    }
    if (configuration.maximumBlockFrames == 0 ||
        configuration.maximumBlockFrames > (1U << 20U)) {
        throw std::invalid_argument("Mixer maximum block size is invalid");
    }
    if (configuration.maximumBeatGridPoints < 2 ||
        configuration.maximumBeatGridPoints > (1U << 20U)) {
        throw std::invalid_argument("Mixer beat-grid capacity is invalid");
    }
    return configuration;
}



class LinearRamp final {
public:
    void setImmediate(float value) noexcept {
        mCurrent = value;
        mTarget = value;
        mStep = 0.0f;
        mRemaining = 0;
    }

    void setTarget(float target, std::uint32_t frames) noexcept {
        if (target == mTarget && frames > 1U) return;
        mTarget = target;
        if (frames <= 1 || std::abs(mTarget - mCurrent) <= kAudibleEpsilon) {
            setImmediate(target);
            return;
        }
        mRemaining = frames;
        mStep = (mTarget - mCurrent) / static_cast<float>(frames - 1U);
    }

    float next() noexcept {
        if (mRemaining == 0) return mCurrent;
        const float result = mCurrent;
        if (mRemaining == 1) {
            setImmediate(mTarget);
        } else {
            mCurrent += mStep;
            --mRemaining;
        }
        return result;
    }

    [[nodiscard]] float value() const noexcept { return mCurrent; }
    [[nodiscard]] float target() const noexcept { return mTarget; }
    [[nodiscard]] bool active() const noexcept { return mRemaining != 0; }

private:
    float mCurrent = 0.0f;
    float mTarget = 0.0f;
    float mStep = 0.0f;
    std::uint32_t mRemaining = 0;
};

/** Smooth interpolation whose Original and Stems coefficients always sum to exactly one. */


enum class ClickKind : std::uint8_t {
    Subdivision,
    BaseBeat,
    Accent,
};

int clickPriority(ClickKind kind) noexcept {
    switch (kind) {
        case ClickKind::Accent: return 2;
        case ClickKind::BaseBeat: return 1;
        case ClickKind::Subdivision: return 0;
    }
    return 0;
}

struct ClickEvent {
    std::int64_t frame = 0;
    ClickKind kind = ClickKind::BaseBeat;
};

struct ClickVoice {
    float phase = 0.0f;
    float phaseIncrement = 0.0f;
    float amplitude = 0.0f;
    float decay = 1.0f;
    std::uint32_t remainingFrames = 0;
};

std::uint32_t sanitizeRampFrames(
    std::uint32_t value,
    std::uint32_t sampleRate,
    std::uint64_t &sanitizedCounter) noexcept {
    const std::uint32_t maximum = sampleRate * 2U;
    if (value == 0) {
        ++sanitizedCounter;
        return 1;
    }
    if (value > maximum) {
        ++sanitizedCounter;
        return maximum;
    }
    return value;
}

float sanitizeControlFloat(
    float value,
    float fallback,
    float minimum,
    float maximum,
    std::uint64_t &sanitizedCounter) noexcept {
    if (!std::isfinite(value)) {
        ++sanitizedCounter;
        return fallback;
    }
    const float clamped = std::clamp(value, minimum, maximum);
    if (clamped != value) ++sanitizedCounter;
    return clamped;
}

}  // namespace

class StemwiseMixer::Impl final {
public:
    explicit Impl(const MixerConfiguration &configuration)
        : mConfiguration(validateConfiguration(configuration)),
          mBeatGrid(mConfiguration.maximumBeatGridPoints, 0),
          mBeatAccents(mConfiguration.maximumBeatGridPoints, 0),
          mClickEvents(
              static_cast<std::size_t>(mConfiguration.maximumBeatGridPoints) * 4U)
    {
        mControls.revision = 0;
        mDeclickRamp.setImmediate(1.0f);
    }

    bool applyControls(const MixerControlSnapshot &snapshot) noexcept {
        if (snapshot.revision == 0 || snapshot.revision <= mAppliedControlRevision || snapshot.stemLaneCount > kStemLaneCount) {
            ++mDiagnostics.staleControlSnapshotsRejected;
            return false;
        }

        MixerControlSnapshot next = snapshot;
        std::uint64_t &sanitized = mDiagnostics.sanitizedControlValues;
        if (next.sourceMode != MixerSourceMode::Original &&
            next.sourceMode != MixerSourceMode::Stems) {
            next.sourceMode = MixerSourceMode::Original;
            ++sanitized;
        }
        next.musicGain = sanitizeControlFloat(next.musicGain, 1.0f, 0.0f, 2.0f, sanitized);
        next.mixHeadroom =
            sanitizeControlFloat(next.mixHeadroom, 0.9f, 0.0f, 1.0f, sanitized);
        next.sourceTransitionFrames = sanitizeRampFrames(
            next.sourceTransitionFrames,
            mConfiguration.sampleRateHz,
            sanitized);
        next.laneRampFrames =
            sanitizeRampFrames(next.laneRampFrames, mConfiguration.sampleRateHz, sanitized);
        next.masterRampFrames =
            sanitizeRampFrames(next.masterRampFrames, mConfiguration.sampleRateHz, sanitized);
        next.defaultDeclickFrames = sanitizeRampFrames(
            next.defaultDeclickFrames,
            mConfiguration.sampleRateHz,
            sanitized);

        next.metronome.bpm =
            sanitizeControlFloat(next.metronome.bpm, 120.0f, 20.0f, 400.0f, sanitized);
        next.metronome.gain =
            sanitizeControlFloat(next.metronome.gain, 0.35f, 0.0f, 1.0f, sanitized);
        if (next.metronome.subdivision < 1 || next.metronome.subdivision > 4) {
            next.metronome.subdivision = 1;
            ++sanitized;
        }
        if (next.metronome.beatsPerBar < 1 || next.metronome.beatsPerBar > 32) {
            next.metronome.beatsPerBar = 4;
            ++sanitized;
        }
        if (!validBeatUnitDenominator(next.metronome.beatUnitDenominator)) {
            next.metronome.beatUnitDenominator = 4;
            ++sanitized;
        }
        if (next.metronome.mode != MetronomeMode::Off &&
            next.metronome.mode != MetronomeMode::FreeBpm &&
            next.metronome.mode != MetronomeMode::BeatGrid) {
            next.metronome.mode = MetronomeMode::Off;
            ++sanitized;
        }

        const MetronomeControl previousMetronome = mControls.metronome;
        const bool metronomeTimingChanged =
            previousMetronome.mode != next.metronome.mode ||
            previousMetronome.bpm != next.metronome.bpm ||
            previousMetronome.subdivision != next.metronome.subdivision ||
            previousMetronome.beatsPerBar != next.metronome.beatsPerBar ||
            previousMetronome.beatUnitDenominator !=
                next.metronome.beatUnitDenominator;
        mControls = next;
        mAppliedControlRevision = next.revision;
        if (metronomeTimingChanged) {
            rebuildBeatEvents();
            resetMetronomeScheduler();
        }
        ++mDiagnostics.controlSnapshotsApplied;
        return true;
    }



    bool setBeatGrid(
        const std::int64_t *beatFrames,
        const std::uint8_t *accentFlags,
        std::size_t beatCount) noexcept {
        return setBeatGridInternal(beatFrames, accentFlags, beatCount);
    }

    bool setBeatGridInternal(
        const std::int64_t *beatFrames,
        const std::uint8_t *accentFlags,
        std::size_t beatCount) noexcept {
        if (beatFrames == nullptr || beatCount < 2 || beatCount > mBeatGrid.size()) {
            ++mDiagnostics.invalidBeatGridsRejected;
            return false;
        }
        if (accentFlags == nullptr) {
            ++mDiagnostics.invalidBeatGridsRejected;
            return false;
        }
        for (std::size_t index = 0; index < beatCount; ++index) {
            if (beatFrames[index] < 0 ||
                (index != 0 && beatFrames[index] <= beatFrames[index - 1]) ||
                accentFlags[index] > 1U) {
                ++mDiagnostics.invalidBeatGridsRejected;
                return false;
            }
        }
        if (mBeatCount == beatCount &&
            std::equal(beatFrames, beatFrames + beatCount, mBeatGrid.begin()) &&
            std::equal(accentFlags, accentFlags + beatCount, mBeatAccents.begin())) return true;
        std::copy_n(beatFrames, beatCount, mBeatGrid.begin());
        std::copy_n(accentFlags, beatCount, mBeatAccents.begin());
        mBeatCount = beatCount;
        rebuildBeatEvents();
        resetMetronomeScheduler();
        return true;
    }

    void clearBeatGrid() noexcept {
        clearBeatGridInternal(true);
    }

    void beginPostDspLoopFadeOut(std::uint32_t fadeFrames) noexcept {
        const std::uint32_t frames = effectiveDeclickFrames(fadeFrames);
        mDeclickRamp.setTarget(0.0f, frames);
        ++mDiagnostics.loopFadeOutRequests;
    }

    void continuePostDspAfterLoopPolicyChange(std::uint32_t fadeFrames) noexcept {
        mDeclickRamp.setTarget(1.0f, effectiveDeclickFrames(fadeFrames));
    }

    void completePostDspLoopWrap(
        std::uint64_t selectionToken,
        std::uint64_t generation,
        std::uint32_t fadeFrames) noexcept {
        const std::uint32_t frames = effectiveDeclickFrames(fadeFrames);
        mDeclickRamp.setImmediate(0.0f);
        mDeclickRamp.setTarget(1.0f, frames);
        resetPostOwnedState(selectionToken, generation);
        ++mDiagnostics.loopWraps;
    }

    void resetPostDspForDiscontinuity(
        std::uint64_t selectionToken,
        std::uint64_t generation,
        std::uint32_t crossfadeFrames) noexcept {
        const std::uint32_t frames = effectiveDeclickFrames(crossfadeFrames);
        mDeclickRamp.setImmediate(0.0f);
        mDeclickRamp.setTarget(1.0f, frames);
        resetPostOwnedState(selectionToken, generation);
        ++mDiagnostics.discontinuityResets;
    }

    bool rebindPostDspOwner(std::uint64_t selectionToken, std::uint64_t previousGeneration,
        std::uint64_t nextGeneration) noexcept {
        if (selectionToken == 0U || selectionToken != mSelectionToken ||
            previousGeneration != mGeneration || nextGeneration <= previousGeneration) return false;
        mGeneration = nextGeneration;
        return true;
    }


    bool renderPreDspMultibus(
        const PreDspRenderBlock &input,
        float *output,
        std::uint32_t outputCapacityFrames) noexcept {
        if (output == nullptr || input.frameCount == 0 ||
            input.frameCount > mConfiguration.maximumBlockFrames ||
            outputCapacityFrames < input.frameCount) {
            ++mDiagnostics.invalidPreDspRenderCalls;
            return false;
        }
        if (input.selectionToken == 0 || input.generation == 0) {
            std::fill_n(
                output,
                static_cast<std::size_t>(input.frameCount) * kStemwiseMusicChannelCount,
                0.0F);
            ++mDiagnostics.invalidOwnershipBlocks;
            return false;
        }

        recordLaneAvailability(input);
        for (std::uint32_t frame = 0U; frame < input.frameCount; ++frame) {
            float *destination = output +
                static_cast<std::size_t>(frame) * kStemwiseMusicChannelCount;
            destination[0U] = preDspLaneSample(input.original, frame, 0U);
            destination[1U] = preDspLaneSample(input.original, frame, 1U);
            std::fill_n(destination + 2U, kStemwiseMusicChannelCount - 2U, 0.0F);
            for (std::size_t lane = 0U; lane < mControls.stemLaneCount; ++lane) {
                destination[stemwiseStemChannel(lane, 0U)] =
                    preDspLaneSample(input.stems[lane], frame, 0U);
                destination[stemwiseStemChannel(lane, 1U)] =
                    preDspLaneSample(input.stems[lane], frame, 1U);
            }
        }
        ++mDiagnostics.preDspRenderCalls;
        mDiagnostics.preDspRenderedFrames += input.frameCount;
        return true;
    }


    bool renderPostDspMultibus(
        const PostDspMultibusRenderBlock &input,
        float *output,
        std::uint32_t outputCapacityFrames) noexcept {
        if (output == nullptr || input.processedMusicBuses == nullptr ||
            input.frameCount == 0 ||
            input.frameCount > mConfiguration.maximumBlockFrames ||
            outputCapacityFrames < input.frameCount || input.outputClockStartFrame < 0 ||
            input.outputClockStartFrame >
                std::numeric_limits<std::int64_t>::max() -
                    static_cast<std::int64_t>(input.frameCount) ||
            !std::isfinite(input.sourceTimeline.startFrame) ||
            !std::isfinite(input.sourceTimeline.endFrameExclusive) ||
            input.sourceTimeline.endFrameExclusive < input.sourceTimeline.startFrame) {
            ++mDiagnostics.invalidPostDspRenderCalls;
            return false;
        }
        if (input.selectionToken == 0 || input.generation == 0) {
            std::fill_n(
                output,
                static_cast<std::size_t>(input.frameCount) * kStemwiseMultibusStride,
                0.0F);
            ++mDiagnostics.invalidOwnershipBlocks;
            return false;
        }

        applyPostDspBoundary(input);
        preparePostDspMetronome(input);
        const long double sourceStart = input.sourceTimeline.startFrame;
        const long double sourceSpan =
            static_cast<long double>(input.sourceTimeline.endFrameExclusive) - sourceStart;
        const long double inverseFrames = 1.0L / input.frameCount;

        for (std::uint32_t frame = 0U; frame < input.frameCount; ++frame) {
            const std::int64_t outputClockFrame =
                input.outputClockStartFrame + static_cast<std::int64_t>(frame);
            const long double segmentStart = sourceStart +
                sourceSpan * static_cast<long double>(frame) * inverseFrames;
            const long double segmentEnd = sourceStart +
                sourceSpan * static_cast<long double>(frame + 1U) * inverseFrames;
            const float *source = input.processedMusicBuses +
                static_cast<std::size_t>(frame) * kStemwiseMusicChannelCount;
            float *destination = output +
                static_cast<std::size_t>(frame) * kStemwiseMultibusStride;
            for (std::size_t channel = 0U; channel < kStemwiseMusicChannelCount; ++channel) {
                float sample = source[channel];
                scrubPostDspNonFinite(sample);
                destination[channel] = sample;
            }
            triggerPostDspMetronomeEvents(outputClockFrame, segmentStart, segmentEnd);
            float click = synthesizeMetronome();
            scrubPostDspNonFinite(click);
            destination[kStemwiseMultibusClickChannel] = click;
            destination[kStemwiseMultibusEnvelopeChannel] = mDeclickRamp.next();
        }

        mPostExpectedOutputClockFrame =
            input.outputClockStartFrame + static_cast<std::int64_t>(input.frameCount);
        mPostExpectedSourceFrame = input.sourceTimeline.endFrameExclusive;
        mPostTimelineValid = true;
        if (mControls.metronome.mode == MetronomeMode::BeatGrid) {
            mGridExpectedSourceFrame = input.sourceTimeline.endFrameExclusive;
            mGridTimelineValid = true;
        } else if (mControls.metronome.mode == MetronomeMode::FreeBpm) {
            mFreeExpectedOutputClockFrame = mPostExpectedOutputClockFrame;
            mFreeClockValid = true;
        }
        ++mDiagnostics.postDspRenderCalls;
        mDiagnostics.postDspRenderedFrames += input.frameCount;
        return true;
    }



    [[nodiscard]] MixerLateControlEndpoint lateControlEndpoint() const noexcept {
        MixerLateControlEndpoint endpoint{
            .mixerRevision = mAppliedControlRevision,
            .sourceMode = mControls.sourceMode,
            .musicGain = mControls.musicGain,
            .mixHeadroom = mControls.mixHeadroom,
            .sourceRampFrames = mControls.sourceTransitionFrames,
            .laneRampFrames = mControls.laneRampFrames,
            .masterRampFrames = mControls.masterRampFrames,
        };
        const bool anySoloed = std::any_of(
            mControls.stems.begin(),
            mControls.stems.begin() + mControls.stemLaneCount,
            [](const StemLaneControl &lane) noexcept { return lane.soloed; });
        const bool anyAudibleStem = std::any_of(
            mControls.stems.begin(),
            mControls.stems.begin() + mControls.stemLaneCount,
            [anySoloed](const StemLaneControl &lane) noexcept {
                return !lane.muted && (!anySoloed || lane.soloed) &&
                    lane.gain > kAudibleEpsilon;
            });
        for (std::size_t laneIndex = 0U;
             laneIndex < endpoint.effectiveStemGains.size();
             ++laneIndex) {
            const StemLaneControl &lane = mControls.stems[laneIndex];
            endpoint.effectiveStemGains[laneIndex] =
                laneIndex < mControls.stemLaneCount && anyAudibleStem && !lane.muted && (!anySoloed || lane.soloed)
                ? lane.gain
                : 0.0F;
        }
        return endpoint;
    }

    [[nodiscard]] const StemwiseMixerDiagnostics &diagnostics() const noexcept {
        return mDiagnostics;
    }

    void clearDiagnostics() noexcept {
        mDiagnostics = {};
    }

private:






    static bool timelineNearlyEqual(double left, double right) noexcept {
        const double scale = std::max({1.0, std::abs(left), std::abs(right)});
        return std::abs(left - right) <= scale * 1.0e-10;
    }

    void applyPostDspBoundary(const PostDspMultibusRenderBlock &input) noexcept {
        const bool identityChanged = input.selectionToken != mSelectionToken ||
            input.generation != mGeneration;
        if (identityChanged) {
            if (mSelectionToken != 0) ++mDiagnostics.ownershipChanges;
            resetPostOwnedState(input.selectionToken, input.generation);
        } else if (mPostTimelineValid &&
                   (input.outputClockStartFrame != mPostExpectedOutputClockFrame ||
                    !timelineNearlyEqual(
                        input.sourceTimeline.startFrame,
                        mPostExpectedSourceFrame))) {
            ++mDiagnostics.timelineDiscontinuities;
            resetMetronomeScheduler();
        }
    }

    void resetPostOwnedState(
        std::uint64_t selectionToken,
        std::uint64_t generation) noexcept {
        const bool identityChanged = selectionToken != mSelectionToken ||
            generation != mGeneration;
        const bool hadOwnedSong = mSelectionToken != 0 || mGeneration != 0;
        if (identityChanged && hadOwnedSong) clearBeatGridInternal(true);
        mSelectionToken = selectionToken;
        mGeneration = generation;
        mPostTimelineValid = false;
        resetMetronomeScheduler();
    }





    std::uint32_t effectiveDeclickFrames(std::uint32_t requested) const noexcept {
        return requested == 0 ? mControls.defaultDeclickFrames : requested;
    }

    float preDspLaneSample(
        const StereoPcmBlock &lane,
        std::uint32_t frame,
        std::size_t channel) noexcept {
        if (lane.samples == nullptr || frame >= lane.frameCount) return 0.0f;
        float sample = lane.samples[static_cast<std::size_t>(frame) * 2U + channel];
        scrubPreDspNonFinite(sample);
        return sample;
    }

    void scrubPreDspNonFinite(float &sample) noexcept {
        if (!std::isfinite(sample)) {
            sample = 0.0f;
            ++mDiagnostics.preDspNonFiniteSamplesScrubbed;
        }
    }

    void scrubPostDspNonFinite(float &sample) noexcept {
        if (!std::isfinite(sample)) {
            sample = 0.0f;
            ++mDiagnostics.postDspNonFiniteSamplesScrubbed;
        }
    }

    void recordLaneAvailability(const PreDspRenderBlock &input) noexcept {
        if (input.original.samples == nullptr) {
            ++mDiagnostics.originalMissingBlocks;
            mDiagnostics.originalUnderrunFrames += input.frameCount;
        } else if (input.original.frameCount < input.frameCount) {
            mDiagnostics.originalUnderrunFrames += input.frameCount - input.original.frameCount;
        }
        for (std::size_t lane = 0; lane < mControls.stemLaneCount; ++lane) {
            if (input.stems[lane].samples == nullptr) {
                ++mDiagnostics.stemMissingBlocks[lane];
                mDiagnostics.stemUnderrunFrames[lane] += input.frameCount;
            } else if (input.stems[lane].frameCount < input.frameCount) {
                mDiagnostics.stemUnderrunFrames[lane] += input.frameCount - input.stems[lane].frameCount;
            }
        }
    }

    void rebuildBeatEvents() noexcept {
        mClickEventCount = 0;
        if (mBeatCount < 2 || mControls.metronome.mode != MetronomeMode::BeatGrid) return;
        const std::uint32_t subdivision = mControls.metronome.subdivision;
        for (std::size_t beat = 0; beat < mBeatCount; ++beat) {
            const std::int64_t interval = beat + 1U < mBeatCount
                ? mBeatGrid[beat + 1U] - mBeatGrid[beat]
                : mBeatGrid[beat] - mBeatGrid[beat - 1U];
            for (std::uint32_t division = 0; division < subdivision; ++division) {
                const std::int64_t offset = (interval / subdivision) * division +
                    ((interval % subdivision) * division + subdivision / 2U) / subdivision;
                if (offset > std::numeric_limits<std::int64_t>::max() - mBeatGrid[beat]) {
                    continue;
                }
                const std::int64_t eventFrame = mBeatGrid[beat] + offset;
                const ClickKind kind = division != 0
                    ? ClickKind::Subdivision
                    : ((mBeatAccents[beat] != 0U)
                           ? ClickKind::Accent
                           : ClickKind::BaseBeat);
                if (mClickEventCount != 0 &&
                    eventFrame <= mClickEvents[mClickEventCount - 1U].frame) {
                    if (eventFrame == mClickEvents[mClickEventCount - 1U].frame &&
                        clickPriority(kind) >
                            clickPriority(mClickEvents[mClickEventCount - 1U].kind)) {
                        mClickEvents[mClickEventCount - 1U].kind = kind;
                    }
                    continue;
                }
                if (mClickEventCount >= mClickEvents.size()) return;
                mClickEvents[mClickEventCount++] = ClickEvent{eventFrame, kind};
            }
        }
    }

    void clearBeatGridInternal(bool countDiagnostic) noexcept {
        if (mBeatCount == 0U) return;
        mBeatCount = 0;
        mClickEventCount = 0;
        mNextGridEvent = 0;
        mGridTimelineValid = false;
        if (mControls.metronome.mode == MetronomeMode::BeatGrid) {
            for (ClickVoice &voice : mClickVoices) voice.remainingFrames = 0;
        }
        if (countDiagnostic) ++mDiagnostics.beatGridClears;
    }

    void resetMetronomeScheduler() noexcept {
        mGridTimelineValid = false;
        mGridExpectedSourceFrame = 0.0;
        mFreeClockValid = false;
        mFreeExpectedOutputClockFrame = 0;
        mNextGridEvent = 0;
        mFreeEventOrdinal = 0;
        mNextFreeEventFrame = 0;
        for (ClickVoice &voice : mClickVoices) voice.remainingFrames = 0;
    }

    void preparePostDspMetronome(const PostDspMultibusRenderBlock &input) noexcept {
        switch (mControls.metronome.mode) {
            case MetronomeMode::Off:
                return;
            case MetronomeMode::BeatGrid: {
                if (mGridTimelineValid && timelineNearlyEqual(
                        input.sourceTimeline.startFrame,
                        mGridExpectedSourceFrame)) {
                    return;
                }
                for (ClickVoice &voice : mClickVoices) voice.remainingFrames = 0;
                std::size_t low = 0;
                std::size_t high = mClickEventCount;
                while (low < high) {
                    const std::size_t middle = low + (high - low) / 2U;
                    if (static_cast<double>(mClickEvents[middle].frame) <
                        input.sourceTimeline.startFrame) {
                        low = middle + 1U;
                    } else {
                        high = middle;
                    }
                }
                mNextGridEvent = low;
                mGridTimelineValid = true;
                mGridExpectedSourceFrame = input.sourceTimeline.startFrame;
                return;
            }
            case MetronomeMode::FreeBpm: {
                if (mFreeClockValid &&
                    input.outputClockStartFrame == mFreeExpectedOutputClockFrame) {
                    return;
                }
                for (ClickVoice &voice : mClickVoices) voice.remainingFrames = 0;
                const long double interval = freeEventIntervalFrames();
                long double ordinal = std::floor(
                    static_cast<long double>(input.outputClockStartFrame) / interval);
                if (ordinal < 0.0L) ordinal = 0.0L;
                mFreeEventOrdinal = static_cast<std::uint64_t>(ordinal);
                updateNextFreeEventFrame();
                while (mNextFreeEventFrame < input.outputClockStartFrame) {
                    ++mFreeEventOrdinal;
                    updateNextFreeEventFrame();
                }
                mFreeClockValid = true;
                mFreeExpectedOutputClockFrame = input.outputClockStartFrame;
                return;
            }
        }
    }

    long double freeEventIntervalFrames() const noexcept {
        const long double beatUnit = 4.0L /
            static_cast<long double>(mControls.metronome.beatUnitDenominator);
        return static_cast<long double>(mConfiguration.sampleRateHz) * 60.0L /
            (static_cast<long double>(mControls.metronome.bpm) *
             static_cast<long double>(mControls.metronome.subdivision)) * beatUnit;
    }

    void updateNextFreeEventFrame() noexcept {
        const long double eventFrame =
            static_cast<long double>(mFreeEventOrdinal) * freeEventIntervalFrames();
        const long double maximumFrame =
            static_cast<long double>(std::numeric_limits<std::int64_t>::max());
        mNextFreeEventFrame = eventFrame >= maximumFrame
            ? std::numeric_limits<std::int64_t>::max()
            : static_cast<std::int64_t>(std::llround(eventFrame));
    }

    ClickKind freeClickKind(std::uint64_t ordinal) const noexcept {
        const std::uint64_t subdivision = mControls.metronome.subdivision;
        const std::uint64_t division = ordinal % subdivision;
        if (division != 0) return ClickKind::Subdivision;
        const std::uint64_t beat = ordinal / subdivision;
        return beat % mControls.metronome.beatsPerBar == 0
            ? ClickKind::Accent
            : ClickKind::BaseBeat;
    }

    void triggerPostDspMetronomeEvents(
        std::int64_t outputClockFrame,
        long double sourceSegmentStart,
        long double sourceSegmentEndExclusive) noexcept {
        switch (mControls.metronome.mode) {
            case MetronomeMode::Off:
                return;
            case MetronomeMode::BeatGrid:
                if (sourceSegmentEndExclusive <= sourceSegmentStart) return;
                while (mNextGridEvent < mClickEventCount &&
                       static_cast<long double>(mClickEvents[mNextGridEvent].frame) <
                           sourceSegmentStart) {
                    ++mNextGridEvent;
                }
                while (mNextGridEvent < mClickEventCount &&
                       static_cast<long double>(mClickEvents[mNextGridEvent].frame) <
                           sourceSegmentEndExclusive) {
                    triggerClick(mClickEvents[mNextGridEvent].kind);
                    ++mNextGridEvent;
                }
                return;
            case MetronomeMode::FreeBpm:
                while (mNextFreeEventFrame <= outputClockFrame) {
                    triggerClick(freeClickKind(mFreeEventOrdinal));
                    ++mFreeEventOrdinal;
                    updateNextFreeEventFrame();
                }
                return;
        }
    }

    void triggerClick(ClickKind kind) noexcept {
        ClickVoice *selected = nullptr;
        for (ClickVoice &voice : mClickVoices) {
            if (voice.remainingFrames == 0) {
                selected = &voice;
                break;
            }
            if (selected == nullptr || voice.amplitude < selected->amplitude) {
                selected = &voice;
            }
        }
        if (selected == nullptr) return;
        if (selected->remainingFrames != 0) ++mDiagnostics.metronomeVoiceSteals;

        float frequency = 990.0f;
        // Preserve the three-level rhythmic hierarchy without throwing away most of the UI's
        // requested gain. The post-DSP limiter below this synthesizer owns final headroom.
        float strength = 0.62f;
        if (kind == ClickKind::BaseBeat) {
            frequency = 1'320.0f;
            strength = 0.86f;
            ++mDiagnostics.metronomeBaseBeatEvents;
        } else if (kind == ClickKind::Accent) {
            frequency = 1'760.0f;
            strength = 1.0f;
            ++mDiagnostics.metronomeAccentEvents;
        } else {
            ++mDiagnostics.metronomeSubdivisionEvents;
        }
        const std::uint32_t durationFrames =
            std::max<std::uint32_t>(mConfiguration.sampleRateHz / 40U, 1U);
        selected->phase = kPi * 0.5f;
        selected->phaseIncrement =
            kTwoPi * frequency / static_cast<float>(mConfiguration.sampleRateHz);
        selected->amplitude = mControls.metronome.gain * strength;
        selected->decay = std::exp(
            std::log(0.001f) / static_cast<float>(durationFrames));
        selected->remainingFrames = durationFrames;
        ++mDiagnostics.metronomeEvents;
    }

    float synthesizeMetronome() noexcept {
        float sample = 0.0f;
        for (ClickVoice &voice : mClickVoices) {
            if (voice.remainingFrames == 0) continue;
            sample += std::sin(voice.phase) * voice.amplitude;
            voice.phase += voice.phaseIncrement;
            if (voice.phase >= kTwoPi) voice.phase -= kTwoPi;
            voice.amplitude *= voice.decay;
            --voice.remainingFrames;
        }
        return sample;
    }






    const MixerConfiguration mConfiguration;
    MixerControlSnapshot mControls{};
    std::uint64_t mAppliedControlRevision = 0;
    StemwiseMixerDiagnostics mDiagnostics{};

    LinearRamp mDeclickRamp{};


    std::vector<std::int64_t> mBeatGrid;
    std::vector<std::uint8_t> mBeatAccents;
    std::size_t mBeatCount = 0;
    std::vector<ClickEvent> mClickEvents;
    std::size_t mClickEventCount = 0;
    std::size_t mNextGridEvent = 0;
    std::uint64_t mFreeEventOrdinal = 0;
    std::int64_t mNextFreeEventFrame = 0;
    std::array<ClickVoice, kClickVoiceCount> mClickVoices{};
    bool mGridTimelineValid = false;
    double mGridExpectedSourceFrame = 0.0;
    bool mFreeClockValid = false;
    std::int64_t mFreeExpectedOutputClockFrame = 0;

    std::uint64_t mSelectionToken = 0;
    std::uint64_t mGeneration = 0;
    bool mPostTimelineValid = false;
    std::int64_t mPostExpectedOutputClockFrame = 0;
    double mPostExpectedSourceFrame = 0.0;


};

StemwiseMixer::StemwiseMixer(const MixerConfiguration &configuration)
    : mImpl(std::make_unique<Impl>(configuration)) {}

StemwiseMixer::~StemwiseMixer() = default;

bool StemwiseMixer::applyControls(const MixerControlSnapshot &snapshot) noexcept {
    return mImpl->applyControls(snapshot);
}



bool StemwiseMixer::setBeatGrid(
    const std::int64_t *beatFrames,
    const std::uint8_t *accentFlags,
    std::size_t beatCount) noexcept {
    return mImpl->setBeatGrid(beatFrames, accentFlags, beatCount);
}

void StemwiseMixer::clearBeatGrid() noexcept {
    mImpl->clearBeatGrid();
}

void StemwiseMixer::beginPostDspLoopFadeOut(std::uint32_t fadeFrames) noexcept {
    mImpl->beginPostDspLoopFadeOut(fadeFrames);
}

void StemwiseMixer::continuePostDspAfterLoopPolicyChange(
    std::uint32_t fadeFrames) noexcept {
    mImpl->continuePostDspAfterLoopPolicyChange(fadeFrames);
}

void StemwiseMixer::completePostDspLoopWrap(
    std::uint64_t selectionToken,
    std::uint64_t generation,
    std::uint32_t fadeFrames) noexcept {
    mImpl->completePostDspLoopWrap(selectionToken, generation, fadeFrames);
}

void StemwiseMixer::resetPostDspForDiscontinuity(
    std::uint64_t selectionToken,
    std::uint64_t generation,
    std::uint32_t crossfadeFrames) noexcept {
    mImpl->resetPostDspForDiscontinuity(
        selectionToken,
        generation,
        crossfadeFrames);
}

bool StemwiseMixer::rebindPostDspOwner(std::uint64_t selectionToken,
    std::uint64_t previousGeneration, std::uint64_t nextGeneration) noexcept {
    return mImpl->rebindPostDspOwner(selectionToken, previousGeneration, nextGeneration);
}


bool StemwiseMixer::renderPreDspMultibus(
    const PreDspRenderBlock &input,
    float *interleavedMusicBuses,
    std::uint32_t outputCapacityFrames) noexcept {
    return mImpl->renderPreDspMultibus(
        input,
        interleavedMusicBuses,
        outputCapacityFrames);
}


bool StemwiseMixer::renderPostDspMultibus(
    const PostDspMultibusRenderBlock &input,
    float *interleavedRenderPayload,
    std::uint32_t outputCapacityFrames) noexcept {
    return mImpl->renderPostDspMultibus(
        input,
        interleavedRenderPayload,
        outputCapacityFrames);
}



MixerLateControlEndpoint StemwiseMixer::lateControlEndpoint() const noexcept {
    return mImpl->lateControlEndpoint();
}

const StemwiseMixerDiagnostics &StemwiseMixer::diagnostics() const noexcept {
    return mImpl->diagnostics();
}

void StemwiseMixer::clearDiagnostics() noexcept {
    mImpl->clearDiagnostics();
}

}  // namespace stemwise::audio
