#if defined(STEMWISE_CACHE_IO_TEST_HOOKS)
#include "CurrentSongPcmCacheTestHooks.h"
#endif
#include "RenderPayloadFingerprint.h"
#include "SongRenderPipeline.h"
#include "CurrentSongPcmCache.h"
#include "../../AudioTrace.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <limits>
#include <stdexcept>
#include <utility>
#include <vector>

namespace stemwise::audio::engine {
namespace {

constexpr std::size_t kStereoChannelCount = 2U;
constexpr std::size_t kMusicChannelCount = kStemwiseMusicChannelCount;
constexpr double kTimelineEpsilon = 1.0e-9;

SongRenderPipelineConfiguration validateConfiguration(
    const SongRenderPipelineConfiguration &configuration) {
    if (configuration.sampleRateHz !=
        SongRenderPipelineConfiguration::kRequiredSampleRateHz) {
        throw std::invalid_argument("SongRenderPipeline requires aligned 48 kHz PCM");
    }
    if (configuration.stemLaneCount > kStemLaneCount) {
        throw std::invalid_argument("SongRenderPipeline stem count is invalid");
    }
    if (configuration.maximumBlockFrames == 0 ||
        configuration.maximumBlockFrames >
            SongRenderPipelineConfiguration::kMaximumSupportedBlockFrames) {
        throw std::invalid_argument("SongRenderPipeline block quantum exceeds supported capacity");
    }
    if (configuration.laneReservoirCapacityFrames <
            TimelineStereoBuffer::kMinimumCapacityFrames ||
        configuration.laneReservoirCapacityFrames >
            TimelineStereoBuffer::kMaximumCapacityFrames) {
        throw std::invalid_argument("SongRenderPipeline reservoir capacity is invalid");
    }
    if (configuration.tuneTransitionFrames < 2 ||
        configuration.tuneTransitionFrames > configuration.laneReservoirCapacityFrames) {
        throw std::invalid_argument("SongRenderPipeline tuning transition is invalid");
    }
    if (configuration.maximumBeatGridPoints < 2) {
        throw std::invalid_argument("SongRenderPipeline beat-grid capacity is invalid");
    }
    return configuration;
}

MixerConfiguration mixerConfiguration(
    const SongRenderPipelineConfiguration &configuration) noexcept {
    return {
        .sampleRateHz = configuration.sampleRateHz,
        .maximumBlockFrames = configuration.maximumBlockFrames,
        .maximumBeatGridPoints = configuration.maximumBeatGridPoints,
    };
}

bool finiteTune(float tempo, float pitchSemitones) noexcept {
    return std::isfinite(tempo) && std::isfinite(pitchSemitones);
}

bool tuneIsNeutral(float tempo, float pitchSemitones) noexcept {
    return tempo == 1.0F && pitchSemitones == 0.0F;
}

std::size_t laneIndex(SongRenderLane lane) noexcept {
    return static_cast<std::size_t>(lane);
}

bool validLane(SongRenderLane lane) noexcept {
    return laneIndex(lane) < kSongRenderLaneCount;
}

bool validOwnership(SongRenderOwnership ownership) noexcept {
    return ownership.selectionToken != 0 && ownership.generation != 0;
}

bool checkedTimelineAdd(
    std::uint64_t first,
    std::size_t count,
    std::uint64_t &end) noexcept {
    if (static_cast<std::uint64_t>(count) >
        std::numeric_limits<std::uint64_t>::max() - first) {
        return false;
    }
    end = first + static_cast<std::uint64_t>(count);
    return true;
}

void saturatingIncrement(std::uint64_t &counter) noexcept {
    if (counter != std::numeric_limits<std::uint64_t>::max()) ++counter;
}

void saturatingAdd(std::uint64_t &counter, std::size_t amount) noexcept {
    const std::uint64_t maximum = std::numeric_limits<std::uint64_t>::max();
    const auto wideAmount = static_cast<std::uint64_t>(amount);
    counter = wideAmount > maximum - counter ? maximum : counter + wideAmount;
}


}  // namespace

bool SongRenderLateMixEndpoint::valid() const noexcept {
    constexpr std::uint32_t kAllPreviewLanes =
        (1U << static_cast<std::uint32_t>(kStemLaneCount)) - 1U;
    const bool validSource = sourceMode == MixerSourceMode::Original ||
        sourceMode == MixerSourceMode::Stems;
    const bool validPreviewIdentity = previewPublicationSequence == 0U
        ? previewCoversOfferSequence == 0U && previewActiveMask == 0U
        : previewCoversOfferSequence != 0U;
    const auto validRamp = [](std::uint32_t frames) noexcept {
        return frames != 0U && frames <=
            SongRenderPipelineConfiguration::kRequiredSampleRateHz * 2U;
    };
    return canonicalControlRevision != 0U && sourceIntentRevision != 0U &&
        stemIntentRevision != 0U && validSource && validPreviewIdentity &&
        (previewActiveMask & ~kAllPreviewLanes) == 0U &&
        (requiredStemMask & ~kStemwiseAllStemBusesReadyMask) == 0U &&
        (requiredStemMask & (requiredStemMask + 1U)) == 0U &&
        (previewActiveMask & ~requiredStemMask) == 0U &&
        (sourceMode != MixerSourceMode::Stems || requiredStemMask != 0U) &&
        std::isfinite(musicGain) && musicGain >= 0.0F && musicGain <= 2.0F &&
        std::isfinite(mixHeadroom) && mixHeadroom >= 0.0F && mixHeadroom <= 1.0F &&
        validRamp(sourceRampFrames) && validRamp(laneRampFrames) &&
        validRamp(masterRampFrames) &&
        std::all_of(
            effectiveStemGains.begin(),
            effectiveStemGains.end(),
            [](float gain) noexcept {
                return std::isfinite(gain) && gain >= 0.0F && gain <= 2.0F;
            }) &&
        std::all_of(
            previewGains.begin(),
            previewGains.end(),
            [](float gain) noexcept {
                return std::isfinite(gain) && gain >= 0.0F && gain <= 2.0F;
            });
}

class SongRenderPipeline::Impl final {
public:
    explicit Impl(const SongRenderPipelineConfiguration &requestedConfiguration)
        : configuration(validateConfiguration(requestedConfiguration)),
          mixer(mixerConfiguration(configuration)),
          timePitch(
              configuration.sampleRateHz,
              configuration.maximumBlockFrames,
              (configuration.stemLaneCount + 1U) * kStereoChannelCount,
              kMusicChannelCount),
          transition(
              configuration.maximumBlockFrames,
              configuration.tuneTransitionFrames,
              kMusicChannelCount),
          beatGrid(configuration.maximumBeatGridPoints, 0),
          beatAccents(configuration.maximumBeatGridPoints, 0),
          directTransitionPcm(
              (static_cast<std::size_t>(configuration.tuneTransitionFrames) +
               configuration.maximumBlockFrames) *
                  kMusicChannelCount,
              0.0F),
          processedPcm(
              static_cast<std::size_t>(configuration.maximumBlockFrames) * kMusicChannelCount,
              0.0F) {
        const TimePitchDiagnostics tuneDiagnostics = timePitch.diagnostics();
        maximumPrimeFrames = tuneDiagnostics.configuredInputLatencyFrames +
            static_cast<std::size_t>(
                std::ceil(
                    static_cast<double>(TimePitchProcessor::kMaximumTempo) *
                    static_cast<double>(tuneDiagnostics.configuredOutputLatencyFrames)));
        maximumInputFrames = tuneDiagnostics.maximumInputFrames;
        if (maximumPrimeFrames < configuration.tuneTransitionFrames) {
            throw std::invalid_argument(
                "SongRenderPipeline transition exceeds minimum retained DSP lookahead");
        }
        if (maximumPrimeFrames > configuration.laneReservoirCapacityFrames ||
            maximumInputFrames > configuration.laneReservoirCapacityFrames ||
            maximumPrimeFrames >
                configuration.laneReservoirCapacityFrames - maximumInputFrames) {
            throw std::invalid_argument(
                "SongRenderPipeline reservoir cannot hold tuning lookahead and input");
        }

        for (std::size_t lane = 0U; lane <= configuration.stemLaneCount; ++lane) {
            reservoirs[lane] = std::make_unique<TimelineStereoBuffer>(
                configuration.laneReservoirCapacityFrames);
        }
        for (auto &scratch : laneScratch) {
            scratch.resize(
                static_cast<std::size_t>(configuration.maximumBlockFrames) *
                    kStereoChannelCount);
        }
        primePcm.resize(maximumPrimeFrames * kMusicChannelCount);
        preDspInputPcm.resize(maximumInputFrames * kMusicChannelCount);
        desiredControls.mixer.revision = 0;
        desiredControls.mixer.sourceMode = MixerSourceMode::Original;
        desiredControls.mixer.stemLaneCount = configuration.stemLaneCount;
        desiredControls.tempo = 1.0F;
        desiredControls.pitchSemitones = 0.0F;
    }

    std::uint32_t requiredSongMask() const noexcept {
        return stemwiseRequiredSongMask(configuration.stemLaneCount);
    }

    bool configureStemLaneCount(std::size_t count) noexcept {
        if (count > kStemLaneCount) return false;
        if (count == configuration.stemLaneCount) return true;
        if (continuationCopy.pending || retainedCopyBorrower != nullptr) return false;
        try {
            auto prepared = SongRenderPipeline::prepareStemLaneCount(configuration, count);
            return adoptPreparedStemLaneCount(*prepared);
        } catch (...) { return false; }
    }

    bool adoptPreparedStemLaneCount(SongRenderPreparedLanes &prepared) noexcept {
        const auto count = prepared.configuration.stemLaneCount;
        if (count > kStemLaneCount ||
            prepared.configuration.sampleRateHz != configuration.sampleRateHz ||
            prepared.configuration.maximumBlockFrames != configuration.maximumBlockFrames ||
            prepared.configuration.laneReservoirCapacityFrames != configuration.laneReservoirCapacityFrames) return false;
        if (count == configuration.stemLaneCount) return true;
        if (continuationCopy.pending || retainedCopyBorrower != nullptr) return false;
        for (std::size_t lane = 1U; lane <= count; ++lane) {
            if (!reservoirs[lane] && !prepared.reservoirs[lane]) return false;
        }
        // Keep one spectral bank for the song: changing optional lanes cannot reset Original's
        // history, fractional input scheduler, or a tuning transition already in progress.
        for (std::size_t lane = 1U; lane <= count; ++lane) {
            if (!reservoirs[lane]) {
                reservoirs[lane].swap(prepared.reservoirs[lane]);
                laneTimelineAttached[lane] = false;
                eosMarked[lane] = false;
                eosEndFrame[lane] = 0U;
                if (active) reservoirs[lane]->reset(currentIngressEpoch, static_cast<std::uint64_t>(sourceCursor));
            }
        }
        for (std::size_t lane = count + 1U; lane < reservoirs.size(); ++lane) {
            reservoirs[lane].swap(prepared.reservoirs[lane]);
            laneTimelineAttached[lane] = false;
            eosMarked[lane] = false;
            eosEndFrame[lane] = 0U;
        }
        stemBusReadyMaskForCurrentRender = 0U;
        primeContainsStemBuses = false;
        for (auto &range : rawRanges) range.valid = false;
        stemDspWarmupFramesRemaining = stemDspHistoryFrames();
        if (!timePitch.setActiveChannelCount((count + 1U) * kStereoChannelCount)) return false;
        configuration.stemLaneCount = static_cast<std::uint32_t>(count);
        desiredControls.mixer.stemLaneCount = static_cast<std::uint32_t>(count);
        mixerDirty = true;
        stemGainPreviewMask &= stemwiseRequiredStemMask(count);
        return true;
    }

    enum class OwnershipCheck : std::uint8_t {
        Ok,
        Invalid,
        Stale,
    };

    struct RenderPlan final {
        std::uint32_t frames{0};
        double sourceEnd{0.0};
        bool beginLoopFade{false};
        bool reachesLoopEnd{false};
        bool reachesEos{false};
    };

    OwnershipCheck checkOwnership(SongRenderOwnership requested) const noexcept {
        if (!validOwnership(requested) || !active) return OwnershipCheck::Invalid;
        if (requested.selectionToken == currentOwnership.selectionToken &&
            requested.generation == currentOwnership.generation) {
            return OwnershipCheck::Ok;
        }
        if (requested.generation < currentOwnership.generation) {
            return OwnershipCheck::Stale;
        }
        return OwnershipCheck::Invalid;
    }

    [[nodiscard]] bool directiveOwnerMatches(
        const LoopCycleDirective &directive) const noexcept {
        return active && directive.owner.selectionToken == currentOwnership.selectionToken &&
            directive.owner.renderGeneration == currentOwnership.generation;
    }

    [[nodiscard]] static bool cacheOnlyAction(
        LoopCycleDirectiveAction action) noexcept {
        return action == LoopCycleDirectiveAction::InstallWarmCacheOnly ||
            action == LoopCycleDirectiveAction::ActivateColdCacheOnly ||
            action == LoopCycleDirectiveAction::WrapCacheOnly;
    }

    [[nodiscard]] static bool streamedAction(
        LoopCycleDirectiveAction action) noexcept {
        return action == LoopCycleDirectiveAction::InstallWarmStreamed ||
            action == LoopCycleDirectiveAction::ActivateColdStreamed ||
            action == LoopCycleDirectiveAction::WrapStreamed;
    }

    [[nodiscard]] static bool installAction(
        LoopCycleDirectiveAction action) noexcept {
        return action == LoopCycleDirectiveAction::InstallWarmCacheOnly ||
            action == LoopCycleDirectiveAction::InstallWarmStreamed ||
            action == LoopCycleDirectiveAction::ActivateColdCacheOnly ||
            action == LoopCycleDirectiveAction::ActivateColdStreamed;
    }

    [[nodiscard]] static bool wrapAction(
        LoopCycleDirectiveAction action) noexcept {
        return action == LoopCycleDirectiveAction::WrapCacheOnly ||
            action == LoopCycleDirectiveAction::WrapStreamed;
    }

    struct RawLoopHeadSource final {
        const LoopHeadCache::ReadView *cache{nullptr};
        std::uint64_t startTimelineFrame{0U};
        std::uint64_t endTimelineFrameExclusive{0U};
        std::uint32_t requiredLaneMask{0U};

        [[nodiscard]] bool present() const noexcept {
            return cache != nullptr &&
                startTimelineFrame < endTimelineFrameExclusive;
        }
    };

    [[nodiscard]] RawLoopHeadSource rawLoopHeadSource() const noexcept {
        if (managedLoopInstalled) {
            return {
                .cache = &managedLoopCache,
                .startTimelineFrame = loopStart,
                .endTimelineFrameExclusive = managedLoopHeadEnd,
                .requiredLaneMask = managedLoopRequiredLaneMask,
            };
        }
        if (atomicLoopCache.valid()) {
            const auto &head = atomicLoopCache.snapshot();
            return {&atomicLoopCache, head.key.startTimelineFrame,
                head.key.startTimelineFrame + head.frameCount, head.requiredLaneMask};
        }
        if (retiredLoopHeadActive &&
            retiredLoopHeadOwner.selectionToken == currentOwnership.selectionToken &&
            retiredLoopHeadOwner.generation == currentOwnership.generation) {
            return {
                .cache = &retiredLoopHeadCache,
                .startTimelineFrame = retiredLoopHeadStart,
                .endTimelineFrameExclusive = retiredLoopHeadEnd,
                .requiredLaneMask = retiredLoopHeadRequiredLaneMask,
            };
        }
        return {};
    }

    void clearRetiredLoopHead() noexcept {
        retiredLoopHeadCache = {};
        retiredLoopHeadActive = false;
        retiredLoopHeadStart = 0U;
        retiredLoopHeadEnd = 0U;
        retiredLoopHeadRequiredLaneMask = 0U;
        retiredLoopHeadOwner = {};
    }

    [[nodiscard]] bool currentLinearPassNeedsManagedHead() const noexcept {
        if (!managedLoopInstalled || !managedLoopCache.valid() ||
            loopStart >= managedLoopHeadEnd) {
            return false;
        }
        // A streamed install may already have redirected its live reservoirs to
        // headEnd while playback is still before A. Keep the immutable head for
        // that upcoming portion of this same linear pass as well as while inside it.
        const bool sourceNeedsHead =
            sourceCursor < static_cast<double>(managedLoopHeadEnd);
        const bool feedNeedsHead = processedForSong && feedCursor < managedLoopHeadEnd;
        return sourceNeedsHead || feedNeedsHead;
    }

    void releaseRetiredLoopHeadIfPassed() noexcept {
        if (atomicLoopCache.valid() && !loopActive) {
            const auto end = atomicLoopCache.snapshot().key.startTimelineFrame + atomicLoopCache.snapshot().frameCount;
            if (sourceCursor + kTimelineEpsilon >= static_cast<double>(end) && feedCursor >= end) atomicLoopCache = {};
        }
        if (!retiredLoopHeadActive) return;
        const bool sourcePassed = sourceCursor + kTimelineEpsilon >=
            static_cast<double>(retiredLoopHeadEnd);
        const bool feedPassed = feedCursor >= retiredLoopHeadEnd;
        if (sourcePassed && feedPassed) clearRetiredLoopHead();
    }

    void disableManagedLoop(bool retainRawHeadForLinearPass = false) noexcept {
        if (retainRawHeadForLinearPass && currentLinearPassNeedsManagedHead()) {
            clearRetiredLoopHead();
            retiredLoopHeadCache = std::move(managedLoopCache);
            if (retiredLoopHeadCache.valid()) {
                const LoopHeadCacheSnapshot &snapshot = retiredLoopHeadCache.snapshot();
                retiredLoopHeadStart = snapshot.key.startTimelineFrame;
                retiredLoopHeadEnd = snapshot.key.startTimelineFrame + snapshot.frameCount;
                retiredLoopHeadRequiredLaneMask = snapshot.requiredLaneMask;
                retiredLoopHeadOwner = currentOwnership;
                retiredLoopHeadActive = retiredLoopHeadStart < retiredLoopHeadEnd;
            }
        } else if (!retainRawHeadForLinearPass || managedLoopInstalled) {
            managedLoopCache = {};
            clearRetiredLoopHead();
        }
        managedLoopInstalled = false;
        managedLoopCacheOnly = false;
        managedLoopBoundaryPending = false;
        managedLoopRevision = 0U;
        managedLoopCycle = 0U;
        managedLoopIngressEpoch = 0U;
        managedLoopHeadEnd = 0U;
        managedLoopChecksum = 0U;
        managedLoopRequiredLaneMask = 0U;
        managedBoundaryEvidence = {};
        loopActive = false;
        loopStart = 0U;
        loopEnd = 0U;
        loopFadeStarted = false;
    }

    [[nodiscard]] bool cacheMatchesDirective(
        const LoopHeadCacheSnapshot &snapshot,
        const LoopCycleDirective &directive) const noexcept {
        if (!snapshot.valid || snapshot.combinedChecksum == 0U ||
            snapshot.key.loopRevision != directive.cacheRevision ||
            snapshot.key.startTimelineFrame != directive.startTimelineFrame ||
            snapshot.requiredLaneMask != directive.requiredLaneMask ||
            (snapshot.completedLaneMask & directive.requiredLaneMask) !=
                directive.requiredLaneMask ||
            snapshot.boundaryMode != directive.boundaryMode ||
            snapshot.combinedChecksum != directive.cacheChecksum) {
            return false;
        }
        const std::uint64_t length = directive.endTimelineFrameExclusive -
            directive.startTimelineFrame;
        const std::uint32_t expectedFrames = static_cast<std::uint32_t>(
            std::min<std::uint64_t>(length, LoopHeadCache::kCapacityFrames));
        const bool boundaryMatches = directive.boundaryMode == LoopHeadBoundaryMode::OriginalEos &&
            snapshot.key.endTimelineFrameExclusive == directive.startTimelineFrame +
                    LoopHeadCache::kCapacityFrames &&
                (!snapshot.originalEndResolved ||
                 snapshot.resolvedOriginalEndTimelineFrameExclusive ==
                     directive.endTimelineFrameExclusive ||
                 (playbackEndFrame != 0U && directive.endTimelineFrameExclusive == playbackEndFrame &&
                     playbackEndFrame < snapshot.resolvedOriginalEndTimelineFrameExclusive));
        return boundaryMatches && snapshot.frameCount >= expectedFrames &&
            directive.headEndTimelineFrameExclusive ==
                directive.startTimelineFrame + expectedFrames;
    }

    void resetManagedBodyIngress(std::uint64_t ingressEpoch) noexcept {
        currentIngressEpoch = ingressEpoch;
        for (auto &reservoir : reservoirs) {
            if (reservoir) reservoir->reset(ingressEpoch, managedLoopHeadEnd);
        }
        laneTimelineAttached.fill(false);
        eosMarked.fill(false);
        eosEndFrame.fill(0U);
    }

    void alignWarmManagedBodyIngress() noexcept {
        if (sourceCursor + kTimelineEpsilon >=
            static_cast<double>(managedLoopHeadEnd)) {
            return;
        }
        for (auto &reservoir : reservoirs) {
            if (!reservoir) continue;
            const TimelineStereoBufferDiagnostics state = reservoir->diagnostics();
            if (state.generation != currentIngressEpoch ||
                state.endTimelineFrameExclusive <= managedLoopHeadEnd) {
                if (reservoir) reservoir->reset(currentIngressEpoch, managedLoopHeadEnd);
            } else {
                static_cast<void>(reservoir->discardBefore(managedLoopHeadEnd));
            }
        }
    }

    void repositionAtManagedLoopStart(bool completedWrap) noexcept {
        sourceCursor = static_cast<double>(loopStart);
        feedCursor = loopStart;
        transition.reset();
        neutralTransition = false;
        transitionDirectCursor = 0U;
        loopFadeStarted = false;
        if (processedForSong) {
            timePitch.onSeek();
            needsPrime = true;
        }
        if (completedWrap) {
            mixer.completePostDspLoopWrap(
                currentOwnership.selectionToken,
                currentOwnership.generation,
                effectiveLoopDeclickFrames());
        } else {
            mixer.resetPostDspForDiscontinuity(
                currentOwnership.selectionToken,
                currentOwnership.generation,
                effectiveLoopDeclickFrames());
        }
    }

    void publishManagedBoundary() noexcept {
        if (!managedLoopInstalled || managedLoopBoundaryPending) return;
        if (managedBoundarySequence == std::numeric_limits<std::uint64_t>::max()) return;
        ++managedBoundarySequence;
        managedLoopBoundaryPending = true;
        managedBoundaryEvidence = {
            .sequence = managedBoundarySequence,
            .loopRevision = managedLoopRevision,
            .cycle = managedLoopCycle,
            .owner = {
                .selectionToken = currentOwnership.selectionToken,
                .renderGeneration = currentOwnership.generation,
            },
            .ingressEpoch = managedLoopIngressEpoch,
            .sourceTimelineFrame = loopEnd,
        };
        saturatingIncrement(diagnostics.loopBoundaryEvents);
    }

    SongRenderLoopDirectiveStatus applyLoopDirective(
        const LoopCycleDirective &directive,
        LoopHeadCache::ReadView view) noexcept {
        const auto reject = [this](SongRenderLoopDirectiveStatus status) noexcept {
            saturatingIncrement(diagnostics.loopDirectivesRejected);
            return status;
        };
        if (!directive.valid || directive.sequence == 0U ||
            directive.action == LoopCycleDirectiveAction::None) {
            return reject(SongRenderLoopDirectiveStatus::InvalidDirective);
        }
        if (!directiveOwnerMatches(directive)) {
            return reject(SongRenderLoopDirectiveStatus::InvalidOwnership);
        }
        if (directive.sequence <= lastManagedDirectiveSequence) {
            return reject(SongRenderLoopDirectiveStatus::StaleDirective);
        }

        if (directive.action == LoopCycleDirectiveAction::Clear ||
            directive.action == LoopCycleDirectiveAction::DeadlineFallback) {
            lastManagedDirectiveSequence = directive.sequence;
            disableManagedLoop(directive.action == LoopCycleDirectiveAction::Clear);
            // Policy retirement does not move the source cursor. Undo only a fade aimed at the
            // obsolete B; a transport/DSP reset here creates a hard zero edge on every pointer MOVE.
            mixer.continuePostDspAfterLoopPolicyChange(effectiveLoopDeclickFrames());
            saturatingIncrement(diagnostics.loopDirectivesAccepted);
            return SongRenderLoopDirectiveStatus::Cleared;
        }

        const bool cacheOnly = cacheOnlyAction(directive.action);
        const bool streamed = streamedAction(directive.action);
        if ((!cacheOnly && !streamed) ||
            directive.loopRevision == 0U || directive.ingressEpoch == 0U ||
            directive.startTimelineFrame >= directive.endTimelineFrameExclusive ||
            (playbackEndFrame != 0U && directive.endTimelineFrameExclusive > playbackEndFrame) ||
            directive.requiredLaneMask == 0U ||
            (directive.requiredLaneMask & 1U) == 0U ||
            (directive.requiredLaneMask & ~requiredSongMask()) != 0U) {
            return reject(SongRenderLoopDirectiveStatus::InvalidDirective);
        }
        const std::uint64_t length = directive.endTimelineFrameExclusive -
            directive.startTimelineFrame;
        if ((cacheOnly && length > LoopHeadCache::kCapacityFrames) ||
            (streamed && length <= LoopHeadCache::kCapacityFrames)) {
            return reject(SongRenderLoopDirectiveStatus::InvalidDirective);
        }

        if (!view.valid()) {
            return reject(SongRenderLoopDirectiveStatus::CacheUnavailable);
        }
        if (!cacheMatchesDirective(view.snapshot(), directive)) {
            return reject(SongRenderLoopDirectiveStatus::CacheMismatch);
        }

        const bool warm =
            directive.action == LoopCycleDirectiveAction::InstallWarmCacheOnly ||
            directive.action == LoopCycleDirectiveAction::InstallWarmStreamed;
        const bool compatibleInstalledRetarget = warm && managedLoopInstalled &&
            directive.loopRevision > managedLoopRevision && managedLoopCache.valid() &&
            view.snapshot().key == managedLoopCache.snapshot().key &&
            directive.startTimelineFrame == loopStart &&
            directive.cacheChecksum == managedLoopChecksum &&
            directive.requiredLaneMask == managedLoopRequiredLaneMask &&
            directive.ingressEpoch == currentIngressEpoch;
        const bool retargetAlreadyPastB = compatibleInstalledRetarget &&
            sourceCursor + kTimelineEpsilon >=
                static_cast<double>(directive.endTimelineFrameExclusive);

        if (installAction(directive.action)) {
            if ((managedLoopInstalled && directive.loopRevision <= managedLoopRevision) ||
                (warm && ((!compatibleInstalledRetarget &&
                           sourceCursor + kTimelineEpsilon >=
                               static_cast<double>(directive.endTimelineFrameExclusive)) ||
                          directive.cycle != 0U)) ||
                (!warm && directive.cycle == 0U)) {
                return reject(SongRenderLoopDirectiveStatus::InvalidDirective);
            }
            if (warm && directive.ingressEpoch != currentIngressEpoch) {
                return reject(SongRenderLoopDirectiveStatus::InvalidDirective);
            }
        } else if (wrapAction(directive.action)) {
            if (!managedLoopInstalled || !managedLoopBoundaryPending) {
                return reject(SongRenderLoopDirectiveStatus::BoundaryNotPending);
            }
            if (managedLoopCycle == std::numeric_limits<std::uint64_t>::max() ||
                directive.loopRevision != managedLoopRevision ||
                directive.cycle != managedLoopCycle + 1U ||
                directive.startTimelineFrame != loopStart ||
                directive.endTimelineFrameExclusive != loopEnd ||
                directive.headEndTimelineFrameExclusive != managedLoopHeadEnd ||
                directive.cacheChecksum != managedLoopChecksum ||
                directive.requiredLaneMask != managedLoopRequiredLaneMask ||
                (cacheOnly && (!managedLoopCacheOnly ||
                               directive.ingressEpoch != managedLoopIngressEpoch)) ||
                (streamed && (managedLoopCacheOnly ||
                              directive.ingressEpoch <= managedLoopIngressEpoch))) {
                return reject(SongRenderLoopDirectiveStatus::BoundaryMismatch);
            }
        }

        const bool completedWrap = wrapAction(directive.action);
        clearRetiredLoopHead();
        managedLoopCache = std::move(view);
        managedLoopInstalled = true;
        managedLoopCacheOnly = cacheOnly;
        managedLoopBoundaryPending = false;
        managedLoopRevision = directive.loopRevision;
        managedLoopCycle = directive.cycle;
        managedLoopIngressEpoch = directive.ingressEpoch;
        managedLoopHeadEnd = directive.headEndTimelineFrameExclusive;
        managedLoopChecksum = directive.cacheChecksum;
        managedLoopRequiredLaneMask = directive.requiredLaneMask;
        managedBoundaryEvidence = {};
        loopActive = true;
        loopStart = directive.startTimelineFrame;
        loopEnd = directive.endTimelineFrameExclusive;
        loopDeclickFrames = directive.declickFrames;

        const bool cold =
            directive.action == LoopCycleDirectiveAction::ActivateColdCacheOnly ||
            directive.action == LoopCycleDirectiveAction::ActivateColdStreamed;
        if (streamed && (cold || completedWrap)) {
            resetManagedBodyIngress(directive.ingressEpoch);
        } else if (streamed) {
            alignWarmManagedBodyIngress();
        }
        if (cold || completedWrap) {
            repositionAtManagedLoopStart(completedWrap);
        } else {
            loopFadeStarted = false;
            mixer.continuePostDspAfterLoopPolicyChange(effectiveLoopDeclickFrames());
        }
        if (retargetAlreadyPastB) publishManagedBoundary();
        lastManagedDirectiveSequence = directive.sequence;
        if (completedWrap) saturatingIncrement(diagnostics.loopWraps);
        saturatingIncrement(diagnostics.loopDirectivesAccepted);
        return SongRenderLoopDirectiveStatus::Accepted;
    }

    SongRenderResetStatus beginSong(
        SongRenderOwnership nextOwnership,
        std::uint64_t nextIngressEpoch,
        std::uint64_t firstTimelineFrame,
        std::int64_t outputClockStartFrame,
        std::uint64_t playbackEndFrameExclusive) noexcept {
        if (continuationCopy.pending || !validOwnership(nextOwnership) || nextIngressEpoch == 0U) {
            return SongRenderResetStatus::InvalidOwnership;
        }
        if (firstTimelineFrame >
                static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max()) ||
            outputClockStartFrame < 0 ||
            (playbackEndFrameExclusive != 0U && (firstTimelineFrame >= playbackEndFrameExclusive ||
                playbackEndFrameExclusive > static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max())))) {
            return SongRenderResetStatus::InvalidTimeline;
        }
        if (active && (nextOwnership.generation <= currentOwnership.generation ||
                       nextIngressEpoch <= currentIngressEpoch)) {
            saturatingIncrement(diagnostics.staleOwnershipRequests);
            return SongRenderResetStatus::StaleGeneration;
        }

        resetBank(
            nextOwnership,
            nextIngressEpoch,
            firstTimelineFrame,
            outputClockStartFrame,
            true,
            false);
        playbackEndFrame = playbackEndFrameExclusive;
        saturatingIncrement(diagnostics.beginSongCount);
        return SongRenderResetStatus::Ok;
    }

    SongRenderResetStatus seek(
        SongRenderOwnership nextOwnership,
        std::uint64_t nextIngressEpoch,
        std::uint64_t firstTimelineFrame,
        std::int64_t outputClockStartFrame,
        std::uint64_t playbackEndFrameExclusive) noexcept {
        if (continuationCopy.pending || !validOwnership(nextOwnership) || nextIngressEpoch == 0U || !active ||
            nextOwnership.selectionToken != currentOwnership.selectionToken) {
            return SongRenderResetStatus::InvalidOwnership;
        }
        if (nextOwnership.generation <= currentOwnership.generation ||
            nextIngressEpoch <= currentIngressEpoch) {
            saturatingIncrement(diagnostics.staleOwnershipRequests);
            return SongRenderResetStatus::StaleGeneration;
        }
        if (firstTimelineFrame >
                static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max()) ||
            outputClockStartFrame < 0 ||
            (playbackEndFrameExclusive != 0U && (firstTimelineFrame >= playbackEndFrameExclusive ||
                playbackEndFrameExclusive > static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max())))) {
            return SongRenderResetStatus::InvalidTimeline;
        }

        resetBank(
            nextOwnership,
            nextIngressEpoch,
            firstTimelineFrame,
            outputClockStartFrame,
            false,
            true);
        playbackEndFrame = playbackEndFrameExclusive;
        saturatingIncrement(diagnostics.seekCount);
        return SongRenderResetStatus::Ok;
    }

    ~Impl() { cacheReadConsumer->active.store(false, std::memory_order_release); }

    void resetBank(
        SongRenderOwnership nextOwnership,
        std::uint64_t nextIngressEpoch,
        std::uint64_t firstTimelineFrame,
        std::int64_t outputClockStartFrame,
        bool newSong,
        bool seekReset) noexcept {
        const bool wasProcessed = processedForSong;
        if (managedLoopInstalled || retiredLoopHeadActive) disableManagedLoop();
        if (active) {
            static_cast<void>(retireStemGainPreview(currentOwnership));
        } else {
            stemGainPreviewMask = 0U;
        }
        atomicLoopCache = {};
        cacheReadConsumer->active.store(false, std::memory_order_release);
        currentOwnership = nextOwnership;
        currentSongCache = nullptr;
        currentSongCacheOwnership = {};
        retainOriginalHead = false;
        retainedCopyBorrower = nullptr;
        retainedCopyOwner = {};
        retainedCopyFloor = 0U;
        currentIngressEpoch = nextIngressEpoch;
        active = true;
        sourceCursor = static_cast<double>(firstTimelineFrame);
        feedCursor = firstTimelineFrame;
        outputClock = outputClockStartFrame;
        for (auto &reservoir : reservoirs) {
            if (reservoir) reservoir->reset(nextIngressEpoch, firstTimelineFrame);
        }
        laneTimelineAttached.fill(false);
        eosMarked.fill(false);
        eosEndFrame.fill(0);
        transition.reset();
        neutralTransition = false;
        transitionDirectCursor = 0;
        activationPrepared = false;
        loopFadeStarted = false;
        // Do not reset stemGainPreviewSequence. It is a lifetime high-water mark for this resident
        // pipeline, not per-song state; resetting it would make a delayed older publication valid.
        stemGainPreviewMask = 0;
        stemGainPreviewGains.fill(1.0F);

        if (seekReset) {
            timePitch.onSeek();
        } else {
            timePitch.onDiscontinuity();
        }
        processedForSong = newSong ? false : wasProcessed;
        needsPrime = processedForSong;
        tuneDirty = true;

        if (newSong) {
            loopActive = false;
            loopStart = 0;
            loopEnd = 0;
            beatCount = 0;
            ++beatGridMutation;
                mixer.clearBeatGrid();
        }

        mixer.resetPostDspForDiscontinuity(
            nextOwnership.selectionToken,
            nextOwnership.generation,
            desiredMixerControls().defaultDeclickFrames);
        resetPreDspControlsImmediate();
        if (!newSong && beatCount != 0) {
            static_cast<void>(mixer.setBeatGrid(beatGrid.data(), beatAccents.data(), beatCount));
        }
    }

    TimelineAppendResult appendLanePcm(
        SongRenderLane lane,
        std::uint64_t ingressEpoch,
        std::uint64_t firstTimelineFrame,
        const float *interleavedStereo,
        std::size_t frameCount) noexcept {
        saturatingIncrement(diagnostics.appendCalls);
        if (!validLane(lane) || laneIndex(lane) > configuration.stemLaneCount || !active || ingressEpoch == 0) {
            return {TimelineAppendStatus::InvalidArgument};
        }
        if (ingressEpoch != currentIngressEpoch) {
            saturatingIncrement(diagnostics.staleAppendCalls);
            return {
                TimelineAppendStatus::StaleGeneration,
                frameCount,
                0,
                frameCount,
                false,
            };
        }
        const std::size_t index = laneIndex(lane);
        TimelineStereoBuffer &reservoir = *reservoirs[index];
        const TimelineStereoBufferDiagnostics retained = reservoir.diagnostics();

        // Stem decoders may be attached after Original playback has already advanced. An empty
        // stem reservoir still points at the bank's initial frame because discardBefore() cannot
        // advance an empty range. Permit exactly one lane-local alignment for the first accepted
        // block in this ingress epoch. Once any block has established that lane's timeline, every
        // later unexplained gap remains a hard rejection. Original never receives this exception.
        const bool firstLateStemAttachment =
            index != laneIndex(SongRenderLane::Original) &&
            !laneTimelineAttached[index] &&
            retained.generation == ingressEpoch &&
            retained.storedFrames == 0U &&
            retained.endTimelineFrameExclusive != firstTimelineFrame;
        const TimelineAppendResult result = reservoir.append(
            ingressEpoch,
            firstTimelineFrame,
            interleavedStereo,
            frameCount,
            firstLateStemAttachment);
        if ((result.status == TimelineAppendStatus::Accepted ||
             result.status == TimelineAppendStatus::Partial) &&
            result.inputFramesAccepted != 0U) {
            laneTimelineAttached[index] = true;
        }
        return result;
    }

    SongRenderEosStatus markLaneEndOfStream(
        SongRenderLane lane,
        std::uint64_t ingressEpoch,
        std::uint64_t endTimelineFrameExclusive) noexcept {
        if (!validLane(lane) || laneIndex(lane) > configuration.stemLaneCount) return SongRenderEosStatus::InvalidLane;
        if (!active || ingressEpoch == 0) return SongRenderEosStatus::InvalidOwnership;
        if (ingressEpoch != currentIngressEpoch) {
            return SongRenderEosStatus::StaleGeneration;
        }
        const std::size_t index = laneIndex(lane);
        if (eosMarked[index]) {
            return eosEndFrame[index] == endTimelineFrameExclusive
                ? SongRenderEosStatus::Accepted
                : SongRenderEosStatus::ConflictingEndFrame;
        }
        eosMarked[index] = true;
        eosEndFrame[index] = endTimelineFrameExclusive;
        saturatingIncrement(diagnostics.eosMarkersAccepted);
        return SongRenderEosStatus::Accepted;
    }

    bool bindCurrentSongPcmCache(
        SongRenderOwnership ownership,
        const CurrentSongPcmCache *cache) noexcept {
        if (!active || ownership.selectionToken != currentOwnership.selectionToken ||
            ownership.generation != currentOwnership.generation) return false;
        if (cache && !cache->admitReadConsumer(cacheReadConsumer, ownership.selectionToken, ownership.generation)) return false;
        if (!cache) cacheReadConsumer->active.store(false, std::memory_order_release);
        currentSongCache = cache;
        currentSongCacheOwnership = cache == nullptr ? SongRenderOwnership{} : ownership;
        return true;
    }

    bool retainOriginalTransitionHead(
        SongRenderOwnership ownership,
        bool retain) noexcept {
        if (!active || ownership.selectionToken != currentOwnership.selectionToken ||
            ownership.generation != currentOwnership.generation) return false;
        retainOriginalHead = retain;
        return true;
    }

    bool beginCurrentSongPcmCacheFallback(
        SongRenderOwnership ownership,
        std::uint64_t firstReservoirFrame) noexcept {
        if (!active || ownership.selectionToken != currentOwnership.selectionToken ||
            ownership.generation != currentOwnership.generation || currentSongCache == nullptr ||
            currentSongCacheOwnership.selectionToken != currentOwnership.selectionToken ||
            currentSongCacheOwnership.generation != currentOwnership.generation) {
            return false;
        }
        const CurrentSongCacheSnapshot cache = currentSongCache->snapshot();
        if (!cache.allocationFailed || cache.publishedFrames[0U] != firstReservoirFrame) {
            return false;
        }
        const std::uint64_t requiredFrame = processedForSong
            ? feedCursor
            : static_cast<std::uint64_t>(std::floor(sourceCursor));
        // A retained frame-zero builder can fail while its sought consumer already owns a
        // usable ordinary suffix beyond the cache prefix. Preserve that exact accepted PCM.
        const auto retained = reservoirs[0U]->diagnostics();
        if (retained.generation == currentIngressEpoch &&
            retained.firstTimelineFrame <= requiredFrame &&
            retained.endTimelineFrameExclusive > std::max(requiredFrame, firstReservoirFrame)) return true;
        if (firstReservoirFrame < requiredFrame) return false;
        reservoirs[0U]->reset(currentIngressEpoch, firstReservoirFrame);
        laneTimelineAttached[0U] = false;
        retainOriginalHead = false;
        return true;
    }

    TimelineCopyResult copyRetainedLanePcm(
        SongRenderOwnership ownership,
        SongRenderLane lane,
        std::uint64_t firstTimelineFrame,
        float *output,
        std::size_t outputCapacityFrames,
        std::size_t requestedFrames) const noexcept {
        if (!active || !validLane(lane) || laneIndex(lane) > configuration.stemLaneCount ||
            ownership.selectionToken != currentOwnership.selectionToken ||
            ownership.generation != currentOwnership.generation) {
            return {TimelineCopyStatus::StaleGeneration, 0U};
        }
        return reservoirs[laneIndex(lane)]->copyAt(
            currentIngressEpoch,
            firstTimelineFrame,
            output,
            outputCapacityFrames,
            requestedFrames);
    }

    SongRenderControlStatus submitControls(
        const SongRenderControlSnapshot &snapshot) noexcept {
        if (snapshot.revision == 0) {
            saturatingIncrement(diagnostics.controlSnapshotsRejected);
            return SongRenderControlStatus::InvalidRevision;
        }
        const bool canonicalRegression = snapshot.revision < acceptedControlRevision;
        const bool invalidApplicationEpoch = snapshot.applicationEpoch == 0U;
        const bool staleApplicationEpoch = snapshot.applicationEpoch <= acceptedControlApplicationEpoch;
        if (canonicalRegression || invalidApplicationEpoch || staleApplicationEpoch) {
            saturatingIncrement(diagnostics.controlSnapshotsRejected);
            return SongRenderControlStatus::StaleRevision;
        }
        if (!finiteTune(snapshot.tempo, snapshot.pitchSemitones)) {
            saturatingIncrement(diagnostics.controlSnapshotsRejected);
            return SongRenderControlStatus::InvalidValues;
        }
        const bool shouldRetireStemGainPreview =
            snapshot.mixer.sourceMode != MixerSourceMode::Stems ||
            snapshot.sourceIntentRevision != desiredControls.sourceIntentRevision ||
            snapshot.stemIntentRevision != desiredControls.stemIntentRevision;
        desiredControls = snapshot;
        desiredControls.mixer.stemLaneCount = configuration.stemLaneCount;
        acceptedControlRevision = snapshot.revision;
        acceptedControlApplicationEpoch = snapshot.applicationEpoch;
        mixerDirty = true;
        tuneDirty = true;
        if (shouldRetireStemGainPreview) {
            static_cast<void>(retireStemGainPreview(currentOwnership));
        }
        saturatingIncrement(diagnostics.controlSnapshotsAccepted);
        return SongRenderControlStatus::Accepted;
    }

    bool retireStemGainPreview(SongRenderOwnership requestedOwnership) noexcept {
        if (checkOwnership(requestedOwnership) != OwnershipCheck::Ok) return false;
        if (stemGainPreviewMask != 0U) {
            stemGainPreviewMask = 0U;
            mixerDirty = true;
        }
        return true;
    }

    SongRenderStemGainPreviewStatus submitStemGainPreview(
        SongRenderOwnership requestedOwnership,
        std::uint64_t sequence,
        std::uint64_t coversOfferSequence,
        std::uint32_t activeMask,
        const std::array<float, kStemLaneCount> &gains) noexcept {
        if (checkOwnership(requestedOwnership) != OwnershipCheck::Ok) {
            return SongRenderStemGainPreviewStatus::InvalidOwnership;
        }
        if (sequence == 0U || coversOfferSequence == 0U ||
            sequence <= stemGainPreviewSequence) {
            return SongRenderStemGainPreviewStatus::StaleSequence;
        }
        if ((activeMask & ~stemwiseRequiredStemMask(configuration.stemLaneCount)) != 0U ||
            std::any_of(gains.begin(), gains.end(), [](float gain) noexcept {
                return !std::isfinite(gain) || gain < 0.0F || gain > 2.0F;
            })) {
            return SongRenderStemGainPreviewStatus::InvalidValues;
        }

        bool changed = stemGainPreviewMask != activeMask;
        for (std::size_t lane = 0U; lane < gains.size(); ++lane) {
            const std::uint32_t bit = 1U << static_cast<std::uint32_t>(lane);
            if ((activeMask & bit) != 0U && stemGainPreviewGains[lane] != gains[lane]) {
                changed = true;
            }
        }
        stemGainPreviewSequence = sequence;
        stemGainPreviewCoversOfferSequence = coversOfferSequence;
        stemGainPreviewMask = activeMask;
        stemGainPreviewGains = gains;
        mixerDirty = mixerDirty || changed;
        return SongRenderStemGainPreviewStatus::Accepted;
    }

    [[nodiscard]] SongRenderLateMixEndpoint composeLateMixEndpoint() const noexcept {
        const MixerLateControlEndpoint endpoint = mixer.lateControlEndpoint();
        return {
            .canonicalControlRevision = acceptedControlRevision,
            .sourceIntentRevision = desiredControls.sourceIntentRevision,
            .stemIntentRevision = desiredControls.stemIntentRevision,
            .sourceMode = endpoint.sourceMode,
            .requiredStemMask = stemwiseRequiredStemMask(configuration.stemLaneCount),
            .effectiveStemGains = endpoint.effectiveStemGains,
            .musicGain = endpoint.musicGain,
            .mixHeadroom = endpoint.mixHeadroom,
            .sourceRampFrames = endpoint.sourceRampFrames,
            .laneRampFrames = endpoint.laneRampFrames,
            .masterRampFrames = endpoint.masterRampFrames,
            .previewPublicationSequence = stemGainPreviewSequence,
            .previewCoversOfferSequence = stemGainPreviewCoversOfferSequence,
            .previewActiveMask = stemGainPreviewMask,
            .previewGains = stemGainPreviewGains,
        };
    }

    SongRenderLateMixEndpoint prepareLateMixEndpoint(
        SongRenderOwnership requestedOwnership) noexcept {
        if (checkOwnership(requestedOwnership) != OwnershipCheck::Ok ||
            acceptedControlRevision == 0U ||
            desiredControls.sourceIntentRevision == 0U ||
            desiredControls.stemIntentRevision == 0U) {
            return {};
        }

        MixerControlSnapshot snapshot = desiredMixerControls();
        if (snapshot.sourceMode == MixerSourceMode::Stems) {
            for (std::size_t lane = 0U; lane < snapshot.stems.size(); ++lane) {
                const std::uint32_t bit = 1U << static_cast<std::uint32_t>(lane);
                if ((stemGainPreviewMask & bit) != 0U) {
                    snapshot.stems[lane].gain = stemGainPreviewGains[lane];
                    snapshot.stems[lane].muted = false;
                }
            }
        }
        snapshot.revision = nextMixerRevision();
        if (!mixer.applyControls(snapshot)) return {};
        // Keep mixerDirty set. The next render must still make its exact range-coverage decision
        // and may publish a safe Original block target when declared parts are not present.
        saturatingIncrement(diagnostics.mixerSnapshotsApplied);
        return composeLateMixEndpoint();
    }



    bool setBeatGrid(
        SongRenderOwnership requestedOwnership,
        const std::int64_t *frames,
        const std::uint8_t *accentFlags,
        std::size_t count) noexcept {
        return setBeatGridInternal(requestedOwnership, frames, accentFlags, count);
    }

    bool setBeatGridInternal(
        SongRenderOwnership requestedOwnership,
        const std::int64_t *frames,
        const std::uint8_t *accentFlags,
        std::size_t count) noexcept {
        if (checkOwnership(requestedOwnership) != OwnershipCheck::Ok || frames == nullptr ||
            count < 2 || count > beatGrid.size() || accentFlags == nullptr) {
            return false;
        }
        for (std::size_t index = 0; index < count; ++index) {
            if (frames[index] < 0 ||
                (index != 0 && frames[index] <= frames[index - 1]) ||
                accentFlags[index] > 1U) {
                return false;
            }
        }
        std::copy_n(frames, count, beatGrid.begin());
        std::copy_n(accentFlags, count, beatAccents.begin());
        if (!mixer.setBeatGrid(beatGrid.data(), beatAccents.data(), count)) return false;
        beatCount = count;
        ++beatGridMutation;
        return true;
    }

    void clearBeatGrid(SongRenderOwnership requestedOwnership) noexcept {
        if (checkOwnership(requestedOwnership) != OwnershipCheck::Ok) return;
        beatCount = 0;
        mixer.clearBeatGrid();
        ++beatGridMutation;
    }

    std::size_t loopContinuationHeadFrames(std::uint64_t start, std::uint64_t end) const noexcept {
        if (end <= start) return 0U;
        return static_cast<std::size_t>(std::min<std::uint64_t>(end - start,
            maximumPrimeFrames + maximumInputFrames + configuration.maximumBlockFrames * 4U));
    }

    [[nodiscard]] bool atomicRangeRetained(std::uint32_t mask, std::uint64_t start,
        std::size_t frames) const noexcept {
        if (atomicLoopCache.valid()) {
            const auto &head = atomicLoopCache.snapshot();
            if ((head.completedLaneMask & mask) == mask && start >= head.key.startTimelineFrame &&
                start <= head.key.startTimelineFrame + head.frameCount &&
                frames <= head.key.startTimelineFrame + head.frameCount - start) return true;
        }
        return currentSongCache && currentSongCacheOwnership.selectionToken == currentOwnership.selectionToken &&
            currentSongCacheOwnership.generation == currentOwnership.generation &&
            currentSongCache->covers(mask, start, frames);
    }

    SongRenderLoopStatus setLoop(
        SongRenderOwnership requestedOwnership,
        std::uint64_t start,
        std::uint64_t end,
        std::uint32_t declickFrames) noexcept {
        const OwnershipCheck ownershipStatus = checkOwnership(requestedOwnership);
        if (ownershipStatus == OwnershipCheck::Stale) {
            return SongRenderLoopStatus::StaleGeneration;
        }
        if (ownershipStatus != OwnershipCheck::Ok) {
            return SongRenderLoopStatus::InvalidOwnership;
        }
        if (start >= end ||
            end > static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max()) ||
            (playbackEndFrame != 0U && end > playbackEndFrame) ||
            (eosMarked[0] && end > eosEndFrame[0])) {
            return SongRenderLoopStatus::InvalidRange;
        }
        if (!atomicRangeRetained(CurrentSongPcmCache::kOriginalLaneMask, end - 1U, 1U) ||
            !atomicRangeRetained(CurrentSongPcmCache::kOriginalLaneMask, start,
                loopContinuationHeadFrames(start, end))) {
            return SongRenderLoopStatus::RangeNotRetained;
        }
        if (loopActive && loopStart == start && loopEnd == end &&
            loopDeclickFrames == declickFrames && !managedLoopInstalled) {
            return SongRenderLoopStatus::Accepted;
        }
        if (managedLoopInstalled || retiredLoopHeadActive) disableManagedLoop();
        loopActive = true;
        loopStart = start;
        loopEnd = end;
        loopDeclickFrames = declickFrames;
        loopFadeStarted = false;
        resetTimelineMappingForLoopChange();
        return SongRenderLoopStatus::Accepted;
    }

    SongRenderLoopStatus clearLoop(SongRenderOwnership requestedOwnership) noexcept {
        const OwnershipCheck ownershipStatus = checkOwnership(requestedOwnership);
        if (ownershipStatus == OwnershipCheck::Stale) {
            return SongRenderLoopStatus::StaleGeneration;
        }
        if (ownershipStatus != OwnershipCheck::Ok) {
            return SongRenderLoopStatus::InvalidOwnership;
        }
        if (!loopActive && !managedLoopInstalled && !retiredLoopHeadActive) {
            return SongRenderLoopStatus::Disabled;
        }
        if (managedLoopInstalled || retiredLoopHeadActive) disableManagedLoop();
        loopActive = false;
        loopFadeStarted = false;
        resetTimelineMappingForLoopChange();
        return SongRenderLoopStatus::Disabled;
    }

    SongRenderLoopStatus retargetAtomicLoopEnd(
        SongRenderOwnership requestedOwnership,
        std::uint64_t start,
        std::uint64_t end) noexcept {
        const OwnershipCheck ownershipStatus = checkOwnership(requestedOwnership);
        if (ownershipStatus == OwnershipCheck::Stale) {
            return SongRenderLoopStatus::StaleGeneration;
        }
        if (ownershipStatus != OwnershipCheck::Ok) {
            return SongRenderLoopStatus::InvalidOwnership;
        }
        if (!loopActive || managedLoopInstalled || loopStart != start || start >= end ||
            sourceCursor + kTimelineEpsilon >= static_cast<double>(end) ||
            (playbackEndFrame != 0U && end > playbackEndFrame) ||
            (eosMarked[0] && end > eosEndFrame[0])) {
            return SongRenderLoopStatus::InvalidRange;
        }
        loopEnd = end;
        loopFadeStarted = false;
        mixer.continuePostDspAfterLoopPolicyChange(effectiveLoopDeclickFrames());
        return SongRenderLoopStatus::Accepted;
    }

    SongRenderLoopStatus prepareAtomicLoopCutover(
        SongRenderOwnership requestedOwnership,
        bool engageLoop,
        std::uint64_t start,
        std::uint64_t end,
        std::uint64_t replacementStart,
        std::uint32_t declickFrames,
        std::uint32_t requiredLaneMask) noexcept {
        const OwnershipCheck ownershipStatus = checkOwnership(requestedOwnership);
        if (ownershipStatus == OwnershipCheck::Stale) {
            return SongRenderLoopStatus::StaleGeneration;
        }
        if (ownershipStatus != OwnershipCheck::Ok ||
            requiredLaneMask == 0U ||
            (requiredLaneMask & ~requiredSongMask()) != 0U ||
            !atomicRangeRetained(
                requiredLaneMask,
                replacementStart,
                1U) ||
            (engageLoop && (start >= end ||
                !atomicRangeRetained(requiredLaneMask, start, loopContinuationHeadFrames(start, end))))) {
            return SongRenderLoopStatus::RangeNotRetained;
        }
        const SongRenderLoopStatus status = engageLoop
            ? setLoop(requestedOwnership, start, end, declickFrames)
            : clearLoop(requestedOwnership);
        if (status != SongRenderLoopStatus::Accepted &&
            status != SongRenderLoopStatus::Disabled) {
            return status;
        }
        sourceCursor = static_cast<double>(replacementStart);
        feedCursor = replacementStart;
        transition.reset();
        neutralTransition = false;
        transitionDirectCursor = 0U;
        loopFadeStarted = false;
        // This discontinuity primes the target DSP; it has no old direct endpoint to crossfade.
        processedForSong = processedForSong || !tuneIsNeutral(desiredTempo(), desiredPitch());
        if (processedForSong || activationPrepared) {
            timePitch.onSeek();
            needsPrime = true;
        }
        mixer.resetPostDspForDiscontinuity(
            currentOwnership.selectionToken,
            currentOwnership.generation,
            effectiveLoopDeclickFrames());
        return status;
    }

    void cancelAtomicLoopContinuationPreparationFrom(Impl &live) noexcept {
        if (!continuationCopy.pending || continuationCopy.sourceIdentity != &live) return;
        if (live.retainedCopyBorrower == this &&
            live.retainedCopyOwner.selectionToken == continuationCopy.owner.selectionToken &&
            live.retainedCopyOwner.generation == continuationCopy.owner.generation) {
            live.retainedCopyBorrower = nullptr;
        }
        continuationCopy = {};
        atomicLoopCache = {};
        cacheReadConsumer->active.store(false, std::memory_order_release);
    }

    void initializeAtomicLoopContinuationBodyCopyFrom(Impl &live, std::uint64_t minimumFrame) noexcept {
        currentIngressEpoch = live.currentIngressEpoch;
        std::uint64_t retainedFloor = std::numeric_limits<std::uint64_t>::max();
        for (std::size_t lane = 0U; lane <= configuration.stemLaneCount; ++lane) {
            const auto retained = live.reservoirs[lane]->diagnostics();
            const auto first = std::max(retained.firstTimelineFrame, minimumFrame);
            reservoirs[lane]->reset(currentIngressEpoch, first);
            continuationCopy.nextFrame[lane] = first;
            continuationCopy.copyBody[lane] = true;
            retainedFloor = std::min(retainedFloor, first);
        }
        live.retainedCopyOwner = continuationCopy.owner;
        live.retainedCopyFloor = retainedFloor;
        live.retainedCopyBorrower = this;
    }

    SongRenderLoopStatus copyAtomicLoopContinuationBodyFrom(Impl &live) noexcept {
#if defined(STEMWISE_CACHE_IO_PROFILING)
        AudioTrace trace("StemWise.audio.retained_body_copy");
        std::int64_t copiedBytes{};
        struct CounterAtExit final {
            const AudioTrace &trace;
            const std::int64_t &bytes;
            ~CounterAtExit() { trace.counter("StemWise.audio.retained_body_bytes", bytes); }
        } counter{trace, copiedBytes};
#endif
        std::size_t budget = kRetainedBodyCopyFramesPerTurn;
        bool failed{};
        for (std::size_t lane = 0U; lane <= configuration.stemLaneCount && budget != 0U && !failed; ++lane) {
            if (!continuationCopy.copyBody[lane]) continue;
            auto &frame = continuationCopy.nextFrame[lane];
            const auto retained = live.reservoirs[lane]->diagnostics();
            while (frame < retained.endTimelineFrameExclusive && budget != 0U) {
                const auto count = static_cast<std::size_t>(std::min<std::uint64_t>(
                    std::min<std::size_t>(configuration.maximumBlockFrames, budget), retained.endTimelineFrameExclusive - frame));
                if (frame < retained.firstTimelineFrame ||
                    live.reservoirs[lane]->copyAt(live.currentIngressEpoch, frame, laneScratch[lane].data(), count, count).status != TimelineCopyStatus::Complete ||
                    reservoirs[lane]->append(currentIngressEpoch, frame, laneScratch[lane].data(), count).inputFramesAccepted != count) {
                    failed = true;
                    break;
                }
                frame += count;
                budget -= count;
#if defined(STEMWISE_CACHE_IO_PROFILING)
                copiedBytes += static_cast<std::int64_t>(count * 2U * sizeof(float));
#endif
            }
        }
        if (failed) {
            cancelAtomicLoopContinuationPreparationFrom(live);
            return SongRenderLoopStatus::RangeNotRetained;
        }
        for (std::size_t lane = 0U; lane <= configuration.stemLaneCount; ++lane)
            if (continuationCopy.copyBody[lane] && continuationCopy.nextFrame[lane] <
                live.reservoirs[lane]->diagnostics().endTimelineFrameExclusive) {
#if defined(STEMWISE_CACHE_IO_TEST_HOOKS)
                if (const auto hook = cache_test::afterRetainedBodyCopySlice.load(std::memory_order_acquire)) hook();
#endif
                return SongRenderLoopStatus::Preparing;
            }
        if (live.retainedCopyBorrower == this) live.retainedCopyBorrower = nullptr;
        if (continuationCopy.refreshing) laneTimelineAttached = live.laneTimelineAttached;
        continuationCopy = {};
        // Source appends and EOS may advance between slices; copy through their current exact boundary.
        eosMarked = live.eosMarked;
        eosEndFrame = live.eosEndFrame;
        const auto &prepared = atomicLoopCache.snapshot();
        if (prepared.originalEndResolved) {
            for (std::size_t lane = 0U; lane <= configuration.stemLaneCount; ++lane) {
                if ((prepared.completedLaneMask & (1U << lane)) == 0U) continue;
                eosMarked[lane] = true;
                eosEndFrame[lane] = prepared.resolvedOriginalEndTimelineFrameExclusive;
            }
        }
        return SongRenderLoopStatus::Accepted;
    }

    SongRenderLoopStatus prepareAtomicLoopContinuationFrom(
        Impl &live,
        SongRenderOwnership requestedOwnership,
        bool engageLoop,
        std::uint64_t start,
        std::uint64_t end,
        std::uint64_t replacementStart,
        std::uint32_t declickFrames,
        std::uint32_t requiredLaneMask, const LoopHeadCache *boundedHead,
        LoopHeadCache::ReadView retainedHead) noexcept {
#if defined(STEMWISE_CACHE_IO_PROFILING)
        AudioTrace trace("StemWise.audio.loop.clone");
#endif
        auto head = retainedHead.valid() ? std::move(retainedHead) :
            boundedHead ? boundedHead->acquireActive() : LoopHeadCache::ReadView{};
        const auto headCovers = head.valid() &&
            (head.snapshot().completedLaneMask & requiredLaneMask) == requiredLaneMask &&
            (!engageLoop || (start >= head.snapshot().key.startTimelineFrame && end <= head.snapshot().key.startTimelineFrame + head.snapshot().frameCount)) &&
            replacementStart >= head.snapshot().key.startTimelineFrame &&
            replacementStart < head.snapshot().key.startTimelineFrame + head.snapshot().frameCount &&
            (!engageLoop || (replacementStart >= start && replacementStart < end));
        if (&live == this || !live.active || live.continuationCopy.pending || retainedCopyBorrower != nullptr ||
            (live.playbackEndFrame != 0U && (replacementStart >= live.playbackEndFrame ||
                (engageLoop && end > live.playbackEndFrame))) ||
            live.currentOwnership.selectionToken != requestedOwnership.selectionToken ||
            live.currentOwnership.generation != requestedOwnership.generation ||
            (!headCovers && live.currentSongCache == nullptr) || requiredLaneMask == 0U ||
            (requiredLaneMask & ~live.requiredSongMask()) != 0U ||
            (!headCovers && !live.currentSongCache->covers(requiredLaneMask, replacementStart, 1U))) {
#if defined(STEMWISE_CACHE_IO_PROFILING)
            trace.counter("StemWise.audio.loop.clone_reject", 1);
            trace.counter("StemWise.audio.loop.clone_head_covers", headCovers);
            trace.counter("StemWise.audio.loop.clone_live_active", live.active);
            trace.counter("StemWise.audio.loop.clone_live_pending", live.continuationCopy.pending);
            trace.counter("StemWise.audio.loop.clone_destination_pending", continuationCopy.pending);
            trace.counter("StemWise.audio.loop.clone_destination_borrowed", retainedCopyBorrower != nullptr);
            trace.counter("StemWise.audio.loop.clone_source_borrower", live.retainedCopyBorrower == nullptr ? 0 :
                live.retainedCopyBorrower == this ? 1 : 2);
            trace.counter("StemWise.audio.loop.clone_owner_matches", live.currentOwnership.selectionToken == requestedOwnership.selectionToken &&
                live.currentOwnership.generation == requestedOwnership.generation);
            trace.counter("StemWise.audio.loop.clone_mask_valid", requiredLaneMask != 0U &&
                (requiredLaneMask & ~live.requiredSongMask()) == 0U);
            trace.counter("StemWise.audio.loop.clone_live_cache", live.currentSongCache != nullptr);
            trace.counter("StemWise.audio.loop.clone_cache_covers_a", live.currentSongCache != nullptr &&
                live.currentSongCache->covers(requiredLaneMask, replacementStart, 1U));
            trace.counter("StemWise.audio.loop.clone_cache_owner_matches", live.currentSongCacheOwnership.selectionToken == requestedOwnership.selectionToken &&
                live.currentSongCacheOwnership.generation == requestedOwnership.generation);
#endif
            cancelAtomicLoopContinuationPreparationFrom(live);
            return SongRenderLoopStatus::InvalidOwnership;
        }
        const bool resume = continuationCopy.pending && !continuationCopy.refreshing &&
            continuationCopy.sourceIdentity == &live &&
            continuationCopy.owner.selectionToken == requestedOwnership.selectionToken &&
            continuationCopy.owner.generation == requestedOwnership.generation &&
            continuationCopy.ingressEpoch == live.currentIngressEpoch &&
            continuationCopy.engageLoop == engageLoop && continuationCopy.start == start && continuationCopy.end == end &&
            continuationCopy.replacementStart == replacementStart && continuationCopy.declickFrames == declickFrames &&
            continuationCopy.requiredLaneMask == requiredLaneMask &&
            atomicLoopCache.valid() == head.valid() &&
            (!head.valid() || (atomicLoopCache.snapshot().key == head.snapshot().key &&
              atomicLoopCache.snapshot().combinedChecksum == head.snapshot().combinedChecksum)) &&
            currentSongCache == live.currentSongCache && configuration.stemLaneCount == live.configuration.stemLaneCount &&
            desiredControls.tempo == live.desiredControls.tempo &&
            desiredControls.pitchSemitones == live.desiredControls.pitchSemitones &&
            desiredControls.mixer.defaultDeclickFrames == live.desiredControls.mixer.defaultDeclickFrames &&
            continuationCopy.sourceBeatGridMutation == live.beatGridMutation &&
            continuationCopy.destinationBeatGridMutation == beatGridMutation;
        if (continuationCopy.pending && !resume) {
            // The coordinator must cancel with the exact old source before swapping its role.
            if (continuationCopy.sourceIdentity != &live) {
#if defined(STEMWISE_CACHE_IO_PROFILING)
                trace.counter("StemWise.audio.loop.clone_reject", 2);
#endif
                return SongRenderLoopStatus::InvalidOwnership;
            }
            cancelAtomicLoopContinuationPreparationFrom(live);
        }
        if (!resume) {
            if (live.retainedCopyBorrower != nullptr && live.retainedCopyBorrower != this) {
#if defined(STEMWISE_CACHE_IO_PROFILING)
                trace.counter("StemWise.audio.loop.clone_reject", 3);
                trace.counter("StemWise.audio.loop.clone_source_borrower", 2);
#endif
                return SongRenderLoopStatus::RangeNotRetained;
            }
            if (!configureStemLaneCount(live.configuration.stemLaneCount)) {
#if defined(STEMWISE_CACHE_IO_PROFILING)
                trace.counter("StemWise.audio.loop.clone_reject", 4);
#endif
                return SongRenderLoopStatus::InvalidOwnership;
            }
            desiredControls = live.desiredControls;
            acceptedControlRevision = live.acceptedControlRevision;
            acceptedControlApplicationEpoch = live.acceptedControlApplicationEpoch;
            processedForSong = live.processedForSong;
            beatCount = live.beatCount;
            std::copy_n(live.beatGrid.begin(), beatCount, beatGrid.begin());
            std::copy_n(live.beatAccents.begin(), beatCount, beatAccents.begin());
            ++beatGridMutation;
            resetBank(requestedOwnership, requestedOwnership.generation, replacementStart, live.outputClock, false, true);
            playbackEndFrame = live.playbackEndFrame;
            activationPrepared = live.activationPrepared;
            atomicLoopCache = std::move(head);
            if (!bindCurrentSongPcmCache(requestedOwnership, live.currentSongCache)) {
#if defined(STEMWISE_CACHE_IO_PROFILING)
                trace.counter("StemWise.audio.loop.clone_reject", 5);
#endif
                return SongRenderLoopStatus::InvalidOwnership;
            }
            cacheReadConsumer->preparation.store(true, std::memory_order_relaxed);
            continuationCopy = {.sourceIdentity = &live, .owner = requestedOwnership,
                .ingressEpoch = live.currentIngressEpoch,
                .sourceBeatGridMutation = live.beatGridMutation,
                .destinationBeatGridMutation = beatGridMutation, .engageLoop = engageLoop,
                .start = start, .end = end, .replacementStart = replacementStart,
                .declickFrames = declickFrames, .requiredLaneMask = requiredLaneMask, .pending = true};
            // The immutable head already owns its prefix. Preserve every accepted raw suffix,
            // including one that begins later than that head, without copying its covered PCM.
            const auto rawFloor = headCovers
                ? atomicLoopCache.snapshot().key.startTimelineFrame + atomicLoopCache.snapshot().frameCount : 0U;
            initializeAtomicLoopContinuationBodyCopyFrom(live, rawFloor);
        }
        if (continuationCopy.pending) {
            const auto copied = copyAtomicLoopContinuationBodyFrom(live);
            if (copied != SongRenderLoopStatus::Accepted) return copied;
        }
        // Late source/gain overlays need no raw-body restart; final DSP priming uses the current numerical state.
        desiredControls = live.desiredControls;
        acceptedControlRevision = live.acceptedControlRevision;
        acceptedControlApplicationEpoch = live.acceptedControlApplicationEpoch;
        processedForSong = live.processedForSong;
        activationPrepared = live.activationPrepared;
        mixerDirty = true;
        currentSongCacheOwnership = requestedOwnership;
        const CurrentSongCacheSnapshot cache = currentSongCache ? currentSongCache->snapshot() : CurrentSongCacheSnapshot{};
        for (std::size_t lane = 0U; lane < kSongRenderLaneCount; ++lane) {
            if ((cache.completeLaneMask & (1U << lane)) != 0U) {
                eosMarked[lane] = true;
                eosEndFrame[lane] = cache.originalEndFrameExclusive;
            }
        }
        stemGainPreviewSequence = live.stemGainPreviewSequence;
        stemGainPreviewCoversOfferSequence = live.stemGainPreviewCoversOfferSequence;
        stemGainPreviewMask = live.stemGainPreviewMask;
        stemGainPreviewGains = live.stemGainPreviewGains;
        return prepareAtomicLoopCutover(requestedOwnership, engageLoop, start, end, replacementStart, declickFrames, requiredLaneMask);
    }

    SongRenderLoopStatus refreshAtomicLoopContinuationControlsFrom(Impl &live,
        std::uint32_t retainedHeadLaneMask) noexcept {
        const auto reject = [&]() noexcept {
            cancelAtomicLoopContinuationPreparationFrom(live);
            return SongRenderLoopStatus::InvalidOwnership;
        };
        const bool resuming = continuationCopy.pending && continuationCopy.refreshing &&
            continuationCopy.sourceIdentity == &live &&
            continuationCopy.owner.selectionToken == live.currentOwnership.selectionToken &&
            continuationCopy.owner.generation == live.currentOwnership.generation &&
            continuationCopy.ingressEpoch == live.currentIngressEpoch &&
            continuationCopy.sourceBeatGridMutation == live.beatGridMutation &&
            continuationCopy.destinationBeatGridMutation == beatGridMutation;
        if ((continuationCopy.pending && !resuming) || live.continuationCopy.pending ||
            (live.retainedCopyBorrower != nullptr && live.retainedCopyBorrower != this)) return reject();
        const bool rebase = checkOwnership(live.currentOwnership) != OwnershipCheck::Ok;
        const bool originalOnly = retainedHeadLaneMask == CurrentSongPcmCache::kOriginalLaneMask;
        if ((retainedHeadLaneMask != 0U && !originalOnly &&
                retainedHeadLaneMask != requiredSongMask()) ||
            (originalOnly && (desiredControls.mixer.sourceMode != MixerSourceMode::Original ||
                live.desiredControls.mixer.sourceMode != MixerSourceMode::Original))) return reject();
        if (!active || !live.active || configuration.stemLaneCount != live.configuration.stemLaneCount ||
            (rebase && (retainedCopyBorrower != nullptr || retainedHeadLaneMask == 0U ||
                currentOwnership.selectionToken != live.currentOwnership.selectionToken ||
                live.currentOwnership.generation <= currentOwnership.generation ||
                desiredControls.mixer.metronome.mode != MetronomeMode::Off)) ||
            currentSongCache == nullptr || currentSongCache != live.currentSongCache ||
            !currentSongCache->covers(originalOnly ? CurrentSongPcmCache::kOriginalLaneMask :
                requiredSongMask(), loopStart, loopContinuationHeadFrames(loopStart, loopEnd)) ||
            desiredControls.tempo != live.desiredControls.tempo ||
            desiredControls.pitchSemitones != live.desiredControls.pitchSemitones ||
            desiredControls.mixer.defaultDeclickFrames != live.desiredControls.mixer.defaultDeclickFrames ||
            beatCount != live.beatCount ||
            !std::equal(beatGrid.begin(), beatGrid.begin() + beatCount, live.beatGrid.begin()) ||
            !std::equal(beatAccents.begin(), beatAccents.begin() + beatCount, live.beatAccents.begin())) {
            return reject();
        }
        const auto &before = desiredControls.mixer.metronome;
        const auto &after = live.desiredControls.mixer.metronome;
        if (before.mode != after.mode || before.bpm != after.bpm || before.gain != after.gain ||
            before.subdivision != after.subdivision || before.beatsPerBar != after.beatsPerBar ||
            before.beatUnitDenominator != after.beatUnitDenominator) return reject();
        if ((acceptedControlRevision != live.acceptedControlRevision ||
             acceptedControlApplicationEpoch != live.acceptedControlApplicationEpoch) &&
            submitControls(live.desiredControls) != SongRenderControlStatus::Accepted) return reject();
        if (rebase && !resuming) {
            // Keep primed DSP/head ownership uncommitted until the bank's exact decoder suffix
            // has transferred. Cancellation must make a retry copy it again, never accept a prefix.
            continuationCopy = {.sourceIdentity = &live, .owner = live.currentOwnership,
                .ingressEpoch = live.currentIngressEpoch,
                .sourceBeatGridMutation = live.beatGridMutation,
                .destinationBeatGridMutation = beatGridMutation, .pending = true, .refreshing = true};
            initializeAtomicLoopContinuationBodyCopyFrom(live, 0U);
        }
        if (continuationCopy.pending) {
            const auto copied = copyAtomicLoopContinuationBodyFrom(live);
            if (copied != SongRenderLoopStatus::Accepted) return copied;
        }
        if (rebase) {
            // Admission can retry under cache contention; retain the old owner until it succeeds.
            if (!currentSongCache->admitReadConsumer(cacheReadConsumer,
                    live.currentOwnership.selectionToken, live.currentOwnership.generation)) return reject();
            if (!mixer.rebindPostDspOwner(currentOwnership.selectionToken,
                    currentOwnership.generation, live.currentOwnership.generation)) return reject();
            currentOwnership = live.currentOwnership;
            currentSongCacheOwnership = currentOwnership;
        }
        stemGainPreviewSequence = live.stemGainPreviewSequence;
        stemGainPreviewCoversOfferSequence = live.stemGainPreviewCoversOfferSequence;
        stemGainPreviewMask = live.stemGainPreviewMask;
        stemGainPreviewGains = live.stemGainPreviewGains;
        mixerDirty = true;
        return SongRenderLoopStatus::Accepted;
    }

    void resetTimelineMappingForLoopChange() noexcept {
        feedCursor = static_cast<std::uint64_t>(std::floor(sourceCursor));
        transition.reset();
        neutralTransition = false;
        transitionDirectCursor = 0;
        if (processedForSong || activationPrepared) {
            timePitch.onSeek();
            needsPrime = true;
        }
        mixer.resetPostDspForDiscontinuity(
            currentOwnership.selectionToken,
            currentOwnership.generation,
            effectiveLoopDeclickFrames());
    }

    const MixerControlSnapshot &desiredMixerControls() const noexcept {
        return desiredControls.mixer;
    }

    float desiredTempo() const noexcept {
        return std::clamp(
            desiredControls.tempo,
            TimePitchProcessor::kMinimumTempo,
            TimePitchProcessor::kMaximumTempo);
    }

    float desiredPitch() const noexcept {
        return std::clamp(
            desiredControls.pitchSemitones,
            TimePitchProcessor::kMinimumPitchSemitones,
            TimePitchProcessor::kMaximumPitchSemitones);
    }

    void resetPreDspControlsImmediate() noexcept {
        MixerControlSnapshot reset = desiredMixerControls();
        reset.revision = nextMixerRevision();
        reset.sourceMode = MixerSourceMode::Original;
        reset.sourceTransitionFrames = 1;
        reset.laneRampFrames = 1;
        reset.masterRampFrames = 1;
        static_cast<void>(mixer.applyControls(reset));
        appliedSourceMode = MixerSourceMode::Original;
        stemsPathEngaged = false;
        stemTransitionFramesRemaining = 0;
        mixerDirty = true;
        saturatingIncrement(diagnostics.mixerSnapshotsApplied);
    }

    std::uint64_t nextMixerRevision() noexcept {
        if (mixerRevision != std::numeric_limits<std::uint64_t>::max()) {
            ++mixerRevision;
        }
        return mixerRevision;
    }

    bool normalizeLogicalFrame(std::uint64_t &frame) const noexcept {
        if (!loopActive || frame < loopEnd) return true;
        const std::uint64_t length = loopEnd - loopStart;
        if (length == 0) return false;
        frame = loopStart + (frame - loopStart) % length;
        return true;
    }

    bool advanceLogicalFrame(
        std::uint64_t start,
        std::size_t count,
        std::uint64_t &advanced) const noexcept {
        if (!loopActive) return checkedTimelineAdd(start, count, advanced);
        if (!normalizeLogicalFrame(start)) return false;
        std::size_t remaining = count;
        while (remaining != 0) {
            if (start >= loopEnd) start = loopStart;
            const std::uint64_t toEnd = loopEnd - start;
            const std::size_t segment = static_cast<std::size_t>(
                std::min<std::uint64_t>(toEnd, remaining));
            start += static_cast<std::uint64_t>(segment);
            remaining -= segment;
            if (start == loopEnd && remaining != 0) start = loopStart;
        }
        advanced = start;
        return true;
    }

    std::size_t contiguousLogicalFrames(
        std::uint64_t start,
        std::size_t requested) const noexcept {
        if (!loopActive) return requested;
        if (start >= loopEnd) start = loopStart;
        return static_cast<std::size_t>(std::min<std::uint64_t>(
            loopEnd - start,
            requested));
    }

    enum class LaneSegmentSource { CompleteCache, PublishedCache, LoopHead, AtomicHead, Reservoir, Silence };

    template <typename Visit>
    bool walkLaneContiguous(
        std::size_t index,
        std::uint64_t start,
        std::size_t count, Visit &&visit) const noexcept {
        if (index > configuration.stemLaneCount || !reservoirs[index]) return false;
        if (count == 0) return true;
        if (playbackEndFrame != 0U) {
            if (start >= playbackEndFrame) return visit(LaneSegmentSource::Silence, start, count, 0U);
            const auto retained = static_cast<std::size_t>(std::min<std::uint64_t>(count, playbackEndFrame - start));
            if (retained < count) {
                return walkLaneContiguous(index, start, retained, visit) &&
                    visit(LaneSegmentSource::Silence, playbackEndFrame, count - retained, retained);
            }
        }
        std::size_t remaining = count;
        std::size_t outputOffset = 0U;
        const RawLoopHeadSource head = rawLoopHeadSource();
        const bool headOwnsLane = head.present() && (head.requiredLaneMask & (1U << index)) != 0U;
        // An atomic continuation may still own the approach to A. Within the installed managed
        // range its accepted head owns the source, including a prefix absent from the atomic head.
        const auto &atomicHead = atomicLoopCache.snapshot();
        const auto atomicEnd = managedLoopInstalled
            ? std::min<std::uint64_t>(atomicHead.key.startTimelineFrame + atomicHead.frameCount, loopStart)
            : atomicHead.key.startTimelineFrame + atomicHead.frameCount;
        if (atomicLoopCache.valid() && (atomicHead.requiredLaneMask & (1U << index)) != 0U &&
            start >= atomicHead.key.startTimelineFrame && start < atomicEnd) {
            const auto frames = static_cast<std::size_t>(std::min<std::uint64_t>(remaining, atomicEnd - start));
            if (!visit(LaneSegmentSource::AtomicHead, start, frames, outputOffset)) return false;
            start += frames;
            outputOffset += frames;
            remaining -= frames;
            if (remaining == 0U) return true;
        }
        if (currentSongCache != nullptr &&
            currentSongCacheOwnership.selectionToken == currentOwnership.selectionToken &&
            currentSongCacheOwnership.generation == currentOwnership.generation) {
            const std::uint32_t bit = 1U << static_cast<std::uint32_t>(index);
            const bool laneReady = currentSongCache->availableFrames(index) != 0U;
            if (laneReady) {
                const CurrentSongCacheSnapshot cache = currentSongCache->snapshot();
                if ((cache.completeLaneMask & bit) != 0U) {
                    if (start >= cache.originalEndFrameExclusive)
                        return visit(LaneSegmentSource::Silence, start, remaining, outputOffset);
                    const std::size_t cached = static_cast<std::size_t>(
                        std::min<std::uint64_t>(
                            cache.originalEndFrameExclusive - start,
                            remaining));
                    if (!visit(LaneSegmentSource::CompleteCache, start, cached, outputOffset)) {
                        return false;
                    }
                    start += cached;
                    outputOffset += cached;
                    remaining -= cached;
                    if (remaining == 0U) return true;
                    if (start >= cache.originalEndFrameExclusive)
                        return visit(LaneSegmentSource::Silence, start, remaining, outputOffset);
                } else if (start < currentSongCache->availableFrames(index)) {
                    const std::size_t cached = static_cast<std::size_t>(std::min<std::uint64_t>(
                        currentSongCache->availableFrames(index) - start, remaining));
                    if (!visit(LaneSegmentSource::PublishedCache, start, cached, outputOffset)) {
                        return false;
                    }
                    start += cached;
                    outputOffset += cached;
                    remaining -= cached;
                    if (remaining == 0U) return true;
                }
            }
        }
        while (remaining != 0U) {
            if (headOwnsLane && start >= head.startTimelineFrame &&
                start < head.endTimelineFrameExclusive) {
                const std::size_t cached = static_cast<std::size_t>(
                    std::min<std::uint64_t>(
                        head.endTimelineFrameExclusive - start,
                        remaining));
                if (!head.cache->valid() ||
                    !visit(LaneSegmentSource::LoopHead, start, cached, outputOffset)) {
                    return false;
                }
                start += static_cast<std::uint64_t>(cached);
                outputOffset += cached;
                remaining -= cached;
                continue;
            }

            std::size_t segment = remaining;
            if (headOwnsLane && start < head.startTimelineFrame) {
                segment = static_cast<std::size_t>(std::min<std::uint64_t>(
                    head.startTimelineFrame - start,
                    segment));
            } else if (managedLoopInstalled && start >= managedLoopHeadEnd &&
                       start < loopEnd) {
                segment = static_cast<std::size_t>(std::min<std::uint64_t>(
                    loopEnd - start,
                    segment));
            }
            if (segment == 0U) return false;
            std::uint64_t requestedEnd = 0U;
            if (!checkedTimelineAdd(start, segment, requestedEnd)) return false;
            const std::uint64_t pcmEnd = eosMarked[index]
                ? std::min(requestedEnd, eosEndFrame[index])
                : requestedEnd;
            const std::size_t pcmFrames = pcmEnd > start
                ? static_cast<std::size_t>(pcmEnd - start)
                : 0U;
            if (pcmFrames != 0U) {
                if (!visit(LaneSegmentSource::Reservoir, start, pcmFrames, outputOffset)) return false;
            }
            if (pcmFrames < segment && !visit(LaneSegmentSource::Silence, start + pcmFrames,
                    segment - pcmFrames, outputOffset + pcmFrames)) return false;
            start = requestedEnd;
            outputOffset += segment;
            remaining -= segment;
        }
        return true;
    }

    bool retryPendingCacheReadRequirements(const CurrentSongPcmCache *liveCache) const noexcept {
        if (!active || liveCache == nullptr || currentSongCache != liveCache ||
            !cacheReadConsumer->active.load(std::memory_order_acquire) ||
            cacheReadConsumer->pendingDomains.load(std::memory_order_acquire) == 0U) return false;
        updateCacheReadRequirements();
        return cacheReadConsumer->pendingDomains.load(std::memory_order_acquire) == 0U;
    }

    void updateCacheReadRequirements() const noexcept {
        if (!active || currentSongCache == nullptr) return;
        if (!cacheReadConsumer->active.load(std::memory_order_acquire) &&
            !currentSongCache->admitReadConsumer(cacheReadConsumer, currentOwnership.selectionToken, currentOwnership.generation)) return;
        cacheReadConsumer->pendingDomains.fetch_or((1U << CurrentSongPcmReadConsumer::kDomains) - 1U,
            std::memory_order_release);
        for (std::size_t domain = 0U; domain < rawRanges.size(); ++domain) {
            const auto &state = rawRanges[domain];
            const bool pending = state.valid && !state.mixed && state.lane <= configuration.stemLaneCount && state.copied < state.frames;
            const bool required = pending || (domain == 1U && (processedForSong || activationPrepared)) ||
                (domain == 2U && (neutralTransition || transition.diagnostics().active)) ||
                (domain == 3U && !processedForSong);
            auto frame = domain == 1U ? feedCursor : static_cast<std::uint64_t>(std::max(0.0, std::floor(sourceCursor)));
            if (pending && !advanceLogicalFrame(state.start, state.copied, frame)) {
                currentSongCache->requireReadRange(*cacheReadConsumer, domain, 0U, 0U, 0U);
                continue;
            }
            auto mask = pending ? 1U << state.lane :
                (state.includeStems || stemsPathEngaged ? requiredSongMask() : CurrentSongPcmCache::kOriginalLaneMask);
            std::size_t frames = 0U;
            if (required && normalizeLogicalFrame(frame))
                frames = std::min<std::size_t>(configuration.maximumBlockFrames,
                    contiguousLogicalFrames(frame, pending ? state.frames - state.copied : configuration.maximumBlockFrames));
            if (frames == 0U) mask = 0U;
            currentSongCache->requireReadRange(*cacheReadConsumer, domain, mask, frame, frames);
        }
    }

    void requestUpcomingCacheInput() const noexcept {
        if (!active || currentSongCache == nullptr) return;
        updateCacheReadRequirements();
        auto start = feedCursor;
        if (!normalizeLogicalFrame(start)) return;
        auto remaining = currentSongCache->upcomingWindowFrames();
        if (loopActive) remaining = static_cast<std::size_t>(std::min<std::uint64_t>(remaining, loopEnd - loopStart));
        const auto lanes = stemsPathEngaged ? configuration.stemLaneCount + 1U : 1U;
        while (remaining != 0U) {
            const auto count = contiguousLogicalFrames(start, remaining);
            if (count == 0U) return;
            for (std::size_t lane = 0U; lane < lanes; ++lane)
                static_cast<void>(walkLaneContiguous(lane, start, count,
                    [&](LaneSegmentSource source, std::uint64_t frame, std::size_t frames, std::size_t) noexcept {
                        if (source == LaneSegmentSource::CompleteCache || source == LaneSegmentSource::PublishedCache)
                            currentSongCache->requestUpcoming(1U << lane, frame, frames, cacheReadConsumer.get());
                        return true;
                    }));
            remaining -= count;
            if (!checkedTimelineAdd(start, count, start)) return;
            if (loopActive && start == loopEnd) start = loopStart;
        }
    }

    bool laneCoversContiguous(std::size_t index, std::uint64_t start, std::size_t count,
        bool requireResident = false) const noexcept {
        return walkLaneContiguous(index, start, count,
            [&](LaneSegmentSource source, std::uint64_t frame, std::size_t frames, std::size_t) noexcept {
                if (requireResident && (source == LaneSegmentSource::CompleteCache ||
                        source == LaneSegmentSource::PublishedCache))
                    return currentSongCache->readWindowRangeReady(1U << index, frame, frames);
                return source != LaneSegmentSource::Reservoir ||
                    reservoirs[index]->availableFrom(currentIngressEpoch, frame) >= frames;
            });
    }

    bool laneCoversLogical(
        std::size_t index,
        std::uint64_t start,
        std::size_t count, bool requireResident = false) const noexcept {
        std::size_t remaining = count;
        if (!normalizeLogicalFrame(start)) return false;
        while (remaining != 0) {
            const std::size_t segment = contiguousLogicalFrames(start, remaining);
            if (segment == 0 || !laneCoversContiguous(index, start, segment, requireResident)) return false;
            remaining -= segment;
            start += static_cast<std::uint64_t>(segment);
            if (loopActive && start == loopEnd && remaining != 0) start = loopStart;
        }
        return true;
    }

    bool allStemLanesCoverLogical(
        std::uint64_t start,
        std::size_t count, bool requireResident = false) const noexcept {
        if (configuration.stemLaneCount == 0U) return false;
        for (std::size_t index = 1; index <= configuration.stemLaneCount; ++index) {
            if (!laneCoversLogical(index, start, count, requireResident)) return false;
        }
        return true;
    }

    bool copyLaneContiguous(std::size_t index, std::uint64_t start,
        float *destination, std::size_t count, bool requestMissing = true, std::size_t domain = 0U) noexcept {
        if (index > configuration.stemLaneCount || !reservoirs[index]) return false;
        if (count == 0U) return true;
        std::fill_n(destination, count * kStereoChannelCount, 0.0F);
        return walkLaneContiguous(index, start, count,
            [&](LaneSegmentSource source, std::uint64_t frame, std::size_t frames, std::size_t offset) noexcept {
                auto *target = destination + offset * kStereoChannelCount;
                switch (source) {
                    case LaneSegmentSource::CompleteCache:
                        if (currentSongCache->copyLane(index, frame, target, frames, frames, requestMissing,
                                cacheReadConsumer.get(), domain)) return true;
                        cacheReadPending = true;
                        return false;
                    case LaneSegmentSource::PublishedCache:
                        if (currentSongCache->copyAvailableLane(index, frame, target, frames, frames, requestMissing,
                                cacheReadConsumer.get(), domain)) return true;
                        cacheReadPending = true;
                        return false;
                    case LaneSegmentSource::AtomicHead:
                    case LaneSegmentSource::LoopHead: {
                        LoopHeadCopyStatus status{};
                        const auto *sourceHead = source == LaneSegmentSource::AtomicHead
                            ? &atomicLoopCache : rawLoopHeadSource().cache;
                        if (!sourceHead->copyLane(index, frame, target, frames, frames, &status)) {
                            cacheReadPending = status == LoopHeadCopyStatus::Pending;
                            return false;
                        }
                        saturatingAdd(diagnostics.loopCacheFramesCopied, frames);
                        return true;
                    }
                    case LaneSegmentSource::Reservoir: {
                        const auto copied = reservoirs[index]->copyAt(currentIngressEpoch, frame, target, frames, frames);
                        if (copied.status != TimelineCopyStatus::Complete || copied.framesCopied != frames) return false;
                        if (managedLoopInstalled && frame >= managedLoopHeadEnd && frame < loopEnd)
                            saturatingAdd(diagnostics.loopBodyFramesCopied, frames);
                        return true;
                    }
                    case LaneSegmentSource::Silence: return true;
                }
                return false;
            });
    }

    bool copyLaneLogical(
        std::size_t index,
        std::uint64_t start,
        float *destination,
        std::size_t count, bool requestMissing = true, std::size_t domain = 0U) noexcept {
        std::size_t remaining = count;
        std::size_t copiedFrames = 0;
        if (!normalizeLogicalFrame(start)) return false;
        while (remaining != 0) {
            const std::size_t segment = contiguousLogicalFrames(start, remaining);
            if (segment == 0 ||
                !copyLaneContiguous(
                    index,
                    start,
                    destination + copiedFrames * kStereoChannelCount,
                    segment, requestMissing, domain)) {
                return false;
            }
            remaining -= segment;
            copiedFrames += segment;
            start += static_cast<std::uint64_t>(segment);
            if (loopActive && start == loopEnd && remaining != 0) start = loopStart;
        }
        return true;
    }

    bool applyMixerControlsForRange(
        std::uint64_t rangeStart,
        std::size_t rangeFrames,
        bool &stemsRequired) noexcept {
        const bool allStemsReady = allStemLanesCoverLogical(rangeStart, rangeFrames);
        const MixerSourceMode desiredSource = desiredMixerControls().sourceMode;
        MixerSourceMode safeSource = desiredSource;
        bool forcedFallback = false;

        if (desiredSource == MixerSourceMode::Stems && !allStemsReady) {
            safeSource = MixerSourceMode::Original;
            saturatingIncrement(diagnostics.stemActivationDeferrals);
        }
        if (stemsPathEngaged && !allStemsReady) {
            safeSource = MixerSourceMode::Original;
            forcedFallback = true;
            saturatingIncrement(diagnostics.stemSafetyFallbacks);
        }

        if (mixerDirty || safeSource != appliedSourceMode || forcedFallback) {
            MixerControlSnapshot snapshot = desiredMixerControls();
            if (safeSource == MixerSourceMode::Stems) {
                for (std::size_t lane = 0U; lane < snapshot.stems.size(); ++lane) {
                    const std::uint32_t bit = 1U << static_cast<std::uint32_t>(lane);
                    if ((stemGainPreviewMask & bit) != 0U) {
                        // The slider supplies effective gain; canonical mute returns when it retires.
                        snapshot.stems[lane].gain = stemGainPreviewGains[lane];
                        snapshot.stems[lane].muted = false;
                    }
                }
            }
            snapshot.revision = nextMixerRevision();
            snapshot.sourceMode = safeSource;
            if (forcedFallback) snapshot.sourceTransitionFrames = 1;
            if (!mixer.applyControls(snapshot)) return false;
            saturatingIncrement(diagnostics.mixerSnapshotsApplied);

            const bool previouslyEngaged = stemsPathEngaged;
            appliedSourceMode = safeSource;
            if (safeSource == MixerSourceMode::Stems) {
                stemsPathEngaged = true;
                stemTransitionFramesRemaining = 0;
            } else if (forcedFallback || !previouslyEngaged ||
                       snapshot.sourceTransitionFrames <= 1) {
                stemsPathEngaged = false;
                stemTransitionFramesRemaining = 0;
            } else {
                stemsPathEngaged = true;
                stemTransitionFramesRemaining = snapshot.sourceTransitionFrames;
            }
            mixerDirty = false;

            if (forcedFallback) {
                mixer.resetPostDspForDiscontinuity(
                    currentOwnership.selectionToken,
                    currentOwnership.generation,
                    desiredMixerControls().defaultDeclickFrames);
            }
        }
        stemsRequired = stemsPathEngaged;
        return !stemsRequired || allStemsReady;
    }

    void accountPreDspProgress(std::size_t frames) noexcept {
        if (stemTransitionFramesRemaining == 0) return;
        if (frames >= stemTransitionFramesRemaining) {
            stemTransitionFramesRemaining = 0;
            if (appliedSourceMode == MixerSourceMode::Original) stemsPathEngaged = false;
        } else {
            stemTransitionFramesRemaining -= static_cast<std::uint32_t>(frames);
        }
    }

    struct RawRangeState final {
        std::uint64_t token{}, generation{}, epoch{}, start{}, mixer{}, loopA{}, loopB{};
        std::size_t frames{}, stems{}, lane{}, copied{};
        bool includeStems{}, loop{}, valid{}, mixed{};
    };

    bool stageRawRange(std::uint64_t start, std::size_t count, bool &includeStems, float *output,
        RawRangeState &state, bool requireStems) noexcept {
        const bool sameRange = state.valid && state.token == currentOwnership.selectionToken &&
            state.generation == currentOwnership.generation && state.epoch == currentIngressEpoch &&
            state.start == start && state.frames == count && state.stems == configuration.stemLaneCount &&
            state.mixer == mixerRevision && state.loop == loopActive && state.loopA == loopStart && state.loopB == loopEnd;
        // A cold optional window can settle this input transaction on Original while its prime is
        // still staging. A later residency hint cannot restart that transaction with optional buses.
        if (sameRange && !requireStems && !state.includeStems) includeStems = false;
        if (!sameRange || state.includeStems != includeStems) {
            state = {.token=currentOwnership.selectionToken, .generation=currentOwnership.generation,
                .epoch=currentIngressEpoch, .start=start, .mixer=mixerRevision, .loopA=loopStart, .loopB=loopEnd,
                .frames=count, .stems=configuration.stemLaneCount, .includeStems=includeStems, .loop=loopActive, .valid=true};
            std::fill_n(output, count * kMusicChannelCount, 0.0F);
        }
        if (state.mixed) return true;
        const auto lanes = includeStems ? configuration.stemLaneCount + 1U : 1U;
        while (state.lane < lanes) {
            while (state.copied < count) {
                std::uint64_t frame{};
                if (!advanceLogicalFrame(start, state.copied, frame)) return false;
                const auto chunk = std::min<std::size_t>(contiguousLogicalFrames(frame, count - state.copied),
                    configuration.maximumBlockFrames);
                if (!copyLaneLogical(state.lane, frame, laneScratch[state.lane].data(), chunk,
                        state.lane == 0U || requireStems, static_cast<std::size_t>(&state - rawRanges.data()))) {
                    if (state.lane == 0U || requireStems) return false;
                    // A residency probe is advisory. Optional copy misses cannot block Original;
                    // this staging boundary has not consumed any DSP input yet.
                    includeStems = false;
                    cacheReadPending = false;
                    return stageRawRange(start, count, includeStems, output, state, false);
                }
                for (std::size_t offset = 0U; offset < chunk; ++offset) {
                    output[(state.copied + offset) * kMusicChannelCount + state.lane * 2U] = laneScratch[state.lane][offset * 2U];
                    output[(state.copied + offset) * kMusicChannelCount + state.lane * 2U + 1U] = laneScratch[state.lane][offset * 2U + 1U];
                }
                state.copied += chunk;
            }
            ++state.lane;
            state.copied = 0U;
        }
        return true;
    }

    bool renderPreDspRange(
        std::uint64_t start,
        std::size_t count,
        bool &includeStems,
        float *output,
        bool advanceControls = true, bool requireStems = true) noexcept {
        std::size_t remaining = count;
        std::size_t outputOffset = 0;
        if (!normalizeLogicalFrame(start)) return false;
        const std::size_t slot = output == primePcm.data() ? 0U : output == preDspInputPcm.data() ? 1U :
            output == directTransitionPcm.data() ? 2U : 3U;
        auto &staging = rawRanges[slot];
        if (!stageRawRange(start, count, includeStems, output, staging, requireStems)) return false;
        if (staging.mixed) return true;
        while (remaining != 0) {
            const std::size_t segmentLimit = contiguousLogicalFrames(start, remaining);
            const std::size_t chunk = std::min<std::size_t>(
                segmentLimit,
                configuration.maximumBlockFrames);
            if (chunk == 0) return false;
            for (std::size_t lane = 0U; lane <= (includeStems ? configuration.stemLaneCount : 0U); ++lane)
                for (std::size_t offset = 0U; offset < chunk; ++offset) {
                    laneScratch[lane][offset * 2U] = output[(outputOffset + offset) * kMusicChannelCount + lane * 2U];
                    laneScratch[lane][offset * 2U + 1U] = output[(outputOffset + offset) * kMusicChannelCount + lane * 2U + 1U];
                }

            PreDspRenderBlock block;
            block.original = {
                laneScratch[0].data(),
                static_cast<std::uint32_t>(chunk),
            };
            if (includeStems) {
                for (std::size_t stem = 0; stem < configuration.stemLaneCount; ++stem) {
                    const std::size_t index = stem + 1U;
                    block.stems[stem] = {
                        laneScratch[index].data(),
                        static_cast<std::uint32_t>(chunk),
                    };
                }
            }
            block.frameCount = static_cast<std::uint32_t>(chunk);
            block.timelineStartFrame = static_cast<std::int64_t>(start);
            block.selectionToken = currentOwnership.selectionToken;
            block.generation = currentOwnership.generation;
            float *chunkOutput = output + outputOffset * kMusicChannelCount;
            if (!mixer.renderPreDspMultibus(
                    block,
                    chunkOutput,
                    static_cast<std::uint32_t>(chunk))) {
                return false;
            }
            if (includeStems) {
                stemBusReadyMaskForCurrentRender = stemwiseRequiredStemMask(configuration.stemLaneCount);
            }
            if (advanceControls) accountPreDspProgress(chunk);
            outputOffset += chunk;
            remaining -= chunk;
            if (!advanceLogicalFrame(start, chunk, start)) return false;
        }
        staging.mixed = true;
        return true;
    }

    bool applyTuneIntent() noexcept {
        const float tempo = desiredTempo();
        const float pitch = desiredPitch();
        if (processedForSong && needsPrime && tuneIsNeutral(tempo, pitch)) {
            // A seek/wrap already owns a declicked discontinuity at the exact source frame.
            processedForSong = false;
            neutralTransition = false;
            tuneDirty = true;
        }
        if (!tuneDirty) return true;
        if (!processedForSong && tuneIsNeutral(tempo, pitch)) {
            appliedTempo = 1.0F;
            appliedPitch = 0.0F;
            activationPrepared = false;
            needsPrime = false;
            tuneDirty = false;
            saturatingIncrement(diagnostics.tuneSnapshotsApplied);
            return true;
        }

        if (!processedForSong && !activationPrepared) {
            // TimePitchProcessor intentionally cannot return to virgin after prior ownership. The
            // pipeline's per-song direct bypass supplies that zero-latency policy, then explicitly
            // re-primes the reusable processor at this song's exact current source frame.
            timePitch.onDiscontinuity();
            needsPrime = true;
            activationPrepared = true;
        }
        static_cast<void>(timePitch.setControls(tempo, pitch, processedForSong));
        const TimePitchDiagnostics applied = timePitch.diagnostics();
        appliedTempo = applied.tempo;
        appliedPitch = applied.pitchSemitones;
        tuneDirty = false;
        saturatingIncrement(diagnostics.tuneSnapshotsApplied);
        return true;
    }

    std::uint32_t effectiveLoopDeclickFrames() const noexcept {
        if (loopDeclickFrames != 0) return loopDeclickFrames;
        return std::max<std::uint32_t>(1, desiredMixerControls().defaultDeclickFrames);
    }

    RenderPlan planOutput(std::uint32_t requestedFrames, float tempo) const noexcept {
        RenderPlan plan;
        plan.frames = requestedFrames;
        plan.sourceEnd = sourceCursor +
            static_cast<double>(requestedFrames) * static_cast<double>(tempo);

        double boundary = playbackEndFrame != 0U
            ? static_cast<double>(playbackEndFrame) : std::numeric_limits<double>::infinity();
        bool loopBoundary = false;
        bool eosBoundary = playbackEndFrame != 0U;
        if (loopActive && sourceCursor < static_cast<double>(loopEnd) + kTimelineEpsilon &&
            static_cast<double>(loopEnd) <= boundary) {
            boundary = static_cast<double>(loopEnd);
            loopBoundary = true;
            eosBoundary = false;
        }
        if (eosMarked[0] &&
            static_cast<double>(eosEndFrame[0]) < boundary) {
            boundary = static_cast<double>(eosEndFrame[0]);
            loopBoundary = false;
            eosBoundary = true;
        }

        if (!std::isfinite(boundary)) return plan;
        const double distance = std::max(0.0, boundary - sourceCursor);
        const double exactFrames = distance / std::max<double>(tempo, 0.000001);
        const std::uint64_t framesToBoundary = static_cast<std::uint64_t>(
            std::ceil(std::max(0.0, exactFrames - kTimelineEpsilon)));
        plan.frames = static_cast<std::uint32_t>(std::min<std::uint64_t>(
            requestedFrames,
            framesToBoundary));

        if (loopBoundary && !loopFadeStarted && framesToBoundary != 0) {
            const std::uint64_t fadeFrames = effectiveLoopDeclickFrames();
            if (framesToBoundary <= fadeFrames) {
                plan.beginLoopFade = true;
            } else {
                const std::uint64_t beforeFade = framesToBoundary - fadeFrames;
                if (plan.frames > beforeFade) {
                    plan.frames = static_cast<std::uint32_t>(beforeFade);
                }
            }
        }

        plan.reachesLoopEnd = loopBoundary && plan.frames == framesToBoundary;
        plan.reachesEos = eosBoundary && plan.frames == framesToBoundary;
        plan.sourceEnd = sourceCursor +
            static_cast<double>(plan.frames) * static_cast<double>(tempo);
        if (plan.reachesLoopEnd || plan.reachesEos) plan.sourceEnd = boundary;
        return plan;
    }

    bool performLoopWrapIfNeeded(bool &wrapped) noexcept {
        if (!loopActive || sourceCursor + kTimelineEpsilon < static_cast<double>(loopEnd)) {
            return true;
        }
        if (managedLoopInstalled) {
            publishManagedBoundary();
            return true;
        }
        sourceCursor = static_cast<double>(loopStart);
        feedCursor = loopStart;
        transition.reset();
        neutralTransition = false;
        transitionDirectCursor = 0;
        loopFadeStarted = false;
        if (processedForSong) {
            timePitch.onSeek();
            needsPrime = true;
        }
        mixer.completePostDspLoopWrap(
            currentOwnership.selectionToken,
            currentOwnership.generation,
            effectiveLoopDeclickFrames());
        wrapped = true;
        saturatingIncrement(diagnostics.loopWraps);
        return true;
    }

    bool atEndOfStream() const noexcept {
        const auto end = playbackEndFrame == 0U ? (eosMarked[0] ? eosEndFrame[0] : 0U) :
            (eosMarked[0] ? std::min(playbackEndFrame, eosEndFrame[0]) : playbackEndFrame);
        if (playbackEndFrame == 0U && !eosMarked[0]) return false;
        if (loopActive && loopEnd <= end &&
            sourceCursor + kTimelineEpsilon >= static_cast<double>(loopEnd)) {
            return false;
        }
        return sourceCursor + kTimelineEpsilon >= static_cast<double>(end);
    }

    SongRenderResult baseResult(
        SongRenderStatus status,
        SongRenderOwnership requestedOwnership,
        std::uint32_t requestedFrames) const noexcept {
        SongRenderResult result;
        result.status = status;
        result.framesRequested = requestedFrames;
        result.ownership = requestedOwnership;
        result.outputClockStartFrame = outputClock;
        result.sourceTimeline.startFrame = sourceCursor;
        result.sourceTimeline.endFrameExclusive = sourceCursor;
        return result;
    }

    SongRenderResult preDspReadFailure(SongRenderOwnership owner, std::uint32_t frames) noexcept {
        if (cacheReadPending) {
            saturatingIncrement(diagnostics.needOriginalDataCount);
            return baseResult(SongRenderStatus::NeedOriginalData, owner, frames);
        }
        saturatingIncrement(diagnostics.internalFailures);
        return baseResult(SongRenderStatus::MixerFailure, owner, frames);
    }

    SongRenderResult render(
        SongRenderOwnership requestedOwnership,
        float *renderPayload,
        std::uint32_t outputCapacityFrames,
        std::uint32_t requestedFrames,
        std::uint32_t maximumPrerollOutputFrames) noexcept {
        saturatingIncrement(diagnostics.renderCalls);
        cacheReadPending = false;
        const OwnershipCheck ownershipStatus = checkOwnership(requestedOwnership);
        if (ownershipStatus == OwnershipCheck::Stale) {
            saturatingIncrement(diagnostics.staleOwnershipRequests);
            return baseResult(
                SongRenderStatus::StaleGeneration,
                requestedOwnership,
                requestedFrames);
        }
        if (ownershipStatus != OwnershipCheck::Ok) {
            return baseResult(
                SongRenderStatus::InvalidOwnership,
                requestedOwnership,
                requestedFrames);
        }
        if (renderPayload == nullptr || requestedFrames == 0 ||
            requestedFrames > configuration.maximumBlockFrames ||
            requestedFrames > outputCapacityFrames) {
            return baseResult(
                SongRenderStatus::InvalidArgument,
                requestedOwnership,
                requestedFrames);
        }
        if (outputClock >
            std::numeric_limits<std::int64_t>::max() -
                static_cast<std::int64_t>(requestedFrames)) {
            return baseResult(
                SongRenderStatus::TimelineOverflow,
                requestedOwnership,
                requestedFrames);
        }

        updateCacheReadRequirements();

        bool loopWrapped = false;
        if (!performLoopWrapIfNeeded(loopWrapped)) {
            return baseResult(
                SongRenderStatus::TimelineOverflow,
                requestedOwnership,
                requestedFrames);
        }
        if (managedLoopBoundaryPending) {
            saturatingIncrement(diagnostics.loopBoundaryWaits);
            SongRenderResult result = baseResult(
                SongRenderStatus::LoopBoundaryPending,
                requestedOwnership,
                requestedFrames);
            result.loopBoundaryPending = true;
            return result;
        }
        if (atEndOfStream()) {
            saturatingIncrement(diagnostics.endOfStreamCount);
            SongRenderResult result = baseResult(
                SongRenderStatus::EndOfStream,
                requestedOwnership,
                requestedFrames);
            result.loopWrappedBeforeBlock = loopWrapped;
            result.endOfStreamAfterBlock = true;
            return result;
        }

        if (!applyTuneIntent()) {
            saturatingIncrement(diagnostics.internalFailures);
            return baseResult(
                SongRenderStatus::TimePitchFailure,
                requestedOwnership,
                requestedFrames);
        }

        stemBusReadyMaskForCurrentRender = 0U;

        const bool wantsProcessed = processedForSong ||
            !tuneIsNeutral(desiredTempo(), desiredPitch());
        SongRenderResult result;
        if (wantsProcessed) {
            result = renderProcessed(
                requestedOwnership,
                renderPayload,
                requestedFrames,
                loopWrapped,
                maximumPrerollOutputFrames);
            if (!processedForSong && !cacheReadPending && result.status == SongRenderStatus::NeedOriginalData) {
                saturatingIncrement(diagnostics.tuneActivationDeferrals);
                result = renderDirect(
                    requestedOwnership,
                    renderPayload,
                    requestedFrames,
                    loopWrapped,
                    true);
            }
        } else {
            result = renderDirect(
                requestedOwnership,
                renderPayload,
                requestedFrames,
                loopWrapped,
                false);
        }
        if (result.framesProduced != 0U) for (auto &range : rawRanges) range.valid = false;
        updateCacheReadRequirements();
        return result;
    }

    SongRenderResult renderDirect(
        SongRenderOwnership requestedOwnership,
        float *renderPayload,
        std::uint32_t requestedFrames,
        bool loopWrapped,
        bool tuneDeferred) noexcept {
        const RenderPlan plan = planOutput(requestedFrames, 1.0F);
        if (plan.frames == 0) {
            saturatingIncrement(diagnostics.internalFailures);
            return baseResult(
                SongRenderStatus::TimelineOverflow,
                requestedOwnership,
                requestedFrames);
        }
        const std::uint64_t rangeStart = static_cast<std::uint64_t>(sourceCursor);
        if (!laneCoversLogical(0, rangeStart, plan.frames)) {
            saturatingIncrement(diagnostics.needOriginalDataCount);
            return baseResult(
                SongRenderStatus::NeedOriginalData,
                requestedOwnership,
                requestedFrames);
        }

        bool stemsRequired = false;
        if (!applyMixerControlsForRange(rangeStart, plan.frames, stemsRequired)) {
            saturatingIncrement(diagnostics.needStemDataCount);
            return baseResult(
                SongRenderStatus::NeedStemData,
                requestedOwnership,
                requestedFrames);
        }
        // Optional Original buses may use resident PCM, but cannot stall audible Original on replay.
        bool includeStemBuses = allStemLanesCoverLogical(rangeStart, plan.frames, !stemsRequired);
        if (!renderPreDspRange(
                rangeStart,
                plan.frames,
                includeStemBuses,
                processedPcm.data(), true, stemsRequired)) {
            return preDspReadFailure(requestedOwnership, requestedFrames);
        }
        if (!advanceLogicalFrame(rangeStart, plan.frames, feedCursor)) {
            return baseResult(
                SongRenderStatus::TimelineOverflow,
                requestedOwnership,
                requestedFrames);
        }
        const SongRenderStatus successfulStatus = tuneDeferred
            ? SongRenderStatus::RenderedTuneDeferred
            : SongRenderStatus::Rendered;
        SongRenderResult result = postAndPublishPayload(
            requestedOwnership,
            renderPayload,
            requestedFrames,
            plan,
            processedPcm.data(),
            successfulStatus,
            loopWrapped,
            false);
        if (result.framesProduced != 0) {
            saturatingAdd(diagnostics.directVirginFrames, result.framesProduced);
        }
        return result;
    }

    SongRenderResult renderProcessed(
        SongRenderOwnership requestedOwnership,
        float *renderPayload,
        std::uint32_t requestedFrames,
        bool loopWrapped,
        std::uint32_t maximumPrerollOutputFrames) noexcept {
        const bool firstActivation = !processedForSong;
        const RenderPlan plan = planOutput(requestedFrames, appliedTempo);
        if (plan.frames == 0) {
            saturatingIncrement(diagnostics.internalFailures);
            return baseResult(
                SongRenderStatus::TimelineOverflow,
                requestedOwnership,
                requestedFrames);
        }

        const TimePitchPrimeRequirements primeRequirements = needsPrime
            ? timePitch.primeRequirements()
            : TimePitchPrimeRequirements{};
        if (needsPrime &&
            (primeRequirements.lookaheadInputFrames == 0 ||
             primeRequirements.lookaheadInputFrames > maximumPrimeFrames ||
             (firstActivation &&
              primeRequirements.lookaheadInputFrames < configuration.tuneTransitionFrames))) {
            saturatingIncrement(diagnostics.internalFailures);
            return baseResult(
                SongRenderStatus::TimePitchFailure,
                requestedOwnership,
                requestedFrames);
        }
        const std::size_t requiredInput = timePitch.requiredInputFrames(plan.frames);
        if (requiredInput > maximumInputFrames ||
            primeRequirements.lookaheadInputFrames >
                std::numeric_limits<std::size_t>::max() - requiredInput) {
            saturatingIncrement(diagnostics.internalFailures);
            return baseResult(
                SongRenderStatus::TimePitchFailure,
                requestedOwnership,
                requestedFrames);
        }
        const std::size_t totalPreDspFrames =
            primeRequirements.lookaheadInputFrames + requiredInput;
        if (!laneCoversLogical(0, feedCursor, totalPreDspFrames)) {
            saturatingIncrement(diagnostics.needOriginalDataCount);
            return baseResult(
                SongRenderStatus::NeedOriginalData,
                requestedOwnership,
                requestedFrames);
        }

        bool stemsRequired = false;
        if (!applyMixerControlsForRange(feedCursor, totalPreDspFrames, stemsRequired)) {
            saturatingIncrement(diagnostics.needStemDataCount);
            return baseResult(
                SongRenderStatus::NeedStemData,
                requestedOwnership,
                requestedFrames);
        }
        bool includeStemBuses = allStemLanesCoverLogical(
            feedCursor,
            totalPreDspFrames, !stemsRequired);

        if (needsPrime) {
            if (!primeRequirements.inProgress && !renderPreDspRange(
                    feedCursor,
                    primeRequirements.lookaheadInputFrames,
                    includeStemBuses,
                    primePcm.data(), true, stemsRequired)) {
                return preDspReadFailure(requestedOwnership, requestedFrames);
            }
            if (!primeRequirements.inProgress) primeContainsStemBuses = includeStemBuses;
            if (firstActivation && !primeRequirements.inProgress) {
                std::copy_n(
                    primePcm.data(),
                    static_cast<std::size_t>(configuration.tuneTransitionFrames) *
                        kMusicChannelCount,
                    directTransitionPcm.data());
                std::fill(
                    directTransitionPcm.begin() +
                        static_cast<std::ptrdiff_t>(
                            static_cast<std::size_t>(configuration.tuneTransitionFrames) *
                            kMusicChannelCount),
                    directTransitionPcm.end(),
                    0.0F);
            }
            const TimePitchPrimeResult primed = timePitch.prime(
                primePcm.data(),
                primeRequirements.lookaheadInputFrames,
                maximumPrerollOutputFrames);
            if (primed.status == TimePitchPrimeStatus::InProgress) {
                return baseResult(SongRenderStatus::PreparingTune, requestedOwnership, requestedFrames);
            }
            if (primed.status != TimePitchPrimeStatus::Ok ||
                primed.nextInputOffsetFrames !=
                    primeRequirements.nextInputOffsetFrames) {
                saturatingIncrement(diagnostics.internalFailures);
                return baseResult(
                    SongRenderStatus::TimePitchFailure,
                    requestedOwnership,
                    requestedFrames);
            }
            if (!advanceLogicalFrame(
                    feedCursor,
                    primed.nextInputOffsetFrames,
                    feedCursor)) {
                return baseResult(
                    SongRenderStatus::TimelineOverflow,
                    requestedOwnership,
                    requestedFrames);
            }
            needsPrime = false;
            stemDspWarmupFramesRemaining = primeContainsStemBuses ? 0U : stemDspHistoryFrames();
            saturatingIncrement(diagnostics.tunePrimes);
        }

        if (requiredInput != 0 &&
            !renderPreDspRange(
                feedCursor,
                requiredInput,
                includeStemBuses,
                preDspInputPcm.data(), true, stemsRequired)) {
            return preDspReadFailure(requestedOwnership, requestedFrames);
        }
        const bool targetDirect = tuneIsNeutral(appliedTempo, appliedPitch);
        const std::uint64_t directStart = static_cast<std::uint64_t>(sourceCursor);
        const bool directReady = laneCoversLogical(0U, directStart, plan.frames) &&
            (!includeStemBuses || allStemLanesCoverLogical(directStart, plan.frames));
        const bool beginNeutralTransition = !firstActivation && targetDirect && directReady &&
            !neutralTransition && !transition.diagnostics().active;
        if ((neutralTransition || beginNeutralTransition) &&
            (!directReady || !renderPreDspRange(directStart, plan.frames, includeStemBuses,
                                                directTransitionPcm.data(), false, stemsRequired))) {
            return baseResult(SongRenderStatus::NeedOriginalData, requestedOwnership, requestedFrames);
        }
        const TimePitchProcessResult processed = timePitch.process(
            requiredInput == 0 ? nullptr : preDspInputPcm.data(),
            requiredInput,
            processedPcm.data(),
            configuration.maximumBlockFrames,
            plan.frames);
        if (processed.status != TimePitchProcessStatus::Ok ||
            processed.outputFramesProduced != plan.frames) {
            saturatingIncrement(diagnostics.internalFailures);
            return baseResult(
                SongRenderStatus::TimePitchFailure,
                requestedOwnership,
                requestedFrames);
        }
        if (!advanceLogicalFrame(
                feedCursor,
                processed.inputFramesConsumed,
                feedCursor)) {
            return baseResult(
                SongRenderStatus::TimelineOverflow,
                requestedOwnership,
                requestedFrames);
        }
        if (!includeStemBuses) {
            stemDspWarmupFramesRemaining = stemDspHistoryFrames();
            stemBusReadyMaskForCurrentRender = 0U;
        } else if (stemDspWarmupFramesRemaining != 0U) {
            stemDspWarmupFramesRemaining -= std::min<std::size_t>(stemDspWarmupFramesRemaining, plan.frames);
            stemBusReadyMaskForCurrentRender = 0U;
        } else {
            // Buffered DSP output retains the warmed buses even when this step needs no new input.
            stemBusReadyMaskForCurrentRender = stemwiseRequiredStemMask(configuration.stemLaneCount);
        }

        if (firstActivation) {
            transition.reset();
            neutralTransition = false;
            transition.begin();
            transitionDirectCursor = 0;
        }
        if (beginNeutralTransition) {
            neutralTransition = true;
            neutralTargetDirect = true;
            transition.begin();
        }
        if (neutralTransition && targetDirect != neutralTargetDirect) {
            transition.reverse();
            neutralTargetDirect = targetDirect;
        }
        const bool tuningTransitionTouched = firstActivation || neutralTransition || transition.diagnostics().active;
        bool returnedDirect = false;
        if (neutralTransition) {
            const auto mixed = transition.mix(
                neutralTargetDirect ? processedPcm.data() : directTransitionPcm.data(),
                neutralTargetDirect ? directTransitionPcm.data() : processedPcm.data(),
                processedPcm.data(), configuration.maximumBlockFrames, plan.frames);
            if (mixed.status != TimePitchTransitionStatus::Ok) {
                return baseResult(SongRenderStatus::TimePitchFailure, requestedOwnership, requestedFrames);
            }
            if (mixed.transitionComplete) {
                neutralTransition = false;
                returnedDirect = neutralTargetDirect;
                if (returnedDirect) {
                    processedForSong = false;
                    activationPrepared = false;
                    needsPrime = false;
                    feedCursor = static_cast<std::uint64_t>(plan.sourceEnd);
                }
            }
        } else if (transition.diagnostics().active) {
            const TimePitchTransitionResult mixed = transition.mix(
                directTransitionPcm.data() + transitionDirectCursor * kMusicChannelCount,
                processedPcm.data(),
                processedPcm.data(),
                configuration.maximumBlockFrames,
                plan.frames);
            if (mixed.status != TimePitchTransitionStatus::Ok ||
                mixed.framesMixed != plan.frames) {
                saturatingIncrement(diagnostics.internalFailures);
                return baseResult(
                    SongRenderStatus::TimePitchFailure,
                    requestedOwnership,
                    requestedFrames);
            }
            transitionDirectCursor += mixed.framesMixed;
            if (mixed.transitionComplete) transitionDirectCursor = 0;
        }

        if (firstActivation) {
            processedForSong = true;
            activationPrepared = false;
            saturatingIncrement(diagnostics.tuneActivations);
        }
        SongRenderResult result = postAndPublishPayload(
            requestedOwnership,
            renderPayload,
            requestedFrames,
            plan,
            processedPcm.data(),
            SongRenderStatus::Rendered,
            loopWrapped,
            !returnedDirect);
        if (tuningTransitionTouched) {
            result.actualTempo = 0.0F;
            result.actualPitchSemitones = 0.0F;
        }
        if (result.framesProduced != 0) {
            saturatingAdd(diagnostics.processedFrames, result.framesProduced);
        }
        return result;
    }

    std::size_t stemDspHistoryFrames() const noexcept {
        const auto dsp = timePitch.diagnostics();
        // Retire the zero-fed analysis window and overlap-add tail without resetting audible Original.
        return static_cast<std::size_t>(std::ceil(
            (dsp.configuredAnalysisBlockFrames + dsp.configuredAnalysisIntervalFrames) /
            static_cast<double>(TimePitchProcessor::kMinimumTempo))) +
            dsp.configuredAnalysisBlockFrames + dsp.configuredOutputLatencyFrames +
            dsp.configuredAnalysisIntervalFrames;
    }

    SongRenderResult postAndPublishPayload(
        SongRenderOwnership requestedOwnership,
        float *renderPayload,
        std::uint32_t originallyRequestedFrames,
        const RenderPlan &plan,
        const float *music,
        SongRenderStatus successfulStatus,
        bool loopWrapped,
        bool tuningProcessed) noexcept {
        if (plan.beginLoopFade && !loopFadeStarted) {
            mixer.beginPostDspLoopFadeOut(effectiveLoopDeclickFrames());
            loopFadeStarted = true;
        }
        const double sourceStart = sourceCursor;
        const PostDspMultibusRenderBlock post{
            .processedMusicBuses = music,
            .frameCount = plan.frames,
            .outputClockStartFrame = outputClock,
            .sourceTimeline = {sourceStart, plan.sourceEnd},
            .selectionToken = currentOwnership.selectionToken,
            .generation = currentOwnership.generation,
        };
        if (!mixer.renderPostDspMultibus(post, renderPayload, plan.frames)) {
            saturatingIncrement(diagnostics.internalFailures);
            return baseResult(
                SongRenderStatus::MixerFailure,
                requestedOwnership,
                originallyRequestedFrames);
        }

        std::uint64_t hash = 0U;
        if (configuration.diagnosticPayloadFingerprints) {
            hash = payload_fingerprint::fingerprintRenderPayload(
                renderPayload, plan.frames, diagnostics.nonFiniteRenderPayloadSamples);
            saturatingIncrement(diagnostics.renderPayloadFingerprintsCompleted);
        }
        saturatingIncrement(fingerprintSequence);

        SongRenderResult result;
        result.status = successfulStatus;
        result.framesProduced = plan.frames;
        result.framesRequested = originallyRequestedFrames;
        result.ownership = currentOwnership;
        result.outputClockStartFrame = outputClock;
        result.sourceTimeline = {sourceStart, plan.sourceEnd};
        result.payloadFingerprint = {
            .sequence = fingerprintSequence,
            .selectionToken = currentOwnership.selectionToken,
            .generation = currentOwnership.generation,
            .outputClockStartFrame = outputClock,
            .sourceTimeline = {sourceStart, plan.sourceEnd},
            .frameCount = plan.frames,
            .hash = hash,
        };
        result.controlRevision = acceptedControlRevision;
        result.stemGainPreviewPublicationSequence = stemGainPreviewSequence;
        result.stemGainPreviewCoversOfferSequence = stemGainPreviewCoversOfferSequence;
        result.stemGainPreviewActiveMask = stemGainPreviewMask;
        result.stemGainPreviewGains = stemGainPreviewGains;
        result.stemBusReadyMask = stemBusReadyMaskForCurrentRender;
        result.lateMixEndpoint = composeLateMixEndpoint();
        if ((result.stemBusReadyMask & result.lateMixEndpoint.requiredStemMask) !=
            result.lateMixEndpoint.requiredStemMask) result.lateMixEndpoint.sourceMode = MixerSourceMode::Original;
        // The callback owns these ramps. Render-bus production cannot claim that final PCM has
        // reached the endpoint merely because this block carries the new target.
        result.tuningProcessed = tuningProcessed;
        // Direct/deferred output remains unity even after a newer tune request was accepted.
        // A crossfade contains two numerical sources and cannot acknowledge either endpoint.
        if (!tuningProcessed) {
            result.actualTempo = 1.0F;
            result.actualPitchSemitones = 0.0F;
        } else if (!transition.diagnostics().active && !neutralTransition) {
            result.actualTempo = appliedTempo;
            result.actualPitchSemitones = appliedPitch;
        }
        result.loopRevision = managedLoopInstalled ? managedLoopRevision : 0U;
        result.loopCycle = managedLoopInstalled ? managedLoopCycle : 0U;
        result.loopEnabled = managedLoopInstalled;
        result.loopWrappedBeforeBlock = loopWrapped;
        result.endOfStreamAfterBlock = plan.reachesEos;

        outputClock += static_cast<std::int64_t>(plan.frames);
        sourceCursor = plan.sourceEnd;
        if (managedLoopInstalled && plan.reachesLoopEnd) {
            publishManagedBoundary();
            result.loopBoundaryPending = managedLoopBoundaryPending;
        }
        commitReservoirProgress();
        releaseRetiredLoopHeadIfPassed();
        saturatingIncrement(diagnostics.renderedBlocks);
        saturatingAdd(diagnostics.renderedFrames, plan.frames);
        return result;
    }

    void commitReservoirProgress() noexcept {
        std::uint64_t discardFrame = 0;
        if (managedLoopInstalled) {
            // The DSP feed cursor runs ahead by its priming latency. Retaining live body PCM from
            // the audible source cursor is what makes a loop Clear/tune re-prime lossless; dropping
            // to feedCursor here would create a raw gap immediately after the immutable head.
            discardFrame = sourceCursor < static_cast<double>(loopStart)
                ? static_cast<std::uint64_t>(std::floor(sourceCursor))
                : (sourceCursor < static_cast<double>(managedLoopHeadEnd)
                    ? managedLoopHeadEnd
                    : static_cast<std::uint64_t>(std::floor(sourceCursor)));
        } else if (retiredLoopHeadActive) {
            discardFrame = sourceCursor < static_cast<double>(retiredLoopHeadStart)
                ? static_cast<std::uint64_t>(std::floor(sourceCursor))
                : (sourceCursor < static_cast<double>(retiredLoopHeadEnd)
                    ? retiredLoopHeadEnd
                    : static_cast<std::uint64_t>(std::floor(sourceCursor)));
        } else if (loopActive) {
            discardFrame = sourceCursor < static_cast<double>(loopStart)
                ? static_cast<std::uint64_t>(std::floor(sourceCursor))
                : loopStart;
        } else {
            // Keep the audible raw frame while DSP reads ahead, so returning to neutral can
            // crossfade at the current source position without seeking or refilling lost PCM.
            discardFrame = static_cast<std::uint64_t>(std::floor(sourceCursor));
        }
        if (retainedCopyBorrower != nullptr &&
            retainedCopyOwner.selectionToken == currentOwnership.selectionToken &&
            retainedCopyOwner.generation == currentOwnership.generation)
            discardFrame = std::min(discardFrame, retainedCopyFloor);
        for (std::size_t index = 0U; index < reservoirs.size(); ++index) {
            if (index == 0U && retainOriginalHead) continue;
            if (reservoirs[index]) static_cast<void>(reservoirs[index]->discardBefore(discardFrame));
        }
    }

    SongRenderPipelineConfiguration configuration;
    StemwiseMixer mixer;
    TimePitchProcessor timePitch;
    TimePitchTransition transition;
    std::array<std::unique_ptr<TimelineStereoBuffer>, kSongRenderLaneCount> reservoirs{};
    // Render-thread-only one-shot fence for late stem decoder attachment in the current epoch.
    std::array<bool, kSongRenderLaneCount> laneTimelineAttached{};

    std::vector<std::int64_t> beatGrid;
    std::vector<std::uint8_t> beatAccents;
    std::size_t beatCount{0};
    std::uint64_t beatGridMutation{};
    std::array<std::vector<float>, kSongRenderLaneCount> laneScratch{};
    std::vector<float> primePcm;
    std::vector<float> preDspInputPcm;
    std::vector<float> directTransitionPcm;
    std::vector<float> processedPcm;
    std::array<RawRangeState, 4U> rawRanges{};
    bool cacheReadPending{};
    std::size_t maximumPrimeFrames{0};
    std::size_t maximumInputFrames{0};

    struct ContinuationCopy final {
        const Impl *sourceIdentity{}; // Compared only; cancellation takes an explicitly live source reference.
        SongRenderOwnership owner{};
        std::uint64_t ingressEpoch{};
        std::uint64_t sourceBeatGridMutation{}, destinationBeatGridMutation{};
        bool engageLoop{};
        std::uint64_t start{}, end{}, replacementStart{};
        std::uint32_t declickFrames{}, requiredLaneMask{};
        bool pending{}, refreshing{};
        std::array<std::uint64_t, kSongRenderLaneCount> nextFrame{};
        std::array<bool, kSongRenderLaneCount> copyBody{};
    } continuationCopy;
    const Impl *retainedCopyBorrower{}; // Identity only; never dereferenced, including destruction.
    SongRenderOwnership retainedCopyOwner{};
    std::uint64_t retainedCopyFloor{};

    SongRenderOwnership currentOwnership{};
    LoopHeadCache::ReadView atomicLoopCache{};
    const CurrentSongPcmCache *currentSongCache{nullptr};
    SongRenderOwnership currentSongCacheOwnership{};
    std::shared_ptr<CurrentSongPcmReadConsumer> cacheReadConsumer{std::make_shared<CurrentSongPcmReadConsumer>()};
    bool retainOriginalHead{false};
    std::uint64_t currentIngressEpoch{0};
    bool active{false};
    double sourceCursor{0.0};
    std::uint64_t feedCursor{0};
    std::uint64_t playbackEndFrame{0};
    std::int64_t outputClock{0};

    SongRenderControlSnapshot desiredControls{};
    std::uint64_t acceptedControlRevision{0};
    // Lifetime high-water mark. Owner resets must not make a delayed execution admissible.
    std::uint64_t acceptedControlApplicationEpoch{0};
    std::uint64_t mixerRevision{0};
    bool mixerDirty{true};
    bool tuneDirty{true};
    std::uint64_t stemGainPreviewSequence{0};
    std::uint64_t stemGainPreviewCoversOfferSequence{0};
    std::uint32_t stemGainPreviewMask{0};
    std::array<float, kStemLaneCount> stemGainPreviewGains = stemwiseFilledArray<float, kStemLaneCount>(1.0F);
    MixerSourceMode appliedSourceMode{MixerSourceMode::Original};
    bool stemsPathEngaged{false};
    std::uint32_t stemTransitionFramesRemaining{0};
    float appliedTempo{1.0F};
    float appliedPitch{0.0F};
    bool processedForSong{false};
    bool neutralTransition{false};
    bool neutralTargetDirect{false};
    bool activationPrepared{false};
    bool needsPrime{false};
    std::size_t transitionDirectCursor{0};
    std::uint32_t stemBusReadyMaskForCurrentRender{0U};
    std::size_t stemDspWarmupFramesRemaining{0U};
    bool primeContainsStemBuses{false};

    std::array<bool, kSongRenderLaneCount> eosMarked{};
    std::array<std::uint64_t, kSongRenderLaneCount> eosEndFrame{};
    bool loopActive{false};
    std::uint64_t loopStart{0};
    std::uint64_t loopEnd{0};
    std::uint32_t loopDeclickFrames{0};
    bool loopFadeStarted{false};

    LoopHeadCache::ReadView managedLoopCache{};
    bool managedLoopInstalled{false};
    bool managedLoopCacheOnly{false};
    bool managedLoopBoundaryPending{false};
    std::uint64_t lastManagedDirectiveSequence{0};
    std::uint64_t managedLoopRevision{0};
    std::uint64_t managedLoopCycle{0};
    std::uint64_t managedLoopIngressEpoch{0};
    std::uint64_t managedLoopHeadEnd{0};
    std::uint64_t managedLoopChecksum{0};
    std::uint32_t managedLoopRequiredLaneMask{0};
    std::uint64_t managedBoundarySequence{0};
    LoopCycleBoundaryEvidence managedBoundaryEvidence{};

    // Clear retires the immutable raw head as a data source for at most the
    // remainder of the current linear pass. It carries no loop policy: wrapping,
    // boundary publication, and output loop tags are disabled immediately.
    LoopHeadCache::ReadView retiredLoopHeadCache{};
    bool retiredLoopHeadActive{false};
    std::uint64_t retiredLoopHeadStart{0U};
    std::uint64_t retiredLoopHeadEnd{0U};
    std::uint32_t retiredLoopHeadRequiredLaneMask{0U};
    SongRenderOwnership retiredLoopHeadOwner{};

    std::uint64_t fingerprintSequence{0};
    SongRenderPipelineDiagnostics diagnostics{};
};

SongRenderPipeline::SongRenderPipeline(
    const SongRenderPipelineConfiguration &configuration)
    : mImpl(std::make_unique<Impl>(configuration)) {}

SongRenderPipeline::~SongRenderPipeline() = default;

void SongRenderPipeline::releaseRetiredLoopHeadReaders() noexcept {
    mImpl->atomicLoopCache = {};
    mImpl->managedLoopCache = {};
    mImpl->clearRetiredLoopHead();
}

std::uint32_t SongRenderPipeline::boundedLoopHeadLaneMask() const noexcept {
    return mImpl->atomicLoopCache.valid() ? mImpl->atomicLoopCache.snapshot().requiredLaneMask : 0U;
}

std::unique_ptr<SongRenderPreparedLanes> SongRenderPipeline::prepareStemLaneCount(
    const SongRenderPipelineConfiguration &configuration, std::size_t count) {
    if (count > kStemLaneCount) throw std::invalid_argument("Invalid prepared stem count");
    auto result = std::make_unique<SongRenderPreparedLanes>();
    result->configuration = configuration;
    result->configuration.stemLaneCount = static_cast<std::uint32_t>(count);
    for (std::size_t lane = 1U; lane <= count; ++lane) {
        result->reservoirs[lane] = std::make_unique<TimelineStereoBuffer>(configuration.laneReservoirCapacityFrames);
    }
    return result;
}

bool SongRenderPipeline::adoptPreparedStemLaneCount(SongRenderPreparedLanes &prepared) noexcept {
    return mImpl->adoptPreparedStemLaneCount(prepared);
}

SongRenderResetStatus SongRenderPipeline::beginSong(
    SongRenderOwnership ownership,
    std::uint64_t ingressEpoch,
    std::uint64_t firstTimelineFrame,
    std::int64_t outputClockStartFrame,
    std::uint64_t playbackEndFrameExclusive) noexcept {
    return mImpl->beginSong(
        ownership,
        ingressEpoch,
        firstTimelineFrame,
        outputClockStartFrame,
        playbackEndFrameExclusive);
}

bool SongRenderPipeline::configureStemLaneCount(std::size_t count) noexcept {
    return mImpl->configureStemLaneCount(count);
}
bool SongRenderPipeline::awaitingCacheRead() const noexcept { return mImpl->cacheReadPending; }

std::size_t SongRenderPipeline::stemLaneCount() const noexcept {
    return mImpl->configuration.stemLaneCount;
}


SongRenderResetStatus SongRenderPipeline::seek(
    SongRenderOwnership ownership,
    std::uint64_t ingressEpoch,
    std::uint64_t firstTimelineFrame,
    std::int64_t outputClockStartFrame,
    std::uint64_t playbackEndFrameExclusive) noexcept {
    return mImpl->seek(
        ownership,
        ingressEpoch,
        firstTimelineFrame,
        outputClockStartFrame,
        playbackEndFrameExclusive);
}


void SongRenderPipeline::discardConsumedPcm() noexcept {
    mImpl->commitReservoirProgress();
}

TimelineAppendResult SongRenderPipeline::appendLanePcm(
    SongRenderLane lane,
    std::uint64_t generation,
    std::uint64_t firstTimelineFrame,
    const float *interleavedStereo,
    std::size_t frameCount) noexcept {
    return mImpl->appendLanePcm(
        lane,
        generation,
        firstTimelineFrame,
        interleavedStereo,
        frameCount);
}

SongRenderEosStatus SongRenderPipeline::markLaneEndOfStream(
    SongRenderLane lane,
    std::uint64_t generation,
    std::uint64_t endTimelineFrameExclusive) noexcept {
    return mImpl->markLaneEndOfStream(lane, generation, endTimelineFrameExclusive);
}

bool SongRenderPipeline::bindCurrentSongPcmCache(
    SongRenderOwnership ownership,
    const CurrentSongPcmCache *cache) noexcept {
    return mImpl->bindCurrentSongPcmCache(ownership, cache);
}

bool SongRenderPipeline::bindRetainedLoopHead(
    SongRenderOwnership ownership,
    LoopHeadCache::ReadView head) noexcept {
    if (!mImpl->active || ownership.selectionToken != mImpl->currentOwnership.selectionToken ||
        ownership.generation != mImpl->currentOwnership.generation || !head.valid() ||
        mImpl->managedLoopCache.valid() || mImpl->retiredLoopHeadCache.valid()) return false;
    const auto value = head.snapshot();
    const auto frame = static_cast<std::uint64_t>(std::floor(mImpl->sourceCursor));
    if (frame < value.key.startTimelineFrame || frame >= value.key.startTimelineFrame + value.frameCount ||
        (value.completedLaneMask & 1U) == 0U || head.readFailureLaneMask() != 0U) return false;
    if (mImpl->atomicLoopCache.valid()) {
        const auto &old = mImpl->atomicLoopCache.snapshot();
        // Move to a newer equivalent prefix without resetting live PCM or waiting for a
        // requested source change; the superseded slot can then prepare that source's head.
        const bool preservesAllLanes = old.key.matchesSource(value.key.songIdentityHash, value.key.originalContentHash,
                value.key.stemPackageHash, old.completedLaneMask) &&
            (value.completedLaneMask & old.completedLaneMask) == old.completedLaneMask &&
            ((value.completedLaneMask & ~old.completedLaneMask) != 0U || value.key.loopRevision > old.key.loopRevision);
        // Settled Original can retire superseded stem PCM without resetting its DSP tail.
        const bool settledOriginalReplacement = !mImpl->loopActive &&
            !mImpl->continuationCopy.pending && mImpl->retainedCopyBorrower == nullptr &&
            mImpl->desiredMixerControls().sourceMode == MixerSourceMode::Original &&
            mImpl->appliedSourceMode == MixerSourceMode::Original && !mImpl->stemsPathEngaged &&
            value.key.loopRevision > old.key.loopRevision &&
            old.key.matchesSource(value.key.songIdentityHash, value.key.originalContentHash,
                value.key.stemPackageHash, CurrentSongPcmCache::kOriginalLaneMask) &&
            mImpl->feedCursor >= value.key.startTimelineFrame &&
            mImpl->feedCursor <= value.key.startTimelineFrame + value.frameCount;
        if ((!preservesAllLanes && !settledOriginalReplacement) ||
            value.key.startTimelineFrame > old.key.startTimelineFrame ||
            value.key.startTimelineFrame + value.frameCount < old.key.startTimelineFrame + old.frameCount) return false;
    }
    mImpl->atomicLoopCache = std::move(head);
    if (value.originalEndResolved) {
        for (std::size_t lane = 0U; lane <= mImpl->configuration.stemLaneCount; ++lane) {
            if ((value.completedLaneMask & (1U << lane)) == 0U) continue;
            mImpl->eosMarked[lane] = true;
            mImpl->eosEndFrame[lane] = value.resolvedOriginalEndTimelineFrameExclusive;
        }
    }
    return true;
}

LoopHeadCache::ReadView SongRenderPipeline::acquireRetainedLoopHead(
    const CurrentSongCacheKey &key, std::uint32_t mask,
    std::uint64_t first, std::uint64_t end) const noexcept {
    if (!mImpl->active || key.selectionToken != mImpl->currentOwnership.selectionToken ||
        key.generation != mImpl->currentOwnership.generation || !mImpl->atomicLoopCache.valid() ||
        mask == 0U || end <= first) return {};
    const auto &head = mImpl->atomicLoopCache;
    const auto &value = head.snapshot();
    if (!value.key.matchesSource(key.songIdentityHash, key.originalContentHash, key.stemPackageHash, mask) ||
        (value.completedLaneMask & mask) != mask || (head.readFailureLaneMask() & (mask | 1U)) != 0U ||
        first < value.key.startTimelineFrame || end > value.key.startTimelineFrame + value.frameCount) return {};
    return head.retain();
}

std::uint32_t SongRenderPipeline::retainedLoopHeadReadFailureLaneMask(const CurrentSongCacheKey &key) const noexcept {
    if (!mImpl->active || key.selectionToken != mImpl->currentOwnership.selectionToken ||
        key.generation != mImpl->currentOwnership.generation || !mImpl->atomicLoopCache.valid()) return 0U;
    const auto &head = mImpl->atomicLoopCache;
    const auto failed = head.readFailureLaneMask();
    return head.snapshot().key.matchesSource(key.songIdentityHash, key.originalContentHash, key.stemPackageHash, failed)
        ? failed : 0U;
}

void SongRenderPipeline::releaseCacheReadRequirements() noexcept {
    mImpl->cacheReadConsumer->active.store(false, std::memory_order_release);
}

bool SongRenderPipeline::retryPendingCacheReadRequirements(const CurrentSongPcmCache *liveCache) noexcept {
    return mImpl->retryPendingCacheReadRequirements(liveCache);
}

void SongRenderPipeline::setCacheReadPreparation(bool preparation) noexcept {
    mImpl->cacheReadConsumer->preparation.store(preparation, std::memory_order_relaxed);
}

bool SongRenderPipeline::retainOriginalTransitionHead(
    SongRenderOwnership ownership,
    bool retain) noexcept {
    return mImpl->retainOriginalTransitionHead(ownership, retain);
}

bool SongRenderPipeline::beginCurrentSongPcmCacheFallback(
    SongRenderOwnership ownership,
    std::uint64_t firstReservoirFrame) noexcept {
    return mImpl->beginCurrentSongPcmCacheFallback(ownership, firstReservoirFrame);
}

TimelineCopyResult SongRenderPipeline::copyRetainedLanePcm(
    SongRenderOwnership ownership,
    SongRenderLane lane,
    std::uint64_t firstTimelineFrame,
    float *interleavedStereoOutput,
    std::size_t outputCapacityFrames,
    std::size_t requestedFrames) const noexcept {
    return mImpl->copyRetainedLanePcm(
        ownership,
        lane,
        firstTimelineFrame,
        interleavedStereoOutput,
        outputCapacityFrames,
        requestedFrames);
}

SongRenderControlStatus SongRenderPipeline::submitControls(
    const SongRenderControlSnapshot &snapshot) noexcept {
    return mImpl->submitControls(snapshot);
}

SongRenderStemGainPreviewStatus SongRenderPipeline::submitStemGainPreview(
    SongRenderOwnership ownership,
    std::uint64_t sequence,
    std::uint64_t coversOfferSequence,
    std::uint32_t activeMask,
    const std::array<float, kStemLaneCount> &gains) noexcept {
    return mImpl->submitStemGainPreview(
        ownership,
        sequence,
        coversOfferSequence,
        activeMask,
        gains);
}

SongRenderLateMixEndpoint SongRenderPipeline::prepareLateMixEndpoint(
    SongRenderOwnership ownership) noexcept {
    return mImpl->prepareLateMixEndpoint(ownership);
}

bool SongRenderPipeline::retireStemGainPreview(
    SongRenderOwnership ownership) noexcept {
    return mImpl->retireStemGainPreview(ownership);
}



bool SongRenderPipeline::setBeatGrid(
    SongRenderOwnership ownership,
    const std::int64_t *beatFrames,
    const std::uint8_t *accentFlags,
    std::size_t beatCount) noexcept {
    return mImpl->setBeatGrid(ownership, beatFrames, accentFlags, beatCount);
}

bool SongRenderPipeline::beatGridMatches(SongRenderOwnership ownership,
    const std::int64_t *frames, const std::uint8_t *accents, std::size_t count) const noexcept {
    return mImpl->checkOwnership(ownership) == Impl::OwnershipCheck::Ok &&
        count == mImpl->beatCount && (count == 0U || (frames != nullptr && accents != nullptr &&
        std::equal(frames, frames + count, mImpl->beatGrid.begin()) &&
        std::equal(accents, accents + count, mImpl->beatAccents.begin())));
}

void SongRenderPipeline::clearBeatGrid(SongRenderOwnership ownership) noexcept {
    mImpl->clearBeatGrid(ownership);
}

std::size_t SongRenderPipeline::loopContinuationHeadFrames(std::uint64_t start, std::uint64_t end) const noexcept {
    return mImpl->loopContinuationHeadFrames(start, end);
}

SongRenderLoopStatus SongRenderPipeline::clearLoop(
    SongRenderOwnership ownership) noexcept {
    return mImpl->clearLoop(ownership);
}

SongRenderLoopStatus SongRenderPipeline::refreshAtomicLoopContinuationControlsFrom(
    SongRenderPipeline &liveProducer, std::uint32_t retainedHeadLaneMask) noexcept {
    return mImpl->refreshAtomicLoopContinuationControlsFrom(*liveProducer.mImpl, retainedHeadLaneMask);
}

SongRenderLoopStatus SongRenderPipeline::retargetAtomicLoopEnd(
    SongRenderOwnership ownership,
    std::uint64_t startTimelineFrame,
    std::uint64_t endTimelineFrameExclusive) noexcept {
    return mImpl->retargetAtomicLoopEnd(
        ownership,
        startTimelineFrame,
        endTimelineFrameExclusive);
}

SongRenderLoopStatus SongRenderPipeline::prepareAtomicLoopCutover(
    SongRenderOwnership ownership,
    bool engageLoop,
    std::uint64_t startTimelineFrame,
    std::uint64_t endTimelineFrameExclusive,
    std::uint64_t replacementStartTimelineFrame,
    std::uint32_t declickFrames) noexcept {
    return prepareAtomicLoopCutover(ownership, engageLoop, startTimelineFrame,
        endTimelineFrameExclusive, replacementStartTimelineFrame, declickFrames,
        mImpl->requiredSongMask());
}

SongRenderLoopStatus SongRenderPipeline::prepareAtomicLoopCutover(
    SongRenderOwnership ownership,
    bool engageLoop,
    std::uint64_t startTimelineFrame,
    std::uint64_t endTimelineFrameExclusive,
    std::uint64_t replacementStartTimelineFrame,
    std::uint32_t declickFrames,
    std::uint32_t requiredLaneMask) noexcept {
    return mImpl->prepareAtomicLoopCutover(
        ownership,
        engageLoop,
        startTimelineFrame,
        endTimelineFrameExclusive,
        replacementStartTimelineFrame,
        declickFrames,
        requiredLaneMask);
}

SongRenderLoopStatus SongRenderPipeline::prepareAtomicLoopContinuationFrom(
    SongRenderPipeline &liveProducer,
    SongRenderOwnership ownership,
    bool engageLoop,
    std::uint64_t startTimelineFrame,
    std::uint64_t endTimelineFrameExclusive,
    std::uint64_t replacementStartTimelineFrame,
    std::uint32_t declickFrames,
    std::uint32_t requiredLaneMask, const LoopHeadCache *boundedHead,
    LoopHeadCache::ReadView retainedHead) noexcept {
    return mImpl->prepareAtomicLoopContinuationFrom(
        *liveProducer.mImpl,
        ownership,
        engageLoop,
        startTimelineFrame,
        endTimelineFrameExclusive,
        replacementStartTimelineFrame,
        declickFrames,
        requiredLaneMask, boundedHead, std::move(retainedHead));
}

void SongRenderPipeline::cancelAtomicLoopContinuationPreparationFrom(SongRenderPipeline &liveProducer) noexcept {
    mImpl->cancelAtomicLoopContinuationPreparationFrom(*liveProducer.mImpl);
}

SongRenderLoopDirectiveStatus SongRenderPipeline::applyLoopDirective(
    const LoopCycleDirective &directive,
    LoopHeadCache::ReadView acceptedHead) noexcept {
    return mImpl->applyLoopDirective(directive, std::move(acceptedHead));
}

LoopCycleBoundaryEvidence SongRenderPipeline::pendingLoopBoundary() const noexcept {
    return mImpl->managedBoundaryEvidence;
}

SongRenderLoopCycleSnapshot SongRenderPipeline::loopCycleSnapshot() const noexcept {
    return {
        .installed = mImpl->managedLoopInstalled,
        .cacheOnly = mImpl->managedLoopCacheOnly,
        .boundaryPending = mImpl->managedLoopBoundaryPending,
        .lastDirectiveSequence = mImpl->lastManagedDirectiveSequence,
        .loopRevision = mImpl->managedLoopRevision,
        .cycle = mImpl->managedLoopCycle,
        .ingressEpoch = mImpl->managedLoopIngressEpoch,
        .startTimelineFrame = mImpl->loopStart,
        .endTimelineFrameExclusive = mImpl->loopEnd,
        .headEndTimelineFrameExclusive = mImpl->managedLoopHeadEnd,
        .cacheChecksum = mImpl->managedLoopChecksum,
        .requiredLaneMask = mImpl->managedLoopRequiredLaneMask,
    };
}

SongRenderLaneEndSnapshot SongRenderPipeline::laneEndSnapshot(
    SongRenderLane lane) const noexcept {
    if (!validLane(lane) || !mImpl->reservoirs[laneIndex(lane)]) return {};
    const std::size_t index = laneIndex(lane);
    return {
        .marked = mImpl->eosMarked[index],
        .ingressEpoch = mImpl->currentIngressEpoch,
        .endTimelineFrameExclusive = mImpl->eosEndFrame[index],
    };
}

SongRenderResult SongRenderPipeline::renderMultibus(
    SongRenderOwnership ownership,
    float *interleavedRenderPayload,
    std::uint32_t outputCapacityFrames,
    std::uint32_t requestedFrames,
    std::uint32_t maximumPrerollOutputFrames) noexcept {
    return mImpl->render(
        ownership,
        interleavedRenderPayload,
        outputCapacityFrames,
        requestedFrames,
        maximumPrerollOutputFrames);
}

void SongRenderPipeline::requestUpcomingCacheInput() const noexcept { mImpl->requestUpcomingCacheInput(); }

SongRenderOwnership SongRenderPipeline::ownership() const noexcept {
    return mImpl->currentOwnership;
}

std::uint64_t SongRenderPipeline::ingressEpoch() const noexcept {
    return mImpl->currentIngressEpoch;
}

double SongRenderPipeline::sourceTimelineFrame() const noexcept {
    return mImpl->sourceCursor;
}

std::uint64_t SongRenderPipeline::nextPreDspTimelineFrame() const noexcept {
    return mImpl->feedCursor;
}

std::int64_t SongRenderPipeline::nextOutputClockFrame() const noexcept {
    return mImpl->outputClock;
}

bool SongRenderPipeline::processedTimelineActive() const noexcept {
    return mImpl->processedForSong;
}

bool SongRenderPipeline::loopEnabled() const noexcept {
    return mImpl->loopActive;
}

bool SongRenderPipeline::laneCoversLogicalRange(
    SongRenderOwnership ownership,
    SongRenderLane lane,
    std::uint64_t firstTimelineFrame,
    std::size_t frameCount) const noexcept {
    return validLane(lane) &&
        laneIndex(lane) <= mImpl->configuration.stemLaneCount &&
        mImpl->checkOwnership(ownership) == Impl::OwnershipCheck::Ok &&
        mImpl->laneCoversLogical(laneIndex(lane), firstTimelineFrame, frameCount);
}

TimelineStereoBufferDiagnostics SongRenderPipeline::laneDiagnostics(
    SongRenderLane lane) const noexcept {
    if (!validLane(lane) || !mImpl->reservoirs[laneIndex(lane)]) return {};
    return mImpl->reservoirs[laneIndex(lane)]->diagnostics();
}

const StemwiseMixerDiagnostics &SongRenderPipeline::mixerDiagnostics() const noexcept {
    return mImpl->mixer.diagnostics();
}

TimePitchDiagnostics SongRenderPipeline::timePitchDiagnostics() const noexcept {
    return mImpl->timePitch.diagnostics();
}

TimePitchTransitionDiagnostics SongRenderPipeline::transitionDiagnostics() const noexcept {
    return mImpl->transition.diagnostics();
}

void SongRenderPipeline::setDiagnosticPayloadFingerprints(bool enabled) noexcept {
    mImpl->configuration.diagnosticPayloadFingerprints = enabled;
}

SongRenderPipelineDiagnostics SongRenderPipeline::diagnostics() const noexcept {
    return mImpl->diagnostics;
}

}  // namespace stemwise::audio::engine
