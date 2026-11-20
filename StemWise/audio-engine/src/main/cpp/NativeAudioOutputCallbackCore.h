#pragma once

#include "NativePacketSchema.h"
#include "GuardedAtomicSnapshot.h"
#include "NativeAudioOutputLoopDemandRetirement.h"
#include "NativeAudioOutputLoopDiagnostics.h"
#include "NativeAudioOutputCounters.h"
#include "AudioTrace.h"

#include "AudibleTimelineHistory.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <bit>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <exception>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <chrono>

#include "FinalPcmFrameTag.h"
#include "SpscTaggedMultibusRingBuffer.h"
#include "LateStemMixControlMailbox.h"
#include "ManualSelectionRenderGate.h"
#include "NativeAudioCallbackEvidence.h"
#include "StemGainCallbackPublication.h"
#include "NativeAudioOutputCallbackDiagnostics.h"
#include "NativeAudioOutputBridge.h"
#include "NativeAudioOutputFinalDeviceStage.h"
#include "NativeAudioOutputLoopBoundaryGate.h"
#include "NativeAudioOutputMultibusCallbackStage.h"
#include "OutputGenerationTransition.h"
#include "OutputCallbackOrchestrator.h"
#include "OutputRingRoleCoordinator.h"
#include "PresentationSpanJournal.h"
#include "StreamLifecycleCoordinator.h"
#include "PresentedPositionPublication.h"
#include "NativePushPresentationHistory.h"

namespace stemwise::audio {
using engine::NativeRenderBusSubmission;

constexpr std::size_t kChannelCount = 2U;
constexpr std::int32_t kDiagnosticsSchemaVersion = packet::OUTPUT_DIAGNOSTICS_VERSION;
constexpr std::size_t kDiagnosticsFieldCount = packet::OUTPUT_DIAGNOSTICS_COUNT;
constexpr std::size_t kPresentationJournalCapacity = 4'096U;
constexpr std::int32_t kPresentationJournalSchemaVersion = packet::PRESENTATION_JOURNAL_VERSION;
constexpr std::size_t kPresentationJournalHeaderFieldCount = packet::PRESENTATION_JOURNAL_HEADER;
constexpr std::size_t kPresentationJournalEntryFieldCount = packet::PRESENTATION_JOURNAL_ENTRY;

[[nodiscard]] constexpr std::uint32_t lateMixerSampleRate(
    std::int32_t requestedSampleRate) noexcept {
    return requestedSampleRate >= 8'000 && requestedSampleRate <= 384'000
        ? static_cast<std::uint32_t>(requestedSampleRate)
        : 48'000U;
}

[[nodiscard]] inline OutputTimelineLoopPolicy timelineLoopPolicy(
    const NativeAudioOutputLoopPolicy *policy) noexcept {
    if (policy == nullptr || !policy->physicalReady) return {};
    return {
        .generation = policy->generation,
        .selectionToken = policy->selectionToken,
        .physicalRevision = policy->physicalRevision(),
        .enabled = policy->enabled,
        .aTimelineFrame = policy->aTimelineFrame,
        .bTimelineFrameExclusive = policy->bTimelineFrameExclusive,
    };
}

/** Shared ring, role, callback and evidence owner; the adapter owns physical stream operations. */
class NativeAudioOutputCallbackCore {
public:
    using PushTransportSilenceKind = stemwise::audio::PushTransportSilenceKind;
private:
    [[nodiscard]] static AudioMemoryFootprint pushStorageFootprint(std::size_t frames, std::size_t blockFrames) {
        const auto history = NativePushPresentationHistory::storageFootprint(frames, blockFrames);
        const auto scratch = audioMemoryBytes(blockFrames,
            3U * kChannelCount * sizeof(float) + sizeof(FinalPcmFrameTag) + sizeof(NativePushPresentationFrame));
        return {addAudioMemoryBytes(history.minimumBytes, scratch), addAudioMemoryBytes(history.peakBytes, scratch)};
    }
    [[nodiscard]] static std::size_t admitBaseStorage(std::size_t frames, std::uint64_t availableBytes) {
        storageFootprint(frames).admit(availableBytes);
        return frames;
    }
    using LoopDemandDiagnostics = NativeAudioOutputLoopDiagnostics::LoopDemandDiagnostics;
    using LoopCallbackDiagnostics = NativeAudioOutputLoopDiagnostics::LoopCallbackDiagnostics;
    struct CallbackSpan final {
        TaggedStereoReadResult read{};
        std::uint64_t generation{0};
        std::uint32_t ring{0};
        std::size_t outputOffset{0};
        bool valid{false};
        bool terminalAfterFrame{false};
        std::uint64_t sourceEpoch{};
    };
    struct CallbackEvidenceContext {
        float *output;
        const std::size_t requestedFrames;
        const std::size_t requestedSamples;
        std::array<CallbackSpan, 3U> spans{};
        std::size_t spanCount{0U}, physicalFrames{0U}, validPcmFrames{0U};
        bool qualification{false};
        NativeAudioPresentationSpan *mappingScratch{nullptr};
    };
    struct CallbackContext final : CallbackEvidenceContext {
        CallbackContext(float *pcm, std::size_t frames, std::size_t samples, std::int64_t start) noexcept
            : CallbackEvidenceContext{pcm, frames, samples}, callbackStartMicros(start) {}
        const std::int64_t callbackStartMicros;
        std::size_t generationTransitionOffset{std::numeric_limits<std::size_t>::max()};
#if defined(STEMWISE_CACHE_IO_PROFILING)
        AudioTrace trace{"StemWise.audio.callback"};
#endif
    };
    struct CallbackLoopPlan final {
        NativeAudioOutputLoopPolicy prepared{}, captured{};
        OutputRoleSwap claimedSwap{};
        bool candidateRealizesDemand{false}, capturedBoundaryPending{false};
        std::uint64_t capturedEnforcedBoundary{0U};
    };
    struct QualificationCapture final {
        PresentationSpanJournal<kPresentationJournalCapacity> journal{};
        std::unique_ptr<NativeAudioPresentationSpan[]> scratch, pending, submitted;
        QualificationCapture(std::size_t capacity, std::size_t block)
            : scratch(std::make_unique<NativeAudioPresentationSpan[]>(capacity)),
              pending(std::make_unique<NativeAudioPresentationSpan[]>(block)),
              submitted(std::make_unique<NativeAudioPresentationSpan[]>(capacity)) {}
    };
    using PushFrame = NativePushPresentationFrame;
    struct PushOperation final {
        std::atomic_flag &flag;
        bool acquired;
        explicit PushOperation(std::atomic_flag &value) noexcept
            : flag(value), acquired(!flag.test_and_set(std::memory_order_acquire)) {}
        ~PushOperation() { if (acquired) flag.clear(std::memory_order_release); }
    };
    struct PushGenerationCompletion final {
        NativeAudioOutputCallbackCore *owner;
        std::uint64_t generation;
        bool accepted{false};
        ~PushGenerationCompletion() { if (owner != nullptr) owner->completePushGenerationChange(generation, accepted); }
    };
public:
    struct PushOutputBlock final {
        const float *samples{nullptr};
        std::size_t frames{0U};
        std::uint64_t sequence{0U};
        std::size_t offset{0U};
        bool terminal{false};
    };

    [[nodiscard]] static AudioMemoryFootprint storageFootprint(std::size_t frames, std::size_t blockFrames = 0U) {
        const auto fixed = addAudioMemoryBytes(sizeof(NativeAudioOutputCallbackCore),
            addAudioMemoryBytes(AudibleTimelineHistory::storageBytes(frames), audioMemoryBytes(frames, sizeof(OutputTimelineFrameTag))));
        AudioMemoryFootprint result{
            addAudioMemoryBytes(fixed, audioMemoryBytes(2U, SpscTaggedMultibusRingBuffer::storageBytes(frames, false))),
            addAudioMemoryBytes(fixed, audioMemoryBytes(2U, SpscTaggedMultibusRingBuffer::storageBytes(frames, true)))};
        if (blockFrames != 0U) {
            const auto push = pushStorageFootprint(frames, blockFrames);
            result.minimumBytes = addAudioMemoryBytes(result.minimumBytes, push.minimumBytes);
            result.peakBytes = addAudioMemoryBytes(result.peakBytes, push.peakBytes);
        }
        return result;
    }

    [[nodiscard]] std::size_t metadataAllocatedBytes() const noexcept {
        return mFirstMultibusRing.metadataAllocatedBytes() + mSecondMultibusRing.metadataAllocatedBytes() +
            mAudibleTimelineHistory.allocatedBytes() + mCallbackTimelineCapacity * sizeof(OutputTimelineFrameTag) +
            (mPushHistory ? mPushHistory->allocatedBytes() + mPushBlockCapacity *
                (sizeof(FinalPcmFrameTag) + sizeof(PushFrame)) : 0U);
    }

private:
    [[nodiscard]] std::size_t unallocatedPeakBytes(std::size_t blockFrames) const {
        const auto allocated = sizeof(NativeAudioOutputCallbackCore) + metadataAllocatedBytes() +
            2U * mCallbackTimelineCapacity * SpscTaggedMultibusRingBuffer::kPayloadStride * sizeof(float) +
            (mPushHistory ? sizeof(NativePushPresentationHistory) +
                3U * mPushBlockCapacity * kChannelCount * sizeof(float) : 0U);
        const auto peak = storageFootprint(mCallbackTimelineCapacity, blockFrames).peakBytes;
        return peak > allocated ? peak - allocated : 0U;
    }

public:

    // AudioTrack push storage. Configuration and retirement run only off the consumer.
    bool configurePushConsumer(std::size_t maximumBlockFrames) {
        auto lock = mStreamLifecycle.acquire();
        if (mPushPcm || mClosed.load(std::memory_order_acquire) ||
            maximumBlockFrames == 0U || maximumBlockFrames > mCallbackTimelineCapacity)
            return false;
        const auto pendingBytes = unallocatedPeakBytes(maximumBlockFrames);
        AudioMemoryFootprint{pendingBytes, pendingBytes}.admit(mMemoryCapacityProbe());
        mPushPcm = std::make_unique<float[]>(maximumBlockFrames * kChannelCount);
        mPushFinalTags = std::make_unique<FinalPcmFrameTag[]>(maximumBlockFrames);
        mPushPendingEvidence = std::make_unique<PushFrame[]>(maximumBlockFrames);
        mPushHistory = std::make_unique<NativePushPresentationHistory>(mCallbackTimelineCapacity, maximumBlockFrames);
        mPushObservationPcm = std::make_unique<float[]>(maximumBlockFrames * kChannelCount);
        mPushWritePcm = std::make_unique<float[]>(maximumBlockFrames * kChannelCount);
        mPushBlockCapacity = maximumBlockFrames;
        mPushObservedNext = mPushPresentationFloor = mCallbackFrames.load(std::memory_order_acquire);
        mPushEnabled.store(true, std::memory_order_release);
        return true;
    }
    bool beginPushConsumer() noexcept {
        auto lock = mStreamLifecycle.acquire();
        PushOperation operation(mPushOperation);
        if (!operation.acquired || !mPushEnabled.load(std::memory_order_acquire) || mClosed.load()) return false;
        mPushConsumerActive.store(true, std::memory_order_release);
        return true;
    }
    // Caller has acknowledged a parked feeder, including completion of its last sink write.
    bool quiescePushConsumer() noexcept {
        auto lock = mStreamLifecycle.acquire();
        PushOperation operation(mPushOperation);
        if (!operation.acquired) return false;
        mPushConsumerActive.store(false, std::memory_order_release);
        return true;
    }
    std::uint64_t pushSubmittedFrames() const noexcept { return mCallbackFrames.load(std::memory_order_acquire); }
    // Sole feeder, after its last write receipt. This fences source access, not the live silence consumer.
    bool beginPushSourceTransition(std::uint64_t generation) noexcept {
        PushOperation operation(mPushOperation);
        const auto previous = mPushSourceTransition.load(std::memory_order_acquire);
        if (!operation.acquired || !mPushConsumerActive.load(std::memory_order_acquire) || mClosed.load() ||
            generation <= mRoles.activeGeneration() || (previous != 0U && generation < previous)) return false;
        mPushTransportSequence = 0U;
        mPushHistory->releaseTransport();
        mPushTransportRemaining = 0U;
        mPushSourceTransition.store(generation, std::memory_order_release);
        return true;
    }
    std::uint64_t preparePushTransportSilence(PushTransportSilenceKind kind) noexcept {
        PushOperation operation(mPushOperation);
        if (!operation.acquired || !mPushConsumerActive.load(std::memory_order_acquire) || mClosed.load()) return 0U;
        // Retirement must progress even when the sink cannot yet accept another frame.
        static_cast<void>(acknowledgeCancellation());
        const auto transition = mPushSourceTransition.load(std::memory_order_acquire);
        const bool authorized = kind == PushTransportSilenceKind::LogicalPause
            ? !mPlaying.load(std::memory_order_acquire) && transition == 0U
            : kind == PushTransportSilenceKind::SourceTransition && transition != 0U;
        if (!authorized) return 0U;
        mEffectiveRenderPlaying.store(false, std::memory_order_release);
        mPushTransportSequence = 0U;
        mPushHistory->releaseTransport();
        mPushTransportRemaining = 0U;
        const auto sourceRemaining = mPushPendingFrames - mPushPendingOffset;
        const auto outstanding = pushSubmittedFrames() - mPushObservedNext;
        if (sourceRemaining > mCallbackTimelineCapacity - mPushBlockCapacity ||
            outstanding > mCallbackTimelineCapacity - mPushBlockCapacity - sourceRemaining) return 0U;
        mPushTransportSequence = nextPushTicket();
        if (mPushTransportSequence == 0U) return 0U;
        if (!mPushHistory->stageTransport(kind, mPushTransportSequence)) {
            mPushTransportSequence = 0U;
            return 0U;
        }
        mPushTransportRemaining = mPushBlockCapacity;
        mPushTransportKind = kind;
        return mPushTransportSequence;
    }
    /** Renders from immutable held source; only the later accepted prefix advances the edge. */
    bool preparePushWrite(float *pcm, std::size_t frames, bool transport) noexcept {
        // Source commits hold this lifecycle too; their brief ownership is not a sink failure.
        auto lock = mStreamLifecycle.acquire();
        PushOperation operation(mPushOperation);
        if (!operation.acquired || !pcm || frames == 0U || frames > mPushBlockCapacity) return false;
        mPushWriteTransitionStart = mPushWriteTransition;
        if (transport != mPushLastAcceptedTransport) {
            mPushWriteTransitionStart.begin(mLastActuallyEmittedLeft, mLastActuallyEmittedRight,
                OutputGenerationTransition::framesForSampleRate(mActualSampleRate.load(std::memory_order_relaxed)));
        }
        auto transition = mPushWriteTransitionStart;
        mPushWriteTransitionFrames = transition.apply(pcm, frames);
        std::copy_n(pcm, frames * kChannelCount, mPushWritePcm.get());
        mPushWriteFrames = frames;
        mPushWriteTransport = transport;
        return true;
    }
    void acceptPushWrite(std::size_t frames) noexcept {
        if (frames == 0U) return;
        mPushWriteTransition = mPushWriteTransitionStart;
        mPushWriteTransition.advance(frames);
        mPushLastAcceptedTransport = mPushWriteTransport;
        if (frames != 0U) {
            mLastActuallyEmittedLeft = mPushWritePcm[(frames - 1U) * kChannelCount];
            mLastActuallyEmittedRight = mPushWritePcm[(frames - 1U) * kChannelCount + 1U];
        }
        mPushWriteFrames = 0U;
    }
    bool acceptPushTransportSilence(std::uint64_t sequence, std::size_t frames) noexcept {
        auto lock = mStreamLifecycle.acquire();
        PushOperation operation(mPushOperation);
        if (!operation.acquired || !mPushConsumerActive.load(std::memory_order_acquire) || mClosed.load() ||
            sequence == 0U || sequence != mPushTransportSequence || frames > mPushTransportRemaining ||
            (mPushWriteFrames != 0U && (!mPushWriteTransport || frames > mPushWriteFrames))) return false;
        const auto first = pushSubmittedFrames();
        if (frames > std::numeric_limits<std::uint64_t>::max() - first ||
            first - mPushObservedNext > mCallbackTimelineCapacity - frames) return false;
        const OutputTimelineFrameTag silence{};
        for (std::size_t index = 0U; index < frames; ++index) {
            mPushHistory->acceptTransport(first + index);
            mAudibleTimelineHistory.publish(first + index, &silence, 1U);
        }
        mPushTransportRemaining -= frames;
        if (mPushTransportRemaining == 0U) mPushHistory->releaseTransport();
        mCallbackFrames.fetch_add(frames, std::memory_order_release);
        if (mPushWriteFrames != 0U) acceptPushWrite(frames);
        else if (frames != 0U) mLastActuallyEmittedLeft = mLastActuallyEmittedRight = 0.0F;
        return true;
    }
    PushOutputBlock preparePushBlock(std::size_t frames) noexcept {
        PushOperation operation(mPushOperation);
        if (!operation.acquired || !mPushConsumerActive.load(std::memory_order_acquire) ||
            mClosed.load(std::memory_order_acquire) || mClosing.load(std::memory_order_acquire) ||
            !mPlaying.load(std::memory_order_acquire) || mPushSourceTransition.load(std::memory_order_acquire) != 0U ||
            frames == 0U || frames > mPushBlockCapacity) return {};
        CallbackLoopPlan loopPlan{};
        const CallbackLoopPlan *preparedLoopPlan = nullptr;
        struct ReleaseLoopClaim final {
            OutputRingRoleCoordinator &roles;
            CallbackLoopPlan &plan;
            ~ReleaseLoopClaim() {
                if (plan.claimedSwap.valid()) static_cast<void>(roles.releaseCallbackLoopSwap(plan.claimedSwap));
            }
        } releaseLoopClaim{mRoles, loopPlan};
        if (mPushPendingFrames == mPushPendingOffset) {
            // Control acknowledgement never grants PCM acceptance or presentation authority.
            static_cast<void>(acknowledgeCancellation());
            std::size_t sourceBoundaryOffset = std::numeric_limits<std::size_t>::max();
            static_cast<void>(adoptRoleSwap(sourceBoundaryOffset,
                mRoles.callbackSwap(mAppliedCommitSequence, outputRingQueuedFrames(mCallbackRing) == 0U, true), 0U));
            static_cast<void>(adoptLatestDirectLoopDemand());
            refreshExplicitLoopDrainFence(renderDecisionPosition().generation,
                renderDecisionPosition().selectionToken, renderDecisionPosition().lastTimelineFrame);
            reconcileLateTerminalBoundary();
            const auto publishedLoop = mRoles.publishedLoopCutover(mAppliedCommitSequence);
            // A drain with an unread outgoing prefix retains its existing mixed-owner transaction.
            if (!mRoles.commitAwaitingAcknowledgement() || publishedLoop.valid()) {
                loopPlan = selectCallbackLoopPlan();
                if (loopPlan.candidateRealizesDemand && mManualSelectionRenderGate.allowsRender(
                        mCallbackGeneration, outputRingQueuedFrames(mCallbackRing) != 0U)) {
                    loopPlan.claimedSwap = mRoles.callbackLoopSwap(mAppliedCommitSequence,
                        mCallbackGeneration, mCallbackLoopCandidate.physicalRevision(), true);
                }
                preparedLoopPlan = &loopPlan;
                // The push writer can wait for source refill. Use the same selected policy,
                // exact boundary scan and pinned role that rendering will consume.
                if (!pushSourceReady(loopPlan, frames)) return {};
            }
        }
        mPushTransportSequence = 0U;
        mPushHistory->releaseTransport();
        mPushTransportRemaining = 0U;
        if (mPushPendingFrames == mPushPendingOffset) {
            const auto submitted = pushSubmittedFrames();
            if (submitted > std::numeric_limits<std::uint64_t>::max() - frames) return {};
            if (submitted - mPushObservedNext > mCallbackTimelineCapacity - frames)
                return {nullptr, 0U, mPushBlockSequence, 0U, mPushTerminal};
            mPushPendingOffset = 0U;
            mPushPendingFrames = frames;
            mPushBlockSequence = nextPushTicket();
            if (mPushBlockSequence == 0U) return {};
            std::fill_n(mPushFinalTags.get(), frames, FinalPcmFrameTag{});
            renderBlock(mPushPcm.get(), static_cast<std::int32_t>(frames), preparedLoopPlan);
        }
        if (!mPushHistory->stageSource(mPushPendingEvidence.get(), mPushPendingFrames, mPushBlockSequence)) return {};
        return {mPushPcm.get() + mPushPendingOffset * kChannelCount,
            mPushPendingFrames - mPushPendingOffset, mPushBlockSequence, mPushPendingOffset, mPushTerminal};
    }
    bool acceptPushPrefix(std::uint64_t sequence, std::size_t frames) noexcept {
        auto lock = mStreamLifecycle.acquire();
        PushOperation operation(mPushOperation);
        if (!operation.acquired || !mPushConsumerActive.load(std::memory_order_acquire) ||
            mClosed.load(std::memory_order_acquire) || sequence == 0U || sequence != mPushBlockSequence ||
            mPushSourceTransition.load(std::memory_order_acquire) != 0U ||
            frames > mPushPendingFrames - mPushPendingOffset ||
            (mPushWriteFrames != 0U && (mPushWriteTransport || frames > mPushWriteFrames))) return false;
        const auto first = pushSubmittedFrames();
        if (first - mPushObservedNext > mCallbackTimelineCapacity - frames) return false;
        for (std::size_t index = 0U; index < frames; ++index) {
            auto evidence = mPushPendingEvidence[mPushPendingOffset + index];
            evidence.outputFrame = first + index;
            if (mPushWriteFrames != 0U) {
                evidence.pcm = {mPushWritePcm[index * kChannelCount], mPushWritePcm[index * kChannelCount + 1U]};
                if (index < mPushWriteTransitionFrames) evidence.eligible = false;
            }
            if (evidence.qualification) {
                auto *capture = mQualificationCapture.load(std::memory_order_acquire);
                capture->submitted[(first + index) % mCallbackTimelineCapacity] = capture->pending[mPushPendingOffset + index];
            }
            mPushHistory->acceptSource(mPushPendingOffset + index, evidence);
            if (evidence.valid) mPushLastAcceptedSource = evidence.tag;
        }
        mAudibleTimelineHistory.publish(first, mCallbackTimelineScratch.get() + mPushPendingOffset, frames);
        mPushPendingOffset += frames;
        if (mPushPendingOffset == mPushPendingFrames) mPushHistory->releaseSource();
        mCallbackFrames.fetch_add(frames, std::memory_order_release);
        if (mPushWriteFrames != 0U) acceptPushWrite(frames);
        else if (frames != 0U) {
            mLastActuallyEmittedLeft = mPushPcm[(mPushPendingOffset - 1U) * kChannelCount];
            mLastActuallyEmittedRight = mPushPcm[(mPushPendingOffset - 1U) * kChannelCount + 1U];
        }
        return true;
    }
    std::int64_t prepareSourceTransition(std::uint64_t outgoingGeneration, std::uint64_t token,
        std::uint64_t targetGeneration, std::int64_t pausedFallbackFrame) noexcept {
        if (outgoingGeneration == 0U || token == 0U || targetGeneration <= outgoingGeneration ||
            pausedFallbackFrame < -1) return -1;
        {
            auto lock = mStreamLifecycle.acquire();
            PushOperation operation(mPushOperation);
            if (!operation.acquired || mClosed.load(std::memory_order_acquire) ||
                mRoles.activeGeneration() != outgoingGeneration ||
                (mPushLastAcceptedSource.generation == outgoingGeneration
                    ? mPushLastAcceptedSource.selectionToken != token
                    : pausedFallbackFrame < 0 || mPlaying.load(std::memory_order_acquire))) return -1;
        }
        if (!preparePushGenerationChange(targetGeneration)) return -1;
        auto lock = mStreamLifecycle.acquire();
        PushOperation operation(mPushOperation);
        if (!operation.acquired || mClosed.load(std::memory_order_acquire) ||
            mRoles.activeGeneration() != outgoingGeneration ||
            (mPushConsumerActive.load(std::memory_order_acquire) &&
                mPushSourceTransition.load(std::memory_order_acquire) != targetGeneration)) return -1;
        if (mPushLastAcceptedSource.generation == outgoingGeneration)
            return mPushLastAcceptedSource.selectionToken == token ? mPushLastAcceptedSource.nextTimelineFrame : -1;
        return pausedFallbackFrame;
    }
    bool cancelPushSourceTransition(std::uint64_t generation) noexcept {
        PushOperation operation(mPushOperation);
        if (!operation.acquired || generation == 0U || generation <= mRoles.activeGeneration()) return false;
        const auto pending = mPushSourceTransition.load(std::memory_order_acquire);
        if (pending != 0U && pending != generation) return false;
        mPushSourceTransition.store(0U, std::memory_order_release);
        return true;
    }
    // A hardware observation never grants authority to an unaccepted frame.
    bool observePushPresentation(std::uint64_t lastOutputFrame) noexcept {
        PushOperation operation(mPushOperation);
        if (!operation.acquired || !mPushEnabled.load(std::memory_order_acquire) ||
            lastOutputFrame >= pushSubmittedFrames() || lastOutputFrame < mPushPresentationFloor) return false;
        if (lastOutputFrame < mPushObservedNext) return lastOutputFrame + 1U == mPushObservedNext;
        while (mPushObservedNext <= lastOutputFrame) {
            const auto evidence = mPushHistory->at(mPushObservedNext);
            if (evidence.outputFrame != mPushObservedNext) return false;
            if (!evidence.valid) { mPushHistory->retire(mPushObservedNext, 1U); ++mPushObservedNext; continue; }
            auto *capture = evidence.qualification ? mQualificationCapture.load(std::memory_order_acquire) : nullptr;
            const NativeAudioPresentationSpan emptyMapping{};
            const auto &firstMapping = capture ? capture->submitted[mPushObservedNext % mCallbackTimelineCapacity] : emptyMapping;
            std::size_t count = 1U;
            while (count < mPushBlockCapacity && mPushObservedNext + count <= lastOutputFrame) {
                const auto previous = mPushHistory->at(mPushObservedNext + count - 1U);
                const auto &nextMapping = capture ? capture->submitted[(mPushObservedNext + count) % mCallbackTimelineCapacity] : emptyMapping;
                if (previous.terminal || !mPushHistory->samePresentationRun(mPushObservedNext, mPushObservedNext + count) ||
                    nextMapping.renderFrameCount != firstMapping.renderFrameCount ||
                    nextMapping.renderSourceStartFrame != firstMapping.renderSourceStartFrame ||
                    nextMapping.renderSourceEndFrameExclusive != firstMapping.renderSourceEndFrameExclusive ||
                    nextMapping.mappingFirstRenderFrameOffset != firstMapping.mappingFirstRenderFrameOffset ||
                    (evidence.qualification && nextMapping.firstRenderFrameOffset != firstMapping.firstRenderFrameOffset + count)) break;
                ++count;
            }
            const auto last = mPushHistory->at(mPushObservedNext + count - 1U);
            mPushHistory->copyPcm(mPushObservedNext, count, mPushObservationPcm.get());
            auto mapping = firstMapping;
            mapping.pcmFrames = count;
            if (capture) {
                const auto &lastMapping = capture->submitted[(mPushObservedNext + count - 1U) % mCallbackTimelineCapacity];
                mapping.lastTimelineFrame = lastMapping.lastTimelineFrame;
                mapping.lastNextTimelineFrame = lastMapping.lastNextTimelineFrame;
            }
            CallbackEvidenceContext context{mPushObservationPcm.get(), count, count * kChannelCount};
            context.validPcmFrames = context.physicalFrames = count;
            context.spanCount = 1U;
            context.qualification = evidence.qualification;
            context.mappingScratch = evidence.qualification ? &mapping : nullptr;
            context.spans[0U] = {
                .read = {.frameCount = count, .absoluteFirstFrame = evidence.sourceWriteBoundary - 1U,
                    .firstTag = evidence.tag, .lastTag = last.tag,
                    .audibleControlTupleCoherent = evidence.control, .audibleTuningTupleCoherent = evidence.tuning,
                    .audibleStemGainTupleCoherent = evidence.stem, .audibleLoopTupleCoherent = evidence.loop,
                    .continuityViolationCount = evidence.continuityViolations},
                .generation = evidence.tag.generation, .ring = evidence.ring,
                .valid = true, .terminalAfterFrame = last.terminal || exactTerminalBoundary(
                    evidence.ring, last.tag, last.sourceWriteBoundary, last.sourceEpoch) != 0U,
                .sourceEpoch = last.sourceEpoch,
            };
            publishCallbackEvidence(context, {{}, evidence.eligible}, evidence.sequence, mPushObservedNext);
            mTotalFramesPresented.fetch_add(count, std::memory_order_relaxed);
            mPushHistory->retire(mPushObservedNext, count);
            mPushObservedNext += count;
        }
        reconcileLateTerminalBoundary();
        return true;
    }
    bool discardPushPending(bool discardSubmitted = false) noexcept {
        auto lock = mStreamLifecycle.acquire();
        PushOperation operation(mPushOperation);
        if (!operation.acquired || mPushConsumerActive.load(std::memory_order_acquire)) return false;
        mPushPendingFrames = mPushPendingOffset = 0U;
        if (mPushHistory) { mPushHistory->releaseSource(); mPushHistory->releaseTransport(); }
        mPushTerminal = false;
        mPushBlockSequence = 0U;
        mPushTransportSequence = 0U;
        mPushTransportRemaining = 0U;
        mPushSourceTransition.store(0U, std::memory_order_release);
        mPushRenderedPosition = {};
        if (discardSubmitted) {
            mPushLastAcceptedSource = {};
            if (mPushHistory) mPushHistory->discardAccepted();
            mPushObservedNext = mPushPresentationFloor = pushSubmittedFrames();
            mPresentedPosition.reset();
        }
        return true;
    }
    void setDiagnosticPayloadFingerprints(bool enabled) noexcept {
        if (enabled && mQualificationCapture.load(std::memory_order_acquire) == nullptr) {
            try {
                const auto blockFrames = mPushBlockCapacity == 0U ? mCallbackTimelineCapacity : mPushBlockCapacity;
                const auto bytes = addAudioMemoryBytes(sizeof(QualificationCapture),
                    audioMemoryBytes(addAudioMemoryBytes(audioMemoryBytes(2U, mCallbackTimelineCapacity), blockFrames),
                        sizeof(NativeAudioPresentationSpan)));
                const auto pendingBytes = addAudioMemoryBytes(bytes, unallocatedPeakBytes(mPushBlockCapacity));
                AudioMemoryFootprint{pendingBytes, pendingBytes}.admit(mMemoryCapacityProbe());
                auto allocated = std::make_unique<QualificationCapture>(mCallbackTimelineCapacity,
                    mPushBlockCapacity == 0U ? mCallbackTimelineCapacity : mPushBlockCapacity);
                QualificationCapture *empty = nullptr;
                if (mQualificationCapture.compare_exchange_strong(empty, allocated.get(),
                        std::memory_order_acq_rel, std::memory_order_acquire)) static_cast<void>(allocated.release());
            } catch (...) { saturatingIncrement(mUnavailableQualificationCapture); }
        }
        mQualificationDiagnostics.store(enabled, std::memory_order_release);
    }
    [[nodiscard]] virtual bool setPlaying(bool playing) noexcept = 0;
    [[nodiscard]] virtual bool quiesceIfPaused() noexcept = 0;
    [[nodiscard]] virtual NativeAudioAudiblePosition audiblePosition() const noexcept = 0;
    NativeAudioOutputCallbackCore(
        std::size_t ringCapacityFrames,
        std::int32_t requestedSampleRate,
        std::uint64_t initialGeneration,
        std::uint64_t (*memoryCapacityProbe)() noexcept = &availableAudioMemoryBytes)
        : mFirstMultibusRing(admitBaseStorage(ringCapacityFrames, memoryCapacityProbe ? memoryCapacityProbe() : 0U)),
          mSecondMultibusRing(ringCapacityFrames),
          mFirstMultibusStage(lateMixerSampleRate(requestedSampleRate)),
          mSecondMultibusStage(lateMixerSampleRate(requestedSampleRate)),
          mAudibleTimelineHistory(ringCapacityFrames),
          mCallbackTimelineScratch(std::make_unique<OutputTimelineFrameTag[]>(ringCapacityFrames)),
          mCallbackTimelineCapacity(ringCapacityFrames),
          mRequestedSampleRate(requestedSampleRate),
          mRoles(initialGeneration),
          mCallbackGeneration(initialGeneration),
          mAppliedGeneration(initialGeneration), mMemoryCapacityProbe(memoryCapacityProbe) {
        saturatingIncrement(gOutputConstructionCount);
        saturatingIncrement(gOutputLiveInstanceCount);
    }

    NativeAudioOutputCallbackCore(const NativeAudioOutputCallbackCore &) = delete;
    NativeAudioOutputCallbackCore &operator=(const NativeAudioOutputCallbackCore &) = delete;

    virtual ~NativeAudioOutputCallbackCore() {
        if (mPushEnabled.load(std::memory_order_acquire) &&
            (mPushConsumerActive.load(std::memory_order_acquire) ||
                mPushOperation.test_and_set(std::memory_order_acquire))) std::terminate();
        delete mQualificationCapture.load(std::memory_order_relaxed);
        gOutputLiveInstanceCount.fetch_sub(1U, std::memory_order_acq_rel);
    }

    std::size_t enqueueRenderBusTimeline(const NativeRenderBusSubmission &submission) noexcept {
        if (submission.frameCount == 0U || submission.samples == nullptr || submission.generation == 0U ||
            submission.selectionToken == 0U || submission.payloadSequence == 0U ||
            submission.canonicalControlRevision == 0U || submission.sourceIntentRevision == 0U ||
            submission.stemIntentRevision == 0U ||
            (submission.stemBusReadyMask & ~kStemwiseAllStemBusesReadyMask) != 0U ||
            (submission.loopEnabled && submission.loopRevision == 0U) ||
            mClosed.load(std::memory_order_acquire)) {
            return 0U;
        }
        const OutputRoleTarget writeTarget =
            mRoles.writableRingForGeneration(submission.generation);
        if (!writeTarget.valid()) {
            mStaleFramesRejected.fetch_add(submission.frameCount, std::memory_order_relaxed);
            return 0U;
        }
        const RenderBusFrameTag tag{
            .generation = submission.generation,
            .selectionToken = submission.selectionToken,
            .payloadSequence = submission.payloadSequence,
            .payloadHash = submission.payloadHash,
            .canonicalControlRevision = submission.canonicalControlRevision,
            .sourceIntentRevision = submission.sourceIntentRevision,
            .stemIntentRevision = submission.stemIntentRevision,
            .stemBusReadyMask = submission.stemBusReadyMask,
            .actualTempo = submission.actualTempo,
            .actualPitchSemitones = submission.actualPitchSemitones,
            .renderSourceStartFrame = submission.renderSourceStartFrame,
            .renderSourceEndFrameExclusive = submission.sourceEndFrameExclusive,
            .renderFrameCount = submission.renderFrameCount,
            .renderFrameOffset = submission.renderFrameOffset,
            .mappingFirstRenderFrameOffset = submission.renderFrameOffset,
            .loopPolicyRevision = submission.loopPolicyRevision,
            .loopRevision = submission.loopEnabled ? submission.loopRevision : 0U,
            .loopCycle = submission.loopEnabled ? submission.loopCycle : 0U,
            .loopEnabled = submission.loopEnabled && submission.loopRevision != 0U,
        };
        if (!lateControlCoversRenderTag(submission.baseControl, tag) ||
            !managedLoopContextCoversRenderTag(submission.managedLoopContext, tag)) return 0U;
        const std::size_t accepted = multibusRing(writeTarget.ring).writeTaggedTimeline(
            submission.samples,
            submission.frameCount,
            tag,
            submission.sourceStartFrame,
            submission.sourceEndFrameExclusive,
            submission.endOfStreamAfterInput,
            &submission.baseControl,
            &submission.managedLoopContext);
        mTotalFramesAccepted.fetch_add(accepted, std::memory_order_relaxed);
        mBackpressureFrames.fetch_add(submission.frameCount - accepted, std::memory_order_relaxed);
        if (accepted != 0U) {
            recordSuccessfulEnqueue(
                submission.generation,
                accepted,
                multibusRing(writeTarget.ring).queuedFrames());
        }
        return accepted;
    }

    [[nodiscard]] bool publishLateMixControl(
        const NativeAudioLateMixControl &control) noexcept {
        if (!control.valid() || mClosed.load(std::memory_order_acquire)) {
            return false;
        }
        const OutputRoleTarget target = mRoles.ringForGeneration(control.generation);
        if (!target.valid() || target.generation != control.generation) return false;
        // The output stream outlives an administratively stopped render runtime. A recreated
        // runtime therefore restarts its local publication counter while the role mailbox keeps
        // its stale-publication high-water mark. Stamp the mailbox publication in the output
        // owner's process-monotonic namespace so a legitimate same-process rebind cannot be
        // mistaken for an old control snapshot.
        if (mLateControlPublicationSequence ==
            std::numeric_limits<std::uint64_t>::max()) {
            return false;
        }
        auto snapshot = control;
        const OutputRoleTarget loopStage = mRoles.loopStagingTarget(control.generation);
        const auto publishToRole = [this, &control, &snapshot](
                                       OutputRoleTarget role,
                                       bool allowInactiveRoleRebind) noexcept {
            if (!role.valid() || role.generation != control.generation ||
                mLateControlPublicationSequence ==
                    std::numeric_limits<std::uint64_t>::max()) {
                return false;
            }
            if (mLateControlBoundGeneration[role.ring] != control.generation ||
                mLateControlBoundToken[role.ring] != control.selectionToken) {
                if (!mLateControlMailbox.resetRole(
                        role.ring,
                        control.generation,
                        control.selectionToken,
                        allowInactiveRoleRebind)) {
                    return false;
                }
                mLateControlBoundGeneration[role.ring] = control.generation;
                mLateControlBoundToken[role.ring] = control.selectionToken;
            }
            snapshot.publicationSequence = ++mLateControlPublicationSequence;
            return mLateControlMailbox.publish(role.ring, snapshot);
        };
        if (!publishToRole(target, false)) return false;
        return !loopStage.valid() || loopStage.ring == target.ring ||
            publishToRole(loopStage, true);
    }

    [[nodiscard]] bool publishLoopPolicy(
        const NativeAudioOutputLoopPolicy &policy) noexcept {
        if (!policy.valid() || mClosed.load(std::memory_order_acquire)) {
            return false;
        }
        const OutputRoleTarget target = mRoles.ringForGeneration(policy.generation);
        if (!target.valid() || target.generation != policy.generation) return false;
        return publishAcceptedLoopPolicy(policy);
    }

    void retireLoopDemand(std::uint64_t revision, std::uint64_t generation,
        std::uint64_t token) noexcept {
        mRetiredLoopDemand.retire(revision, generation, token);
    }

    [[nodiscard]] bool publishLoopDemand(
        const NativeAudioOutputLoopPolicy &policy,
        bool boundaryCrossedAtPublication,
        std::uint64_t capturedBTimelineFrameExclusive) noexcept {
        if (!policy.valid() || policy.physicalReady ||
            (boundaryCrossedAtPublication &&
             (!policy.enabled || !policy.boundaryArmedAtPublication ||
              capturedBTimelineFrameExclusive <= policy.aTimelineFrame)) ||
            (!boundaryCrossedAtPublication && capturedBTimelineFrameExclusive != 0U) ||
            mClosed.load(std::memory_order_acquire)) {
            return false;
        }
        // Demand may target a command-accepted generation whose ring is not staged yet. Fence an
        // outgoing automatic successor before exposing the demand; the callback retains future
        // generations in the mailbox until their exact role becomes active.
        const NativeAudioPresentedPosition presented = presentedPosition();
        const bool exactPresentedOwner =
            presented.generation == policy.generation &&
            presented.selectionToken == policy.selectionToken;
        const std::int64_t presentedFrame = exactPresentedOwner
            ? presented.lastTimelineFrame
            : -1;
        LoopCallbackDiagnostics latestCutover{};
        if (mLoopDiagnostics.tryLoopCallbackDiagnostics(latestCutover)) {
            mProducerLoopCallbackDiagnostics = latestCutover;
        }
        const LoopCallbackDiagnostics &cutover = mProducerLoopCallbackDiagnostics;
        const bool captureBoundary = mLoopGestureCaptureGate.shouldCapture(
            policy,
            boundaryCrossedAtPublication,
            presented.generation,
            presented.selectionToken,
            presentedFrame,
            presented.callbackSequence,
            NativeAudioOutputLoopCaptureEvidence{
                .revision = cutover.latestCutoverCaptureRevision,
                .callbackSequence = cutover.latestCutoverCallbackSequence,
                .generation = cutover.latestCutoverGeneration,
                .selectionToken = cutover.latestCutoverSelectionToken,
                .aTimelineFrame = cutover.latestCutoverAFrame,
                .heldBGestureId = cutover.latestCutoverGestureId,
            });
        NativeAudioOutputLoopPolicy effectivePolicy = policy;
        if (captureBoundary) effectivePolicy.boundaryArmedAtPublication = true;
        NativeAudioOutputLoopPolicy capturedPolicy = effectivePolicy;
        if (boundaryCrossedAtPublication) {
            capturedPolicy.bTimelineFrameExclusive = capturedBTimelineFrameExclusive;
        }
        if (captureBoundary && capturedPolicy.captureReferenceTimelineFrame < 0 &&
            presentedFrame >= 0 && static_cast<std::uint64_t>(presentedFrame) >=
                capturedPolicy.bTimelineFrameExclusive) {
            // Retain the exact crossed cursor if the old producer wraps before A is ready.
            capturedPolicy.captureReferenceTimelineFrame = presentedFrame;
        }
        // The callback may clear a dormant demand's drain fence from exact PCM evidence. Bracket
        // fence + mailbox publication so that an older callback snapshot cannot clear a newer
        // finger update in the middle of this transaction.
        beginGuardedPublication(mLoopDemandPublicationGuard);
        const bool previousFence =
            mRoles.setExplicitLoopDrainFence(nativeAudioOutputLoopDemandBlocksDrain(
                effectivePolicy,
                presented.generation,
                presented.selectionToken,
                presentedFrame));
        bool published = false;
        if (captureBoundary && !mLoopCaptureMailbox.publish(capturedPolicy)) {
            mLoopGestureCaptureGate.retryCapture(policy.revision);
            static_cast<void>(mRoles.setExplicitLoopDrainFence(previousFence));
        } else if (!mLoopDemandMailbox.publish(effectivePolicy)) {
            if (captureBoundary) mLoopGestureCaptureGate.retryCapture(policy.revision);
            static_cast<void>(mRoles.setExplicitLoopDrainFence(previousFence));
        } else {
            mLoopDiagnostics.recordLoopDemandPublication(
                effectivePolicy,
                presentedFrame,
                captureBoundary,
                captureBoundary && !boundaryCrossedAtPublication,
                capturedPolicy.bTimelineFrameExclusive);
            published = true;
        }
        mLoopDemandPublicationGuard.fetch_add(1U, std::memory_order_release);
        // Latest-value conflation is correct for the visible B position, but not for a fast drag
        // that briefly crosses the live cursor and moves ahead again before the next callback.
        // The controller publishes that crossing as a one-shot edge; callback position can lag
        // one quantum and must never be used to reconstruct or overwrite it here.
        return published;
    }

    [[nodiscard]] NativeAudioPendingLoopCapture pendingLoopCapture(
        const NativeAudioOutputLoopPolicy &demand) const noexcept {
        if (!demand.valid() || demand.physicalReady || mClosed.load(std::memory_order_acquire)) return {};
        const auto before = mLoopDemandPublicationGuard.load(std::memory_order_acquire);
        if ((before & 1U) != 0U) return {};
        const auto latest = mLoopDiagnostics.loopDemandDiagnostics();
        NativeAudioOutputLoopPolicy captured{};
        if (latest.latestDemandRevision != demand.revision || latest.latestDemandAFrame != demand.aTimelineFrame ||
            latest.latestDemandBFrameExclusive != demand.bTimelineFrameExclusive ||
            latest.latestDemandGestureId != demand.heldBGestureId ||
            (latest.acceptedCaptureCount != 0U && (!mLoopCaptureMailbox.tryRead(captured) ||
                captured.revision != latest.latestCaptureRevision))) return {};
        const auto retired = mRetiredCallbackLoopCaptureRevision.load(std::memory_order_acquire);
        if (before != mLoopDemandPublicationGuard.load(std::memory_order_acquire)) return {};
        const bool pending = demand.enabled && captured.revision > retired &&
            captured.enabled && captured.generation == demand.generation &&
            captured.selectionToken == demand.selectionToken && captured.aTimelineFrame == demand.aTimelineFrame &&
            captured.heldBGestureId == demand.heldBGestureId && captured.revision <= demand.revision;
        return {true, pending ? captured.revision : 0U};
    }

    [[nodiscard]] NativeAudioLoopBoundaryCoverage loopBoundaryCoverage(
        std::uint64_t generation,
        std::uint64_t selectionToken,
        std::uint64_t cutoverTimelineFrameExclusive,
        std::uint64_t expectedLoopPolicyRevision) const noexcept {
        if (generation == 0U || selectionToken == 0U ||
            cutoverTimelineFrameExclusive == 0U) {
            return {};
        }
        const OutputRoleTarget target = mRoles.ringForGeneration(generation);
        if (!target.valid()) return {};
        const std::size_t queued = outputRingQueuedFrames(target.ring);
        if (queued == 0U) return {};
        const NativeAudioPresentedPosition presented = presentedPosition();
        const bool exactPresentedOwner =
            presented.generation == generation &&
            presented.selectionToken == selectionToken;
        bool reached = false;
        std::uint64_t boundaryRevision = 0U;
        const std::size_t prefix = taggedFramesBeforeLoopBoundary(
            multibusRing(target.ring),
            generation,
            selectionToken,
            cutoverTimelineFrameExclusive,
            queued,
            reached,
            true,
            exactPresentedOwner ? presented.lastTimelineFrame : -1,
            expectedLoopPolicyRevision,
            &boundaryRevision);
        return {
            .boundaryQueued = reached,
            .unreadPrefixFrames = prefix,
            .queuedFrames = queued,
            .boundaryLoopPolicyRevision = boundaryRevision,
        };
    }

    [[nodiscard]] bool publishAcceptedLoopPolicy(
        const NativeAudioOutputLoopPolicy &policy) noexcept {
        return mLoopPolicyMailbox.publish(policy);
    }

    bool beginLoopCutover(
        std::uint64_t generation,
        std::uint64_t loopPolicyRevision) noexcept {
        if (mClosed.load(std::memory_order_acquire)) return false;
        const OutputHandoffBeginResult result = mRoles.beginLoopCutover(
            generation,
            loopPolicyRevision);
        if (!result.accepted()) return false;
        resetInactiveOutputRing(result.stagingRing);
        return true;
    }

    NativeAudioDrainCancelStatus cancelLoopCutover(
        std::uint64_t generation,
        std::uint64_t loopPolicyRevision) noexcept {
        if (mClosed.load(std::memory_order_acquire)) {
            return NativeAudioDrainCancelStatus::NoMatchingDrain;
        }
        const OutputRoleTarget target = mRoles.loopStagingTarget(
            generation,
            loopPolicyRevision);
        const OutputDrainCancelStatus status = mRoles.cancelLoopCutover(
            generation,
            loopPolicyRevision);
        if (status == OutputDrainCancelStatus::AcceptedOpenStage && target.valid()) {
            resetInactiveOutputRing(target.ring);
        }
        switch (status) {
            case OutputDrainCancelStatus::AcceptedOpenStage:
                return NativeAudioDrainCancelStatus::CancelledOpenStage;
            case OutputDrainCancelStatus::AcceptedAwaitingCallback:
            case OutputDrainCancelStatus::AlreadyRequested:
                return NativeAudioDrainCancelStatus::CancellationRequested;
            case OutputDrainCancelStatus::TooLate:
                return NativeAudioDrainCancelStatus::AdoptionInFlight;
            case OutputDrainCancelStatus::NoMatchingDrain:
                return mRoles.activeGeneration() == generation
                    ? NativeAudioDrainCancelStatus::OutgoingActive
                    : NativeAudioDrainCancelStatus::NoMatchingDrain;
        }
        return NativeAudioDrainCancelStatus::NoMatchingDrain;
    }

    std::size_t enqueueLoopCutoverTimeline(const NativeRenderBusSubmission &submission) noexcept {
        if (submission.frameCount == 0U || submission.samples == nullptr || submission.generation == 0U ||
            submission.selectionToken == 0U || submission.payloadSequence == 0U ||
            submission.canonicalControlRevision == 0U || submission.sourceIntentRevision == 0U ||
            submission.stemIntentRevision == 0U || submission.loopPolicyRevision == 0U ||
            (submission.stemBusReadyMask & ~kStemwiseAllStemBusesReadyMask) != 0U ||
            (submission.loopEnabled && submission.loopRevision == 0U) ||
            mClosed.load(std::memory_order_acquire)) {
            return 0U;
        }
        const OutputRoleTarget target = mRoles.loopStagingTarget(
            submission.generation,
            submission.loopPolicyRevision);
        if (!target.valid()) return 0U;
        const RenderBusFrameTag tag{
            .generation = submission.generation,
            .selectionToken = submission.selectionToken,
            .payloadSequence = submission.payloadSequence,
            .payloadHash = submission.payloadHash,
            .canonicalControlRevision = submission.canonicalControlRevision,
            .sourceIntentRevision = submission.sourceIntentRevision,
            .stemIntentRevision = submission.stemIntentRevision,
            .stemBusReadyMask = submission.stemBusReadyMask,
            .actualTempo = submission.actualTempo,
            .actualPitchSemitones = submission.actualPitchSemitones,
            .renderSourceStartFrame = submission.renderSourceStartFrame,
            .renderSourceEndFrameExclusive = submission.sourceEndFrameExclusive,
            .renderFrameCount = submission.renderFrameCount,
            .renderFrameOffset = submission.renderFrameOffset,
            .mappingFirstRenderFrameOffset = submission.renderFrameOffset,
            .loopPolicyRevision = submission.loopPolicyRevision,
            .loopRevision = submission.loopEnabled ? submission.loopRevision : 0U,
            .loopCycle = submission.loopEnabled ? submission.loopCycle : 0U,
            .loopEnabled = submission.loopEnabled && submission.loopRevision != 0U,
        };
        if (!lateControlCoversRenderTag(submission.baseControl, tag) ||
            !managedLoopContextCoversRenderTag(submission.managedLoopContext, tag)) return 0U;
        const std::size_t accepted = multibusRing(target.ring).writeTaggedTimeline(
            submission.samples,
            submission.frameCount,
            tag,
            submission.sourceStartFrame,
            submission.sourceEndFrameExclusive,
            submission.endOfStreamAfterInput,
            &submission.baseControl,
            &submission.managedLoopContext);
        mTotalFramesAccepted.fetch_add(accepted, std::memory_order_relaxed);
        mBackpressureFrames.fetch_add(submission.frameCount - accepted, std::memory_order_relaxed);
        if (accepted != 0U) {
            recordSuccessfulEnqueue(
                submission.generation,
                accepted,
                multibusRing(target.ring).queuedFrames());
        }
        return accepted;
    }

    NativeAudioLoopCutoverCommitStatus commitLoopCutover(
        const NativeAudioOutputLoopPolicy &policy) noexcept {
        if (!policy.valid() || !policy.hasPhysicalCutover() ||
            mClosed.load(std::memory_order_acquire)) {
            return NativeAudioLoopCutoverCommitStatus::Retired;
        }
        if (mRoles.commitAwaitingAcknowledgement()) {
            return NativeAudioLoopCutoverCommitStatus::Retry;
        }
        const OutputRoleTarget target = mRoles.loopStagingTarget(
            policy.generation,
            policy.physicalRevision());
        const std::size_t queued = target.valid()
            ? outputRingQueuedFrames(target.ring)
            : 0U;
        const std::size_t callbackRequestCeiling = loopCutoverRequiredFrames();
        if (!target.valid()) return NativeAudioLoopCutoverCommitStatus::Retired;
        RenderBusFrameTag head{};
        if (!multibusRing(target.ring).peekInactiveHead(head) ||
            head.generation != policy.generation ||
            head.selectionToken != policy.selectionToken ||
            head.loopPolicyRevision != policy.physicalRevision() ||
            head.timelineFrame != static_cast<std::int64_t>(
                 policy.continuationTimelineFrame)) {
            return NativeAudioLoopCutoverCommitStatus::Retired;
        }
        const bool continuationLoopEnabled = policy.enabled &&
            policy.continuationTimelineFrame < policy.bTimelineFrameExclusive;
        if (head.loopEnabled != continuationLoopEnabled ||
             (continuationLoopEnabled
                  ? head.loopRevision != policy.physicalRevision()
                  : head.loopRevision != 0U)) {
            return NativeAudioLoopCutoverCommitStatus::Retired;
        }
        if (queued < callbackRequestCeiling) {
            RenderBusFrameTag tail{};
            if (continuationLoopEnabled || !multibusRing(target.ring).peekInactiveTail(tail) ||
                tail.generation != policy.generation || tail.selectionToken != policy.selectionToken ||
                tail.loopPolicyRevision != policy.physicalRevision() || tail.loopEnabled) {
                return NativeAudioLoopCutoverCommitStatus::Retry;
            }
            const auto terminal = multibusRing(target.ring).terminalBoundary();
            if (tail.terminalWriteBoundary != multibusRing(target.ring).writePosition() &&
                !(terminal.generation == tail.generation && terminal.selectionToken == tail.selectionToken &&
                  terminal.physicalRevision == tail.loopPolicyRevision && terminal.writePosition != 0U &&
                  tail.nextTimelineFrame == static_cast<std::int64_t>(terminal.sourceEnd)))
                return NativeAudioLoopCutoverCommitStatus::Retry;
        }
        // Store the complete immutable continuation tuple before the coordinator's release
        // publication. The callback first acquires that exact role revision, then reads this
        // revision-keyed policy; it can therefore observe neither a role without B/A nor B/A
        // without a claimable prepared role.
        if (!mCommittedLoopCutoverPolicy.publish(policy)) {
            return NativeAudioLoopCutoverCommitStatus::Retired;
        }
        const OutputHandoffStatus status = mRoles.commitLoopCutover(
            policy.generation,
            policy.physicalRevision(),
            queued);
        if (status == OutputHandoffStatus::AwaitingAcknowledgement) {
            return NativeAudioLoopCutoverCommitStatus::Retry;
        }
        if (status != OutputHandoffStatus::Accepted) {
            return NativeAudioLoopCutoverCommitStatus::Retired;
        }
        // The dormant spare is already published with its physical role above. It must not
        // overwrite an in-place expansion before the callback adopts that active-ring policy.
        if (!policy.nextRevisionOnly) {
            static_cast<void>(publishAcceptedLoopPolicy(policy));
        }
        return NativeAudioLoopCutoverCommitStatus::Committed;
    }

    [[nodiscard]] bool releaseLoopCutoverWrites(
        std::uint64_t generation,
        std::uint64_t loopPolicyRevision) noexcept {
        return !mClosed.load(std::memory_order_acquire) &&
            mRoles.releaseLoopCutoverWrites(generation, loopPolicyRevision);
    }

    bool beginHandoff(std::uint64_t newGeneration) noexcept {
        if (mClosed.load(std::memory_order_acquire)) return false;
        const OutputHandoffBeginResult result = mRoles.begin(newGeneration);
        if (!result.accepted()) return false;
        resetInactiveOutputRing(result.stagingRing);
        return true;
    }

    // Called only by the callback or after the stream is physically stopped.
    void resetCallbackLoopOwnerState() noexcept {
        mCallbackRecovery.reset();
        mCallbackLoopPolicy = {};
        mHasCallbackLoopPolicy = false;
        mCallbackLoopDemand = {};
        mHasCallbackLoopDemand = false;
        mCallbackLoopCandidate = {};
        mHasCallbackLoopCandidate = false;
        mRetiredCallbackLoopCandidateRevision = 0U;
        mRetiredCallbackLoopCaptureRevision.store(0U, std::memory_order_release);
        mPushRenderedPosition = {};
    }

    // Lifecycle mutex held, after the stream has physically stopped. A commit can race Pausing
    // and outlive the final callback; complete that exact immediate publication here.
    void adoptHandoffAfterPhysicalPause() noexcept {
        if (mPushConsumerActive.load(std::memory_order_acquire)) return;
        PushOperation operation(mPushOperation);
        if (!operation.acquired) return;
        mPhysicalRunRestartPending.store(true, std::memory_order_release);
        static_cast<void>(adoptHandoffWithSourceExcluded());
    }

private:
    bool adoptHandoffWithSourceExcluded(std::uint64_t expectedGeneration = 0U) noexcept {
        const OutputRoleSwap roleSwap = mRoles.callbackSwap(mAppliedCommitSequence);
        if (!roleSwap.valid() || roleSwap.expectedActiveGeneration != mCallbackGeneration ||
            (expectedGeneration != 0U && roleSwap.generation != expectedGeneration) ||
            !mRoles.commitCallbackSwap(roleSwap)) return false;
        mCallbackRing = roleSwap.ring;
        mCallbackGeneration = roleSwap.generation;
        resetCallbackLoopOwnerState();
        mAppliedCommitSequence = roleSwap.sequence;
        mRoles.acknowledge(roleSwap);
        mAppliedGeneration.store(mCallbackGeneration, std::memory_order_release);
        mControlEvidenceContinuity = false;
        mStemGainEvidenceContinuity = false;
        mLoopEvidenceContinuity = false;
        return true;
    }

public:
    bool commitHandoff(std::uint64_t generation) noexcept {
        if (mClosed.load(std::memory_order_acquire)) return false;
        const auto preparedTarget = mRoles.ringForGeneration(generation);
        if (!preparedTarget.valid() || outputRingQueuedFrames(preparedTarget.ring) == 0U) return false;
        const bool pushChange = mPushEnabled.load(std::memory_order_acquire) && generation != mRoles.activeGeneration();
        if (pushChange &&
            !preparePushGenerationChange(generation)) return false;
        PushGenerationCompletion completion{pushChange ? this : nullptr, generation};
        const OutputRoleTarget target = mRoles.ringForGeneration(generation);
        if (!target.valid()) return false;
        const std::uint32_t targetRing = target.ring;
        auto lock = mStreamLifecycle.acquire();
        if (mPushSourceTransition.load(std::memory_order_acquire) == generation) {
            PushOperation operation(mPushOperation);
            if (!operation.acquired) return false;
            const auto status = mRoles.commit(generation, outputRingQueuedFrames(targetRing));
            if (status != OutputHandoffStatus::Accepted && status != OutputHandoffStatus::AwaitingAcknowledgement)
                return false;
            if (!adoptHandoffWithSourceExcluded(generation)) return false;
            retirePushPendingSource();
            mCallbackPlaying = false;
            mEffectiveRenderPlaying.store(false, std::memory_order_release);
            mPushSourceTransition.store(0U, std::memory_order_release);
            completion.accepted = true;
            return true;
        }
        if (mRoles.commit(generation, outputRingQueuedFrames(targetRing)) != OutputHandoffStatus::Accepted)
            return false;
        if (!mPushConsumerActive.load(std::memory_order_acquire)) {
            // Before first Play or after a confirmed physical pause, no callback can acknowledge
            // this owner. Adopt it on the producer while requestStart() is excluded; live
            // callback handoffs remain callback-owned.
            adoptHandoffAfterPhysicalPause();
            if (mCallbackRing != targetRing || mCallbackGeneration != generation) {
                return false;
            }
        }
        completion.accepted = true;
        return true;
    }

    bool beginDrainHandoff(
        std::uint64_t expectedActiveGeneration,
        std::uint64_t stagedGeneration,
        std::uint64_t transitionRevision) noexcept {
        if (mClosed.load(std::memory_order_acquire)) return false;
        const OutputHandoffBeginResult result = mRoles.beginDrain(
            expectedActiveGeneration,
            stagedGeneration,
            transitionRevision);
        if (!result.accepted()) return false;
        resetInactiveOutputRing(result.stagingRing);
        return true;
    }

    bool commitDrainHandoff(
        std::uint64_t expectedActiveGeneration,
        std::uint64_t stagedGeneration,
        std::uint64_t transitionRevision) noexcept {
        if (mClosed.load(std::memory_order_acquire)) return false;
        const OutputRoleTarget target = mRoles.ringForGeneration(stagedGeneration);
        if (!target.valid()) return false;
        return mRoles.commitDrain(
            expectedActiveGeneration,
            stagedGeneration,
            transitionRevision,
            outputRingQueuedFrames(target.ring)) == OutputHandoffStatus::Accepted;
    }

    NativeAudioDrainCancelStatus cancelDrainHandoff(
        std::uint64_t expectedActiveGeneration,
        std::uint64_t stagedGeneration,
        std::uint64_t transitionRevision) noexcept {
        if (mClosed.load(std::memory_order_acquire)) {
            return NativeAudioDrainCancelStatus::NoMatchingDrain;
        }
        const OutputRoleTarget target = mRoles.ringForGeneration(stagedGeneration);
        const OutputDrainCancelStatus status = mRoles.cancelDrain(
            expectedActiveGeneration,
            stagedGeneration,
            transitionRevision);
        if (status == OutputDrainCancelStatus::AcceptedOpenStage && target.valid()) {
            resetInactiveOutputRing(target.ring);
        }
        switch (status) {
            case OutputDrainCancelStatus::AcceptedOpenStage:
                return NativeAudioDrainCancelStatus::CancelledOpenStage;
            case OutputDrainCancelStatus::AcceptedAwaitingCallback:
            case OutputDrainCancelStatus::AlreadyRequested:
                return NativeAudioDrainCancelStatus::CancellationRequested;
            case OutputDrainCancelStatus::TooLate:
                return mRoles.activeGeneration() == stagedGeneration
                    ? NativeAudioDrainCancelStatus::SuccessorActive
                    : NativeAudioDrainCancelStatus::AdoptionInFlight;
            case OutputDrainCancelStatus::NoMatchingDrain: {
                const std::uint64_t active = mRoles.activeGeneration();
                if (active == stagedGeneration) {
                    return NativeAudioDrainCancelStatus::SuccessorActive;
                }
                if (active == expectedActiveGeneration) {
                    return NativeAudioDrainCancelStatus::OutgoingActive;
                }
                return NativeAudioDrainCancelStatus::NoMatchingDrain;
            }
        }
        return NativeAudioDrainCancelStatus::NoMatchingDrain;
    }

    NativeAudioFlushStatus flushToGenerationStatus(std::uint64_t newGeneration) noexcept {
        if (mClosed.load(std::memory_order_acquire)) return NativeAudioFlushStatus::InvalidState;
        if (newGeneration < mRoles.producerGeneration()) return NativeAudioFlushStatus::StaleGeneration;
        const bool pushChange = mPushEnabled.load(std::memory_order_acquire) && newGeneration != mRoles.producerGeneration();
        if (pushChange &&
            !preparePushGenerationChange(newGeneration)) return NativeAudioFlushStatus::RetryAfterCallbackAcknowledgement;
        PushGenerationCompletion completion{pushChange ? this : nullptr, newGeneration};
        auto lock = mStreamLifecycle.acquire();
        if (mClosed.load(std::memory_order_acquire)) {
            return NativeAudioFlushStatus::InvalidState;
        }
        const std::uint64_t producerGeneration = mRoles.producerGeneration();
        if (mPushSourceTransition.load(std::memory_order_acquire) == newGeneration &&
            newGeneration > producerGeneration) {
            PushOperation operation(mPushOperation);
            if (!operation.acquired) return NativeAudioFlushStatus::RetryAfterCallbackAcknowledgement;
#ifdef STEMWISE_OUTPUT_CALLBACK_TEST_HOOKS
            if (mSourceFlushTestHook != nullptr) mSourceFlushTestHook(mSourceFlushTestContext);
#endif
            // The live transport can write silence, but the exact source fence excludes every ring reader.
            resetAllOutputRingsAfterCallbackStopped();
            const auto sequence = mRoles.forceResetAfterCallbackStopped(newGeneration, 0U);
            if (sequence == 0U) return NativeAudioFlushStatus::InvalidState;
            mAppliedCommitSequence = sequence;
            mCallbackRing = 0U;
            mCallbackGeneration = newGeneration;
            resetCallbackLoopOwnerState();
            retirePushPendingSource();
            mNaturalEndHoldGeneration = mNaturalEndHoldWriteBoundary = 0U;
            mNaturalEndHoldRing = 0U;
            mTerminalSilenceAuthorizedGeneration.store(0U, std::memory_order_release);
            mAppliedGeneration.store(newGeneration, std::memory_order_release);
            mGenerationTransition.cancel();
            mCallbackRecovery.reset();
            mCallbackPlaying = false;
            mEffectiveRenderPlaying.store(false, std::memory_order_release);
            mControlEvidenceContinuity = mStemGainEvidenceContinuity = mLoopEvidenceContinuity = false;
            mPushSourceTransition.store(0U, std::memory_order_release);
            completion.accepted = true;
            return NativeAudioFlushStatus::Accepted;
        }
        if (newGeneration == producerGeneration) {
            // The synchronous silence gate may publish the exact administrative empty epoch before
            // the render worker consumes its matching unselected command. Repeating that exact
            // generation is pending while the callback owns it, idempotently accepted after the
            // callback acknowledges it, and invalid while it is merely an uncommitted open stage.
            if (mRoles.commitAwaitingAcknowledgement()) {
                return NativeAudioFlushStatus::RetryAfterCallbackAcknowledgement;
            }
            return mRoles.activeGeneration() == newGeneration
                ? NativeAudioFlushStatus::Accepted
                : NativeAudioFlushStatus::InvalidState;
        }
        if (newGeneration < producerGeneration) return NativeAudioFlushStatus::StaleGeneration;
        // Before first Play or after a confirmed physical pause, no callback owns a ring.
        // Administrative STOP/isolation resets directly at that proven callback-free boundary.
        // A live stream continues through the normal callback-acknowledged handoff below.
        if (!mPushConsumerActive.load(std::memory_order_acquire)) {
            PushOperation operation(mPushOperation);
            if (!operation.acquired) return NativeAudioFlushStatus::RetryAfterCallbackAcknowledgement;
            resetAllOutputRingsAfterCallbackStopped();
            const std::uint64_t appliedSequence =
                mRoles.forceResetAfterCallbackStopped(newGeneration, 0U);
            if (appliedSequence == 0U) return NativeAudioFlushStatus::InvalidState;
            mAppliedCommitSequence = appliedSequence;
            mCallbackRing = 0U;
            mCallbackGeneration = newGeneration;
            resetCallbackLoopOwnerState();
            mNaturalEndHoldGeneration = 0U;
            mNaturalEndHoldRing = 0U;
            mNaturalEndHoldWriteBoundary = 0U;
            mTerminalSilenceAuthorizedGeneration.store(0U, std::memory_order_release);
            mAppliedGeneration.store(newGeneration, std::memory_order_release);
            mGenerationTransition.cancel();
            mCallbackRecovery.reset();
            mCallbackPlaying = false;
            mEffectiveRenderPlaying.store(false, std::memory_order_release);
            mControlEvidenceContinuity = false;
            mStemGainEvidenceContinuity = false;
            mLoopEvidenceContinuity = false;
            if (mPushEnabled.load(std::memory_order_acquire)) {
                retirePushPendingSource();
                mPushTransportSequence = 0U;
                mPushHistory->releaseTransport();
                mPushTransportRemaining = 0U;
                mPushSourceTransition.store(0U, std::memory_order_release);
                mPushObservedNext = mPushPresentationFloor = pushSubmittedFrames();
                mPushLastAcceptedSource = {};
                mPushHistory->discardAccepted();
                mPresentedPosition.reset();
            }
            completion.accepted = true;
            return NativeAudioFlushStatus::Accepted;
        }
        // A hard flush intentionally publishes an empty inactive ring. Selection and seeking use
        // beginHandoff/commitHandoff so their callback-boundary swap is already prefilled.
        const OutputHandoffBeginResult result = mRoles.begin(newGeneration);
        if (!result.accepted()) return flushStatus(result.status);
        resetInactiveOutputRing(result.stagingRing);
        const auto status = flushStatus(mRoles.commitEmpty(newGeneration));
        completion.accepted = status == NativeAudioFlushStatus::Accepted;
        return status;
    }

    /**
     * The native coordinator is the single generation authority. Both the Kotlin owner and the
     * borrowed render-runtime port mutate the same coordinator, so no caller-side mirror can stay
     * correct across production handoffs.
     */
    [[nodiscard]] std::uint64_t producerGeneration() const noexcept {
        return mRoles.producerGeneration();
    }

    /**
     * Stops callbacks without closing or reopening the device stream.
     *
     * requestPause() is asynchronous. The controller calls this again on a bounded cadence until
     * Paused is observed, so callback-free ring operations are never inferred early. A concurrent
     * Play wins through mPlaying and setPlaying() retries a transient Pausing state.
     */

    void setTerminalSilenceAuthorization(
        std::uint64_t generation,
        bool authorized) noexcept {
        mTerminalSilenceAuthorizedGeneration.store(
            authorized && generation != 0U ? generation : 0U,
            std::memory_order_release);
    }

    [[nodiscard]] bool publishTerminalBoundary(std::uint64_t generation, std::uint64_t token,
        std::uint64_t physicalRevision, double sourceEnd) noexcept {
        if (mClosed.load(std::memory_order_acquire)) return false;
        auto target = physicalRevision != 0U ? mRoles.loopStagingTarget(generation, physicalRevision) : OutputRoleTarget{};
        if (!target.valid()) target = mRoles.ringForGeneration(generation);
        return target.valid() && multibusRing(target.ring).publishTerminalBoundary(
            generation, token, physicalRevision, sourceEnd);
    }

    [[nodiscard]] std::uint64_t exactTerminalBoundary(std::uint32_t ring,
        const FinalPcmFrameTag &tag, std::uint64_t sourceWriteBoundary, std::uint64_t epoch) const noexcept {
        const auto boundary = multibusRing(ring).terminalBoundary();
        return boundary.writePosition == sourceWriteBoundary && boundary.writePosition != 0U && boundary.epoch == epoch &&
            boundary.generation == tag.generation && boundary.selectionToken == tag.selectionToken &&
            boundary.physicalRevision == tag.loopPolicyRevision &&
            tag.nextTimelineFrame == static_cast<std::int64_t>(boundary.sourceEnd)
            ? boundary.writePosition : 0U;
    }

    // Sole consumer adds only EOF to an existing exact source/output proof, never a new frame.
    void reconcileLateTerminalBoundary() noexcept {
        const auto &read = mLastSourceRead[mCallbackRing];
        const auto boundary = exactTerminalBoundary(mCallbackRing, read.tag, read.writeBoundary, read.epoch);
        if (boundary != 0U && read.tag.generation == mCallbackGeneration &&
            multibusRing(mCallbackRing).readPosition() == boundary) {
            if (mPushEnabled.load(std::memory_order_acquire) &&
                mPushRenderedPosition.generation == read.tag.generation &&
                mPushRenderedPosition.selectionToken == read.tag.selectionToken &&
                mPushRenderedPosition.loopPolicyRevision == read.tag.loopPolicyRevision &&
                mPushRenderedPosition.lastTimelineFrame == read.tag.timelineFrame &&
                mPushRenderedPosition.lastNextTimelineFrame == read.tag.nextTimelineFrame)
                mPushRenderedPosition.terminalAfterFrame = true;
            if (mTerminalSilenceAuthorizedGeneration.load(std::memory_order_acquire) == mCallbackGeneration) {
                mNaturalEndHoldGeneration = mCallbackGeneration;
                mNaturalEndHoldRing = mCallbackRing;
                mNaturalEndHoldWriteBoundary = boundary;
            }
        }
        auto position = mPresentedPosition.callbackPosition();
        if (!position.terminalAfterFrame && position.lastOutputFrame == mLastPresentedSourceOutputFrame &&
            position.generation == mLastPresentedSource.tag.generation &&
            position.selectionToken == mLastPresentedSource.tag.selectionToken &&
            position.fingerprintSequence == mLastPresentedSource.tag.fingerprintSequence &&
            position.loopPolicyRevision == mLastPresentedSource.tag.loopPolicyRevision &&
            position.lastTimelineFrame == mLastPresentedSource.tag.timelineFrame &&
            position.lastNextTimelineFrame == mLastPresentedSource.tag.nextTimelineFrame &&
            exactTerminalBoundary(mLastPresentedSourceRing, mLastPresentedSource.tag,
                mLastPresentedSource.writeBoundary, mLastPresentedSource.epoch) != 0U) {
            position.terminalAfterFrame = true;
            mPresentedPosition.position(position);
        }
    }

    void armManualSelectionGate() noexcept {
        // Fence automatic successor adoption before closing render. The actor remains the sole
        // exact drain canceller; this bit only prevents a staged successor from winning while
        // manual source resolution is suspended.
        mRoles.armAutomaticDrainFence();
        mManualSelectionRenderGate.arm();
        // SC with the callback ticket increment and gate-decision load. An outgoing callback that
        // already observed OPEN must have a ticket at or below this exact ceiling; a callback that
        // enters after the arm observes CLOSED and cannot publish a presentation span.
        const std::uint64_t callbackCeiling =
            mCallbackCount.load(std::memory_order_seq_cst);
        mManualSelectionGateLastArmCallbackSequence.store(
            callbackCeiling,
            std::memory_order_seq_cst);
    }

    [[nodiscard]] bool authorizeManualSelectionGate(
        std::uint64_t generation) noexcept {
        if (!mManualSelectionRenderGate.authorize(generation)) return false;
        static_cast<void>(
            mRoles.clearAutomaticDrainFenceForAuthorizedGeneration(generation));
        return true;
    }

    [[nodiscard]] std::size_t queuedFrames() const noexcept {
        const auto target = mRoles.ringForGeneration(mRoles.producerGeneration());
        return target.valid() ? outputRingQueuedFrames(target.ring) : 0U;
    }

    [[nodiscard]] std::size_t writableFrames() const noexcept {
        const auto target = mRoles.writableRingForGeneration(mRoles.producerGeneration());
        return target.valid() ? outputRingWritableFrames(target.ring) : 0U;
    }

    [[nodiscard]] std::size_t queuedFramesForGeneration(
        std::uint64_t generation) const noexcept {
        const OutputRoleTarget target = mRoles.ringForGeneration(generation);
        return target.valid() ? outputRingQueuedFrames(target.ring) : 0U;
    }

    [[nodiscard]] std::size_t writableFramesForGeneration(
        std::uint64_t generation) const noexcept {
        const OutputRoleTarget target = mRoles.writableRingForGeneration(generation);
        return target.valid() ? outputRingWritableFrames(target.ring) : 0U;
    }

    [[nodiscard]] std::int32_t framesPerBurst() const noexcept {
        return mFramesPerBurst.load(std::memory_order_acquire);
    }

    [[nodiscard]] std::size_t stableBufferFrames() const noexcept {
        const std::int32_t frames = mActualBufferSize.load(std::memory_order_acquire);
        return frames > 0 ? static_cast<std::size_t>(frames) : 0U;
    }

    /** Exact prepared span needed to satisfy the largest callback observed on this route. */
    [[nodiscard]] std::size_t loopCutoverRequiredFrames() const noexcept {
        const auto burst = static_cast<std::size_t>(std::max(
            mFramesPerBurst.load(std::memory_order_acquire),
            1));
        return std::max(
            burst,
            mMaximumCallbackRequestFrames.load(std::memory_order_acquire));
    }

private:
    std::uint64_t nextPushTicket() noexcept {
        if (mPushTicket == std::numeric_limits<std::uint64_t>::max()) return 0U;
        return ++mPushTicket;
    }
    void retirePushPendingSource() noexcept {
        if (mPushHistory) mPushHistory->releaseSource();
        mPushPendingFrames = mPushPendingOffset = 0U;
        mPushBlockSequence = 0U;
        mPushTerminal = false;
        mPushRenderedPosition = {};
    }
    void renderBlock(void *audioData, std::int32_t numFrames,
        const CallbackLoopPlan *preparedLoopPlan = nullptr) noexcept {
        if (audioData == nullptr || numFrames <= 0) {
            return;
        }

#ifdef STEMWISE_OUTPUT_CALLBACK_TEST_HOOKS
        mCallbackTestObservation = {};
#endif
        const std::int64_t callbackStartMicros = monotonicMicros();
        auto *output = static_cast<float *>(audioData);
        const std::size_t requestedFrames = static_cast<std::size_t>(numFrames);
        updateMaximum(mMaximumCallbackRequestFrames, requestedFrames);
        const std::size_t requestedSamples = requestedFrames * kChannelCount;

        if (mClosing.load(std::memory_order_acquire) ||
            mClosed.load(std::memory_order_acquire)) {
            std::memset(output, 0, requestedSamples * sizeof(float));
            return;
        }

        if (mPhysicalRunRestartPending.exchange(false, std::memory_order_acq_rel)) {
            mPreviousCallbackStartMicros = 0;
            mLatestLateCallbackStartGapMicros = 0U;
            mLatestLateCallbackSequence = 0U;
            saturatingIncrement(mPhysicalRunSequence);
        }
        const std::int64_t previousCallbackStartMicros = mPreviousCallbackStartMicros;
        mPreviousCallbackStartMicros = callbackStartMicros;
        const std::uint64_t callbackStartGapMicros =
            previousCallbackStartMicros > 0 && callbackStartMicros >= previousCallbackStartMicros
            ? static_cast<std::uint64_t>(callbackStartMicros - previousCallbackStartMicros)
            : 0U;
        mLastCallbackStartGapMicros.store(
            callbackStartGapMicros,
            std::memory_order_relaxed);
        updateMaximum(mMaxCallbackStartGapMicros, callbackStartGapMicros);

        if (preparedLoopPlan == nullptr) static_cast<void>(acknowledgeCancellation());

        CallbackContext context{output, requestedFrames, requestedSamples, callbackStartMicros};
        // A control-only adoption can precede several source waits. Its reset private cursor
        // keeps the generation boundary pending until real rendering, using the latest accepted
        // endpoint even if transport silence was accepted while the source was unavailable.
        if (preparedLoopPlan != nullptr && mPushRenderedPosition.generation != mCallbackGeneration)
            context.generationTransitionOffset = 0U;
#if defined(STEMWISE_CACHE_IO_PROFILING)
        context.trace.counter("StemWise.audio.callback.run", saturatedCounter(mPhysicalRunSequence.load(std::memory_order_relaxed)));
        context.trace.counter("StemWise.audio.callback.start_gap_us", saturatedCounter(callbackStartGapMicros));
        context.trace.counter("StemWise.audio.callback.requested_frames", saturatedCounter(requestedFrames));
#endif
        context.qualification = mQualificationDiagnostics.load(std::memory_order_acquire);
        if (context.qualification && requestedFrames <= mCallbackTimelineCapacity) {
            auto *capture = mQualificationCapture.load(std::memory_order_acquire);
            if (capture) context.mappingScratch = capture->scratch.get();
        }
        if (context.qualification && context.mappingScratch == nullptr)
            saturatingIncrement(mUnavailableQualificationCapture);
        context.qualification = context.qualification && context.mappingScratch != nullptr;

        const bool activeEmptyAtBoundary = outputRingQueuedFrames(mCallbackRing) == 0U;
        bool roleSwapAtCallbackStart = preparedLoopPlan == nullptr && adoptRoleSwap(context.generationTransitionOffset,
            mRoles.callbackSwap(
                mAppliedCommitSequence,
                activeEmptyAtBoundary,
                true),
            0U);

        if (preparedLoopPlan == nullptr) static_cast<void>(adoptLatestDirectLoopDemand());
        refreshExplicitLoopDrainFence(
            renderDecisionPosition().generation,
            renderDecisionPosition().selectionToken,
            renderDecisionPosition().lastTimelineFrame);

        auto loopPlan = preparedLoopPlan != nullptr ? *preparedLoopPlan : selectCallbackLoopPlan();
        const bool candidateRealizesDemand = loopPlan.candidateRealizesDemand;
        const auto &callbackPreparedLoopPolicy = loopPlan.prepared;
        const std::uint64_t callbackSequence =
            mCallbackCount.fetch_add(1, std::memory_order_seq_cst) + 1U;
#if defined(STEMWISE_CACHE_IO_PROFILING)
        context.trace.counter("StemWise.audio.callback.sequence", saturatedCounter(callbackSequence));
#endif
        OutputTimelineFrameTag *const callbackTimelineTags =
            requestedFrames <= mCallbackTimelineCapacity
            ? mCallbackTimelineScratch.get()
            : nullptr;
        if (callbackTimelineTags != nullptr) {
            std::fill_n(callbackTimelineTags, requestedFrames, OutputTimelineFrameTag{});
        }
        const std::int32_t sampleRate =
            std::max(mActualSampleRate.load(std::memory_order_relaxed), 1);
        const std::uint64_t callbackDeadlineMicros = std::max<std::uint64_t>(
            static_cast<std::uint64_t>(requestedFrames) * 1'000'000ULL /
                static_cast<std::uint64_t>(sampleRate),
            1U);
        const std::uint64_t lateGapThresholdMicros =
            callbackDeadlineMicros > std::numeric_limits<std::uint64_t>::max() / 2U
            ? std::numeric_limits<std::uint64_t>::max()
            : callbackDeadlineMicros * 2U;
        if (callbackStartGapMicros > lateGapThresholdMicros) {
            // Callback-only state copied into any later underrun publication. It identifies the
            // latest scheduler/device-clock discontinuity without sampling or logging on the
            // real-time thread.
            mLatestLateCallbackStartGapMicros = callbackStartGapMicros;
            mLatestLateCallbackSequence = callbackSequence;
        }

        const std::uint64_t generationAtRenderDecision = mCallbackGeneration;
        const std::size_t queuedFramesAtRenderDecision =
            outputRingQueuedFrames(mCallbackRing);
        const bool exactPreparedLoopForNaturalEnd =
            candidateRealizesDemand &&
            mCallbackLoopCandidate.callbackPhysicalCutoverReady();
        const std::int64_t presentedTimelineFrame =
            renderDecisionPosition().lastTimelineFrame;
        const bool logicalLoopDemandBlocksNaturalEnd = mHasCallbackLoopDemand &&
            nativeAudioOutputLoopDemandBlocksDrain(
                mCallbackLoopDemand,
                renderDecisionPosition().generation,
                renderDecisionPosition().selectionToken,
                presentedTimelineFrame);
        const bool callbackLoopAlreadyBlocksNaturalEnd =
            logicalLoopDemandBlocksNaturalEnd || nativeAudioOutputPreparedLoopBlocksEnd(
                exactPreparedLoopForNaturalEnd,
                exactPreparedLoopForNaturalEnd &&
                    renderDecisionPosition().generation ==
                        mCallbackGeneration &&
                    renderDecisionPosition().selectionToken ==
                        callbackPreparedLoopPolicy.selectionToken &&
                    renderDecisionPosition().loopPolicyRevision ==
                        callbackPreparedLoopPolicy.cutoverSourceRevision,
                presentedTimelineFrame >= 0 && exactPreparedLoopForNaturalEnd &&
                    static_cast<std::uint64_t>(presentedTimelineFrame) >=
                        callbackPreparedLoopPolicy.aTimelineFrame &&
                    static_cast<std::uint64_t>(presentedTimelineFrame) <=
                        callbackPreparedLoopPolicy.bTimelineFrameExclusive);
        reconcileLateTerminalBoundary();
        const bool naturalEndHoldActive =
            mNaturalEndHoldGeneration == mCallbackGeneration &&
            mNaturalEndHoldRing == mCallbackRing &&
            mNaturalEndHoldWriteBoundary != 0U &&
            !callbackLoopAlreadyBlocksNaturalEnd &&
            mTerminalSilenceAuthorizedGeneration.load(std::memory_order_acquire) ==
                mCallbackGeneration &&
            queuedFramesAtRenderDecision == 0U &&
            outputRingWritePosition(mCallbackRing) == mNaturalEndHoldWriteBoundary;
        if (!naturalEndHoldActive) {
            mNaturalEndHoldGeneration = 0U;
            mNaturalEndHoldRing = 0U;
            mNaturalEndHoldWriteBoundary = 0U;
        }
        const bool manualGateAllowsRender = mManualSelectionRenderGate.allowsRender(
            mCallbackGeneration,
            queuedFramesAtRenderDecision != 0U);
        const bool requestedPlaying = mPlaying.load(std::memory_order_acquire);
        const bool effectiveRenderPlaying =
            requestedPlaying && manualGateAllowsRender && !naturalEndHoldActive;
        mEffectiveRenderPlaying.store(effectiveRenderPlaying, std::memory_order_release);
        if (effectiveRenderPlaying != mCallbackPlaying) {
            mCallbackPlaying = effectiveRenderPlaying;
            mCallbackRecovery.reset();
            mGenerationTransition.begin(
                mLastActuallyEmittedLeft,
                mLastActuallyEmittedRight,
                OutputGenerationTransition::framesForSampleRate(
                mActualSampleRate.load(std::memory_order_relaxed)));
        }

        // Claim the prepared continuation before inspecting any PCM that may reach B. The producer may
        // still replace an unclaimed role between callbacks, but it cannot cancel this callback's
        // continuation after the callback has captured its immutable A/B snapshot.
        if (preparedLoopPlan == nullptr) {
            loopPlan.claimedSwap = mCallbackPlaying && candidateRealizesDemand
                ? mRoles.callbackLoopSwap(mAppliedCommitSequence, mCallbackGeneration,
                    mCallbackLoopCandidate.physicalRevision(), true)
                : OutputRoleSwap{};
        } else if (!mCallbackPlaying && loopPlan.claimedSwap.valid()) {
            static_cast<void>(mRoles.releaseCallbackLoopSwap(loopPlan.claimedSwap));
            loopPlan.claimedSwap = {};
        }
        const auto &claimedLoopSwap = loopPlan.claimedSwap;
        const bool callbackOwnsPreparedLoop = claimedLoopSwap.valid();
        const NativeAudioOutputLoopPolicy *adoptedLoopPolicy = callbackOwnsPreparedLoop
            ? &callbackPreparedLoopPolicy
            : (mHasCallbackLoopPolicy && mCallbackLoopPolicy.physicalReady
                ? &mCallbackLoopPolicy
                : nullptr);
        if (!mHasCallbackLoopDemand) recordLoopPolicyAdoption(adoptedLoopPolicy);

        auto &spans = context.spans;
        auto &spanCount = context.spanCount;
        auto &physicalFrames = context.physicalFrames;
        auto &validPcmFrames = context.validPcmFrames;
#if defined(STEMWISE_CACHE_IO_PROFILING)
        context.trace.counter("StemWise.audio.callback.queue_frames", saturatedCounter(queuedFramesAtRenderDecision));
        context.trace.counter("StemWise.audio.callback.generation_at_render_decision", saturatedCounter(generationAtRenderDecision));
        context.trace.counter("StemWise.audio.callback.playing", mCallbackPlaying ? 1 : 0);
#endif
        const bool roleSwapDuringDrain = readAndCutoverCallback(context, loopPlan,
            callbackTimelineTags, callbackSequence, queuedFramesAtRenderDecision);
#if defined(STEMWISE_CACHE_IO_PROFILING)
        context.trace.counter("StemWise.audio.callback.generation_at_completion", saturatedCounter(mCallbackGeneration));
#endif
        // Every callback-owned ring advance and loop-role mutation is now final.

        // A real natural end is carried by the exact final accepted PCM frame. The same ring
        // write-position release publishes both the samples and terminal boundary, so the callback
        // never infers EOS from an empty queue. A later same-generation append advances the write
        // boundary and invalidates the marker automatically.
        const bool exactPreparedLoopForTerminalDrain =
            candidateRealizesDemand &&
            mCallbackLoopCandidate.callbackPhysicalCutoverReady();
        const std::int64_t terminalPresentedTimelineFrame =
            renderDecisionPosition().lastTimelineFrame;
        const bool logicalLoopDemandBlocksTerminalDrain = mHasCallbackLoopDemand &&
            nativeAudioOutputLoopDemandBlocksDrain(
                mCallbackLoopDemand,
                renderDecisionPosition().generation,
                renderDecisionPosition().selectionToken,
                terminalPresentedTimelineFrame);
        const bool callbackLoopBlocksTerminalDrain =
            logicalLoopDemandBlocksTerminalDrain || nativeAudioOutputPreparedLoopBlocksEnd(
                exactPreparedLoopForTerminalDrain,
                exactPreparedLoopForTerminalDrain &&
                    renderDecisionPosition().generation ==
                        mCallbackGeneration &&
                    renderDecisionPosition().selectionToken ==
                        callbackPreparedLoopPolicy.selectionToken &&
                    renderDecisionPosition().loopPolicyRevision ==
                        callbackPreparedLoopPolicy.cutoverSourceRevision,
                terminalPresentedTimelineFrame >= 0 &&
                    exactPreparedLoopForTerminalDrain &&
                    static_cast<std::uint64_t>(terminalPresentedTimelineFrame) >=
                        callbackPreparedLoopPolicy.aTimelineFrame &&
                    static_cast<std::uint64_t>(terminalPresentedTimelineFrame) <=
                        callbackPreparedLoopPolicy.bTimelineFrameExclusive);
        bool terminalDrain = false;
        for (std::size_t spanIndex = 0U; spanIndex < spanCount; ++spanIndex) {
            CallbackSpan &span = spans[spanIndex];
            const std::uint64_t boundary = span.read.lastTag.terminalWriteBoundary;
            span.terminalAfterFrame = span.valid && span.read.frameCount != 0U &&
                boundary != 0U && boundary >= span.read.absoluteFirstFrame &&
                boundary - span.read.absoluteFirstFrame == span.read.frameCount &&
                outputRingWritePosition(span.ring) == boundary;
            if (span.read.frameCount != 0U) {
                // Only the last physical span can terminalize this callback. An outgoing terminal
                // followed by a successor therefore cannot silence the successor generation.
                terminalDrain = span.terminalAfterFrame &&
                    !callbackLoopBlocksTerminalDrain &&
                    mTerminalSilenceAuthorizedGeneration.load(std::memory_order_acquire) ==
                        span.generation &&
                    span.generation == mCallbackGeneration && span.ring == mCallbackRing;
            }
        }
        if (terminalDrain) {
            const CallbackSpan &terminal = spans[spanCount - 1U];
            mNaturalEndHoldGeneration = terminal.generation;
            mNaturalEndHoldRing = terminal.ring;
            mNaturalEndHoldWriteBoundary = terminal.read.lastTag.terminalWriteBoundary;
        }
        mPushTerminal = terminalDrain || naturalEndHoldActive;

        if (physicalFrames < requestedFrames) {
            std::memset(
                output + physicalFrames * kChannelCount,
                0,
                (requestedFrames - physicalFrames) * kChannelCount * sizeof(float));
        }
        const std::size_t missingFrames = terminalDrain
            ? physicalFrames - std::min(physicalFrames, validPcmFrames)
            : requestedFrames - std::min(requestedFrames, validPcmFrames);
        if (mCallbackPlaying && missingFrames != 0U) {
            // SC with the callback ticket and arm publication. callbackSequence <= armCeiling is
            // possible only when this callback had already observed OPEN before that arm; a
            // callback entering after the arm receives the next ticket and observes CLOSED.
            const std::uint64_t manualArmCallbackCeiling =
                mManualSelectionGateLastArmCallbackSequence.load(
                    std::memory_order_seq_cst);
            const bool manualArmBoundary = manualArmCallbackCeiling != 0U &&
                callbackSequence <= manualArmCallbackCeiling;
            const std::uint64_t underrunMonotonicMicros = callbackStartMicros > 0
                ? static_cast<std::uint64_t>(callbackStartMicros)
                : 0U;
            SuccessfulEnqueueEvidence successfulEnqueue{};
            const bool successfulEnqueueSnapshotCoherent =
                trySuccessfulEnqueueEvidence(successfulEnqueue);
            const std::uint64_t successfulEnqueueAgeMicros =
                successfulEnqueueSnapshotCoherent &&
                    successfulEnqueue.monotonicMicros != 0U &&
                    underrunMonotonicMicros >= successfulEnqueue.monotonicMicros
                ? underrunMonotonicMicros - successfulEnqueue.monotonicMicros
                : 0U;
            publishUnderrunEvidence(
                generationAtRenderDecision,
                mCallbackGeneration,
                callbackSequence,
                requestedFrames,
                queuedFramesAtRenderDecision,
                physicalFrames,
                validPcmFrames,
                missingFrames,
                callbackStartGapMicros,
                mLatestLateCallbackStartGapMicros,
                mLatestLateCallbackSequence,
                manualArmCallbackCeiling,
                mAppliedCommitSequence,
                roleSwapAtCallbackStart,
                roleSwapDuringDrain,
                manualArmBoundary,
                underrunMonotonicMicros,
                successfulEnqueueSnapshotCoherent,
                successfulEnqueue,
                successfulEnqueueAgeMicros,
                mMaxSuccessfulEnqueueGapMicros.load(std::memory_order_relaxed));
            mUnderflowEvents.fetch_add(1, std::memory_order_relaxed);
            saturatingIncrement(gOutputUnderrunCallbackCount);
            mUnderflowFrames.fetch_add(
                missingFrames,
                std::memory_order_relaxed);
            saturatingAdd(
                gOutputUnderrunFrames,
                missingFrames);
            if (manualArmBoundary) {
                saturatingIncrement(gOutputManualArmBoundaryUnderrunCallbackCount);
                saturatingAdd(gOutputManualArmBoundaryUnderrunFrames, missingFrames);
            }
        }

        finalizePreparedBlock(context, callbackSequence, callbackDeadlineMicros);
    }

public:
    template <typename Value = std::int64_t>
    std::array<Value, kDiagnosticsFieldCount> diagnostics(
        std::int32_t xRunCount = -1, std::int32_t streamState = 0,
        std::int32_t outputBufferSize = -1) const noexcept {
        const auto callbackMix = mCallbackMixPublication.read();
        const NativeAudioPresentationEvidence ownerEvidence = presentationEvidence();
        const NativeAudioPresentedPosition position = presentedPosition();
        const CallbackPcmLevelEvidence pcmLevel = callbackPcmLevelEvidence();
        const CallbackUnderrunEvidence underrunEvidence = callbackUnderrunEvidence();
        const SuccessfulEnqueueEvidence successfulEnqueue = successfulEnqueueEvidence();
        const LoopDemandDiagnostics loopDemand = mLoopDiagnostics.loopDemandDiagnostics();
        const LoopCallbackDiagnostics loopCallback = mLoopDiagnostics.loopCallbackDiagnostics();
        // Creation publishes construction before live. Read live first so one snapshot cannot
        // report live > constructed while another output owner is being created concurrently.
        const std::uint64_t processLiveInstances =
            gOutputLiveInstanceCount.load(std::memory_order_acquire);
        const std::uint64_t processConstructions =
            gOutputConstructionCount.load(std::memory_order_acquire);
        const ManualSelectionRenderGateSnapshot manualGate =
            mManualSelectionRenderGate.snapshot();
        const auto deadlineMiss = mCallbackDeadlineMissGuard.read([this](std::uint64_t) noexcept {
            return std::array<std::int64_t, 2U>{mLastCallbackDeadlineMissStartMicros.load(std::memory_order_acquire),
                mLastCallbackDeadlineMissEndMicros.load(std::memory_order_acquire)};
        });

        return packet::packOutputDiagnostics(packet::OutputDiagnosticsFields<Value>{
            .SCHEMA = kDiagnosticsSchemaVersion,
            .STREAM_GENERATION = static_cast<Value>(mRoles.producerGeneration()),
            .STREAM_STATE = streamState,
            .BACKEND = mAudioApi.load(std::memory_order_relaxed),
            .SAMPLE_RATE_HZ = mActualSampleRate.load(std::memory_order_relaxed),
            .CHANNEL_COUNT = mActualChannelCount.load(std::memory_order_relaxed),
            .FRAMES_PER_BURST = mFramesPerBurst.load(std::memory_order_relaxed),
            .OUTPUT_BUFFER_CAPACITY_FRAMES = mOutputBufferCapacity.load(std::memory_order_relaxed),
            .OUTPUT_BUFFER_SIZE_FRAMES = outputBufferSize,
            .RING_CAPACITY_FRAMES = static_cast<Value>(mFirstMultibusRing.capacityFrames()),
            .QUEUED_FRAMES = static_cast<Value>(queuedFrames()),
            .TOTAL_FRAMES_ACCEPTED = static_cast<Value>(mTotalFramesAccepted.load(std::memory_order_relaxed)),
            .RENDERED_FRAMES = static_cast<Value>(mTotalFramesPresented.load(std::memory_order_relaxed)),
            .CALLBACK_COUNT = static_cast<Value>(mCallbackCount.load(std::memory_order_seq_cst)),
            .CALLBACK_FRAMES = static_cast<Value>(mCallbackFrames.load(std::memory_order_relaxed)),
            .X_RUN_COUNT = xRunCount,
            .UNDERRUN_CALLBACK_COUNT = static_cast<Value>(mUnderflowEvents.load(std::memory_order_relaxed)),
            .UNDERRUN_FRAMES = static_cast<Value>(mUnderflowFrames.load(std::memory_order_relaxed)),
            .STALE_FRAMES_REJECTED = static_cast<Value>(mStaleFramesRejected.load(std::memory_order_relaxed)),
            .PRODUCER_BACKPRESSURE_FRAMES = static_cast<Value>(mBackpressureFrames.load(std::memory_order_relaxed)),
            .APPLIED_GENERATION = static_cast<Value>(mAppliedGeneration.load(std::memory_order_acquire)),
            .LAST_PRESENTED_GENERATION = static_cast<Value>(position.generation),
            .PLAYING = mPlaying.load(std::memory_order_acquire) ? 1 : 0,
            .RECOVERY_REQUIRED = mRecoveryRequired.load(std::memory_order_acquire) ? 1 : 0,
            .LAST_ERROR_CODE = mLastError.load(std::memory_order_acquire),
            .REOPEN_COUNT = static_cast<Value>(mReopenCount.load(std::memory_order_relaxed)),
            .LAST_ERROR_MONOTONIC_MILLIS = static_cast<Value>(mLastErrorRealtimeMillis.load(std::memory_order_relaxed)),
            .CALLBACK_DEADLINE_MISS_COUNT = static_cast<Value>(mCallbackDeadlineMisses.load(std::memory_order_relaxed)),
            .MAX_CALLBACK_DURATION_MICROS = static_cast<Value>(mMaxCallbackDurationMicros.load(std::memory_order_relaxed)),
            .PERFORMANCE_MODE = mPerformanceMode.load(std::memory_order_relaxed),
            .LAST_PRESENTED_SELECTION_TOKEN = static_cast<Value>(position.selectionToken),
            .LAST_PRESENTED_FINGERPRINT_SEQUENCE = static_cast<Value>(position.fingerprintSequence),
            .LAST_PRESENTED_TIMELINE_FRAME = static_cast<Value>(position.lastTimelineFrame),
            .PRESENTED_OWNER_SEQUENCE = static_cast<Value>(ownerEvidence.sequence),
            .LAST_OWNER_FIRST_OUTPUT_FRAME = static_cast<Value>(ownerEvidence.firstOutputFrame),
            .LAST_OWNER_FIRST_CALLBACK_SEQUENCE = static_cast<Value>(ownerEvidence.callbackSequence),
            .LAST_OWNER_FIRST_PCM_FRAMES = static_cast<Value>(ownerEvidence.pcmFrames),
            .INVALID_OWNERSHIP_TAG_FRAMES = static_cast<Value>(mInvalidOwnershipTagFrames.load(std::memory_order_relaxed)),
            .PROCESS_CONSTRUCTION_COUNT = saturatedCounter(processConstructions),
            .PROCESS_LIVE_INSTANCE_COUNT = saturatedCounter(processLiveInstances),
            .EFFECTIVE_RENDER_PLAYING = mEffectiveRenderPlaying.load(std::memory_order_acquire) ? 1 : 0,
            .MANUAL_SELECTION_GATE_STATE = static_cast<Value>(manualGate.state),
            .MANUAL_SELECTION_AUTHORIZED_GENERATION = saturatedCounter(manualGate.authorizedGeneration),
            .MANUAL_SELECTION_GATE_ARM_COUNT = saturatedCounter(manualGate.armCount),
            .MANUAL_SELECTION_GATE_AUTHORIZATION_COUNT = saturatedCounter(manualGate.authorizationCount),
            .MANUAL_SELECTION_GATE_REJECTED_AUTHORIZATION_COUNT = saturatedCounter(manualGate.rejectedAuthorizationCount),
            .MANUAL_SELECTION_GATE_OPEN_COUNT = saturatedCounter(manualGate.openCount),
            .MANUAL_SELECTION_GATE_LAST_ARM_CALLBACK_SEQUENCE = saturatedCounter(mManualSelectionGateLastArmCallbackSequence.load(
                std::memory_order_seq_cst)),
            .PCM_LEVEL_SEQUENCE = saturatedCounter(pcmLevel.sequence),
            .PCM_LEVEL_GENERATION = saturatedCounter(pcmLevel.generation),
            .PCM_LEVEL_SELECTION_TOKEN = saturatedCounter(pcmLevel.selectionToken),
            .PCM_LEVEL_FINGERPRINT_SEQUENCE = saturatedCounter(pcmLevel.fingerprintSequence),
            .PCM_LEVEL_CALLBACK_SEQUENCE = saturatedCounter(pcmLevel.callbackSequence),
            .PCM_LEVEL_FRAMES = saturatedCounter(pcmLevel.pcmFrames),
            .PCM_LEVEL_MEAN_SQUARE = static_cast<Value>(std::bit_cast<std::uint32_t>(pcmLevel.meanSquare)),
            .PCM_LEVEL_PEAK = static_cast<Value>(std::bit_cast<std::uint32_t>(pcmLevel.peak)),
            .PROCESS_INVALID_OWNERSHIP_TAG_FRAMES = saturatedCounter(
                gOutputInvalidOwnershipTagFrames.load(std::memory_order_relaxed)),
            .PROCESS_UNDERRUN_CALLBACK_COUNT = saturatedCounter(
                gOutputUnderrunCallbackCount.load(std::memory_order_relaxed)),
            .PROCESS_UNDERRUN_FRAMES = saturatedCounter(gOutputUnderrunFrames.load(std::memory_order_relaxed)),
            .PROCESS_XRUN_COUNT = saturatedCounter(gOutputXRunCount.load(std::memory_order_relaxed)),
            .PROCESS_CALLBACK_DEADLINE_MISS_COUNT = saturatedCounter(
                gOutputCallbackDeadlineMissCount.load(std::memory_order_relaxed)),
            .CALLBACK_START_GAP_MICROS = saturatedCounter(
                mLastCallbackStartGapMicros.load(std::memory_order_relaxed)),
            .MAXIMUM_CALLBACK_START_GAP_MICROS = saturatedCounter(
                mMaxCallbackStartGapMicros.load(std::memory_order_relaxed)),
            .LAST_UNDERRUN_SEQUENCE = saturatedCounter(underrunEvidence.sequence),
            .LAST_UNDERRUN_GENERATION_AT_RENDER_DECISION = saturatedCounter(underrunEvidence.generationAtRenderDecision),
            .LAST_UNDERRUN_GENERATION_AT_COMPLETION = saturatedCounter(underrunEvidence.generationAtCompletion),
            .LAST_UNDERRUN_CALLBACK_SEQUENCE = saturatedCounter(underrunEvidence.callbackSequence),
            .LAST_UNDERRUN_REQUESTED_FRAMES = saturatedCounter(underrunEvidence.requestedFrames),
            .LAST_UNDERRUN_QUEUED_FRAMES_AT_RENDER_DECISION = saturatedCounter(underrunEvidence.queuedFramesAtRenderDecision),
            .LAST_UNDERRUN_PHYSICAL_FRAMES = saturatedCounter(underrunEvidence.physicalFrames),
            .LAST_UNDERRUN_VALID_PCM_FRAMES = saturatedCounter(underrunEvidence.validPcmFrames),
            .LAST_UNDERRUN_MISSING_FRAMES = saturatedCounter(underrunEvidence.missingFrames),
            .LAST_UNDERRUN_CALLBACK_START_GAP_MICROS = saturatedCounter(underrunEvidence.callbackStartGapMicros),
            .LAST_UNDERRUN_LATEST_LATE_CALLBACK_START_GAP_MICROS = saturatedCounter(underrunEvidence.latestLateCallbackStartGapMicros),
            .LAST_UNDERRUN_LATEST_LATE_CALLBACK_SEQUENCE = saturatedCounter(underrunEvidence.latestLateCallbackSequence),
            .LAST_UNDERRUN_MANUAL_ARM_CALLBACK_CEILING = saturatedCounter(underrunEvidence.manualArmCallbackCeiling),
            .LAST_UNDERRUN_APPLIED_COMMIT_SEQUENCE = saturatedCounter(underrunEvidence.appliedCommitSequence),
            .UNDERRUN_EVIDENCE_ROLE_SWAP_AT_CALLBACK_START = underrunEvidence.roleSwapAtCallbackStart ? 1 : 0,
            .UNDERRUN_EVIDENCE_ROLE_SWAP_DURING_DRAIN = underrunEvidence.roleSwapDuringDrain ? 1 : 0,
            .UNDERRUN_EVIDENCE_MANUAL_ARM_BOUNDARY = underrunEvidence.manualArmBoundary ? 1 : 0,
            .PROCESS_MANUAL_ARM_BOUNDARY_UNDERRUN_CALLBACK_COUNT = saturatedCounter(gOutputManualArmBoundaryUnderrunCallbackCount.load(
                std::memory_order_relaxed)),
            .PROCESS_MANUAL_ARM_BOUNDARY_UNDERRUN_FRAMES = saturatedCounter(gOutputManualArmBoundaryUnderrunFrames.load(
                std::memory_order_relaxed)),
            .SUCCESSFUL_ENQUEUE_SEQUENCE = saturatedCounter(successfulEnqueue.sequence),
            .LAST_SUCCESSFUL_ENQUEUE_MONOTONIC_MICROS = saturatedCounter(successfulEnqueue.monotonicMicros),
            .LAST_SUCCESSFUL_ENQUEUE_GENERATION = saturatedCounter(successfulEnqueue.generation),
            .LAST_SUCCESSFUL_ENQUEUE_ACCEPTED_FRAMES = saturatedCounter(successfulEnqueue.acceptedFrames),
            .LAST_SUCCESSFUL_ENQUEUE_QUEUE_AFTER_FRAMES = saturatedCounter(successfulEnqueue.queueAfterFrames),
            .MAXIMUM_SUCCESSFUL_ENQUEUE_GAP_MICROS = saturatedCounter(
                mMaxSuccessfulEnqueueGapMicros.load(std::memory_order_relaxed)),
            .LAST_UNDERRUN_MONOTONIC_MICROS = saturatedCounter(underrunEvidence.monotonicMicros),
            .UNDERRUN_EVIDENCE_SUCCESSFUL_ENQUEUE_SNAPSHOT_COHERENT = underrunEvidence.successfulEnqueueSnapshotCoherent ? 1 : 0,
            .LAST_UNDERRUN_SUCCESSFUL_ENQUEUE_SEQUENCE = saturatedCounter(underrunEvidence.successfulEnqueueSequence),
            .LAST_UNDERRUN_SUCCESSFUL_ENQUEUE_GENERATION = saturatedCounter(underrunEvidence.successfulEnqueueGeneration),
            .LAST_UNDERRUN_SUCCESSFUL_ENQUEUE_AGE_MICROS = saturatedCounter(underrunEvidence.successfulEnqueueAgeMicros),
            .LAST_UNDERRUN_SUCCESSFUL_ENQUEUE_ACCEPTED_FRAMES = saturatedCounter(underrunEvidence.successfulEnqueueAcceptedFrames),
            .LAST_UNDERRUN_SUCCESSFUL_ENQUEUE_QUEUE_AFTER_FRAMES = saturatedCounter(underrunEvidence.successfulEnqueueQueueAfterFrames),
            .LAST_UNDERRUN_MAXIMUM_SUCCESSFUL_ENQUEUE_GAP_MICROS = saturatedCounter(underrunEvidence.maximumSuccessfulEnqueueGapMicros),
            .ACCEPTED_DEMAND_COUNT = saturatedCounter(loopDemand.acceptedDemandCount),
            .ACCEPTED_CAPTURE_COUNT = saturatedCounter(loopDemand.acceptedCaptureCount),
            .LATEST_DEMAND_REVISION = saturatedCounter(loopDemand.latestDemandRevision),
            .LATEST_DEMAND_AFRAME = saturatedCounter(loopDemand.latestDemandAFrame),
            .LATEST_DEMAND_BFRAME_EXCLUSIVE = saturatedCounter(loopDemand.latestDemandBFrameExclusive),
            .LATEST_DEMAND_PRESENTED_FRAME = static_cast<Value>(loopDemand.latestDemandPresentedFrame),
            .LOOP_DEMAND_LATEST_DEMAND_BOUNDARY_ARMED = loopDemand.latestDemandBoundaryArmed ? 1 : 0,
            .LOOP_DEMAND_LATEST_DEMAND_BOUNDARY_CROSSED = loopDemand.latestDemandBoundaryCrossed ? 1 : 0,
            .LATEST_CAPTURE_REVISION = saturatedCounter(loopDemand.latestCaptureRevision),
            .LATEST_CAPTURE_AFRAME = saturatedCounter(loopDemand.latestCaptureAFrame),
            .LATEST_CAPTURE_BFRAME_EXCLUSIVE = saturatedCounter(loopDemand.latestCaptureBFrameExclusive),
            .LATEST_CAPTURE_PRESENTED_FRAME = static_cast<Value>(loopDemand.latestCapturePresentedFrame),
            .SUCCESSFUL_CAPTURE_CUTOVER_COUNT = saturatedCounter(loopCallback.successfulCaptureCutoverCount),
            .LATEST_CUTOVER_CAPTURE_REVISION = saturatedCounter(loopCallback.latestCutoverCaptureRevision),
            .LATEST_CUTOVER_CALLBACK_SEQUENCE = saturatedCounter(loopCallback.latestCutoverCallbackSequence),
            .LATEST_CUTOVER_INPUT_LATENCY_MICROS = saturatedCounter(loopCallback.latestCutoverInputLatencyMicros),
            .PROCESS_BOUNDARY_MISS_COUNT = saturatedCounter(gOutputLoopBoundaryMissCount.load(std::memory_order_relaxed)),
            .LATEST_MISS_CAPTURE_REVISION = saturatedCounter(loopCallback.latestMissCaptureRevision),
            .LATEST_MISS_CALLBACK_SEQUENCE = saturatedCounter(loopCallback.latestMissCallbackSequence),
            .LATEST_MISS_PRESENTED_FRAME = static_cast<Value>(loopCallback.latestMissPresentedFrame),
            .LATEST_DEMAND_GESTURE_ID = saturatedCounter(loopDemand.latestDemandGestureId),
            .LATEST_CAPTURE_GESTURE_ID = saturatedCounter(loopDemand.latestCaptureGestureId),
            .LOOP_DEMAND_LATEST_CAPTURE_INFERRED = loopDemand.latestCaptureInferred ? 1 : 0,
            .LATEST_CUTOVER_GENERATION = saturatedCounter(loopCallback.latestCutoverGeneration),
            .LATEST_CUTOVER_SELECTION_TOKEN = saturatedCounter(loopCallback.latestCutoverSelectionToken),
            .LATEST_CUTOVER_AFRAME = saturatedCounter(loopCallback.latestCutoverAFrame),
            .LATEST_CUTOVER_GESTURE_ID = saturatedCounter(loopCallback.latestCutoverGestureId),
            .CALLBACK_MIX_SEQUENCE = saturatedCounter(callbackMix.sequence),
            .CALLBACK_MIX_CALLBACK_SEQUENCE = saturatedCounter(callbackMix.callbackSequence),
            .CALLBACK_MIX_GENERATION = saturatedCounter(callbackMix.generation),
            .CALLBACK_MIX_SELECTION_TOKEN = saturatedCounter(callbackMix.selectionToken),
            .CALLBACK_MIX_CONTROL_REVISION = saturatedCounter(callbackMix.controlRevision),
            .CALLBACK_MIX_MISSING_CONTROL_FRAMES = saturatedCounter(callbackMix.missingControlFrames),
            .CALLBACK_MIX_STALE_CONTROL_FRAMES = saturatedCounter(callbackMix.staleControlFrames),
            .CALLBACK_MIX_MIXER_REJECTED_FRAMES = saturatedCounter(callbackMix.mixerRejectedFrames),
            .CALLBACK_MIX_INVALID_TAG_FRAMES = saturatedCounter(callbackMix.invalidTagFrames),
            .CALLBACK_MIX_ADVANCE_FAILURES = saturatedCounter(callbackMix.advanceFailures),
            .CALLBACK_MIX_SAFE_ORIGINAL_FALLBACK_FRAMES = saturatedCounter(callbackMix.safeOriginalFallbackFrames),
            .CALLBACK_MIX_MIXER_NON_FINITE_SAMPLES_SCRUBBED = saturatedCounter(callbackMix.mixerNonFiniteSamplesScrubbed),
            .CALLBACK_MIX_MIXER_LIMITER_ACTIVE_FRAMES = saturatedCounter(callbackMix.mixerLimiterActiveFrames),
            .CALLBACK_MIX_MIXER_LIMITER_SAFETY_CLAMPS = saturatedCounter(callbackMix.mixerLimiterSafetyClamps),
            .CALLBACK_MIX_FINAL_NON_FINITE_SAMPLES_SCRUBBED = saturatedCounter(callbackMix.finalNonFiniteSamplesScrubbed),
            .CALLBACK_MIX_FINAL_SAFETY_CLAMPS = saturatedCounter(callbackMix.finalSafetyClamps),
            .PHYSICAL_RUN_SEQUENCE = saturatedCounter(mPhysicalRunSequence.load(std::memory_order_relaxed)),
            .LAST_UNDERRUN_PHYSICAL_RUN_SEQUENCE = saturatedCounter(underrunEvidence.physicalRunSequence),
            .PROCESS_INCOMPLETE_STREAM_OBSERVATION_COUNT = saturatedCounter(
                gOutputIncompleteStreamObservationCount.load(std::memory_order_relaxed)),
            .LAST_CALLBACK_DEADLINE_MISS_START_MICROS = deadlineMiss[0U],
            .LAST_CALLBACK_DEADLINE_MISS_END_MICROS = deadlineMiss[1U],
        });
    }

    NativeAudioPresentationEvidence presentationEvidence() const noexcept {
        NativeAudioPresentationEvidence evidence{};
        for (unsigned attempt = 0U; attempt < 8U; ++attempt) {
            const std::uint64_t before = mOwnerEvidenceGuard.load(std::memory_order_acquire);
            if ((before & 1U) != 0U) continue;
            evidence = NativeAudioPresentationEvidence{
                .sequence = before / 2U,
                .generation = mLastOwnerGeneration.load(std::memory_order_relaxed),
                .selectionToken =
                    mLastOwnerSelectionToken.load(std::memory_order_relaxed),
                .fingerprintSequence =
                    mLastOwnerFirstFingerprintSequence.load(std::memory_order_relaxed),
                .firstTimelineFrame =
                    mLastOwnerFirstTimelineFrame.load(std::memory_order_relaxed),
                .firstOutputFrame =
                    mLastOwnerFirstOutputFrame.load(std::memory_order_relaxed),
                .callbackSequence =
                    mLastOwnerFirstCallbackSequence.load(std::memory_order_relaxed),
                .pcmFrames =
                    mLastOwnerFirstPcmFrames.load(std::memory_order_relaxed),
            };
            std::atomic_thread_fence(std::memory_order_acquire);
            const std::uint64_t after = mOwnerEvidenceGuard.load(std::memory_order_acquire);
            if (before == after) return evidence;
        }
        return {};
    }

    NativeAudioPresentedPosition presentedPosition() const noexcept {
        return mPresentedPosition.read();
    }

    NativeAudioAppliedControlEvidence appliedControlEvidence() const noexcept {
        NativeAudioAppliedControlEvidence evidence{};
        for (unsigned attempt = 0U; attempt < 8U; ++attempt) {
            const std::uint64_t before =
                mAppliedControlEvidenceGuard.load(std::memory_order_acquire);
            if ((before & 1U) != 0U) continue;
            evidence = NativeAudioAppliedControlEvidence{
                .sequence = mAppliedControlSequence.load(std::memory_order_relaxed),
                .generation = mAppliedControlGeneration.load(std::memory_order_relaxed),
                .selectionToken = mAppliedControlToken.load(std::memory_order_relaxed),
                .controlRevision = mAppliedControlRevision.load(std::memory_order_relaxed),
                .actualSourceMode = static_cast<NativeAudioSourceMode>(
                    mAppliedControlSourceMode.load(std::memory_order_relaxed)),
                .actualTempo = mAppliedControlActualTempo.load(std::memory_order_relaxed),
                .actualPitchSemitones = mAppliedControlActualPitch.load(std::memory_order_relaxed),
                .firstFingerprintSequence =
                    mAppliedControlFirstFingerprint.load(std::memory_order_relaxed),
                .lastFingerprintSequence =
                    mAppliedControlLastFingerprint.load(std::memory_order_relaxed),
                .callbackSequence =
                    mAppliedControlCallbackSequence.load(std::memory_order_relaxed),
                .pcmFrames = mAppliedControlPcmFrames.load(std::memory_order_relaxed),
            };
            std::atomic_thread_fence(std::memory_order_acquire);
            const std::uint64_t after =
                mAppliedControlEvidenceGuard.load(std::memory_order_acquire);
            if (before == after) return evidence;
        }
        return {};
    }

    NativeAudioStemGainCallbackEvidence stemGainCallbackEvidence() const noexcept {
        return mStemGainEvidence.snapshot();
    }

    NativeAudioAudibleLoopEvidence audibleLoopEvidence() const noexcept {
        NativeAudioAudibleLoopEvidence evidence{};
        for (unsigned attempt = 0U; attempt < 8U; ++attempt) {
            const std::uint64_t before =
                mAudibleLoopEvidenceGuard.load(std::memory_order_acquire);
            if ((before & 1U) != 0U) continue;
            evidence = NativeAudioAudibleLoopEvidence{
                .sequence = mAudibleLoopSequence.load(std::memory_order_relaxed),
                .generation = mAudibleLoopGeneration.load(std::memory_order_relaxed),
                .selectionToken = mAudibleLoopToken.load(std::memory_order_relaxed),
                .loopRevision = mAudibleLoopRevision.load(std::memory_order_relaxed),
                .loopCycle = mAudibleLoopCycle.load(std::memory_order_relaxed),
                .firstFingerprintSequence =
                    mAudibleLoopFirstFingerprint.load(std::memory_order_relaxed),
                .lastFingerprintSequence =
                    mAudibleLoopLastFingerprint.load(std::memory_order_relaxed),
                .callbackSequence =
                    mAudibleLoopCallbackSequence.load(std::memory_order_relaxed),
                .pcmFrames = mAudibleLoopPcmFrames.load(std::memory_order_relaxed),
            };
            std::atomic_thread_fence(std::memory_order_acquire);
            const std::uint64_t after =
                mAudibleLoopEvidenceGuard.load(std::memory_order_acquire);
            if (before == after) return evidence;
        }
        return {};
    }

    [[nodiscard]] NativeAudioPresentationJournalSnapshot presentationJournal(
        std::uint64_t afterSequence) const {
        auto *capture = mQualificationCapture.load(std::memory_order_acquire);
        auto result = capture ? capture->journal.readAfter(afterSequence) : NativeAudioPresentationJournalSnapshot{};
        result.requestedAfterSequence = afterSequence;
        result.invalidPublicationCount += std::min(mUnavailableQualificationCapture.load(std::memory_order_relaxed),
            std::numeric_limits<std::uint64_t>::max() - result.invalidPublicationCount);
        if (result.invalidPublicationCount != 0U) result.coherent = false;
        return result;
    }

private:
    CallbackLoopPlan selectCallbackLoopPlan() noexcept {
        NativeAudioOutputLoopPolicy latestLoopPolicy{};
        bool latestLoopPolicyRead = false;
        for (std::uint32_t attempt = 0U;
             attempt < 3U && !latestLoopPolicyRead;
             ++attempt) {
            latestLoopPolicyRead = mLoopPolicyMailbox.tryRead(latestLoopPolicy);
        }
        if (latestLoopPolicyRead &&
            latestLoopPolicy.generation == mCallbackGeneration) {
            const bool directDemandOwnsCallback = mHasCallbackLoopDemand &&
                mCallbackLoopDemand.generation == mCallbackGeneration;
            const bool realizesDirectDemand = directDemandOwnsCallback &&
                latestLoopPolicy.realizesLogicalDemand(mCallbackLoopDemand);
            if (!latestLoopPolicy.hasPhysicalCutover() &&
                latestLoopPolicy.physicalReady &&
                // A retained in-place tuple cannot roll back a callback-adopted physical role.
                (!mHasCallbackLoopPolicy || latestLoopPolicy.physicalRevision() ==
                    mCallbackLoopPolicy.physicalRevision()) &&
                (directDemandOwnsCallback
                    ? realizesDirectDemand
                    : (!mHasCallbackLoopPolicy ||
                       latestLoopPolicy.revision >= mCallbackLoopPolicy.revision))) {
                mCallbackLoopPolicy = latestLoopPolicy;
                mHasCallbackLoopPolicy = true;
                if (mHasCallbackLoopCandidate &&
                    mCallbackLoopCandidate.revision <= latestLoopPolicy.revision) {
                    const auto pendingRole = mRoles.publishedLoopCutover(mAppliedCommitSequence);
                    const bool ownsPendingContinuation = pendingRole.valid() &&
                        pendingRole.generation == mCallbackLoopCandidate.generation &&
                        pendingRole.transitionRevision == mCallbackLoopCandidate.physicalRevision();
                    // Logical B metadata cannot retire a separately published physical PCM owner.
                    if (!ownsPendingContinuation) {
                        mRetiredCallbackLoopCandidateRevision =
                            mCallbackLoopCandidate.physicalRevision();
                        mCallbackLoopCandidate = {};
                        mHasCallbackLoopCandidate = false;
                    }
                }
            }
        }
        const OutputRoleSwap publishedLoopRole =
            mRoles.publishedLoopCutover(mAppliedCommitSequence);
        NativeAudioOutputLoopPolicy committedLoopPolicy{};
        bool committedLoopPolicyRead = false;
        if (publishedLoopRole.valid() &&
            publishedLoopRole.generation == mCallbackGeneration) {
            for (std::uint32_t attempt = 0U;
                 attempt < 3U && !committedLoopPolicyRead;
                 ++attempt) {
                committedLoopPolicyRead =
                    mCommittedLoopCutoverPolicy.tryRead(committedLoopPolicy);
            }
        }
        const bool directDemandOwnsCallback = mHasCallbackLoopDemand &&
            mCallbackLoopDemand.generation == mCallbackGeneration;
        NativeAudioOutputLoopPolicy capturedLoopBoundary{};
        const bool capturedLoopBoundaryPending =
            mLoopCaptureMailbox.tryRead(capturedLoopBoundary) &&
            capturedLoopBoundary.revision > mRetiredCallbackLoopCaptureRevision.load(std::memory_order_acquire) &&
            mHasCallbackLoopDemand && mCallbackLoopDemand.enabled &&
            capturedLoopBoundary.enabled &&
            capturedLoopBoundary.generation == mCallbackGeneration &&
            capturedLoopBoundary.generation == mCallbackLoopDemand.generation &&
            capturedLoopBoundary.selectionToken ==
                mCallbackLoopDemand.selectionToken &&
            capturedLoopBoundary.aTimelineFrame ==
                mCallbackLoopDemand.aTimelineFrame &&
            capturedLoopBoundary.heldBGestureId ==
                mCallbackLoopDemand.heldBGestureId &&
            capturedLoopBoundary.revision <= mCallbackLoopDemand.revision;
        const bool committedRealizesDirectDemand = directDemandOwnsCallback &&
            (!capturedLoopBoundaryPending ||
                committedLoopPolicy.continuationTimelineFrame == capturedLoopBoundary.aTimelineFrame) &&
            (committedLoopPolicy.realizesLogicalDemand(mCallbackLoopDemand) ||
             committedLoopPolicy.canRetargetPreparedContinuationTo(
                 mCallbackLoopDemand, capturedLoopBoundaryPending));
        if (committedLoopPolicyRead &&
            committedLoopPolicy.callbackPhysicalCutoverReady() &&
            committedLoopPolicy.generation == publishedLoopRole.generation &&
            committedLoopPolicy.physicalRevision() ==
                publishedLoopRole.transitionRevision &&
            committedLoopPolicy.physicalRevision() !=
                mRetiredCallbackLoopCandidateRevision &&
            (directDemandOwnsCallback
                ? committedRealizesDirectDemand
                : (!mHasCallbackLoopCandidate ||
                   committedLoopPolicy.revision > mCallbackLoopCandidate.revision ||
                   committedLoopPolicy.isPhysicalPromotionOf(mCallbackLoopCandidate) ||
                   committedLoopPolicy.isPhysicalCycleSuccessorOf(
                       mCallbackLoopCandidate)))) {
            mCallbackLoopCandidate = committedLoopPolicy;
            mHasCallbackLoopCandidate = true;
        }
        // Receipt acknowledges the canonical demand; physical cutover still requires matching,
        // claimable PCM. Logical receipt alone cannot satisfy the physical boundary gate.
        const bool candidateRealizesDemand = mHasCallbackLoopCandidate &&
            mCallbackLoopCandidate.physicalReady &&
            (!capturedLoopBoundaryPending ||
                mCallbackLoopCandidate.continuationTimelineFrame == capturedLoopBoundary.aTimelineFrame) &&
            (!mHasCallbackLoopDemand ||
             mCallbackLoopCandidate.realizesLogicalDemand(mCallbackLoopDemand) ||
             mCallbackLoopCandidate.canRetargetPreparedContinuationTo(
                 mCallbackLoopDemand, capturedLoopBoundaryPending));
        NativeAudioOutputLoopPolicy callbackPreparedLoopPolicy =
            candidateRealizesDemand && mHasCallbackLoopDemand
            ? bindLogicalDemandToPreparedContinuation(
                mCallbackLoopCandidate,
                mCallbackLoopDemand, capturedLoopBoundaryPending)
            : mCallbackLoopCandidate;
        if (candidateRealizesDemand && capturedLoopBoundaryPending &&
            callbackPreparedLoopPolicy.callbackPhysicalCutoverReady()) {
            callbackPreparedLoopPolicy = bindCapturedLoopCutover(
                callbackPreparedLoopPolicy,
                mCallbackLoopDemand.bTimelineFrameExclusive,
                capturedLoopBoundary.bTimelineFrameExclusive);
        }
        const std::uint64_t capturedLoopEnforcedBoundary =
            candidateRealizesDemand && capturedLoopBoundaryPending &&
                callbackPreparedLoopPolicy.callbackPhysicalCutoverReady()
            ? callbackPreparedLoopPolicy.cutoverTimelineFrameExclusive
            : capturedLoopBoundary.bTimelineFrameExclusive;
        return {.prepared = callbackPreparedLoopPolicy, .captured = capturedLoopBoundary,
            .candidateRealizesDemand = candidateRealizesDemand,
            .capturedBoundaryPending = capturedLoopBoundaryPending,
            .capturedEnforcedBoundary = capturedLoopEnforcedBoundary};
    }

    std::size_t callbackFirstReadLimit(const CallbackLoopPlan &plan, std::size_t requestedFrames,
        std::size_t queuedFramesAtRenderDecision, bool &fixedLoopBoundaryReached) const noexcept {
        const bool callbackOwnsPreparedLoop = plan.claimedSwap.valid();
        const auto &callbackPreparedLoopPolicy = plan.prepared;
        const auto &capturedLoopBoundary = plan.captured;
        const bool capturedLoopBoundaryPending = plan.capturedBoundaryPending;
        fixedLoopBoundaryReached = false;
        const bool preparedCutoverRealizesDemand = callbackOwnsPreparedLoop &&
            mCallbackLoopCandidate.callbackPhysicalCutoverReady() &&
            mCallbackLoopCandidate.generation == mCallbackGeneration;
        const NativeAudioOutputLoopPcmAuthority pcmAuthority =
            nativeAudioOutputLoopPcmAuthority(preparedCutoverRealizesDemand);
        const bool physicalLoopCutover =
            pcmAuthority == NativeAudioOutputLoopPcmAuthority::PreparedCutover;
        const NativeAudioOutputLoopPolicy &boundaryPolicy =
            callbackPreparedLoopPolicy;
        const std::uint64_t enforcedBoundary =
            boundaryPolicy.cutoverTimelineFrameExclusive;
        std::size_t firstMaximum = requestedFrames;
        if (physicalLoopCutover &&
            boundaryPolicy.generation == mCallbackGeneration) {
            const std::uint64_t expectedPhysicalRevision =
                boundaryPolicy.cutoverSourceRevision;
            const bool exactPreviousCommittedTag =
                renderDecisionPosition().generation ==
                    mCallbackGeneration &&
                renderDecisionPosition().selectionToken ==
                    boundaryPolicy.selectionToken &&
                renderDecisionPosition().loopPolicyRevision ==
                    expectedPhysicalRevision;
            const std::int64_t previousTimeline = exactPreviousCommittedTag
                ? renderDecisionPosition().lastTimelineFrame
                : -1;
            const bool previouslyInsideRange = previousTimeline >= 0 &&
                static_cast<std::uint64_t>(previousTimeline) >=
                    boundaryPolicy.aTimelineFrame &&
                static_cast<std::uint64_t>(previousTimeline) <
                    boundaryPolicy.bTimelineFrameExclusive;
            const bool continuationOutsideRange =
                boundaryPolicy.continuationTimelineFrame <
                    boundaryPolicy.aTimelineFrame ||
                boundaryPolicy.continuationTimelineFrame >=
                    boundaryPolicy.bTimelineFrameExclusive;
            const bool exitsInstalledLoop =
                boundaryPolicy.cutoverSourceRevision != 0U &&
                (!boundaryPolicy.enabled || continuationOutsideRange);
            // Readiness may publish after the exact outgoing B tag was already consumed.
            // Retain that private read cursor; do not require a fabricated next-A tag.
            const auto &previous = renderDecisionPosition();
            const bool consumedInstalledBoundary = exactPreviousCommittedTag &&
                queuedFramesAtRenderDecision == 0U && mHasCallbackLoopPolicy &&
                mCallbackLoopPolicy.enabled &&
                mCallbackLoopPolicy.generation == mCallbackGeneration &&
                mCallbackLoopPolicy.selectionToken == boundaryPolicy.selectionToken &&
                mCallbackLoopPolicy.physicalRevision() == expectedPhysicalRevision &&
                mCallbackLoopPolicy.bTimelineFrameExclusive == enforcedBoundary &&
                previous.lastTimelineFrame >= 0 &&
                static_cast<std::uint64_t>(previous.lastTimelineFrame) >= mCallbackLoopPolicy.aTimelineFrame &&
                static_cast<std::uint64_t>(previous.lastTimelineFrame) < enforcedBoundary &&
                previous.lastNextTimelineFrame > 0 &&
                static_cast<std::uint64_t>(previous.lastNextTimelineFrame) == enforcedBoundary;
            if (consumedInstalledBoundary || nativeAudioOutputPreparedLoopOwnsTerminalCutover(
                    preparedCutoverRealizesDemand,
                    boundaryPolicy.boundaryArmedAtPublication,
                    exactPreviousCommittedTag,
                    renderDecisionPosition().terminalAfterFrame,
                    queuedFramesAtRenderDecision == 0U)) {
                firstMaximum = 0U;
                fixedLoopBoundaryReached = true;
            } else if (exitsInstalledLoop ||
                boundaryPolicy.boundaryArmedAtPublication ||
                previouslyInsideRange) {
                firstMaximum = taggedFramesBeforeLoopBoundary(
                    multibusRing(mCallbackRing),
                    mCallbackGeneration,
                    boundaryPolicy.selectionToken,
                    enforcedBoundary,
                    requestedFrames,
                    fixedLoopBoundaryReached,
                    true,
                    previousTimeline,
                    expectedPhysicalRevision,
                    nullptr,
                    capturedLoopBoundaryPending && exactPreviousCommittedTag &&
                            boundaryPolicy.continuationTimelineFrame ==
                                capturedLoopBoundary.aTimelineFrame
                        ? capturedLoopBoundary.captureReferenceTimelineFrame
                        : -1);
            } else {
                bool rangeEntered = false;
                firstMaximum = taggedFramesBeforeDormantLoopBoundary(
                    multibusRing(mCallbackRing),
                    mCallbackGeneration,
                    boundaryPolicy.selectionToken,
                    boundaryPolicy.aTimelineFrame,
                    enforcedBoundary,
                    requestedFrames,
                    rangeEntered,
                    fixedLoopBoundaryReached,
                    expectedPhysicalRevision);
            }
        }
        return firstMaximum;
    }

    bool pushTerminalPaddingReady(std::uint32_t ring, const TaggedMultibusReadLease &readable) const noexcept {
        if (mTerminalSilenceAuthorizedGeneration.load(std::memory_order_acquire) != mCallbackGeneration) return false;
        if (readable.empty()) {
            return mNaturalEndHoldGeneration == mCallbackGeneration && mNaturalEndHoldRing == ring &&
                mNaturalEndHoldWriteBoundary != 0U && outputRingWritePosition(ring) == mNaturalEndHoldWriteBoundary;
        }
        const auto &tail = readable.spans[readable.spanCount - 1U];
        const auto &tag = tail.tags[tail.frameCount - 1U];
        FinalPcmFrameTag finalTag{};
        finalTag.generation = tag.generation;
        finalTag.selectionToken = tag.selectionToken;
        finalTag.loopPolicyRevision = tag.loopPolicyRevision;
        finalTag.nextTimelineFrame = tag.nextTimelineFrame;
        const auto terminal = tag.terminalWriteBoundary != 0U ? tag.terminalWriteBoundary :
            exactTerminalBoundary(ring, finalTag, readable.absoluteFirstFrame + readable.frameCount, multibusRing(ring).epoch());
        return tag.generation == mCallbackGeneration && terminal != 0U &&
            terminal == readable.observedWritePosition &&
            terminal - readable.absoluteFirstFrame == readable.frameCount &&
            outputRingWritePosition(ring) == terminal;
    }

    bool pushSourceReady(const CallbackLoopPlan &plan, std::size_t frames) noexcept {
        const auto current = multibusRing(mCallbackRing).acquireRead(frames);
        if (!mManualSelectionRenderGate.allowsRender(mCallbackGeneration, !current.empty())) return true;
        bool boundaryReached = false;
        const auto prefix = callbackFirstReadLimit(plan, frames, current.frameCount, boundaryReached);
        if (boundaryReached && plan.claimedSwap.valid()) {
            if (prefix > current.frameCount) return false;
            const auto required = frames - prefix;
            const auto continuation = multibusRing(plan.claimedSwap.ring).acquireRead(required);
            return continuation.frameCount == required || pushTerminalPaddingReady(plan.claimedSwap.ring, continuation);
        }
        return current.frameCount == frames || pushTerminalPaddingReady(mCallbackRing, current);
    }

    bool acknowledgeCancellation() noexcept {
        return callbackAcknowledgeCancellation(mRoles, mAppliedCommitSequence);
    }

    bool readAndCutoverCallback(CallbackContext &context, const CallbackLoopPlan &plan,
        OutputTimelineFrameTag *callbackTimelineTags, std::uint64_t callbackSequence,
        std::size_t queuedFramesAtRenderDecision) noexcept {
        auto *output = context.output;
        const auto callbackStartMicros = context.callbackStartMicros;
        const auto requestedFrames = context.requestedFrames;
        auto &spans = context.spans;
        auto &spanCount = context.spanCount;
        auto &physicalFrames = context.physicalFrames;
        auto &validPcmFrames = context.validPcmFrames;
        const auto &callbackPreparedLoopPolicy = plan.prepared;
        const auto &claimedLoopSwap = plan.claimedSwap;
        const auto &capturedLoopBoundary = plan.captured;
        const bool capturedLoopBoundaryPending = plan.capturedBoundaryPending;
        const auto capturedLoopEnforcedBoundary = plan.capturedEnforcedBoundary;
        const bool callbackOwnsPreparedLoop = claimedLoopSwap.valid();
        bool roleSwapDuringDrain = false;
        const auto callbackSpanHasExactOwner = [](const CallbackSpan &span) noexcept {
            if (span.read.frameCount == 0U) return false;
            const FinalPcmFrameTag &firstTag = span.read.firstTag;
            const FinalPcmFrameTag &lastTag = span.read.lastTag;
            const bool ownershipPairValid =
                (firstTag.selectionToken == 0U &&
                 firstTag.fingerprintSequence == 0U &&
                 lastTag.selectionToken == 0U &&
                 lastTag.fingerprintSequence == 0U) ||
                (firstTag.selectionToken != 0U &&
                 firstTag.fingerprintSequence != 0U &&
                 lastTag.selectionToken == firstTag.selectionToken &&
                 lastTag.fingerprintSequence != 0U);
            return ownershipPairValid && firstTag.generation == span.generation &&
                lastTag.generation == span.generation;
        };
        if (mCallbackPlaying) {
            // A later finger publication owns the next callback. Resampling after the continuation
            // claim would combine two policy snapshots in one device quantum.
            CallbackSpan &first = spans[spanCount++];
            first.generation = mCallbackGeneration;
            first.ring = mCallbackRing;
            first.outputOffset = 0U;
            const bool physicalLoopCutover = callbackOwnsPreparedLoop &&
                mCallbackLoopCandidate.callbackPhysicalCutoverReady() &&
                mCallbackLoopCandidate.generation == mCallbackGeneration;
            const auto &boundaryPolicy = callbackPreparedLoopPolicy;
            bool fixedLoopBoundaryReached = false;
            const auto firstMaximum = callbackFirstReadLimit(plan, requestedFrames,
                queuedFramesAtRenderDecision, fixedLoopBoundaryReached);
            const OutputTimelineLoopPolicy firstTimelinePolicy = timelineLoopPolicy(
                mHasCallbackLoopPolicy ? &mCallbackLoopPolicy : nullptr);
            first.read = readOutputRing(
                mCallbackRing,
                mCallbackGeneration,
                output,
                firstMaximum,
                callbackTimelineTags,
                firstTimelinePolicy, context.mappingScratch);
            physicalFrames = first.read.frameCount;

            bool loopSwapDuringCallback = false;
            const auto readLinearRemainder = [&]() noexcept {
                if (physicalFrames >= requestedFrames || spanCount >= spans.size()) return;
                CallbackSpan &linear = spans[spanCount++];
                linear.generation = mCallbackGeneration;
                linear.ring = mCallbackRing;
                linear.outputOffset = physicalFrames;
                linear.read = readOutputRing(
                    mCallbackRing,
                    mCallbackGeneration,
                    output + physicalFrames * kChannelCount,
                    requestedFrames - physicalFrames,
                    callbackTimelineTags == nullptr
                        ? nullptr
                        : callbackTimelineTags + physicalFrames,
                    timelineLoopPolicy(
                        mHasCallbackLoopPolicy ? &mCallbackLoopPolicy : nullptr),
                        context.mappingScratch == nullptr ? nullptr : context.mappingScratch + physicalFrames);
                physicalFrames += linear.read.frameCount;
            };
            if (fixedLoopBoundaryReached && physicalFrames == firstMaximum) {
                if (physicalLoopCutover &&
                    adoptRoleSwap(context.generationTransitionOffset, claimedLoopSwap, physicalFrames)) {
                    loopSwapDuringCallback = true;
                    NativeAudioOutputLoopPolicy installed = boundaryPolicy;
                    mRetiredCallbackLoopCandidateRevision =
                        installed.physicalRevision();
                    installed.physicalReady = true;
                    installed.cutoverTimelineFrameExclusive = 0U;
                    installed.continuationTimelineFrame = 0U;
                    installed.cutoverSourceRevision = 0U;
                    mCallbackLoopPolicy = installed;
                    mHasCallbackLoopPolicy = true;
                    mCallbackLoopCandidate = {};
                    mHasCallbackLoopCandidate = false;
                    if (capturedLoopBoundaryPending &&
                        boundaryPolicy.continuationTimelineFrame == capturedLoopBoundary.aTimelineFrame) {
                        mRetiredCallbackLoopCaptureRevision.store(
                            capturedLoopBoundary.revision, std::memory_order_release);
                        mLoopDiagnostics.recordLoopCaptureCutover(
                            capturedLoopBoundary,
                            callbackSequence,
                            callbackStartMicros > 0
                                ? static_cast<std::uint64_t>(callbackStartMicros)
                                : 0U);
                    }
                    readLinearRemainder();
                } else {
                    // A failed physical claim must never turn a loop edit into silence. Keep the
                    // current producer audible; the immutable continuation remains retryable.
                    readLinearRemainder();
                }
            }
            if (callbackOwnsPreparedLoop && !loopSwapDuringCallback) {
                static_cast<void>(mRoles.releaseCallbackLoopSwap(claimedLoopSwap));
            }

            // Automatic successor drain is lower priority than an exact fixed-loop cutover.
            if (!loopSwapDuringCallback && physicalFrames < requestedFrames) {
                // The producer intentionally fences unknown ownership. Once this callback has an
                // exact current-owner tag at/after an unarmed B, that demand is dormant and must
                // no longer latch EOS or a ready automatic successor.
                const CallbackSpan *latestExactOwnerSpan = nullptr;
                for (std::size_t spanIndex = 0U; spanIndex < spanCount; ++spanIndex) {
                    const CallbackSpan &span = spans[spanIndex];
                    if (!callbackSpanHasExactOwner(span)) continue;
                    latestExactOwnerSpan = &span;
                }
                if (latestExactOwnerSpan != nullptr) {
                    refreshExplicitLoopDrainFence(
                        latestExactOwnerSpan->generation,
                        latestExactOwnerSpan->read.lastTag.selectionToken,
                        latestExactOwnerSpan->read.lastTag.timelineFrame);
                }
                static_cast<void>(acknowledgeCancellation());
                // A late logical demand becomes eligible only after matching physical PCM is
                // ready. It therefore cannot block the current callback or an exact role swap.
                static_cast<void>(adoptLatestDirectLoopDemand());
                const OutputRoleSwap drainSwap = mRoles.callbackSwap(
                    mAppliedCommitSequence,
                    true,
                    false);
                physicalFrames += callbackReadDrainRemainder(
                    requestedFrames - physicalFrames,
                    [&]() noexcept { return adoptRoleSwap(context.generationTransitionOffset, drainSwap, physicalFrames); },
                    [&](std::size_t maximumFrames) noexcept {
                    roleSwapDuringDrain = true;
                    CallbackSpan &successor = spans[spanCount++];
                    successor.generation = mCallbackGeneration;
                    successor.ring = mCallbackRing;
                    successor.outputOffset = physicalFrames;
                    successor.read = readOutputRing(
                        mCallbackRing,
                        mCallbackGeneration,
                        output + physicalFrames * kChannelCount,
                        maximumFrames,
                        callbackTimelineTags == nullptr
                            ? nullptr
                            : callbackTimelineTags + physicalFrames,
                        timelineLoopPolicy(
                            mHasCallbackLoopPolicy ? &mCallbackLoopPolicy : nullptr),
                        context.mappingScratch == nullptr ? nullptr : context.mappingScratch + physicalFrames);
                    return successor.read.frameCount;
                });
            }

            for (std::size_t spanIndex = 0U; spanIndex < spanCount; ++spanIndex) {
                CallbackSpan &span = spans[spanIndex];
                if (span.read.frameCount == 0U) continue;
                if (!callbackSpanHasExactOwner(span)) {
                    std::memset(
                        output + span.outputOffset * kChannelCount,
                        0,
                        span.read.frameCount * kChannelCount * sizeof(float));
                    mInvalidOwnershipTagFrames.fetch_add(
                        span.read.frameCount,
                        std::memory_order_relaxed);
                    saturatingAdd(
                        gOutputInvalidOwnershipTagFrames,
                        span.read.frameCount);
                    if (callbackTimelineTags != nullptr) {
                        std::fill_n(
                            callbackTimelineTags + span.outputOffset,
                            span.read.frameCount,
                            OutputTimelineFrameTag{});
                    }
                    continue;
                }
                span.valid = true;
                validPcmFrames += span.read.frameCount;
            }
            if (capturedLoopBoundaryPending && !loopSwapDuringCallback &&
                capturedLoopBoundary.revision !=
                    mLastReportedCallbackLoopCaptureMissRevision) {
                std::int64_t emittedPastCapturedBoundary = -1;
                for (std::size_t spanIndex = 0U; spanIndex < spanCount; ++spanIndex) {
                    const CallbackSpan &span = spans[spanIndex];
                    if (!span.valid || span.read.frameCount == 0U ||
                        span.generation != capturedLoopBoundary.generation ||
                        span.read.lastTag.selectionToken !=
                            capturedLoopBoundary.selectionToken ||
                        span.read.lastTag.timelineFrame < 0 ||
                        static_cast<std::uint64_t>(span.read.lastTag.timelineFrame) <
                            capturedLoopEnforcedBoundary) {
                        continue;
                    }
                    emittedPastCapturedBoundary = std::max(
                        emittedPastCapturedBoundary,
                        span.read.lastTag.timelineFrame);
                }
                if (emittedPastCapturedBoundary >= 0) {
                    mLoopDiagnostics.recordLoopBoundaryMiss(
                        capturedLoopBoundary,
                        callbackSequence,
                        emittedPastCapturedBoundary);
                    mLastReportedCallbackLoopCaptureMissRevision =
                        capturedLoopBoundary.revision;
                }
            }
        }
        return roleSwapDuringDrain;
    }

    void publishCallbackEvidence(const CallbackEvidenceContext &context,
        const OutputCallbackFinalization &finalization, std::uint64_t callbackSequence,
        std::uint64_t callbackOutputStart) noexcept {
        auto *output = context.output;
        const auto requestedFrames = context.requestedFrames;
        const auto validPcmFrames = context.validPcmFrames;
        const auto &spans = context.spans;
        const auto spanCount = context.spanCount;
        std::array<OutputOwnedFrameSpan, 3U> ownedFrameSpans{};
        for (std::size_t spanIndex = 0U; spanIndex < spanCount; ++spanIndex) {
            ownedFrameSpans[spanIndex] = {
                .outputOffset = spans[spanIndex].outputOffset,
                .frameCount = spans[spanIndex].valid
                    ? spans[spanIndex].read.frameCount
                    : 0U,
            };
        }
        const bool transitionTouchedOwnedCallback = !finalization.complete ||
            transitionTouchesAnyOwnedSpan(
                finalization.coverage,
                ownedFrameSpans.data(),
                spanCount);

        const bool mixedOwnerCallback = spanCount > 1U;
        for (std::size_t spanIndex = 0U; spanIndex < spanCount; ++spanIndex) {
            const CallbackSpan &span = spans[spanIndex];
            if (!span.valid || span.read.frameCount == 0U ||
                span.read.lastTag.selectionToken == 0U) {
                continue;
            }
            const FinalPcmFrameTag &firstTag = span.read.firstTag;
            const FinalPcmFrameTag &lastTag = span.read.lastTag;
            const bool qualification = context.qualification;
            const bool needsGainEvidence = firstTag.actualSourceMode == FinalPcmSourceMode::Stems &&
                firstTag.stemGainPreviewPublicationSequence != 0U &&
                firstTag.stemGainPreviewCoversOfferSequence != 0U;
            const std::size_t nonZeroPcmFrameCount = qualification || needsGainEvidence
                ? publishPcmLevelEvidence(span.generation, lastTag,
                    output + span.outputOffset * kChannelCount, span.read.frameCount,
                    callbackSequence, qualification)
                : 0U;
            if (qualification && context.mappingScratch != nullptr) {
                // The ring lease is released and final callback ownership is now authoritative.
                std::size_t offset = 0U;
                while (offset < span.read.frameCount) {
                    auto mapping = context.mappingScratch[span.outputOffset + offset];
                    if (mapping.pcmFrames == 0U || mapping.pcmFrames > span.read.frameCount - offset ||
                        mapping.generation != span.generation || mapping.selectionToken != firstTag.selectionToken) {
                        saturatingIncrement(mUnavailableQualificationCapture);
                        break;
                    }
                    mapping.callbackSequence = callbackSequence;
                    mapping.continuityViolationCount = span.read.continuityViolationCount;
                    mapping.callbackPcmFrames = span.read.frameCount;
                    static_cast<void>(mQualificationCapture.load(std::memory_order_acquire)->journal.publish(mapping));
                    offset += static_cast<std::size_t>(mapping.pcmFrames);
                }
            }
            const std::uint64_t previousOwner =
                mLastPresentedSelectionToken.load(std::memory_order_relaxed);
            const std::uint64_t previousPresentedGeneration =
                mLastPresentedGeneration.load(std::memory_order_relaxed);
            const bool firstPresentationForOwner =
                previousOwner != lastTag.selectionToken ||
                previousPresentedGeneration != span.generation;
            // Process outgoing then successor. The coherent public position left by a mixed
            // callback therefore belongs to the successor suffix, never stale outgoing metadata.
            mLastPresentedSelectionToken.store(lastTag.selectionToken, std::memory_order_relaxed);
            publishPresentedPosition(
                span.generation,
                lastTag,
                callbackOutputStart + span.outputOffset + span.read.frameCount - 1U,
                callbackSequence,
                span.read.frameCount,
                span.terminalAfterFrame);
            mLastPresentedSource = {lastTag, span.read.absoluteFirstFrame + span.read.frameCount,
                span.sourceEpoch != 0U ? span.sourceEpoch : multibusRing(span.ring).epoch()};
            mLastPresentedSourceRing = span.ring;
            mLastPresentedSourceOutputFrame = callbackOutputStart + span.outputOffset + span.read.frameCount - 1U;
            if (firstPresentationForOwner) {
                publishFirstPresentationEvidence(
                    span.generation,
                    firstTag,
                    callbackOutputStart + span.outputOffset,
                    callbackSequence,
                    span.read.frameCount);
            }
            mLastPresentedGeneration.store(span.generation, std::memory_order_release);

            const bool wholeCallbackOwnedPcm = completeOwnedCallbackPcm(
                mixedOwnerCallback,
                validPcmFrames,
                requestedFrames);
            const bool audibleControlEvidenceEligible = callbackControlEvidenceEligible(
                span.read, wholeCallbackOwnedPcm, transitionTouchedOwnedCallback);
            const bool audibleStemGainEvidenceEligible =
                !transitionTouchedOwnedCallback &&
                stemGainCallbackCanEstablishContinuity(
                    mixedOwnerCallback,
                    validPcmFrames,
                    requestedFrames,
                    nonZeroPcmFrameCount) &&
                span.read.audibleStemGainTupleCoherent &&
                firstTag.sourceTransitionSettled && lastTag.sourceTransitionSettled &&
                firstTag.actualSourceMode == FinalPcmSourceMode::Stems &&
                lastTag.actualSourceMode == FinalPcmSourceMode::Stems &&
                firstTag.stemGainPreviewPublicationSequence != 0U &&
                firstTag.stemGainPreviewCoversOfferSequence != 0U;
            const bool audibleLoopEvidenceEligible =
                !transitionTouchedOwnedCallback && wholeCallbackOwnedPcm &&
                span.read.audibleLoopTupleCoherent &&
                firstTag.loopEnabled && firstTag.loopRevision != 0U &&
                lastTag.loopEnabled && lastTag.loopRevision == firstTag.loopRevision &&
                lastTag.loopCycle == firstTag.loopCycle;
            if (audibleControlEvidenceEligible) {
                const NativeAudioSourceMode sourceMode =
                    firstTag.actualSourceMode == FinalPcmSourceMode::Stems
                    ? NativeAudioSourceMode::Stems
                    : NativeAudioSourceMode::Original;
                const float actualTempo = span.read.audibleTuningTupleCoherent ? firstTag.actualTempo : 0.0F;
                const float actualPitch = span.read.audibleTuningTupleCoherent ? firstTag.actualPitchSemitones : 0.0F;
                const bool tupleChanged =
                    mLastPublishedControlActualTempo != actualTempo ||
                    mLastPublishedControlActualPitch != actualPitch ||
                    !mControlEvidenceContinuity ||
                    mLastPublishedControlGeneration != span.generation ||
                    mLastPublishedControlToken != firstTag.selectionToken ||
                    mLastPublishedControlRevision != firstTag.controlRevision ||
                    mLastPublishedControlSourceMode != sourceMode;
                if (tupleChanged) {
                    publishAppliedControlEvidence(
                        span.generation,
                        firstTag,
                        lastTag,
                        sourceMode,
                        actualTempo, actualPitch,
                        callbackSequence,
                        span.read.frameCount);
                }
                mControlEvidenceContinuity = true;
            } else {
                // A transition or tuple straddle breaks the settled run. The first later
                // all-coherent callback republishes even if it returns to an earlier tuple.
                mControlEvidenceContinuity = false;
            }
            if (audibleStemGainEvidenceEligible) {
                const bool tupleChanged =
                    !mStemGainEvidenceContinuity ||
                    mLastPublishedStemGainGeneration != span.generation ||
                    mLastPublishedStemGainToken != firstTag.selectionToken ||
                    mLastPublishedStemGainPreviewSequence !=
                        firstTag.stemGainPreviewPublicationSequence ||
                    mLastPublishedStemGainOfferedSequence !=
                        firstTag.stemGainPreviewCoversOfferSequence ||
                    mLastPublishedStemGainActiveMask !=
                        firstTag.stemGainPreviewActiveMask ||
                    mLastPublishedStemGainBits != firstTag.stemGainPreviewGainBits;
                if (tupleChanged) {
                    publishStemGainCallbackEvidence(
                        span.generation,
                        firstTag,
                        lastTag,
                        callbackSequence,
                        span.read.frameCount,
                        nonZeroPcmFrameCount);
                }
                mStemGainEvidenceContinuity = true;
            } else {
                mStemGainEvidenceContinuity = false;
            }
            if (audibleLoopEvidenceEligible) {
                const bool tupleChanged =
                    !mLoopEvidenceContinuity ||
                    mLastPublishedLoopGeneration != span.generation ||
                    mLastPublishedLoopToken != firstTag.selectionToken ||
                    mLastPublishedLoopRevision != firstTag.loopRevision ||
                    mLastPublishedLoopCycle != firstTag.loopCycle;
                if (tupleChanged) {
                    publishAudibleLoopEvidence(
                        span.generation,
                        firstTag,
                        lastTag,
                        callbackSequence,
                        span.read.frameCount);
                }
                mLoopEvidenceContinuity = true;
            } else {
                mLoopEvidenceContinuity = false;
            }
        }
        if (mixedOwnerCallback) {
            // Whole-callback evidence must never be inferred from either coherent subspan.
            mControlEvidenceContinuity = false;
            mStemGainEvidenceContinuity = false;
            mLoopEvidenceContinuity = false;
        }
    }

    void recordLoopPolicyAdoption(const NativeAudioOutputLoopPolicy *policy, bool retired = false) noexcept {
        if (policy == nullptr || policy->revision == 0U ||
            mPresentedPosition.adoptedRevision() > policy->revision ||
            (!retired && mPresentedPosition.adoptedRevision() == policy->revision)) {
            return;
        }
        const std::uint64_t acceptedAt =
            policy->acceptedAtMonotonicMicros;
        const std::int64_t adoptedAtMonotonicMicros = monotonicMicros();
        const std::uint64_t callbackAt = adoptedAtMonotonicMicros > 0
            ? static_cast<std::uint64_t>(adoptedAtMonotonicMicros)
            : 0U;
        const auto inputAt = policy->inputEventAtMonotonicMicros;
        const auto publishedAt = policy->publishedAtMonotonicMicros;
        mPresentedPosition.adopt({policy->generation, policy->selectionToken, policy->revision,
            policy->enabled, policy->aTimelineFrame, policy->bTimelineFrameExclusive,
            callbackAt >= acceptedAt ? callbackAt - acceptedAt : 0U,
            inputAt != 0U && publishedAt >= inputAt ? publishedAt - inputAt : 0U,
            inputAt != 0U && callbackAt >= inputAt ? callbackAt - inputAt : 0U});
#if defined(STEMWISE_CACHE_IO_PROFILING)
        if (inputAt != 0U && publishedAt >= inputAt && callbackAt >= publishedAt) {
            AudioTrace trace{"StemWise.loop.adoption"};
            trace.counter("StemWise.loop.revision", saturatedCounter(policy->revision));
            trace.counter("StemWise.loop.input_to_atomic_us", saturatedCounter(publishedAt - inputAt));
            trace.counter("StemWise.loop.input_to_adoption_us", saturatedCounter(callbackAt - inputAt));
        }
#endif
    }

    bool installDirectLoopDemand(const NativeAudioOutputLoopPolicy &latest) noexcept {
        if (latest.physicalReady || latest.generation != mCallbackGeneration ||
            (mHasCallbackLoopDemand &&
             latest.revision < mCallbackLoopDemand.revision)) {
            return false;
        }
        mCallbackLoopDemand = latest;
        mHasCallbackLoopDemand = true;
        // Receipt of the canonical demand is distinct from preparing its physical PCM.
        recordLoopPolicyAdoption(&mCallbackLoopDemand);
        // Logical MOVE events never own the prepared role. Keep an incompatible candidate
        // dormant until the producer replaces it; a later latest-only B may reuse the same
        // immutable A head. Retiring here made a transient B sample permanently discard the
        // sole ready continuation and forced the render worker to chase every finger update.
        return true;
    }

    bool adoptRoleSwap(std::size_t &generationTransitionOffset,
        const OutputRoleSwap &roleSwap, std::size_t outputOffset) noexcept {
        const bool generationChanged = roleSwap.generation != mCallbackGeneration;
        if (!callbackAdoptRoleSwap(mRoles, roleSwap, mCallbackRing,
                mCallbackGeneration, mAppliedCommitSequence)) return false;
        if (generationChanged) {
            // A loop policy is exact-owner state. Retire it in the same callback transaction
            // that adopts a seek/new-song generation, before any PCM from that role can be
            // interpreted or discarded through the outgoing owner's A/B gate.
            resetCallbackLoopOwnerState();
            NativeAudioOutputLoopPolicy pending{};
            const bool hasPendingDemand = mLoopDemandMailbox.readLatestForGeneration(
                mCallbackGeneration,
                pending);
            std::uint64_t pendingHeadGeneration = 0U;
            std::uint64_t pendingHeadSelectionToken = 0U;
            std::int64_t pendingHeadTimelineFrame = -1;
            const TaggedMultibusReadLease pendingHead =
                multibusRing(mCallbackRing).acquireRead(1U);
            if (!pendingHead.empty() && pendingHead.spans[0U].valid()) {
                const RenderBusFrameTag &tag = pendingHead.spans[0U].tags[0U];
                pendingHeadGeneration = tag.generation;
                pendingHeadSelectionToken = tag.selectionToken;
                pendingHeadTimelineFrame = tag.timelineFrame;
            }
            static_cast<void>(mRoles.setExplicitLoopDrainFence(
                hasPendingDemand && nativeAudioOutputLoopDemandBlocksDrain(
                    pending,
                    pendingHeadGeneration,
                    pendingHeadSelectionToken,
                    pendingHeadTimelineFrame)));
            if (hasPendingDemand) {
                static_cast<void>(installDirectLoopDemand(pending));
            }
        }
        // Observing appliedGeneration also proves the old role is callback-acknowledged.
        mAppliedGeneration.store(mCallbackGeneration, std::memory_order_release);
        generationTransitionOffset = outputOffset;
        mControlEvidenceContinuity = false;
        mStemGainEvidenceContinuity = false;
        mLoopEvidenceContinuity = false;
        return true;
    }

    bool adoptLatestDirectLoopDemand() noexcept {
        NativeAudioOutputLoopPolicy latest{};
        bool adopted = false;
        if (mLoopDemandMailbox.readLatestForGeneration(mCallbackGeneration, latest) &&
            !latest.physicalReady) {
            adopted = installDirectLoopDemand(latest);
        }
        if (mHasCallbackLoopDemand && mCallbackLoopDemand.enabled &&
            mRetiredLoopDemand.matches(mCallbackLoopDemand.revision,
                mCallbackLoopDemand.generation, mCallbackLoopDemand.selectionToken)) {
            mCallbackLoopDemand.enabled = false;
            mCallbackLoopDemand.boundaryArmedAtPublication = false;
            recordLoopPolicyAdoption(&mCallbackLoopDemand, true);
            adopted = true;
        }
        return adopted;
    }

    void refreshExplicitLoopDrainFence(std::uint64_t generation, std::uint64_t selectionToken, std::int64_t timelineFrame) noexcept {
        const std::uint64_t publicationBefore =
            mLoopDemandPublicationGuard.load(std::memory_order_acquire);
        if ((publicationBefore & 1U) != 0U) return;
        const bool blocksDrain = mHasCallbackLoopDemand &&
            mCallbackLoopDemand.generation == mCallbackGeneration &&
            nativeAudioOutputLoopDemandBlocksDrain(
                mCallbackLoopDemand,
                generation,
                selectionToken,
                timelineFrame);
        if (blocksDrain) return;
        static_cast<void>(mRoles.setExplicitLoopDrainFence(false));
        const std::uint64_t publicationAfter =
            mLoopDemandPublicationGuard.load(std::memory_order_acquire);
        if (publicationAfter != publicationBefore) {
            // A concurrent producer owns the conservative state. The next callback can clear
            // it only after adopting that completed publication and proving its exact owner.
            static_cast<void>(mRoles.setExplicitLoopDrainFence(true));
        }
    }

    const NativeAudioPresentedPosition &renderDecisionPosition() const noexcept {
        return mPushRenderedPosition;
    }

    void finalizePreparedBlock(CallbackContext &context,
        std::uint64_t callbackSequence, std::uint64_t callbackDeadlineMicros) noexcept {
        auto *output = context.output;
        const auto requestedFrames = context.requestedFrames;
        const auto callbackStartMicros = context.callbackStartMicros;
        const auto generationTransitionOffset = context.generationTransitionOffset;
#ifdef STEMWISE_OUTPUT_CALLBACK_TEST_HOOKS
        mCallbackTestObservation.physicalFrames = context.physicalFrames;
        mCallbackTestObservation.preTransitionFirstLeft = output[0U];
        mCallbackTestObservation.transitionFromLeft = mLastActuallyEmittedLeft;
        mCallbackTestObservation.read = context.spanCount != 0U ? context.spans[0U].read : TaggedStereoReadResult{};
        mCallbackTestObservation.spanCount = context.spanCount;
        for (std::size_t index = 0U; index < context.spanCount; ++index)
            mCallbackTestObservation.spans[index] = context.spans[index].read;
#endif
        std::array<OutputOwnedFrameSpan, 3U> ownedSpans{};
        std::size_t ownedSpanCount = 0U;
        for (std::size_t index = 0U; index < context.spanCount; ++index) {
            const auto &span = context.spans[index];
            if (span.valid && span.read.frameCount != 0U)
                ownedSpans[ownedSpanCount++] = {span.outputOffset, span.read.frameCount};
        }
        const auto finalization = finalizeOutputCallback(
            output, requestedFrames, std::span(ownedSpans.data(), ownedSpanCount),
            mCallbackPlaying, generationTransitionOffset,
            mLastActuallyEmittedLeft, mLastActuallyEmittedRight,
            mActualSampleRate.load(std::memory_order_relaxed),
            mGenerationTransition, mFinalDeviceStage, mCallbackRecovery);
#ifdef STEMWISE_OUTPUT_CALLBACK_TEST_HOOKS
        mCallbackTestObservation.transitionTouched = !finalization.complete ||
            finalization.coverage.overlaps(0U, requestedFrames);
        while (mCallbackTestObservation.transitionFrames < requestedFrames &&
               finalization.coverage.overlaps(mCallbackTestObservation.transitionFrames, 1U)) {
            ++mCallbackTestObservation.transitionFrames;
        }
#endif
        {
            for (std::size_t frame = 0U; frame < requestedFrames; ++frame) {
                auto &evidence = mPushPendingEvidence[frame];
                evidence = {};
                evidence.tag = mPushFinalTags[frame];
                evidence.pcm = {output[frame * 2U], output[frame * 2U + 1U]};
                evidence.sequence = callbackSequence;
                evidence.qualification = context.qualification;
                if (context.mappingScratch != nullptr)
                    mQualificationCapture.load(std::memory_order_acquire)->pending[frame] = context.mappingScratch[frame];
                for (std::size_t index = 0U; index < context.spanCount; ++index) {
                    const auto &span = context.spans[index];
                    if (frame < span.outputOffset || frame - span.outputOffset >= span.read.frameCount) continue;
                    evidence.valid = span.valid;
                    evidence.ring = span.ring;
                    evidence.sourceWriteBoundary = span.read.absoluteFirstFrame +
                        frame - span.outputOffset + 1U;
                    evidence.sourceEpoch = multibusRing(span.ring).epoch();
                    evidence.terminal = span.terminalAfterFrame && frame + 1U == span.outputOffset + span.read.frameCount;
                    evidence.control = span.read.audibleControlTupleCoherent;
                    evidence.tuning = span.read.audibleTuningTupleCoherent;
                    evidence.stem = span.read.audibleStemGainTupleCoherent;
                    evidence.loop = span.read.audibleLoopTupleCoherent;
                    evidence.continuityViolations = span.read.continuityViolationCount;
                    evidence.eligible = context.spanCount == 1U && context.validPcmFrames == requestedFrames &&
                        finalization.complete && !transitionTouchesAnyOwnedSpan(finalization.coverage,
                            ownedSpans.data(), ownedSpanCount);
                    if (evidence.valid) {
                        mPushRenderedPosition = {.generation = span.generation,
                            .selectionToken = evidence.tag.selectionToken, .loopPolicyRevision = evidence.tag.loopPolicyRevision,
                            .lastTimelineFrame = evidence.tag.timelineFrame, .terminalAfterFrame = evidence.terminal,
                            .lastNextTimelineFrame = evidence.tag.nextTimelineFrame};
                    }
                    break;
                }
            }
        }

        publishCallbackMixDiagnostics(callbackSequence);
        const std::int64_t callbackEndMicros = monotonicMicros();
        const std::int64_t callbackDuration =
            std::max<std::int64_t>(callbackEndMicros - callbackStartMicros, 0);
#if defined(STEMWISE_CACHE_IO_PROFILING)
        context.trace.counter("StemWise.audio.callback.execution_us", callbackDuration);
        context.trace.counter("StemWise.audio.callback.deadline_us", saturatedCounter(callbackDeadlineMicros));
        context.trace.counter("StemWise.audio.callback.physical_frames", saturatedCounter(context.physicalFrames));
        context.trace.counter("StemWise.audio.callback.valid_frames", saturatedCounter(context.validPcmFrames));
#endif
        updateMaximum(mMaxCallbackDurationMicros, callbackDuration);
        if (static_cast<std::uint64_t>(callbackDuration) > callbackDeadlineMicros) {
            const auto publication = mCallbackDeadlineMissGuard.publish();
            mLastCallbackDeadlineMissStartMicros.store(callbackStartMicros, std::memory_order_release);
            mLastCallbackDeadlineMissEndMicros.store(callbackEndMicros, std::memory_order_release);
            mCallbackDeadlineMisses.fetch_add(1, std::memory_order_relaxed);
            saturatingIncrement(gOutputCallbackDeadlineMissCount);
        }
        return;
    }

protected:
    // Lifecycle mutex held; the adapter has joined its sole feeder and retired the physical sink.
    // Reject a live native consumer or operation before changing any ring/owner state.
    bool resetOutputAfterConsumerStopped(std::uint64_t generation) noexcept {
        PushOperation operation(mPushOperation);
        if (!operation.acquired || mClosed.load(std::memory_order_acquire) ||
            !mPushEnabled.load(std::memory_order_acquire) ||
            mPushConsumerActive.load(std::memory_order_acquire) ||
            generation <= mRoles.producerGeneration()) return false;
        mPlaying.store(false, std::memory_order_release);
        mClosing.store(true, std::memory_order_release);
        resetAllOutputRingsAfterCallbackStopped();
        const auto sequence = mRoles.forceResetAfterCallbackStopped(generation, 0U);
        if (sequence == 0U) {
            mClosing.store(false, std::memory_order_release);
            return false;
        }
        mAppliedCommitSequence = sequence;
        mCallbackRing = 0U;
        mCallbackGeneration = generation;
        resetCallbackLoopOwnerState();
        mNaturalEndHoldGeneration = mNaturalEndHoldWriteBoundary = 0U;
        mNaturalEndHoldRing = 0U;
        mTerminalSilenceAuthorizedGeneration.store(0U, std::memory_order_release);
        mAppliedGeneration.store(generation, std::memory_order_release);
        mGenerationTransition.cancel();
        mLastActuallyEmittedLeft = mLastActuallyEmittedRight = 0.F;
        mPushWriteTransition.cancel();
        mPushWriteTransitionStart.cancel();
        mPushWriteFrames = mPushWriteTransitionFrames = 0U;
        mPushLastAcceptedTransport = mPushWriteTransport = false;
        mPushTransportKind = PushTransportSilenceKind::LogicalPause;
        mControlEvidenceContinuity = mStemGainEvidenceContinuity = mLoopEvidenceContinuity = false;
        mManualSelectionRenderGate.resetOpenForRouteReopen();
        retirePushPendingSource();
        mPushTransportSequence = mPushTransportRemaining = 0U;
        if (mPushHistory) mPushHistory->releaseTransport();
        mPushSourceTransition.store(0U, std::memory_order_release);
        mPushObservedNext = mPushPresentationFloor = pushSubmittedFrames();
        mPushLastAcceptedSource = {};
        if (mPushHistory) mPushHistory->discardAccepted();
        // Last actual presentation/clock, journal and fault counters remain historical evidence.
        mEffectiveRenderPlaying.store(false, std::memory_order_release);
        mPhysicalRunRestartPending.store(true, std::memory_order_release);
        mClosing.store(false, std::memory_order_release);
        return true;
    }

    // The adapter requests a parked writer plus sink flush without holding Core's lifecycle lock.
    // Success means its ByteBuffer and Core's pending/submitted epoch have both been retired.
    virtual bool preparePushGenerationChange(std::uint64_t generation) noexcept {
        auto lock = mStreamLifecycle.acquire();
        PushOperation operation(mPushOperation);
        if (!operation.acquired) return false;
        if (mPushConsumerActive.load(std::memory_order_acquire) &&
            mPushSourceTransition.load(std::memory_order_acquire) == generation) return true;
        return !mPushConsumerActive.load(std::memory_order_acquire) &&
            mPushPendingFrames == mPushPendingOffset && mPushObservedNext == pushSubmittedFrames();
    }
    virtual void completePushGenerationChange(std::uint64_t, bool) noexcept {}
#ifdef STEMWISE_OUTPUT_CALLBACK_TEST_HOOKS
    struct CallbackTestObservation final {
        std::size_t physicalFrames{0U}, transitionFrames{0U};
        bool transitionTouched{false};
        float preTransitionFirstLeft{0.0F}, transitionFromLeft{0.0F};
        TaggedStereoReadResult read{};
        std::array<TaggedStereoReadResult, 3U> spans{};
        std::size_t spanCount{0U};
    } mCallbackTestObservation{};
    void *mCallbackTestContext{nullptr};
    void (*mCallbackTestPreview)(void *, std::uint32_t, std::uint64_t, std::size_t) noexcept{nullptr};
    void *mSourceFlushTestContext{nullptr};
    void (*mSourceFlushTestHook)(void *) noexcept{nullptr};
#endif
    [[nodiscard]] static constexpr NativeAudioFlushStatus flushStatus(
        OutputHandoffStatus status) noexcept {
        switch (status) {
            case OutputHandoffStatus::Accepted:
                return NativeAudioFlushStatus::Accepted;
            case OutputHandoffStatus::AwaitingAcknowledgement:
            case OutputHandoffStatus::WrongActiveGeneration:
                return NativeAudioFlushStatus::RetryAfterCallbackAcknowledgement;
            case OutputHandoffStatus::StaleGeneration:
                return NativeAudioFlushStatus::StaleGeneration;
            case OutputHandoffStatus::NoOpenStage:
            case OutputHandoffStatus::WrongGeneration:
            case OutputHandoffStatus::EmptyStage:
            case OutputHandoffStatus::WrongMode:
            case OutputHandoffStatus::InvalidTransitionRevision:
            case OutputHandoffStatus::AutomaticDrainFenced:
                return NativeAudioFlushStatus::InvalidState;
        }
        return NativeAudioFlushStatus::InvalidState;
    }

    struct SuccessfulEnqueueEvidence final {
        std::uint64_t sequence{0U};
        std::uint64_t monotonicMicros{0U};
        std::uint64_t generation{0U};
        std::uint64_t acceptedFrames{0U};
        std::uint64_t queueAfterFrames{0U};
    };

    void recordSuccessfulEnqueue(
        std::uint64_t generation,
        std::size_t acceptedFrames,
        std::size_t queueAfterFrames) noexcept {
        // This runs only after a write accepted final PCM (roughly refill cadence), never on a
        // rejected/backpressured attempt and never on the callback. One vDSO monotonic read here is
        // materially cheaper than timing every outer render-worker cycle.
        const std::int64_t rawNow = monotonicMicros();
        const std::uint64_t now = rawNow > 0 ? static_cast<std::uint64_t>(rawNow) : 0U;
        if (now != 0U && mPreviousSuccessfulEnqueueMonotonicMicros != 0U &&
            now >= mPreviousSuccessfulEnqueueMonotonicMicros) {
            updateMaximum(
                mMaxSuccessfulEnqueueGapMicros,
                now - mPreviousSuccessfulEnqueueMonotonicMicros);
        }
        mPreviousSuccessfulEnqueueMonotonicMicros = now;

        // The output ring is SPSC, so its sole producer is also the sole writer of this seqlock.
        // The callback never retries: an overlapping publication is reported as incoherent in the
        // fault record instead of spinning on the real-time thread.
        beginGuardedPublication(mSuccessfulEnqueueEvidenceGuard);
        mSuccessfulEnqueueMonotonicMicros.store(now, std::memory_order_relaxed);
        mSuccessfulEnqueueGeneration.store(generation, std::memory_order_relaxed);
        mSuccessfulEnqueueAcceptedFrames.store(acceptedFrames, std::memory_order_relaxed);
        mSuccessfulEnqueueQueueAfterFrames.store(queueAfterFrames, std::memory_order_relaxed);
        mSuccessfulEnqueueEvidenceGuard.fetch_add(1U, std::memory_order_release);
    }

    [[nodiscard]] bool trySuccessfulEnqueueEvidence(
        SuccessfulEnqueueEvidence &evidence) const noexcept {
        const std::uint64_t before =
            mSuccessfulEnqueueEvidenceGuard.load(std::memory_order_acquire);
        if ((before & 1U) != 0U) return false;
        SuccessfulEnqueueEvidence candidate{
            .sequence = before / 2U,
            .monotonicMicros =
                mSuccessfulEnqueueMonotonicMicros.load(std::memory_order_relaxed),
            .generation =
                mSuccessfulEnqueueGeneration.load(std::memory_order_relaxed),
            .acceptedFrames =
                mSuccessfulEnqueueAcceptedFrames.load(std::memory_order_relaxed),
            .queueAfterFrames =
                mSuccessfulEnqueueQueueAfterFrames.load(std::memory_order_relaxed),
        };
        std::atomic_thread_fence(std::memory_order_acquire);
        const std::uint64_t after =
            mSuccessfulEnqueueEvidenceGuard.load(std::memory_order_acquire);
        if (before != after) return false;
        evidence = candidate;
        return true;
    }

    [[nodiscard]] SuccessfulEnqueueEvidence successfulEnqueueEvidence() const noexcept {
        return boundedSnapshotRead<SuccessfulEnqueueEvidence>([this](auto &result) noexcept {
            return trySuccessfulEnqueueEvidence(result);
        });
    }

    struct CallbackUnderrunEvidence final {
        std::uint64_t sequence{0U};
        std::uint64_t physicalRunSequence{0U};
        std::uint64_t generationAtRenderDecision{0U};
        std::uint64_t generationAtCompletion{0U};
        std::uint64_t callbackSequence{0U};
        std::uint64_t requestedFrames{0U};
        std::uint64_t queuedFramesAtRenderDecision{0U};
        std::uint64_t physicalFrames{0U};
        std::uint64_t validPcmFrames{0U};
        std::uint64_t missingFrames{0U};
        std::uint64_t callbackStartGapMicros{0U};
        std::uint64_t latestLateCallbackStartGapMicros{0U};
        std::uint64_t latestLateCallbackSequence{0U};
        std::uint64_t manualArmCallbackCeiling{0U};
        std::uint64_t appliedCommitSequence{0U};
        bool roleSwapAtCallbackStart{false};
        bool roleSwapDuringDrain{false};
        bool manualArmBoundary{false};
        std::uint64_t monotonicMicros{0U};
        bool successfulEnqueueSnapshotCoherent{false};
        std::uint64_t successfulEnqueueSequence{0U};
        std::uint64_t successfulEnqueueGeneration{0U};
        std::uint64_t successfulEnqueueAgeMicros{0U};
        std::uint64_t successfulEnqueueAcceptedFrames{0U};
        std::uint64_t successfulEnqueueQueueAfterFrames{0U};
        std::uint64_t maximumSuccessfulEnqueueGapMicros{0U};
    };

    void publishUnderrunEvidence(
        std::uint64_t generationAtRenderDecision,
        std::uint64_t generationAtCompletion,
        std::uint64_t callbackSequence,
        std::size_t requestedFrames,
        std::size_t queuedFramesAtRenderDecision,
        std::size_t physicalFrames,
        std::size_t validPcmFrames,
        std::size_t missingFrames,
        std::uint64_t callbackStartGapMicros,
        std::uint64_t latestLateCallbackStartGapMicros,
        std::uint64_t latestLateCallbackSequence,
        std::uint64_t manualArmCallbackCeiling,
        std::uint64_t appliedCommitSequence,
        bool roleSwapAtCallbackStart,
        bool roleSwapDuringDrain,
        bool manualArmBoundary,
        std::uint64_t underrunMonotonicMicros,
        bool successfulEnqueueSnapshotCoherent,
        const SuccessfulEnqueueEvidence &successfulEnqueue,
        std::uint64_t successfulEnqueueAgeMicros,
        std::uint64_t maximumSuccessfulEnqueueGapMicros) noexcept {
        // The output consumer is the sole writer. The odd/even guard gives diagnostics one coherent
        // fault record without locks, allocation, JNI, logging, or any producer-side sampling.
        beginGuardedPublication(mUnderrunEvidenceGuard);
        mUnderrunPhysicalRunSequence.store(mPhysicalRunSequence.load(std::memory_order_relaxed),
            std::memory_order_relaxed);
        mUnderrunGenerationAtRenderDecision.store(
            generationAtRenderDecision,
            std::memory_order_relaxed);
        mUnderrunGenerationAtCompletion.store(
            generationAtCompletion,
            std::memory_order_relaxed);
        mUnderrunCallbackSequence.store(callbackSequence, std::memory_order_relaxed);
        mUnderrunRequestedFrames.store(requestedFrames, std::memory_order_relaxed);
        mUnderrunQueuedFramesAtRenderDecision.store(
            queuedFramesAtRenderDecision,
            std::memory_order_relaxed);
        mUnderrunPhysicalFrames.store(physicalFrames, std::memory_order_relaxed);
        mUnderrunValidPcmFrames.store(validPcmFrames, std::memory_order_relaxed);
        mUnderrunMissingFrames.store(missingFrames, std::memory_order_relaxed);
        mUnderrunCallbackStartGapMicros.store(
            callbackStartGapMicros,
            std::memory_order_relaxed);
        mUnderrunLatestLateCallbackStartGapMicros.store(
            latestLateCallbackStartGapMicros,
            std::memory_order_relaxed);
        mUnderrunLatestLateCallbackSequence.store(
            latestLateCallbackSequence,
            std::memory_order_relaxed);
        mUnderrunManualArmCallbackCeiling.store(
            manualArmCallbackCeiling,
            std::memory_order_relaxed);
        mUnderrunAppliedCommitSequence.store(
            appliedCommitSequence,
            std::memory_order_relaxed);
        mUnderrunRoleSwapAtCallbackStart.store(
            roleSwapAtCallbackStart ? 1U : 0U,
            std::memory_order_relaxed);
        mUnderrunRoleSwapDuringDrain.store(
            roleSwapDuringDrain ? 1U : 0U,
            std::memory_order_relaxed);
        mUnderrunManualArmBoundary.store(
            manualArmBoundary ? 1U : 0U,
            std::memory_order_relaxed);
        mUnderrunMonotonicMicros.store(
            underrunMonotonicMicros,
            std::memory_order_relaxed);
        mUnderrunSuccessfulEnqueueSnapshotCoherent.store(
            successfulEnqueueSnapshotCoherent ? 1U : 0U,
            std::memory_order_relaxed);
        mUnderrunSuccessfulEnqueueSequence.store(
            successfulEnqueue.sequence,
            std::memory_order_relaxed);
        mUnderrunSuccessfulEnqueueGeneration.store(
            successfulEnqueue.generation,
            std::memory_order_relaxed);
        mUnderrunSuccessfulEnqueueAgeMicros.store(
            successfulEnqueueAgeMicros,
            std::memory_order_relaxed);
        mUnderrunSuccessfulEnqueueAcceptedFrames.store(
            successfulEnqueue.acceptedFrames,
            std::memory_order_relaxed);
        mUnderrunSuccessfulEnqueueQueueAfterFrames.store(
            successfulEnqueue.queueAfterFrames,
            std::memory_order_relaxed);
        mUnderrunMaximumSuccessfulEnqueueGapMicros.store(
            maximumSuccessfulEnqueueGapMicros,
            std::memory_order_relaxed);
        mUnderrunEvidenceGuard.fetch_add(1U, std::memory_order_release);
    }

    [[nodiscard]] CallbackUnderrunEvidence callbackUnderrunEvidence() const noexcept {
        CallbackUnderrunEvidence evidence{};
        for (unsigned attempt = 0U; attempt < 8U; ++attempt) {
            const std::uint64_t before =
                mUnderrunEvidenceGuard.load(std::memory_order_acquire);
            if ((before & 1U) != 0U) continue;
            evidence = {
                .sequence = before / 2U,
                .physicalRunSequence = mUnderrunPhysicalRunSequence.load(std::memory_order_relaxed),
                .generationAtRenderDecision =
                    mUnderrunGenerationAtRenderDecision.load(std::memory_order_relaxed),
                .generationAtCompletion =
                    mUnderrunGenerationAtCompletion.load(std::memory_order_relaxed),
                .callbackSequence =
                    mUnderrunCallbackSequence.load(std::memory_order_relaxed),
                .requestedFrames =
                    mUnderrunRequestedFrames.load(std::memory_order_relaxed),
                .queuedFramesAtRenderDecision =
                    mUnderrunQueuedFramesAtRenderDecision.load(std::memory_order_relaxed),
                .physicalFrames =
                    mUnderrunPhysicalFrames.load(std::memory_order_relaxed),
                .validPcmFrames =
                    mUnderrunValidPcmFrames.load(std::memory_order_relaxed),
                .missingFrames =
                    mUnderrunMissingFrames.load(std::memory_order_relaxed),
                .callbackStartGapMicros =
                    mUnderrunCallbackStartGapMicros.load(std::memory_order_relaxed),
                .latestLateCallbackStartGapMicros =
                    mUnderrunLatestLateCallbackStartGapMicros.load(
                        std::memory_order_relaxed),
                .latestLateCallbackSequence =
                    mUnderrunLatestLateCallbackSequence.load(std::memory_order_relaxed),
                .manualArmCallbackCeiling =
                    mUnderrunManualArmCallbackCeiling.load(std::memory_order_relaxed),
                .appliedCommitSequence =
                    mUnderrunAppliedCommitSequence.load(std::memory_order_relaxed),
                .roleSwapAtCallbackStart =
                    mUnderrunRoleSwapAtCallbackStart.load(std::memory_order_relaxed) != 0U,
                .roleSwapDuringDrain =
                    mUnderrunRoleSwapDuringDrain.load(std::memory_order_relaxed) != 0U,
                .manualArmBoundary =
                    mUnderrunManualArmBoundary.load(std::memory_order_relaxed) != 0U,
                .monotonicMicros =
                    mUnderrunMonotonicMicros.load(std::memory_order_relaxed),
                .successfulEnqueueSnapshotCoherent =
                    mUnderrunSuccessfulEnqueueSnapshotCoherent.load(
                        std::memory_order_relaxed) != 0U,
                .successfulEnqueueSequence =
                    mUnderrunSuccessfulEnqueueSequence.load(std::memory_order_relaxed),
                .successfulEnqueueGeneration =
                    mUnderrunSuccessfulEnqueueGeneration.load(std::memory_order_relaxed),
                .successfulEnqueueAgeMicros =
                    mUnderrunSuccessfulEnqueueAgeMicros.load(std::memory_order_relaxed),
                .successfulEnqueueAcceptedFrames =
                    mUnderrunSuccessfulEnqueueAcceptedFrames.load(
                        std::memory_order_relaxed),
                .successfulEnqueueQueueAfterFrames =
                    mUnderrunSuccessfulEnqueueQueueAfterFrames.load(
                        std::memory_order_relaxed),
                .maximumSuccessfulEnqueueGapMicros =
                    mUnderrunMaximumSuccessfulEnqueueGapMicros.load(
                        std::memory_order_relaxed),
            };
            std::atomic_thread_fence(std::memory_order_acquire);
            const std::uint64_t after =
                mUnderrunEvidenceGuard.load(std::memory_order_acquire);
            if (before == after) return evidence;
        }
        return {};
    }

    struct CallbackPcmLevelEvidence final {
        std::uint64_t sequence{0U};
        std::uint64_t generation{0U};
        std::uint64_t selectionToken{0U};
        std::uint64_t fingerprintSequence{0U};
        std::uint64_t callbackSequence{0U};
        std::uint64_t pcmFrames{0U};
        float meanSquare{0.0F};
        float peak{0.0F};
    };

    [[nodiscard]] std::size_t publishPcmLevelEvidence(
        std::uint64_t generation,
        const FinalPcmFrameTag &lastTag,
        const float *interleavedStereo,
        std::size_t frameCount,
        std::uint64_t callbackSequence,
        bool qualification) noexcept {
        if (generation == 0U || lastTag.selectionToken == 0U ||
            lastTag.fingerprintSequence == 0U || interleavedStereo == nullptr ||
            frameCount == 0U || callbackSequence == 0U) {
            return 0U;
        }
        double sumSquares = 0.0;
        float peak = 0.0F;
        std::size_t nonZeroFrames = 0U;
        const std::size_t sampleCount = frameCount * kChannelCount;
        for (std::size_t frame = 0U; frame < frameCount; ++frame) {
            bool frameNonZero = false;
            for (std::size_t channel = 0U; channel < kChannelCount; ++channel) {
                const float sample = interleavedStereo[frame * kChannelCount + channel];
                if (!std::isfinite(sample)) continue;
                const float magnitude = std::abs(sample);
                frameNonZero = frameNonZero || magnitude > 0.0F;
                if (qualification) {
                    peak = std::max(peak, magnitude);
                    sumSquares += static_cast<double>(sample) * static_cast<double>(sample);
                }
            }
            if (frameNonZero) ++nonZeroFrames;
        }
        if (!qualification) return nonZeroFrames;
        const float meanSquare = static_cast<float>(
            sumSquares / static_cast<double>(sampleCount));

        beginGuardedPublication(mPcmLevelEvidenceGuard);
        mPcmLevelGeneration.store(generation, std::memory_order_relaxed);
        mPcmLevelSelectionToken.store(lastTag.selectionToken, std::memory_order_relaxed);
        mPcmLevelFingerprintSequence.store(
            lastTag.fingerprintSequence,
            std::memory_order_relaxed);
        mPcmLevelCallbackSequence.store(callbackSequence, std::memory_order_relaxed);
        mPcmLevelFrames.store(frameCount, std::memory_order_relaxed);
        mPcmLevelMeanSquareBits.store(
            std::bit_cast<std::uint32_t>(meanSquare),
            std::memory_order_relaxed);
        mPcmLevelPeakBits.store(
            std::bit_cast<std::uint32_t>(peak),
            std::memory_order_relaxed);
        mPcmLevelEvidenceGuard.fetch_add(1U, std::memory_order_release);
        return nonZeroFrames;
    }

    [[nodiscard]] CallbackPcmLevelEvidence callbackPcmLevelEvidence() const noexcept {
        CallbackPcmLevelEvidence evidence{};
        for (unsigned attempt = 0U; attempt < 8U; ++attempt) {
            const std::uint64_t before =
                mPcmLevelEvidenceGuard.load(std::memory_order_acquire);
            if ((before & 1U) != 0U) continue;
            evidence = {
                .sequence = before / 2U,
                .generation = mPcmLevelGeneration.load(std::memory_order_relaxed),
                .selectionToken =
                    mPcmLevelSelectionToken.load(std::memory_order_relaxed),
                .fingerprintSequence =
                    mPcmLevelFingerprintSequence.load(std::memory_order_relaxed),
                .callbackSequence =
                    mPcmLevelCallbackSequence.load(std::memory_order_relaxed),
                .pcmFrames = mPcmLevelFrames.load(std::memory_order_relaxed),
                .meanSquare = std::bit_cast<float>(
                    mPcmLevelMeanSquareBits.load(std::memory_order_relaxed)),
                .peak = std::bit_cast<float>(
                    mPcmLevelPeakBits.load(std::memory_order_relaxed)),
            };
            std::atomic_thread_fence(std::memory_order_acquire);
            const std::uint64_t after =
                mPcmLevelEvidenceGuard.load(std::memory_order_acquire);
            if (before == after) return evidence;
        }
        return {};
    }

    [[nodiscard]] SpscTaggedMultibusRingBuffer &multibusRing(
        std::uint32_t index) noexcept {
        return index == 0U ? mFirstMultibusRing : mSecondMultibusRing;
    }

    [[nodiscard]] const SpscTaggedMultibusRingBuffer &multibusRing(
        std::uint32_t index) const noexcept {
        return index == 0U ? mFirstMultibusRing : mSecondMultibusRing;
    }

    [[nodiscard]] NativeAudioOutputMultibusCallbackStage &multibusStage(
        std::uint32_t index) noexcept {
        return index == 0U ? mFirstMultibusStage : mSecondMultibusStage;
    }

    [[nodiscard]] std::size_t outputRingQueuedFrames(
        std::uint32_t index) const noexcept {
        return multibusRing(index).queuedFrames();
    }

    [[nodiscard]] std::size_t outputRingWritableFrames(
        std::uint32_t index) const noexcept {
        return multibusRing(index).writableFrames();
    }

    [[nodiscard]] std::uint64_t outputRingWritePosition(
        std::uint32_t index) const noexcept {
        return multibusRing(index).writePosition();
    }

    [[nodiscard]] TaggedStereoReadResult readOutputRing(
        std::uint32_t index,
        std::uint64_t generation,
        float *interleavedStereo,
        std::size_t maximumFrames,
        OutputTimelineFrameTag *outputTimelineTags,
        OutputTimelineLoopPolicy loopPolicy,
        NativeAudioPresentationSpan *mappingRuns) noexcept {
#ifdef STEMWISE_OUTPUT_CALLBACK_TEST_HOOKS
        if (mCallbackTestPreview != nullptr) {
            mCallbackTestPreview(mCallbackTestContext, index, generation, maximumFrames);
        }
#endif
        auto read = callbackReadMultibus(multibusStage(index),
            index,
            generation,
            multibusRing(index),
            mLateControlMailbox,
            interleavedStereo,
            maximumFrames,
            outputTimelineTags,
            loopPolicy, mappingRuns, mPushFinalTags.get() +
                (interleavedStereo - mPushPcm.get()) / kChannelCount);
        if (read.frameCount != 0U && read.lastTag.generation == generation && read.lastTag.selectionToken != 0U) {
            const auto boundary = read.absoluteFirstFrame + read.frameCount;
            const auto terminal = exactTerminalBoundary(index, read.lastTag, boundary, multibusRing(index).epoch());
            if (terminal != 0U) {
                read.lastTag.terminalWriteBoundary = terminal;
                if (read.frameCount == 1U) read.firstTag.terminalWriteBoundary = terminal;
                mPushFinalTags[
                    (interleavedStereo - mPushPcm.get()) / kChannelCount + read.frameCount - 1U]
                    .terminalWriteBoundary = terminal;
            }
            mLastSourceRead[index] = {read.lastTag, boundary, multibusRing(index).epoch()};
        }
        mCallbackStageSnapshots[index] = multibusStage(index).callbackSnapshot();
        mLastCallbackMixRole = index;
        return read;
    }

    void publishCallbackMixDiagnostics(std::uint64_t callbackSequence) noexcept {
        const auto &first = mCallbackStageSnapshots[0U];
        const auto &second = mCallbackStageSnapshots[1U];
        const auto &last = mCallbackStageSnapshots[mLastCallbackMixRole];
        const auto &final = mFinalDeviceStage.diagnostics();
        const auto sum = [](std::uint64_t a, std::uint64_t b) noexcept {
            return a + std::min(b, std::numeric_limits<std::uint64_t>::max() - a);
        };
        mCallbackMixPublication.publish({
            .callbackSequence = callbackSequence,
            .generation = last.generation, .selectionToken = last.selectionToken,
            .controlRevision = last.controlRevision,
            .missingControlFrames = sum(first.stage.missingControlFrames, second.stage.missingControlFrames),
            .staleControlFrames = sum(first.stage.staleControlFrames, second.stage.staleControlFrames),
            .mixerRejectedFrames = sum(first.stage.mixerRejectedFrames, second.stage.mixerRejectedFrames),
            .invalidTagFrames = sum(first.stage.invalidTagFrames, second.stage.invalidTagFrames),
            .advanceFailures = sum(first.stage.advanceFailures, second.stage.advanceFailures),
            .safeOriginalFallbackFrames = sum(first.mixer.safeOriginalFallbackFrames, second.mixer.safeOriginalFallbackFrames),
            .mixerNonFiniteSamplesScrubbed = sum(first.mixer.nonFiniteSamplesScrubbed, second.mixer.nonFiniteSamplesScrubbed),
            .mixerLimiterActiveFrames = sum(first.mixer.limiterActiveFrames, second.mixer.limiterActiveFrames),
            .mixerLimiterSafetyClamps = sum(first.mixer.limiterSafetyClamps, second.mixer.limiterSafetyClamps),
            .finalNonFiniteSamplesScrubbed = final.nonFiniteSamplesScrubbed,
            .finalSafetyClamps = final.safetyClamps,
        });
    }

    void resetInactiveOutputRing(std::uint32_t index) noexcept {
        multibusRing(index).resetInactive();
        multibusStage(index).reset();
        mLateControlBoundGeneration[index] = 0U;
        mLateControlBoundToken[index] = 0U;
    }

    void resetAllOutputRingsAfterCallbackStopped() noexcept {
        mFirstMultibusRing.resetInactive();
        mSecondMultibusRing.resetInactive();
        mFirstMultibusStage.reset();
        mSecondMultibusStage.reset();
        mLateControlBoundGeneration.fill(0U);
        mLateControlBoundToken.fill(0U);
    }

    void publishFirstPresentationEvidence(
        std::uint64_t generation,
        const FinalPcmFrameTag &firstTag,
        std::uint64_t firstOutputFrame,
        std::uint64_t callbackSequence,
        std::size_t pcmFrames) noexcept {
        beginGuardedPublication(mOwnerEvidenceGuard);
        mLastOwnerGeneration.store(generation, std::memory_order_relaxed);
        mLastOwnerSelectionToken.store(firstTag.selectionToken, std::memory_order_relaxed);
        mLastOwnerFirstFingerprintSequence.store(
            firstTag.fingerprintSequence,
            std::memory_order_relaxed);
        mLastOwnerFirstTimelineFrame.store(firstTag.timelineFrame, std::memory_order_relaxed);
        mLastOwnerFirstOutputFrame.store(firstOutputFrame, std::memory_order_relaxed);
        mLastOwnerFirstCallbackSequence.store(callbackSequence, std::memory_order_relaxed);
        mLastOwnerFirstPcmFrames.store(pcmFrames, std::memory_order_relaxed);
        mOwnerEvidenceGuard.fetch_add(1U, std::memory_order_release);
    }

    void publishPresentedPosition(
        std::uint64_t generation,
        const FinalPcmFrameTag &lastTag,
        std::uint64_t lastOutputFrame,
        std::uint64_t callbackSequence,
        std::size_t pcmFrames,
        bool terminalAfterFrame) noexcept {
        mPresentedPosition.position({
            .generation = generation, .selectionToken = lastTag.selectionToken,
            .fingerprintSequence = lastTag.fingerprintSequence, .loopPolicyRevision = lastTag.loopPolicyRevision,
            .lastTimelineFrame = lastTag.timelineFrame, .lastOutputFrame = lastOutputFrame,
            .callbackSequence = callbackSequence, .pcmFrames = pcmFrames, .terminalAfterFrame = terminalAfterFrame,
            .lastNextTimelineFrame = lastTag.nextTimelineFrame});
    }

    void publishAppliedControlEvidence(
        std::uint64_t generation,
        const FinalPcmFrameTag &firstTag,
        const FinalPcmFrameTag &lastTag,
        NativeAudioSourceMode sourceMode,
        float actualTempo, float actualPitch,
        std::uint64_t callbackSequence,
        std::size_t pcmFrames) noexcept {
        if (mAppliedControlSequenceValue != std::numeric_limits<std::uint64_t>::max()) {
            ++mAppliedControlSequenceValue;
        }
        beginGuardedPublication(mAppliedControlEvidenceGuard);
        mAppliedControlSequence.store(
            mAppliedControlSequenceValue,
            std::memory_order_relaxed);
        mAppliedControlGeneration.store(generation, std::memory_order_relaxed);
        mAppliedControlToken.store(firstTag.selectionToken, std::memory_order_relaxed);
        mAppliedControlRevision.store(firstTag.controlRevision, std::memory_order_relaxed);
        mAppliedControlActualTempo.store(actualTempo, std::memory_order_relaxed);
        mAppliedControlActualPitch.store(actualPitch, std::memory_order_relaxed);
        mAppliedControlSourceMode.store(
            static_cast<std::uint8_t>(sourceMode),
            std::memory_order_relaxed);
        mAppliedControlFirstFingerprint.store(
            firstTag.fingerprintSequence,
            std::memory_order_relaxed);
        mAppliedControlLastFingerprint.store(
            lastTag.fingerprintSequence,
            std::memory_order_relaxed);
        mAppliedControlCallbackSequence.store(callbackSequence, std::memory_order_relaxed);
        mAppliedControlPcmFrames.store(pcmFrames, std::memory_order_relaxed);
        mAppliedControlEvidenceGuard.fetch_add(1U, std::memory_order_release);

        mLastPublishedControlGeneration = generation;
        mLastPublishedControlToken = firstTag.selectionToken;
        mLastPublishedControlRevision = firstTag.controlRevision;
        mLastPublishedControlSourceMode = sourceMode;
        mLastPublishedControlActualTempo = actualTempo;
        mLastPublishedControlActualPitch = actualPitch;
    }

    void publishStemGainCallbackEvidence(
        std::uint64_t generation,
        const FinalPcmFrameTag &firstTag,
        const FinalPcmFrameTag &lastTag,
        std::uint64_t callbackSequence,
        std::size_t taggedFrameCount,
        std::size_t nonZeroPcmFrameCount) noexcept {
        if (mStemGainSequenceValue != std::numeric_limits<std::uint64_t>::max()) {
            ++mStemGainSequenceValue;
        }
        mStemGainEvidence.publish({
            .sequence = mStemGainSequenceValue,
            .generation = generation,
            .selectionToken = firstTag.selectionToken,
            .publishedSequence = firstTag.stemGainPreviewPublicationSequence,
            .offeredSequence = firstTag.stemGainPreviewCoversOfferSequence,
            .activeMask = firstTag.stemGainPreviewActiveMask,
            .gainBits = firstTag.stemGainPreviewGainBits,
            .callbackSequence = callbackSequence,
            .firstTaggedFrame = firstTag.timelineFrame,
            .lastTaggedFrame = lastTag.timelineFrame,
            .taggedFrameCount = taggedFrameCount,
            .nonZeroPcmFrameCount = nonZeroPcmFrameCount,
            .callbackObservedAtNs = monotonicNanos(),
        });

        mLastPublishedStemGainGeneration = generation;
        mLastPublishedStemGainToken = firstTag.selectionToken;
        mLastPublishedStemGainPreviewSequence =
            firstTag.stemGainPreviewPublicationSequence;
        mLastPublishedStemGainOfferedSequence =
            firstTag.stemGainPreviewCoversOfferSequence;
        mLastPublishedStemGainActiveMask = firstTag.stemGainPreviewActiveMask;
        mLastPublishedStemGainBits = firstTag.stemGainPreviewGainBits;
    }

    void publishAudibleLoopEvidence(
        std::uint64_t generation,
        const FinalPcmFrameTag &firstTag,
        const FinalPcmFrameTag &lastTag,
        std::uint64_t callbackSequence,
        std::size_t pcmFrames) noexcept {
        if (mAudibleLoopSequenceValue != std::numeric_limits<std::uint64_t>::max()) {
            ++mAudibleLoopSequenceValue;
        }
        beginGuardedPublication(mAudibleLoopEvidenceGuard);
        mAudibleLoopSequence.store(mAudibleLoopSequenceValue, std::memory_order_relaxed);
        mAudibleLoopGeneration.store(generation, std::memory_order_relaxed);
        mAudibleLoopToken.store(firstTag.selectionToken, std::memory_order_relaxed);
        mAudibleLoopRevision.store(firstTag.loopRevision, std::memory_order_relaxed);
        mAudibleLoopCycle.store(firstTag.loopCycle, std::memory_order_relaxed);
        mAudibleLoopFirstFingerprint.store(
            firstTag.fingerprintSequence,
            std::memory_order_relaxed);
        mAudibleLoopLastFingerprint.store(
            lastTag.fingerprintSequence,
            std::memory_order_relaxed);
        mAudibleLoopCallbackSequence.store(callbackSequence, std::memory_order_relaxed);
        mAudibleLoopPcmFrames.store(pcmFrames, std::memory_order_relaxed);
        mAudibleLoopEvidenceGuard.fetch_add(1U, std::memory_order_release);

        mLastPublishedLoopGeneration = generation;
        mLastPublishedLoopToken = firstTag.selectionToken;
        mLastPublishedLoopRevision = firstTag.loopRevision;
        mLastPublishedLoopCycle = firstTag.loopCycle;
    }

    std::array<NativeAudioOutputMultibusCallbackSnapshot, 2U> mCallbackStageSnapshots{};
    std::uint32_t mLastCallbackMixRole{0U};
    NativeAudioCallbackMixPublication mCallbackMixPublication;
    SpscTaggedMultibusRingBuffer mFirstMultibusRing;
    SpscTaggedMultibusRingBuffer mSecondMultibusRing;
    NativeAudioOutputMultibusCallbackStage mFirstMultibusStage;
    NativeAudioOutputMultibusCallbackStage mSecondMultibusStage;
    AudibleTimelineHistory mAudibleTimelineHistory;
    std::unique_ptr<OutputTimelineFrameTag[]> mCallbackTimelineScratch;
    const std::size_t mCallbackTimelineCapacity;
    AtomicNativeAudioOutputLoopPolicy mLoopPolicyMailbox{};
    /** Latest direct A/B. Producer publication is wait-free with respect to the audio callback. */
    LatestNativeAudioOutputLoopPolicyMailbox mLoopDemandMailbox{};
    NativeAudioOutputLoopDemandRetirement mRetiredLoopDemand{};
    /** Even/odd guard for the producer's explicit-fence + demand-mailbox transaction. */
    std::atomic<std::uint64_t> mLoopDemandPublicationGuard{0U};
    /** Physical tuple stored before the coordinator publishes its revision-keyed callback role. */
    AtomicNativeAudioOutputLoopPolicy mCommittedLoopCutoverPolicy{};
    AtomicNativeAudioOutputLoopPolicy mLoopCaptureMailbox{};
    NativeAudioOutputLoopDiagnostics mLoopDiagnostics{};
    /** Last coherent callback proof observed without ever spinning the MOVE publisher. */
    LoopCallbackDiagnostics mProducerLoopCallbackDiagnostics{};
    NativeAudioOutputLoopGestureCaptureGate mLoopGestureCaptureGate{};
    /** Last installed physical role, retained only for adoption diagnostics/history. */
    NativeAudioOutputLoopPolicy mCallbackLoopPolicy{};
    bool mHasCallbackLoopPolicy{false};
    /** Latest logical A/B, visible immediately even while its physical continuation is prepared. */
    NativeAudioOutputLoopPolicy mCallbackLoopDemand{};
    bool mHasCallbackLoopDemand{false};
    /** Latest one-shot C/S replacement, separate from the installed active-ring policy. */
    NativeAudioOutputLoopPolicy mCallbackLoopCandidate{};
    bool mHasCallbackLoopCandidate{false};
    std::uint64_t mRetiredCallbackLoopCandidateRevision{0U};
    std::atomic<std::uint64_t> mRetiredCallbackLoopCaptureRevision{0U};
    std::uint64_t mLastReportedCallbackLoopCaptureMissRevision{0U};
    NativeAudioOutputFinalDeviceStage mFinalDeviceStage;
    LateStemMixControlMailbox mLateControlMailbox;
    std::uint64_t mLateControlPublicationSequence{0U};
    std::array<std::uint64_t, LateStemMixControlMailbox::kRoleCount>
        mLateControlBoundGeneration{};
    std::array<std::uint64_t, LateStemMixControlMailbox::kRoleCount>
        mLateControlBoundToken{};
    const std::int32_t mRequestedSampleRate;
    mutable StreamLifecycleCoordinator mStreamLifecycle;
    /** Global callback-frame base corresponding to frame zero of the current route stream. */
    std::uint64_t mStreamOutputFrameBase{0U};
    /** Current stream's xrun prefix already transferred into the process ledger. */
    mutable std::int32_t mAccountedStreamXRunCount{0};

    std::atomic<bool> mClosed{false};
    std::atomic<bool> mClosing{false};
    /** Raw transport request; the manual-selection gate separately controls callback rendering. */
    std::atomic<bool> mPlaying{false};
    std::atomic<bool> mEffectiveRenderPlaying{false};
    std::atomic<std::uint64_t> mTerminalSilenceAuthorizedGeneration{0U};
    std::atomic<bool> mRecoveryRequired{false};

    ManualSelectionRenderGate mManualSelectionRenderGate;

    OutputRingRoleCoordinator mRoles;
    std::uint64_t mAppliedCommitSequence{0};
    std::uint32_t mCallbackRing{0};
    std::uint64_t mCallbackGeneration;
    std::atomic<std::uint64_t> mAppliedGeneration{1};
    std::uint64_t (*const mMemoryCapacityProbe)() noexcept;
    OutputGenerationTransition mGenerationTransition;
    OutputCallbackRecovery mCallbackRecovery;
    bool mCallbackPlaying{false};
    /** Callback-owned natural-end hold; later PCM or a role swap invalidates the exact boundary. */
    std::uint64_t mNaturalEndHoldGeneration{0U};
    std::uint32_t mNaturalEndHoldRing{0U};
    std::uint64_t mNaturalEndHoldWriteBoundary{0U};
    struct SourceReadProof { FinalPcmFrameTag tag{}; std::uint64_t writeBoundary{}, epoch{}; };
    std::array<SourceReadProof, 2U> mLastSourceRead{};
    SourceReadProof mLastPresentedSource{};
    std::uint32_t mLastPresentedSourceRing{};
    std::uint64_t mLastPresentedSourceOutputFrame{};
    float mLastActuallyEmittedLeft{0.0f};
    float mLastActuallyEmittedRight{0.0f};
    std::atomic<std::uint64_t> mLastPresentedGeneration{0};
    std::atomic<std::uint64_t> mLastPresentedSelectionToken{0};
    std::atomic<std::uint64_t> mOwnerEvidenceGuard{0};
    std::atomic<std::uint64_t> mLastOwnerGeneration{0};
    std::atomic<std::uint64_t> mLastOwnerSelectionToken{0};
    std::atomic<std::uint64_t> mLastOwnerFirstFingerprintSequence{0};
    std::atomic<std::int64_t> mLastOwnerFirstTimelineFrame{0};
    std::atomic<std::uint64_t> mLastOwnerFirstOutputFrame{0};
    std::atomic<std::uint64_t> mLastOwnerFirstCallbackSequence{0};
    std::atomic<std::uint64_t> mLastOwnerFirstPcmFrames{0};
    PresentedPositionPublication mPresentedPosition;
    std::atomic<std::uint64_t> mInvalidOwnershipTagFrames{0};
    /** Intentional newest-B fence; unlike an underrun, queued stale PCM remains unread. */
    std::atomic<QualificationCapture *> mQualificationCapture{nullptr};
    std::atomic<std::uint64_t> mUnavailableQualificationCapture{0U};

    // Exact post-output samples for the newest valid owned callback span. Float bit patterns use
    // lock-free integer atomics so the audio callback never depends on a platform float-atomic.
    std::atomic<std::uint64_t> mPcmLevelEvidenceGuard{0};
    std::atomic<std::uint64_t> mPcmLevelGeneration{0};
    std::atomic<std::uint64_t> mPcmLevelSelectionToken{0};
    std::atomic<std::uint64_t> mPcmLevelFingerprintSequence{0};
    std::atomic<std::uint64_t> mPcmLevelCallbackSequence{0};
    std::atomic<std::uint64_t> mPcmLevelFrames{0};
    std::atomic<std::uint32_t> mPcmLevelMeanSquareBits{0};
    std::atomic<std::uint32_t> mPcmLevelPeakBits{0};
    std::atomic<bool> mQualificationDiagnostics{false};

    // Written only by the output consumer; read through one seqlock off the callback.
    bool mControlEvidenceContinuity{false};
    std::uint64_t mLastPublishedControlGeneration{0};
    std::uint64_t mLastPublishedControlToken{0};
    std::uint64_t mLastPublishedControlRevision{0};
    NativeAudioSourceMode mLastPublishedControlSourceMode{NativeAudioSourceMode::Original};
    float mLastPublishedControlActualTempo{0.0F}, mLastPublishedControlActualPitch{0.0F};
    std::atomic<float> mAppliedControlActualTempo{0.0F}, mAppliedControlActualPitch{0.0F};
    std::uint64_t mAppliedControlSequenceValue{0};
    std::atomic<std::uint64_t> mAppliedControlEvidenceGuard{0};
    std::atomic<std::uint64_t> mAppliedControlSequence{0};
    std::atomic<std::uint64_t> mAppliedControlGeneration{0};
    std::atomic<std::uint64_t> mAppliedControlToken{0};
    std::atomic<std::uint64_t> mAppliedControlRevision{0};
    std::atomic<std::uint8_t> mAppliedControlSourceMode{0};
    std::atomic<std::uint64_t> mAppliedControlFirstFingerprint{0};
    std::atomic<std::uint64_t> mAppliedControlLastFingerprint{0};
    std::atomic<std::uint64_t> mAppliedControlCallbackSequence{0};
    std::atomic<std::uint64_t> mAppliedControlPcmFrames{0};

    // Whole-callback proof for the exact gain-overlay tuple carried by final PCM tags.
    bool mStemGainEvidenceContinuity{false};
    std::uint64_t mLastPublishedStemGainGeneration{0};
    std::uint64_t mLastPublishedStemGainToken{0};
    std::uint64_t mLastPublishedStemGainPreviewSequence{0};
    std::uint64_t mLastPublishedStemGainOfferedSequence{0};
    std::uint32_t mLastPublishedStemGainActiveMask{0};
    std::array<std::uint32_t, kNativeAudioStemGainLaneCount>
        mLastPublishedStemGainBits{};
    std::uint64_t mStemGainSequenceValue{0};
    StemGainCallbackPublication mStemGainEvidence{};

    // Written only by the output consumer; read through a separate sibling seqlock off callback.
    bool mLoopEvidenceContinuity{false};
    std::uint64_t mLastPublishedLoopGeneration{0};
    std::uint64_t mLastPublishedLoopToken{0};
    std::uint64_t mLastPublishedLoopRevision{0};
    std::uint64_t mLastPublishedLoopCycle{0};
    std::uint64_t mAudibleLoopSequenceValue{0};
    std::atomic<std::uint64_t> mAudibleLoopEvidenceGuard{0};
    std::atomic<std::uint64_t> mAudibleLoopSequence{0};
    std::atomic<std::uint64_t> mAudibleLoopGeneration{0};
    std::atomic<std::uint64_t> mAudibleLoopToken{0};
    std::atomic<std::uint64_t> mAudibleLoopRevision{0};
    std::atomic<std::uint64_t> mAudibleLoopCycle{0};
    std::atomic<std::uint64_t> mAudibleLoopFirstFingerprint{0};
    std::atomic<std::uint64_t> mAudibleLoopLastFingerprint{0};
    std::atomic<std::uint64_t> mAudibleLoopCallbackSequence{0};
    std::atomic<std::uint64_t> mAudibleLoopPcmFrames{0};

    std::atomic<std::uint64_t> mTotalFramesAccepted{0};
    std::atomic<std::uint64_t> mTotalFramesPresented{0};
    std::atomic<std::uint64_t> mCallbackCount{0};
    /** Exact SC callback-ticket ceiling captured immediately after the latest manual gate arm. */
    std::atomic<std::uint64_t> mManualSelectionGateLastArmCallbackSequence{0};
    std::atomic<std::uint64_t> mCallbackFrames{0};
    std::unique_ptr<float[]> mPushPcm{};
    std::unique_ptr<float[]> mPushObservationPcm{}, mPushWritePcm{};
    OutputGenerationTransition mPushWriteTransition{}, mPushWriteTransitionStart{};
    std::size_t mPushWriteFrames{}, mPushWriteTransitionFrames{};
    bool mPushLastAcceptedTransport{}, mPushWriteTransport{};
    std::unique_ptr<FinalPcmFrameTag[]> mPushFinalTags{};
    std::unique_ptr<PushFrame[]> mPushPendingEvidence{};
    std::unique_ptr<NativePushPresentationHistory> mPushHistory{};
    std::size_t mPushBlockCapacity{0U}, mPushPendingFrames{0U}, mPushPendingOffset{0U};
    std::uint64_t mPushBlockSequence{0U}, mPushObservedNext{0U}, mPushPresentationFloor{0U};
    std::uint64_t mPushTicket{0U}, mPushTransportSequence{0U};
    std::size_t mPushTransportRemaining{0U};
    PushTransportSilenceKind mPushTransportKind{PushTransportSilenceKind::LogicalPause};
    std::atomic<std::uint64_t> mPushSourceTransition{0U};
    NativeAudioPresentedPosition mPushRenderedPosition{};
    FinalPcmFrameTag mPushLastAcceptedSource{};
    bool mPushTerminal{false};
    std::atomic<bool> mPushEnabled{false};
    std::atomic_flag mPushOperation = ATOMIC_FLAG_INIT;
    std::atomic<bool> mPushConsumerActive{false};
    /** Largest device request observed on this stream; a committed A head covers at least this. */
    std::atomic<std::size_t> mMaximumCallbackRequestFrames{0U};
    std::atomic<std::uint64_t> mUnderflowEvents{0};
    std::atomic<std::uint64_t> mUnderflowFrames{0};
    // Callback-start timing separates a slow callback body from a scheduler/device-clock gap.
    // The three plain values are callback-owned and reset only while no active stream can call us.
    std::atomic<bool> mPhysicalRunRestartPending{true};
    std::atomic<std::uint64_t> mPhysicalRunSequence{0U};
    std::int64_t mPreviousCallbackStartMicros{0};
    std::uint64_t mLatestLateCallbackStartGapMicros{0};
    std::uint64_t mLatestLateCallbackSequence{0};
    std::atomic<std::uint64_t> mLastCallbackStartGapMicros{0};
    std::atomic<std::uint64_t> mMaxCallbackStartGapMicros{0};

    std::uint64_t mPreviousSuccessfulEnqueueMonotonicMicros{0};
    std::atomic<std::uint64_t> mMaxSuccessfulEnqueueGapMicros{0};
    std::atomic<std::uint64_t> mSuccessfulEnqueueEvidenceGuard{0};
    std::atomic<std::uint64_t> mSuccessfulEnqueueMonotonicMicros{0};
    std::atomic<std::uint64_t> mSuccessfulEnqueueGeneration{0};
    std::atomic<std::uint64_t> mSuccessfulEnqueueAcceptedFrames{0};
    std::atomic<std::uint64_t> mSuccessfulEnqueueQueueAfterFrames{0};

    // Written only on a real output underrun and read through one lock-free seqlock. This bounded
    // record intentionally stays separate from high-rate presentation evidence.
    std::atomic<std::uint64_t> mUnderrunEvidenceGuard{0};
    std::atomic<std::uint64_t> mUnderrunGenerationAtRenderDecision{0};
    std::atomic<std::uint64_t> mUnderrunGenerationAtCompletion{0};
    std::atomic<std::uint64_t> mUnderrunCallbackSequence{0};
    std::atomic<std::uint64_t> mUnderrunRequestedFrames{0};
    std::atomic<std::uint64_t> mUnderrunQueuedFramesAtRenderDecision{0};
    std::atomic<std::uint64_t> mUnderrunPhysicalFrames{0};
    std::atomic<std::uint64_t> mUnderrunPhysicalRunSequence{0U};
    std::atomic<std::uint64_t> mUnderrunValidPcmFrames{0};
    std::atomic<std::uint64_t> mUnderrunMissingFrames{0};
    std::atomic<std::uint64_t> mUnderrunCallbackStartGapMicros{0};
    std::atomic<std::uint64_t> mUnderrunLatestLateCallbackStartGapMicros{0};
    std::atomic<std::uint64_t> mUnderrunLatestLateCallbackSequence{0};
    std::atomic<std::uint64_t> mUnderrunManualArmCallbackCeiling{0};
    std::atomic<std::uint64_t> mUnderrunAppliedCommitSequence{0};
    std::atomic<std::uint8_t> mUnderrunRoleSwapAtCallbackStart{0};
    std::atomic<std::uint8_t> mUnderrunRoleSwapDuringDrain{0};
    std::atomic<std::uint8_t> mUnderrunManualArmBoundary{0};
    std::atomic<std::uint64_t> mUnderrunMonotonicMicros{0};
    std::atomic<std::uint8_t> mUnderrunSuccessfulEnqueueSnapshotCoherent{0};
    std::atomic<std::uint64_t> mUnderrunSuccessfulEnqueueSequence{0};
    std::atomic<std::uint64_t> mUnderrunSuccessfulEnqueueGeneration{0};
    std::atomic<std::uint64_t> mUnderrunSuccessfulEnqueueAgeMicros{0};
    std::atomic<std::uint64_t> mUnderrunSuccessfulEnqueueAcceptedFrames{0};
    std::atomic<std::uint64_t> mUnderrunSuccessfulEnqueueQueueAfterFrames{0};
    std::atomic<std::uint64_t> mUnderrunMaximumSuccessfulEnqueueGapMicros{0};
    std::atomic<std::uint64_t> mStaleFramesRejected{0};
    std::atomic<std::uint64_t> mBackpressureFrames{0};
    std::atomic<std::uint64_t> mCallbackDeadlineMisses{0};
    GuardedAtomicSnapshot mCallbackDeadlineMissGuard;
    std::atomic<std::int64_t> mLastCallbackDeadlineMissStartMicros{0}, mLastCallbackDeadlineMissEndMicros{0};
    std::atomic<std::int64_t> mMaxCallbackDurationMicros{0};

    std::atomic<std::int32_t> mObservedStreamState{
        0};
    std::atomic<std::int32_t> mAudioApi{0};
    std::atomic<std::int32_t> mActualSampleRate{0};
    std::atomic<std::int32_t> mActualChannelCount{0};
    std::atomic<std::int32_t> mFramesPerBurst{0};
    std::atomic<std::int32_t> mOutputBufferCapacity{0};
    std::atomic<std::int32_t> mActualBufferSize{0};
    std::atomic<std::int32_t> mPerformanceMode{
        0};
    std::atomic<std::int32_t> mLastError{0};
    std::atomic<std::uint64_t> mReopenCount{0};
    std::atomic<std::int64_t> mLastErrorRealtimeMillis{0};
};

} // namespace stemwise::audio
