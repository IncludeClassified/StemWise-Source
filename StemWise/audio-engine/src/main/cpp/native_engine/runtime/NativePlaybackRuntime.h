#pragma once

#include "NativeAudioOutputBridge.h"
#include "../ingress/LaneIngress.h"
#include "../ingress/LoopHeadPreparationIngress.h"
#include "../render/SongRenderPipeline.h"
#include "../render/CurrentSongPcmCache.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <memory>
#include <string>

namespace stemwise::audio::engine {

/**
 * Allocation-free function table around one borrowed output handle.
 *
 * Production uses borrowNativeAudioOutputPort(). Tests may inject a fake with identical
 * acceptance/backpressure/presentation semantics. The owner of handle must stop and join the
 * runtime before destroying the handle.
 */
struct NativePlaybackOutputPort final {
    using EnqueueFunction = std::size_t (*)(
        NativeAudioOutputHandle, const NativeRenderBusSubmission &) noexcept;
    using PublishLateMixControlFunction = bool (*)(
        NativeAudioOutputHandle,
        const NativeAudioLateMixControl &) noexcept;
    using PublishLoopPolicyFunction = bool (*)(
        NativeAudioOutputHandle,
        const NativeAudioOutputLoopPolicy &) noexcept;
    using PublishLoopDemandFunction = bool (*)(
        NativeAudioOutputHandle,
        const NativeAudioOutputLoopPolicy &,
        bool,
        std::uint64_t) noexcept;
    using BeginLoopCutoverFunction = bool (*)(
        NativeAudioOutputHandle,
        std::uint64_t,
        std::uint64_t) noexcept;
    using CancelLoopCutoverFunction = NativeAudioDrainCancelStatus (*)(
        NativeAudioOutputHandle,
        std::uint64_t,
        std::uint64_t) noexcept;
    using CommitLoopCutoverFunction = NativeAudioLoopCutoverCommitStatus (*)(
        NativeAudioOutputHandle,
        const NativeAudioOutputLoopPolicy &) noexcept;
    using ReleaseLoopCutoverWritesFunction = bool (*)(
        NativeAudioOutputHandle,
        std::uint64_t,
        std::uint64_t) noexcept;
    using LoopBoundaryCoverageFunction = NativeAudioLoopBoundaryCoverage (*)(
        NativeAudioOutputHandle,
        std::uint64_t,
        std::uint64_t,
        std::uint64_t,
        std::uint64_t) noexcept;
    using FlushFunction = NativeAudioFlushStatus (*)(
        NativeAudioOutputHandle,
        std::uint64_t) noexcept;
    using HandoffFunction = bool (*)(NativeAudioOutputHandle, std::uint64_t) noexcept;
    using BeginDrainHandoffFunction = bool (*)(
        NativeAudioOutputHandle,
        std::uint64_t,
        std::uint64_t,
        std::uint64_t) noexcept;
    using CommitDrainHandoffFunction = BeginDrainHandoffFunction;
    using CancelDrainHandoffFunction = NativeAudioDrainCancelStatus (*)(
        NativeAudioOutputHandle,
        std::uint64_t,
        std::uint64_t,
        std::uint64_t) noexcept;
    using SetPlayingFunction = bool (*)(NativeAudioOutputHandle, bool) noexcept;
    using SetTerminalSilenceAuthorizationFunction = void (*)(
        NativeAudioOutputHandle,
        std::uint64_t,
        bool) noexcept;
    using FrameCountFunction = std::size_t (*)(NativeAudioOutputHandle) noexcept;
    using GenerationFrameCountFunction = std::size_t (*)(
        NativeAudioOutputHandle,
        std::uint64_t) noexcept;
    using FramesPerBurstFunction = std::int32_t (*)(NativeAudioOutputHandle) noexcept;
    using PresentationEvidenceFunction = NativeAudioPresentationEvidence (*)(
        NativeAudioOutputHandle) noexcept;
    using PresentedPositionFunction = NativeAudioPresentedPosition (*)(
        NativeAudioOutputHandle) noexcept;
    using AppliedControlEvidenceFunction = NativeAudioAppliedControlEvidence (*)(
        NativeAudioOutputHandle) noexcept;
    using StemGainCallbackEvidenceFunction = NativeAudioStemGainCallbackEvidence (*)(
        NativeAudioOutputHandle) noexcept;
    using AudibleLoopEvidenceFunction = NativeAudioAudibleLoopEvidence (*)(
        NativeAudioOutputHandle) noexcept;

    NativeAudioOutputHandle handle{nullptr};
    EnqueueFunction enqueue{nullptr};
    PublishLateMixControlFunction publishLateMixControl{nullptr};
    /** Control-thread logical A/B mailbox; physically prepared policies use publishLoopPolicy. */
    PublishLoopDemandFunction publishLoopDemand{nullptr};
    void (*retireLoopDemand)(NativeAudioOutputHandle, std::uint64_t, std::uint64_t, std::uint64_t) noexcept{nullptr};
    NativeAudioPendingLoopCapture (*pendingLoopCapture)(NativeAudioOutputHandle,
        const NativeAudioOutputLoopPolicy &) noexcept{nullptr};
    PublishLoopPolicyFunction publishLoopPolicy{nullptr};
    BeginLoopCutoverFunction beginLoopCutover{nullptr};
    CancelLoopCutoverFunction cancelLoopCutover{nullptr};
    EnqueueFunction enqueueLoopCutover{nullptr};
    CommitLoopCutoverFunction commitLoopCutover{nullptr};
    ReleaseLoopCutoverWritesFunction releaseLoopCutoverWrites{nullptr};
    LoopBoundaryCoverageFunction loopBoundaryCoverage{nullptr};
    HandoffFunction beginHandoff{nullptr};
    HandoffFunction commitHandoff{nullptr};
    FlushFunction flush{nullptr};
    SetPlayingFunction setPlaying{nullptr};
    SetTerminalSilenceAuthorizationFunction setTerminalSilenceAuthorization{nullptr};
    bool (*publishTerminalBoundary)(NativeAudioOutputHandle, std::uint64_t, std::uint64_t,
        std::uint64_t, double) noexcept{nullptr};
    FrameCountFunction queuedFrames{nullptr};
    FrameCountFunction writableFrames{nullptr};
    FramesPerBurstFunction framesPerBurst{nullptr};
    FrameCountFunction loopCutoverRequiredFrames{nullptr};
    PresentationEvidenceFunction presentationEvidence{nullptr};
    PresentedPositionFunction presentedPosition{nullptr};
    AppliedControlEvidenceFunction appliedControlEvidence{nullptr};
    StemGainCallbackEvidenceFunction stemGainCallbackEvidence{nullptr};
    AudibleLoopEvidenceFunction audibleLoopEvidence{nullptr};
    BeginDrainHandoffFunction beginDrainHandoff{nullptr};
    CommitDrainHandoffFunction commitDrainHandoff{nullptr};
    CancelDrainHandoffFunction cancelDrainHandoff{nullptr};
    GenerationFrameCountFunction queuedFramesForGeneration{nullptr};
    GenerationFrameCountFunction writableFramesForGeneration{nullptr};

    void (*setDiagnosticPayloadFingerprints)(NativeAudioOutputHandle, bool) noexcept{nullptr};

    [[nodiscard]] constexpr bool valid() const noexcept {
        return handle != nullptr && enqueue != nullptr &&
            publishLateMixControl != nullptr && publishLoopDemand != nullptr && retireLoopDemand != nullptr &&
            pendingLoopCapture != nullptr &&
            publishLoopPolicy != nullptr &&
            beginLoopCutover != nullptr && enqueueLoopCutover != nullptr &&
            commitLoopCutover != nullptr && releaseLoopCutoverWrites != nullptr &&
            loopBoundaryCoverage != nullptr &&
            cancelLoopCutover != nullptr &&
            beginHandoff != nullptr &&
            commitHandoff != nullptr && flush != nullptr &&
            setPlaying != nullptr && setTerminalSilenceAuthorization != nullptr && publishTerminalBoundary != nullptr &&
            queuedFrames != nullptr && writableFrames != nullptr &&
            framesPerBurst != nullptr &&
            loopCutoverRequiredFrames != nullptr &&
            presentationEvidence != nullptr &&
            presentedPosition != nullptr &&
            appliedControlEvidence != nullptr &&
            stemGainCallbackEvidence != nullptr && audibleLoopEvidence != nullptr;
    }

    /** Complete optional surface required for exact automatic end-to-successor handoff. */
    [[nodiscard]] constexpr bool supportsDrainHandoff() const noexcept {
        return beginDrainHandoff != nullptr && commitDrainHandoff != nullptr &&
            cancelDrainHandoff != nullptr && queuedFramesForGeneration != nullptr &&
            writableFramesForGeneration != nullptr;
    }
};

/** Android production adapter; kept in a separate translation unit so host tests need no Android sink. */
[[nodiscard]] NativePlaybackOutputPort borrowNativeAudioOutputPort(
    NativeAudioOutputHandle handle) noexcept;

enum class NativePlaybackBank : std::uint8_t {
    First = 0,
    Second = 1,
};

inline constexpr std::size_t kNativePlaybackBankCount = 2;

enum class NativePlaybackWorkerServiceKind : std::uint8_t {
    CurrentOutput = 0,
    IngressLane = 1,
};

/**
 * One allocation-free action in the render worker's bounded ingress service plan.
 *
 * Action zero always services the current audible owner. Every later ingress pull is immediately
 * followed by another current-output action. Pull rounds are lane-major: selected Original,
 * preview Original, then each selected/preview stem pair. That gives both the audible owner and a
 * swipe/skip target their earliest useful PCM without letting background work form one unbounded
 * batch ahead of an audible refill.
 */
struct NativePlaybackWorkerServiceAction final {
    NativePlaybackWorkerServiceKind kind{NativePlaybackWorkerServiceKind::CurrentOutput};
    std::size_t bank{0};
    std::size_t lane{0};
    std::uint32_t pullRound{0};
};

class NativePlaybackIngressServicePlan final {
public:
    constexpr NativePlaybackIngressServicePlan(
        std::size_t selectedBank,
        std::uint32_t pullsPerLane,
        std::array<std::size_t, kNativePlaybackBankCount> laneCounts = {kSongRenderLaneCount, kSongRenderLaneCount}) noexcept
        : mSelectedBank(selectedBank), mPullsPerLane(pullsPerLane) {
        if (selectedBank >= kNativePlaybackBankCount) return;
        for (std::size_t lane = 0U; lane < kSongRenderLaneCount; ++lane) {
            for (std::size_t rank = 0U; rank < kNativePlaybackBankCount; ++rank) {
                const std::size_t bank = rank == 0U ? selectedBank : 1U - selectedBank;
                if (laneCounts[bank] > kSongRenderLaneCount) { mSlotCount = 0U; return; }
                if (lane >= laneCounts[bank]) continue;
                mBanks[mSlotCount] = bank;
                mLanes[mSlotCount++] = lane;
            }
        }
    }

    [[nodiscard]] constexpr bool valid() const noexcept {
        return mSelectedBank < kNativePlaybackBankCount && mPullsPerLane != 0U;
    }

    [[nodiscard]] constexpr std::size_t actionCount() const noexcept {
        return valid()
            ? 1U + 2U * static_cast<std::size_t>(mPullsPerLane) *
                mSlotCount
            : 0U;
    }

    [[nodiscard]] constexpr NativePlaybackWorkerServiceAction actionAt(
        std::size_t actionIndex) const noexcept {
        if (!valid() || actionIndex >= actionCount() || actionIndex == 0U ||
            (actionIndex & 1U) == 0U) {
            return {};
        }

        const std::size_t ingressOrdinal = (actionIndex - 1U) / 2U;
        const std::size_t slotInRound = ingressOrdinal % mSlotCount;
        return {
            NativePlaybackWorkerServiceKind::IngressLane,
            mBanks[slotInRound],
            mLanes[slotInRound],
            static_cast<std::uint32_t>(ingressOrdinal / mSlotCount),
        };
    }

private:
    std::size_t mSelectedBank{0};
    std::uint32_t mPullsPerLane{0};
    std::size_t mSlotCount{0U};
    std::array<std::size_t, kNativePlaybackBankCount * kSongRenderLaneCount> mBanks{};
    std::array<std::size_t, kNativePlaybackBankCount * kSongRenderLaneCount> mLanes{};
};

struct NativePlaybackBankIntent final {
    /** Changes whenever bank ownership, timeline assignment, or cache content identity changes. */
    std::uint64_t revision{0};
    bool assigned{false};
    SongRenderOwnership ownership{};
    std::uint64_t firstTimelineFrame{0};
    std::int64_t outputClockStartFrame{0};
    std::uint64_t songIdentityHash{0};
    std::uint64_t originalContentHash{0};
    std::uint64_t stemPackageHash{0};
    std::uint32_t stemLaneCount{0};
    /** Conservative upper bound on this owner's decoded 48 kHz interval; zero uses the supported 600s ceiling. */
    std::uint64_t cacheFrameUpperBound{0};
    /** Exclusive render boundary; zero preserves the physical Original EOS. */
    std::uint64_t playbackEndFrameExclusive{0};
};

struct NativePlaybackAutomaticSuccessorIntent final {
    bool enabled{false};
    bool inheritOutgoingControls{false};
    std::uint64_t revision{0};
    SongRenderOwnership outgoingOwnership{};
    NativePlaybackBank successorBank{NativePlaybackBank::First};
    SongRenderOwnership successorOwnership{};
};

/**
 * Complete desired transport state published by exactly one Kotlin command actor.
 *
 * The mailbox retains only the newest complete snapshot. Intermediate rapid selections may be
 * skipped, but fields from different revisions can never be combined. This is intentional:
 * selection, seek, play, source, mix, tuning, loop and metronome state converge atomically to the
 * latest user intent instead of replaying stale work.
 */
struct NativePlaybackCommandSnapshot final {
    std::uint64_t revision{0};
    /**
     * Monotonic transport epoch. It equals selectedOwnership.generation while selected and still
     * advances for unload/task-removal so queued PCM can be invalidated without a fake song.
     */
    std::uint64_t outputGeneration{0};
    std::array<NativePlaybackBankIntent, kNativePlaybackBankCount> banks{};
    bool selectionAssigned{false};
    NativePlaybackBank selectedBank{NativePlaybackBank::First};
    SongRenderOwnership selectedOwnership{};
    bool playing{false};
    SongRenderControlSnapshot controls{};
    NativePlaybackAutomaticSuccessorIntent automaticSuccessor{};
};

/** Latest-only gain overlay published by the exact selected Stems gesture owner. */
struct NativePlaybackStemGainPreview final {
    std::uint64_t publicationSequence{0};
    /** Highest Kotlin offer represented by this complete overlay state; may repeat on retirement. */
    std::uint64_t coversOfferSequence{0};
    SongRenderOwnership ownership{};
    std::uint64_t sourceIntentRevision{0};
    std::uint64_t stemIntentRevision{0};
    /** Do not apply or retire this overlay before the selected pipeline has canonical revision C. */
    std::uint64_t canonicalControlFloorRevision{0};
    std::uint32_t activeMask{0};
    std::array<float, kStemLaneCount> gains{};
};

struct NativePlaybackBeatGridUpdate final {
    static constexpr std::uint32_t kSchemaVersion = 3;

    std::uint64_t revision{0};
    NativePlaybackBank bank{NativePlaybackBank::First};
    SongRenderOwnership ownership{};
    /** Zero clears the grid. Non-zero updates require at least two strictly increasing frames. */
    std::size_t frameCount{0};
};

struct NativePlaybackRuntimeConfiguration final {
    using CommandPublicationVisibleHook = void (*)(
        void *,
        const NativePlaybackCommandSnapshot &) noexcept;

    static constexpr std::uint32_t kMinimumTargetBursts = 4;
    static constexpr std::uint32_t kMaximumTargetBursts = 8;
    static constexpr std::uint32_t kMaximumIngressPullFrames = 4'096;
    static constexpr std::uint32_t kMaximumPullsPerLanePerCycle = 8;
    static constexpr std::uint32_t kOutputSampleRateHz = 48'000;
    static constexpr std::uint32_t kMinimumOutputReserveMicros = 4'000;
    static constexpr std::uint32_t kMaximumOutputReserveMicros = 64'000;
    static constexpr std::uint32_t kDefaultOutputReserveMicros = 50'000;
    static constexpr std::uint64_t kMicrosPerSecond = 1'000'000;

    SongRenderPipelineConfiguration pipeline{};
    std::uint32_t targetBursts{6};
    /** Time floor applied in addition to the device-dependent burst count. */
    std::uint32_t minimumOutputReserveMicros{kDefaultOutputReserveMicros};
    std::uint32_t fallbackFramesPerBurst{192};
    std::uint32_t ingressPullFrames{1'024};
    std::uint32_t maximumPullsPerLanePerCycle{4};
    std::uint32_t maximumOutputBlocksPerCycle{32};
    std::uint32_t idleSleepMicros{10'000};
    std::string currentSongCacheDirectory{};
    /**
     * Optional non-production interleaving seam invoked after an accepted command becomes
     * mailbox-visible but before publishCommand() completes. Android leaves both values null.
     */
    void *commandPublicationVisibleHookContext{nullptr};
    CommandPublicationVisibleHook commandPublicationVisibleHook{nullptr};

    [[nodiscard]] constexpr std::uint64_t minimumOutputReserveFrames() const noexcept {
        return (
            static_cast<std::uint64_t>(kOutputSampleRateHz) * minimumOutputReserveMicros +
            kMicrosPerSecond - 1U) / kMicrosPerSecond;
    }

    [[nodiscard]] constexpr std::uint64_t desiredOutputFrames(
        std::uint64_t framesPerBurst) const noexcept {
        const std::uint64_t burstReserve = framesPerBurst >
                std::numeric_limits<std::uint64_t>::max() / targetBursts
            ? std::numeric_limits<std::uint64_t>::max()
            : framesPerBurst * targetBursts;
        return burstReserve > minimumOutputReserveFrames()
            ? burstReserve
            : minimumOutputReserveFrames();
    }
};

enum class NativePlaybackPublishStatus : std::uint8_t {
    Accepted,
    StaleRevision,
    InvalidSnapshot,
    Stopped,
};

enum class NativePlaybackRuntimeStatus : std::uint8_t {
    Constructed,
    Running,
    StopRequested,
    Stopped,
};

struct NativePlaybackLaneDiagnostics final {
    std::uint64_t pulls{0};
    std::uint64_t pulledFrames{0};
    std::uint64_t appendedFrames{0};
    std::uint64_t retainedSuffixFrames{0};
    std::uint64_t partialAppends{0};
    std::uint64_t staleAfterPull{0};
    std::uint64_t futureGenerationDeferrals{0};
    std::uint64_t discontinuityRejects{0};
    std::uint64_t endOfStreamMarks{0};
    std::uint64_t endOfStreamFailures{0};
};

struct NativePlaybackBankDiagnostics final {
    std::uint64_t appliedIntentRevision{0};
    bool assigned{false};
    std::uint64_t beginSongCount{0};
    std::uint64_t seekCount{0};
    std::uint64_t rejectedBankIntents{0};
    std::uint64_t beatGridUpdates{0};
    std::uint64_t beatGridClears{0};
    std::array<NativePlaybackLaneDiagnostics, kSongRenderLaneCount> lanes{};
};

struct NativePlaybackRenderLaneReadinessDiagnostics final {
    std::uint64_t generation{0};
    std::uint64_t firstTimelineFrame{0};
    std::uint64_t endTimelineFrameExclusive{0};
    std::uint64_t availableFromRenderStart{0};
    std::uint64_t reservoirGapRejectionCount{0};
    std::uint64_t ingressFailureGeneration{0};
    std::uint64_t ingressActiveGeneration{0};
    std::uint64_t ingressProducerGeneration{0};
    std::uint64_t ingressConsumerGeneration{0};
    std::uint64_t ingressReadableSourceFrames{0};
    std::uint64_t ingressPulls{0};
    std::uint64_t ingressStaleAfterPull{0};
    std::uint64_t ingressFutureGenerationDeferrals{0};
};

/**
 * On-demand worker-published view of the selected pipeline's exact Stems coverage decision.
 * It is deliberately separate from the stable runtime-diagnostics schema.
 */
enum class NativePlaybackLoopPreparationFailureReason : std::uint8_t { None = 0, Pipeline = 1, Lanes = 2 };
struct NativePlaybackLoopPreparationFailure final {
    SongRenderOwnership ownership{};
    std::uint64_t revision{};
    NativePlaybackLoopPreparationFailureReason reason{NativePlaybackLoopPreparationFailureReason::None};
};

struct NativePlaybackRenderReadinessDiagnostics final {
    std::uint64_t sequence{0};
    bool assigned{false};
    NativePlaybackBank bank{NativePlaybackBank::First};
    SongRenderOwnership ownership{};
    std::uint64_t controlRevision{0};
    MixerSourceMode desiredSourceMode{MixerSourceMode::Original};
    std::uint64_t renderStartTimelineFrame{0};
    std::uint64_t pendingControlRevision{0};
    NativeAudioSourceMode pendingActualSourceMode{NativeAudioSourceMode::Original};
    std::uint64_t pendingSourceStartTimelineFrame{0};
    std::uint64_t pendingSourceEndTimelineFrameExclusive{0};
    std::uint64_t pendingFrames{0};
    std::uint64_t outputQueuedFrames{0};
    std::uint64_t stemActivationDeferrals{0};
    std::uint64_t stemSafetyFallbacks{0};
    std::uint64_t needStemDataCount{0};
    std::array<NativePlaybackRenderLaneReadinessDiagnostics, kSongRenderLaneCount> lanes{};
    bool currentSongCacheBoundToSelectedOwner{false};
    std::uint32_t currentSongCacheCompleteLaneMask{0U};
    bool currentSongCacheCanGrow{false};
    std::uint64_t ingressBankConfiguredLaneCount{0};
    std::uint64_t directAtomicPolicyGeneration{0};
    std::uint64_t directAtomicPolicyPublishedRevision{0};
    std::uint64_t directAtomicPolicyAdoptedRevision{0};
    std::uint64_t directAtomicPolicyObservedRevision{0};
    bool fixedLoopHeadPrepared{false};
    NativePlaybackLoopPreparationFailure loopPreparationFailure{};
    std::uint64_t ingressServicePolicyBreaks{0};
    std::uint64_t ingressBankIdentity{0};
    std::uint32_t requiredSongLaneMask{0U};
    CurrentSongCacheStorageStatus cacheStorageStatus{CurrentSongCacheStorageStatus::NotRequested};
};

/** Once-only proof that the committed owner's final accepted PCM has drained while playing. */
struct NativePlaybackAudibleEndEvent final {
    std::uint64_t sequence{0};
    SongRenderOwnership ownership{};
    /** Direct-loop policy current when this terminal decision was made; zero means none. */
    std::uint64_t directLoopPolicyRevision{0};
};

/** Exact whole-device-callback proof, filtered to the runtime's committed owner. */
struct NativePlaybackAudibleControlEvidence final {
    std::uint64_t sequence{0};
    SongRenderOwnership ownership{};
    std::uint64_t controlRevision{0};
    MixerSourceMode actualSourceMode{MixerSourceMode::Original};
    /** Numerical DSP tuple represented by this payload; zero tempo means transition/unknown. */
    float actualTempo{0.0F};
    float actualPitchSemitones{0.0F};
    std::uint64_t firstFingerprintSequence{0};
    std::uint64_t lastFingerprintSequence{0};
    std::uint64_t callbackSequence{0};
    std::uint64_t pcmFrames{0};
};

/**
 * Sibling long-loop protocol state. It is intentionally not appended to the command or runtime
 * diagnostics payloads. Kotlin uses this independently versioned sibling surface so changes do
 * not shift either transport ABI.
 */
struct NativePlaybackLoopProtocolSnapshot final {
    bool adopted{false};
    NativePlaybackBank bank{NativePlaybackBank::First};
    LoopCycleStateSnapshot state{};
    LoopCycleIngressRequest ingressRequest{};
    LoopCycleDirective renderDirective{};
    LoopCycleAudibleEvidence audibleEvidence{};
    LoopHeadCacheSnapshot activeCache{};
    LoopHeadPreparationSnapshot preparation{};
    LoopCycleDiagnostics diagnostics{};
    LoopCycleSubmitStatus lastSubmitStatus{LoopCycleSubmitStatus::InvalidIntent};
    LoopCycleCacheStatus lastCacheStatus{LoopCycleCacheStatus::NotWaiting};
    LoopCycleOriginalEosStatus lastOriginalEosStatus{
        LoopCycleOriginalEosStatus::NotRequired};
    LoopCycleIngressStatus lastIngressStatus{LoopCycleIngressStatus::NoRequest};
    LoopCyclePositionStatus lastPositionStatus{LoopCyclePositionStatus::Disabled};
    LoopCycleBoundaryStatus lastBoundaryStatus{LoopCycleBoundaryStatus::NotActive};
    LoopCycleRenderCommitStatus lastRenderCommitStatus{
        LoopCycleRenderCommitStatus::NoDirective};
    LoopCycleCallbackStatus lastCallbackStatus{LoopCycleCallbackStatus::NoActiveLoop};
    std::uint64_t positionEvidenceSequence{0};
    std::uint64_t lastOutputLoopEvidenceSequence{0};
};

/** Worker-visible lifecycle of one exact automatic drain handoff. */
enum class NativePlaybackAutomaticSuccessorPhase : std::uint8_t {
    Idle = 0,
    Staging = 1,
    DrainCommitted = 2,
    PresentedAwaitingSelection = 3,
    /** Callback adopted the successor while paused, before any successor PCM became audible. */
    AppliedPausedAwaitingSelection = 4,
};

/** Coherent observability for native likely-next PCM staging and callback-role adoption. */
struct NativePlaybackAutomaticSuccessorDiagnostics final {
    NativePlaybackAutomaticSuccessorPhase phase{
        NativePlaybackAutomaticSuccessorPhase::Idle};
    NativeAudioDrainCancelStatus cancellationStatus{
        NativeAudioDrainCancelStatus::NotRequested};
    std::uint64_t revision{0};
    std::uint64_t transitionRevision{0};
    SongRenderOwnership outgoingOwnership{};
    SongRenderOwnership successorOwnership{};
    std::uint64_t successorQueuedFrames{0};
    std::uint64_t firstFingerprintSequence{0};
    bool cancellationDesired{false};
    bool successorEndObserved{false};
};

/** Newest per-generation low-water publication for the committed audible output ring. */
struct NativePlaybackCurrentOutputQueueLowWater final {
    std::uint64_t sequence{0};
    std::uint64_t generation{0};
    std::uint64_t frames{0};
    std::uint64_t monotonicMicros{0};
};

/**
 * Split committed-output wait evidence. The sequence advances only when this reason is observed
 * at a new per-generation current-output queue low; it is not a total render-wait count.
 * Timestamp uses CLOCK_MONOTONIC microseconds.
 */
struct NativePlaybackOutputWaitReasonDiagnostics final {
    std::uint64_t lowWaterEvidenceSequence{0};
    std::uint64_t lastMonotonicMicros{0};
    std::uint64_t lastGeneration{0};
};

/**
 * One ordered, production-adjacent observation from the natural-end/Pause boundary.
 *
 * sequence belongs to one runtime-wide monotonic diagnostic clock. Comparing sequences across
 * the event fields below therefore establishes whether a command was published/consumed, output
 * playing was applied, the active owner physically drained, or audible end was published first.
 */
struct NativePlaybackEndOrderingEvent final {
    std::uint64_t sequence{0};
    /** Worker-state checkpoint supplying the output/EOS/frame fields; self for worker events. */
    std::uint64_t workerStateSequence{0};
    std::uint64_t commandRevision{0};
    SongRenderOwnership ownership{};
    bool desiredPlaying{false};
    bool outputPlaying{false};
    bool pipelineEndObserved{false};
    bool commandUnread{false};
    std::uint64_t pendingOutputFrames{0};
    std::uint64_t queuedOutputFrames{0};
    bool terminalPositionValid{false};
    bool terminalAfterFrame{false};
};

/** Bounded last-event trace; no logging, allocation, callback work, or unbounded history. */
struct NativePlaybackEndOrderingDiagnostics final {
    NativePlaybackEndOrderingEvent commandPublished{};
    NativePlaybackEndOrderingEvent commandConsumed{};
    NativePlaybackEndOrderingEvent pipelineEndObserved{};
    NativePlaybackEndOrderingEvent outputPlayingApplied{};
    NativePlaybackEndOrderingEvent physicalDrainObserved{};
    NativePlaybackEndOrderingEvent audibleEndPublished{};
};

/** Compact control-lane publication; detailed counters and lane history remain on demand. */
struct NativePlaybackReadinessProgress final {
    std::uint64_t sequence{};
    bool assigned{};
    SongRenderOwnership ownership{};
    std::uint64_t controlRevision{};
    bool cacheBound{}, cacheCanGrow{}, fixedLoopHeadPrepared{};
    std::uint32_t completeLaneMask{}, requiredLaneMask{};
    CurrentSongCacheStorageStatus storageStatus{CurrentSongCacheStorageStatus::NotRequested};
    std::uint64_t directAtomicPolicyGeneration{};
    std::int32_t failureLane{-1};
    std::uint64_t failureGeneration{};
    NativePlaybackLoopPreparationFailure loopPreparationFailure{};
};
struct NativePlaybackBankControlProgress final {
    std::uint64_t appliedIntentRevision{};
    bool assigned{};
};
struct NativePlaybackControlProgress final {
    NativePlaybackRuntimeStatus status{NativePlaybackRuntimeStatus::Constructed};
    SongRenderOwnership requestedOwnership{}, acceptedOwnership{}, committedOwnership{};
    std::uint64_t acceptedFirstFingerprintSequence{};
    NativeAudioPresentationEvidence presentationEvidence{};
    NativeAudioPresentedPosition presentedPosition{};
    NativePlaybackAudibleEndEvent audibleEnd{};
    NativePlaybackAudibleControlEvidence audibleControl{};
    NativePlaybackAutomaticSuccessorDiagnostics automaticSuccessor{};
    std::array<NativePlaybackBankControlProgress, kNativePlaybackBankCount> banks{};
    NativePlaybackReadinessProgress readiness{};
};

struct NativePlaybackRuntimeDiagnostics final {
    NativePlaybackRuntimeStatus status{NativePlaybackRuntimeStatus::Constructed};
    /** Process-global evidence; construction is cumulative and live counts current instances. */
    std::uint64_t processConstructionCount{0};
    std::uint64_t processLiveInstanceCount{0};
    /** Process-cumulative hard faults; runtime replacement cannot reset qualification evidence. */
    std::uint64_t processOutputInvalidRenderResults{0};
    std::uint64_t processOutputFlushFailures{0};
    std::uint64_t commandSnapshotsPublished{0};
    std::uint64_t commandSnapshotsConsumed{0};
    std::uint64_t commandSnapshotsSuperseded{0};
    std::uint64_t rejectedCommandSnapshots{0};
    std::uint64_t beatGridUpdatesPublished{0};
    std::uint64_t rejectedBeatGridUpdates{0};
    std::uint64_t renderCycles{0};
    std::uint64_t idleSleeps{0};
    std::uint64_t resourceOperationsCompleted{0};
    std::uint64_t resourceLastOperationMicros{0};
    std::uint64_t resourceMaximumOperationMicros{0};
    std::uint64_t resourceMaximumQueueAgeMicros{0};
    /** Playing/committed render turns exceeding the queued PCM duration at turn entry. */
    std::uint64_t refillDeadlineMisses{0};
    CurrentSongCacheKey canonicalCacheKey{};
    CanonicalPcmIoDiagnostics canonicalPcmIo{};
    std::uint64_t outputFlushes{0};
    std::uint64_t outputFlushFailures{0};
    /** Expected callback-acknowledgement backpressure before an empty flush can publish. */
    std::uint64_t outputFlushRetries{0};
    std::uint64_t outputHandoffBegins{0};
    std::uint64_t outputHandoffBeginRetries{0};
    std::uint64_t outputHandoffCommits{0};
    std::uint64_t outputHandoffCommitRetries{0};
    std::uint64_t outputBlocksRendered{0};
    std::uint64_t outputFramesRendered{0};
    std::uint64_t outputFramesAccepted{0};
    std::uint64_t outputPartialAccepts{0};
    std::uint64_t outputBackpressureEvents{0};
    std::uint64_t outputInvalidRenderResults{0};
    std::uint64_t outputNeedDataEvents{0};
    std::uint64_t outputEndOfStreamEvents{0};
    std::uint64_t targetQueuedFrames{0};
    std::uint64_t lastOutputFingerprintSequence{0};
    std::uint64_t lastRenderPayloadHash{0};
    SongRenderOwnership requestedOwnership{};
    SongRenderOwnership acceptedOwnership{};
    SongRenderOwnership committedOwnership{};
    std::uint64_t acceptedFirstFingerprintSequence{0};
    /**
     * Render-worker scheduling evidence. Android requests nice -16 (THREAD_PRIORITY_AUDIO) before
     * entering the work loop; the requested/applied split and errno keep rejection observable.
     */
    bool renderWorkerStarted{false};
    bool renderWorkerAudioPriorityRequested{false};
    bool renderWorkerAudioPriorityApplied{false};
    std::int32_t renderWorkerNiceValue{0};
    std::int32_t renderWorkerPriorityErrorCode{0};
    NativePlaybackCurrentOutputQueueLowWater currentOutputQueueLowWater{};
    NativePlaybackOutputWaitReasonDiagnostics needOriginalData{};
    NativePlaybackOutputWaitReasonDiagnostics needStemData{};
    NativePlaybackOutputWaitReasonDiagnostics loopBoundaryPending{};
    NativeAudioPresentationEvidence presentationEvidence{};
    NativeAudioPresentedPosition presentedPosition{};
    NativePlaybackAudibleEndEvent audibleEnd{};
    NativePlaybackEndOrderingDiagnostics endOrdering{};
    NativePlaybackAudibleControlEvidence audibleControl{};
    NativePlaybackAutomaticSuccessorDiagnostics automaticSuccessor{};
    std::array<NativePlaybackBankDiagnostics, kNativePlaybackBankCount> banks{};
};

/**
 * One fixed-resource native transport/render coordinator.
 *
 * The class owns two bank pipelines and one spare loop continuation pipeline and one non-real-time render worker. It
 * borrows two already-configured SongLaneIngressBank objects and one output port. Lane producers
 * may run concurrently. Kotlin may lazily configure published lanes, but it must first publish an
 * unassigned bank intent and observe diagnostics for that exact applied revision with
 * assigned=false before releasing any lane. join() is the stronger whole-runtime lifetime fence.
 * The output consumer never enters this class: it consumes only final PCM accepted by the output.
 */
class NativePlaybackRuntime final {
public:
    NativePlaybackRuntime(
        const NativePlaybackRuntimeConfiguration &configuration,
        SongLaneIngressBank &firstBank,
        SongLaneIngressBank &secondBank,
        NativePlaybackOutputPort output);
    ~NativePlaybackRuntime();

    NativePlaybackRuntime(const NativePlaybackRuntime &) = delete;
    NativePlaybackRuntime &operator=(const NativePlaybackRuntime &) = delete;
    NativePlaybackRuntime(NativePlaybackRuntime &&) = delete;
    NativePlaybackRuntime &operator=(NativePlaybackRuntime &&) = delete;

    /** Move sealed PCM from a joined predecessor before this replacement starts. */
    [[nodiscard]] bool inheritCompletedSongCache(NativePlaybackRuntime &previous) noexcept;
    [[nodiscard]] bool start() noexcept;
    void requestStop() noexcept;
    void join() noexcept;

    [[nodiscard]] NativePlaybackPublishStatus publishCommand(
        const NativePlaybackCommandSnapshot &snapshot) noexcept;

    [[nodiscard]] NativePlaybackPublishStatus publishStemGainPreview(
        const NativePlaybackStemGainPreview &preview) noexcept;

    /** Copies into fixed staging storage; caller memory may be released after this returns. */
    [[nodiscard]] NativePlaybackPublishStatus publishBeatGrid(
        const NativePlaybackBeatGridUpdate &update,
        const std::int64_t *beatFrames,
        const std::uint8_t *accentFlags) noexcept;

    /** One serialized controller actor publishes stable loop identity through this sibling ABI. */
    [[nodiscard]] NativePlaybackPublishStatus publishLoopCycleIntent(
        const LoopCycleIntent &intent) noexcept;

    /** Immediate five-field A-B policy; cache/readiness remains on the managed sibling lane. */
    [[nodiscard]] NativePlaybackPublishStatus publishDirectLoopPolicy(
        std::uint64_t revision,
        std::uint64_t selectionToken,
        std::uint64_t generation,
        bool enabled,
        std::uint64_t aTimelineFrame,
        std::uint64_t bTimelineFrameExclusive,
        std::uint64_t inputEventAtMonotonicMicros = 0U,
        bool boundaryArmedAtPublication = false,
        bool boundaryCrossedAtPublication = false,
        std::uint64_t heldBGestureId = 0U,
        std::uint64_t capturedBTimelineFrameExclusive = 0U,
        std::int64_t captureReferenceTimelineFrame = -1) noexcept;

    /** Decoder-ready evidence is validated on the native render worker against its exact request. */
    [[nodiscard]] NativePlaybackPublishStatus publishLoopIngressReady(
        const LoopCycleIngressReadyEvidence &evidence) noexcept;

    /** One serialized preparation caller owns begin/configure/offer/EOS/abort; render never waits. */
    [[nodiscard]] LoopHeadPreparationBeginStatus beginLoopHeadPreparation(
        const LoopHeadContentKey &key,
        std::uint32_t requiredLaneMask,
        LoopHeadBoundaryMode boundaryMode = LoopHeadBoundaryMode::OriginalEos,
        SongRenderOwnership ownership = {}) noexcept;
    [[nodiscard]] LoopHeadPreparationConfigureStatus configureLoopHeadPreparationLane(
        std::uint64_t loopRevision,
        std::size_t laneIndex,
        std::uint32_t sourceRateHz,
        std::uint64_t preparationGeneration) noexcept;
    [[nodiscard]] LoopHeadPreparationOfferResult offerLoopHeadPreparationPcm(
        std::uint64_t loopRevision,
        std::size_t laneIndex,
        const LaneInputBlock &block) noexcept;
    [[nodiscard]] LoopHeadPreparationEndStatus markLoopHeadPreparationEndOfStream(
        std::uint64_t loopRevision,
        std::size_t laneIndex,
        std::uint64_t preparationGeneration,
        std::uint64_t finalSourceFrameExclusive) noexcept;
    [[nodiscard]] LoopHeadPreparationAbortStatus abortLoopHeadPreparation(
        std::uint64_t loopRevision) noexcept;

    [[nodiscard]] NativePlaybackLoopProtocolSnapshot loopProtocolSnapshot() const noexcept;

    /**
     * Explicit worker wake after externally owned ingress or output evidence changes.
     *
     * Running playback polls on its device deadline. A globally paused runtime parks, so an
     * external actor that publishes progress while paused must call this after the publication.
     */
    void notifyIngressAvailable() noexcept;

    /**
     * Blocks a non-real-time observer until the render worker publishes newer whole-callback
     * audible-control evidence, or returns zero when this runtime stops. The output consumer never
     * enters this wait or wakes Java directly.
     */
    [[nodiscard]] std::uint64_t waitForAudibleControlProgress(
        std::uint64_t afterSequence) noexcept;

    /** Latest committed-owner whole-callback proof for one exact stem gain overlay. */
    [[nodiscard]] NativeAudioStemGainCallbackEvidence
        stemGainCallbackSettlementDiagnostics() const noexcept;

    void setDiagnosticPayloadFingerprints(bool enabled) noexcept;
    [[nodiscard]] NativePlaybackRuntimeStatus status() const noexcept;
    [[nodiscard]] NativePlaybackAudibleControlEvidence audibleControlEvidence() const noexcept;
    [[nodiscard]] NativePlaybackControlProgress controlProgress() const noexcept;
    [[nodiscard]] NativePlaybackRuntimeDiagnostics diagnostics() const noexcept;
    [[nodiscard]] NativePlaybackRenderReadinessDiagnostics
        renderReadinessDiagnostics() noexcept;
    [[nodiscard]] std::uint64_t waitPcmCacheProgress(std::uint64_t after) noexcept;
    [[nodiscard]] std::uint64_t beginPcmWindowRead(const CurrentSongCacheKey &, std::size_t lane,
        std::uint64_t firstFrame, std::size_t frames) noexcept;
    [[nodiscard]] int pollPcmWindowRead(std::uint64_t ticket, float *samples, std::size_t frames) noexcept;
    void cancelPcmWindowRead(std::uint64_t ticket) noexcept;
    [[nodiscard]] bool readPcmCacheForAudit(const CurrentSongCacheKey &, std::size_t lane,
        std::uint64_t firstFrame, float *samples, std::size_t frames) noexcept;
    [[nodiscard]] bool pcmCacheRangeReady(const CurrentSongCacheKey &, std::uint32_t laneMask,
        std::uint64_t firstFrame, std::uint64_t endFrame) noexcept;

private:
    class Impl;
    std::unique_ptr<Impl> mImpl;
};

}  // namespace stemwise::audio::engine
