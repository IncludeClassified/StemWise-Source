#include "AllocationProbeAttributes.h"
#include "NativeAudioOutputCallbackCore.h"
#include "LateStemMixControlMailbox.h"
#include "NativeAudioOutputFinalDeviceStage.h"
#include "NativeAudioOutputMultibusCallbackStage.h"
#include "NativeAudioOutputLoopBoundaryGate.h"
#include "OutputGenerationTransition.h"
#include "OutputCallbackOrchestrator.h"
#include "OutputRingRoleCoordinator.h"
#include "SpscTaggedMultibusRingBuffer.h"
#include "native_engine/runtime/NativePlaybackRuntime.h"
#include "native_engine/runtime/ReusableLoopContinuation.h"
#include "native_engine/dsp/SampleRateConverter.h"
#include "BoundedTestChildProcess.h"
#include "CurrentSongPcmCacheTestHooks.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <bit>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <exception>
#include <filesystem>
#include <iomanip>
#include <iostream>
#include <limits>
#include <memory>
#include <new>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <type_traits>
#include <vector>

#ifdef _WIN32
#include <windows.h>
#endif

namespace stemwise::audio::engine::cache_test {
std::size_t beforeIo(bool, std::size_t requested) noexcept { return requested; }
std::uint64_t availableStorageBytes() noexcept {
    std::error_code error;
    const auto directory = std::filesystem::temp_directory_path(error);
    if (error) return 0U;
    const auto storage = std::filesystem::space(directory, error);
    return error ? 0U : storage.available;
}
}

namespace allocation_probe {

thread_local bool enabled = false;
thread_local std::uint64_t count = 0U;
thread_local std::uint64_t bytes = 0U;
thread_local bool failNext = false;

}  // namespace allocation_probe

STEMWISE_ALLOCATION_HOOK void *operator new(std::size_t size) {
    if (allocation_probe::failNext) { allocation_probe::failNext = false; throw std::bad_alloc(); }
    if (allocation_probe::enabled) { ++allocation_probe::count; allocation_probe::bytes += size; }
    if (void *allocation = std::malloc(size)) return allocation;
    throw std::bad_alloc();
}

STEMWISE_ALLOCATION_HOOK void *operator new[](std::size_t size) {
    return ::operator new(size);
}

STEMWISE_ALLOCATION_HOOK void operator delete(void *allocation) noexcept {
    std::free(allocation);
}

STEMWISE_ALLOCATION_HOOK void operator delete[](void *allocation) noexcept {
    std::free(allocation);
}

STEMWISE_ALLOCATION_HOOK void operator delete(void *allocation, std::size_t) noexcept {
    std::free(allocation);
}

STEMWISE_ALLOCATION_HOOK void operator delete[](void *allocation, std::size_t) noexcept {
    std::free(allocation);
}

using stemwise::audio::engine::NativeRenderBusSubmission;

namespace {

using stemwise::audio::FinalPcmSourceMode;
using stemwise::audio::LateStemMixControlMailbox;
using stemwise::audio::LateStemMixControlSnapshot;
using stemwise::audio::LateStemSourceMode;
using stemwise::audio::MetronomeMode;
using stemwise::audio::MixerSourceMode;
using stemwise::audio::NativeAudioAppliedControlEvidence;
using stemwise::audio::NativeAudioAudiblePosition;
using stemwise::audio::NativeAudioAudibleLoopEvidence;
using stemwise::audio::NativeAudioDrainCancelStatus;
using stemwise::audio::NativeAudioFlushStatus;
using stemwise::audio::NativeAudioLateMixControl;
using stemwise::audio::NativeAudioOutputFinalDeviceStage;
using stemwise::audio::NativeAudioOutputLoopPolicy;
using stemwise::audio::NativeAudioOutputHandle;
using stemwise::audio::NativeAudioLoopBoundaryCoverage;
using stemwise::audio::NativeAudioLoopCutoverCommitStatus;
using stemwise::audio::NativeAudioOutputMultibusCallbackStage;
using stemwise::audio::NativeAudioPresentationEvidence;
using stemwise::audio::NativeAudioPresentedPosition;
using stemwise::audio::NativeAudioSourceMode;
using stemwise::audio::NativeAudioStemGainCallbackEvidence;
using stemwise::audio::OutputGenerationTransition;
using stemwise::audio::OutputHandoffStatus;
using stemwise::audio::OutputRingRoleCoordinator;
using stemwise::audio::RenderBusFrameTag;
using stemwise::audio::SpscTaggedMultibusRingBuffer;
using stemwise::audio::TaggedStereoReadResult;
constexpr std::uint32_t kStemwiseAllStemBusesReadyMask = 0x3FU;
using stemwise::audio::kStemwiseMultibusClickChannel;
using stemwise::audio::kStemwiseMultibusEnvelopeChannel;
using stemwise::audio::kStemwiseMultibusStride;
using stemwise::audio::stemwiseOriginalChannel;
using stemwise::audio::stemwiseStemChannel;
using stemwise::audio::engine::LaneInputBlock;
using stemwise::audio::engine::LaneInputFormat;
using stemwise::audio::engine::LaneEndOfStreamStatus;
using stemwise::audio::engine::LaneOfferStatus;
using stemwise::audio::engine::LanePcmEncoding;
using stemwise::audio::engine::NativePlaybackBank;
using stemwise::audio::engine::NativePlaybackBankIntent;
using stemwise::audio::engine::NativePlaybackCommandSnapshot;
using stemwise::audio::engine::NativePlaybackOutputPort;
using stemwise::audio::engine::NativePlaybackPublishStatus;
using stemwise::audio::engine::NativePlaybackRuntime;
using stemwise::audio::engine::NativePlaybackRuntimeConfiguration;
using stemwise::audio::engine::NativePlaybackRuntimeStatus;
using stemwise::audio::engine::SongLaneIngressBank;
using stemwise::audio::engine::SongRenderControlSnapshot;
using stemwise::audio::engine::SongRenderOwnership;

constexpr std::uint32_t kSampleRateHz = 48'000U;
constexpr std::uint32_t kCallbackFrames = 64U;
constexpr std::uint32_t kSourceRampFrames = 240U;
constexpr std::size_t kMaximumCapturedFrames = 512U;
constexpr std::size_t kOutputCapacityFrames = 4'096U;
constexpr std::size_t kInputFrames = 8'192U;
constexpr std::array<std::uint32_t, 8U> kConfigurations{
    0x00U, 0x3EU, 0x3DU, 0x3BU, 0x37U, 0x2FU, 0x1FU, 0x3FU};
constexpr std::array<std::uint32_t, 6U> kPartialMasks{
    0x3EU, 0x3DU, 0x3BU, 0x37U, 0x2FU, 0x1FU};

void require(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}

void requireNear(float actual, float expected, float tolerance, const char *message) {
    if (!std::isfinite(actual) || !std::isfinite(expected) ||
        std::abs(actual - expected) > tolerance) {
        throw std::runtime_error(std::string(message) + " actual=" + std::to_string(actual) + " expected=" + std::to_string(expected));
    }
}

template <typename Predicate>
void waitUntil(Predicate &&predicate, const char *message, std::uint32_t timeoutMs = 3'000U) {
    const auto deadline = std::chrono::steady_clock::now() +
        std::chrono::milliseconds(timeoutMs);
    while (!predicate()) {
        if (std::chrono::steady_clock::now() >= deadline) {
            throw std::runtime_error(message);
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
}

[[nodiscard]] bool sameOwner(
    SongRenderOwnership left,
    SongRenderOwnership right) noexcept {
    return left.selectionToken == right.selectionToken &&
        left.generation == right.generation;
}

class Digest final {
public:
    void addByte(std::uint8_t value) noexcept {
        mValue ^= value;
        mValue *= 1'099'511'628'211ULL;
    }

    template <typename Integer>
    void addInteger(Integer value) noexcept {
        using Unsigned = std::make_unsigned_t<Integer>;
        Unsigned converted = static_cast<Unsigned>(value);
        for (std::size_t index = 0U; index < sizeof(Unsigned); ++index) {
            addByte(static_cast<std::uint8_t>(converted & static_cast<Unsigned>(0xFFU)));
            converted >>= 8U;
        }
    }

    void addFloat(float value) noexcept {
        addInteger(std::bit_cast<std::uint32_t>(value));
    }

    [[nodiscard]] std::string hex() const {
        std::ostringstream stream;
        stream << std::uppercase << std::hex << std::setfill('0') << std::setw(16)
               << mValue;
        return stream.str();
    }

private:
    std::uint64_t mValue{14'695'981'039'346'656'037ULL};
};

struct CallbackRecord final {
    std::size_t requestedFrames{0U};
    std::size_t physicalFrames{0U};
    std::size_t transitionFrames{0U};
    bool transitionTouched{false};
    bool controlEvidencePublished{false};
    std::uint64_t generationBefore{0U}, generationAfter{0U};
    TaggedStereoReadResult read{};
    std::array<TaggedStereoReadResult, 3U> spans{};
    std::size_t spanCount{0U};
    float transitionFromLeft{0.0F};
    float preTransitionFirstLeft{0.0F};
    std::array<float, kMaximumCapturedFrames * 2U> pcm{};
    std::array<float, kMaximumCapturedFrames> originalEndpointLeft{};
    std::array<float, kMaximumCapturedFrames> stemsEndpointLeft{};

    TaggedStereoReadResult wholeRead() const noexcept {
        TaggedStereoReadResult result{};
        for (std::size_t index = 0U; index < spanCount; ++index) {
            const auto &span = spans[index];
            if (span.frameCount == 0U) continue;
            if (result.frameCount == 0U) result.firstTag = span.firstTag;
            result.lastTag = span.lastTag;
            result.frameCount += span.frameCount;
            result.continuityViolationCount += span.continuityViolationCount;
        }
        return result;
    }
};

class ClosureOutput final : public stemwise::audio::NativeAudioOutputCallbackCore {
public:
    ~ClosureOutput() override { static_cast<void>(quiescePushConsumer()); }
    explicit ClosureOutput(std::uint32_t declaredMask = kStemwiseAllStemBusesReadyMask,
        std::size_t capacityFrames = kOutputCapacityFrames, std::uint64_t initialGeneration = 1U,
        std::int32_t burstFrames = kCallbackFrames, std::int32_t bufferFrames = kCallbackFrames * 4U,
        std::uint64_t (*memoryCapacityProbe)() noexcept = &stemwise::audio::availableAudioMemoryBytes)
        : NativeAudioOutputCallbackCore(capacityFrames, kSampleRateHz, initialGeneration, memoryCapacityProbe), mDeclaredMask(declaredMask) {
        mActualSampleRate.store(kSampleRateHz);
        mActualChannelCount.store(2);
        mFramesPerBurst.store(burstFrames);
        mActualBufferSize.store(bufferFrames);
        mCallbackTestContext = this;
        mCallbackTestPreview = [](void *context, std::uint32_t role, std::uint64_t generation,
                                   std::size_t frames) noexcept {
            auto &output = *static_cast<ClosureOutput *>(context);
            const auto heldPhysical = output.mHeldPushPhysical.load(std::memory_order_acquire);
            const auto head = heldPhysical == 0U ? stemwise::audio::TaggedMultibusReadLease{} :
                output.multibusRing(role).acquireRead(1U);
            const bool heldRole = heldPhysical == 0U || (!head.empty() &&
                head.spans[0U].tags[0U].loopPolicyRevision == heldPhysical);
            if (output.mHoldPushRead.load(std::memory_order_acquire) && heldRole) {
                output.mPushReadEntered.store(true, std::memory_order_release);
                const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(2);
                while (!output.mReleasePushRead.load(std::memory_order_acquire) &&
                    std::chrono::steady_clock::now() < deadline) std::this_thread::yield();
            }
            if (output.mCurrentRecord != nullptr) {
                output.previewEndpoints(role, generation, frames, *output.mCurrentRecord);
            }
        };
        mSourceFlushTestContext = this;
        mSourceFlushTestHook = [](void *context) noexcept {
            auto &output = *static_cast<ClosureOutput *>(context);
            if (!output.mHoldSourceFlush.load(std::memory_order_acquire)) return;
            output.mSourceFlushEntered.store(true, std::memory_order_release);
            const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(2);
            while (!output.mReleaseSourceFlush.load(std::memory_order_acquire) &&
                std::chrono::steady_clock::now() < deadline) std::this_thread::yield();
        };
    }
    [[nodiscard]] NativePlaybackOutputPort port() noexcept {
        auto result = stemwise::audio::engine::borrowNativeAudioOutputPort(
            static_cast<NativeAudioOutputCallbackCore *>(this));
        // These observers retain failure injection and independent payload assertions; all work
        // is delegated to the same producer/bridge/callback implementation used by Android.
        result.enqueue = &enqueueObserved;
        result.publishLateMixControl = &publishObserved;
        result.commitHandoff = &commitObserved;
        result.queuedFrames = &queuedObserved;
        result.queuedFramesForGeneration = &queuedGenerationObserved;
        return result;
    }
    [[nodiscard]] bool setPlaying(bool playing) noexcept override {
        if (!mPushEnabled.load(std::memory_order_acquire))
            static_cast<void>(configurePushConsumer(std::min<std::size_t>(kMaximumCapturedFrames, mCallbackTimelineCapacity)));
        auto lock = mStreamLifecycle.acquire();
        mPlaying.store(playing, std::memory_order_release);
        return true;
    }
    [[nodiscard]] bool quiesceIfPaused() noexcept override {
        auto lock = mStreamLifecycle.acquire();
        if (mPlaying.load(std::memory_order_acquire)) return false;
        adoptHandoffAfterPhysicalPause();
        return true;
    }
    bool retireForStop(std::uint64_t generation) noexcept {
        auto lock = mStreamLifecycle.acquire();
        return resetOutputAfterConsumerStopped(generation);
    }
    [[nodiscard]] NativeAudioAudiblePosition audiblePosition() const noexcept override { return {}; }
    [[nodiscard]] std::uint64_t commitCount() const noexcept { return mCommitCount.load(); }
    void holdCommit(std::uint64_t generation) noexcept { mHeldCommitGeneration.store(generation); }
    bool commitEntered() const noexcept { return mCommitEntered.load(); }
    void releaseCommit() noexcept { mHeldCommitGeneration.store(0U); }
    [[nodiscard]] bool playing() const noexcept { return mPlaying.load(std::memory_order_acquire); }
    void setPushPrefillPlaying() noexcept { mPlaying.store(true, std::memory_order_release); }
    void holdPushRead() noexcept { mHoldPushRead.store(true, std::memory_order_release); }
    void holdPushReadForPhysical(std::uint64_t physical) noexcept {
        mHeldPushPhysical.store(physical, std::memory_order_release);
        holdPushRead();
    }
    bool pushReadEntered() const noexcept { return mPushReadEntered.load(std::memory_order_acquire); }
    void releasePushRead() noexcept { mReleasePushRead.store(true, std::memory_order_release); }
    void holdSourceFlush() noexcept { mHoldSourceFlush.store(true, std::memory_order_release); }
    bool sourceFlushEntered() const noexcept { return mSourceFlushEntered.load(std::memory_order_acquire); }
    void releaseSourceFlush() noexcept { mReleaseSourceFlush.store(true, std::memory_order_release); }
    std::uint64_t pushResetRequests() const noexcept { return mPushResetRequests.load(); }
    [[nodiscard]] std::uint64_t terminalHoldGeneration() const noexcept { return mNaturalEndHoldGeneration; }
    [[nodiscard]] std::uint64_t terminalAuthorizationGeneration() const noexcept {
        return mTerminalSilenceAuthorizedGeneration.load(std::memory_order_acquire);
    }
    [[nodiscard]] std::size_t queuedForGeneration(std::uint64_t generation) const noexcept {
        return queuedFramesForGeneration(generation);
    }
    [[nodiscard]] std::size_t injectUnauthorizedPcm(
        const float *pcm, std::size_t frames, const RenderBusFrameTag &tag) noexcept {
        return multibusRing(mCallbackRing).writeTagged(pcm, frames, tag);
    }
    [[nodiscard]] std::uint64_t pendingPhysicalLoopRevision() const noexcept {
        const auto swap = mRoles.publishedLoopCutover(mAppliedCommitSequence);
        return swap.valid() ? swap.transitionRevision : 0U;
    }
    [[nodiscard]] bool callbackHasPublishedCoverage(std::uint64_t generation, std::size_t frames) const noexcept {
        const auto queued = queuedFramesForGeneration(generation);
        if (queued >= frames) return true;
        const auto swap = mRoles.publishedLoopCutover(mAppliedCommitSequence);
        NativeAudioOutputLoopPolicy policy{};
        if (!swap.valid() || swap.generation != generation ||
            !mCommittedLoopCutoverPolicy.tryRead(policy) ||
            policy.physicalRevision() != swap.transitionRevision) return false;
        if (mHasCallbackLoopDemand) {
            if (!policy.realizesLogicalDemand(mCallbackLoopDemand) &&
                !policy.canRetargetPreparedContinuationTo(mCallbackLoopDemand)) return false;
            policy = stemwise::audio::bindLogicalDemandToPreparedContinuation(policy, mCallbackLoopDemand);
        }
        const auto position = presentedPosition();
        const auto coverage = loopBoundaryCoverage(generation, policy.selectionToken,
            policy.cutoverTimelineFrameExclusive, policy.cutoverSourceRevision);
        const bool boundaryPresented = position.generation == generation &&
            position.selectionToken == policy.selectionToken && position.lastTimelineFrame >= 0 &&
            position.loopPolicyRevision == policy.cutoverSourceRevision &&
            position.lastNextTimelineFrame >= 0 &&
            static_cast<std::uint64_t>(position.lastNextTimelineFrame) >= policy.cutoverTimelineFrameExclusive;
        // A spare is consumable only when exact outgoing PCM reaches its boundary, never merely
        // because a producer committed it. This driver waits for readiness, then invokes real render.
        return (coverage.boundaryQueued || boundaryPresented) &&
            queued + multibusRing(swap.ring).queuedFrames() >= frames;
    }
    [[nodiscard]] bool nextFramesMatch(std::uint64_t generation, std::size_t frames,
                                      std::uint32_t mask, std::uint64_t revision = 0U) const noexcept {
        const auto target = mRoles.ringForGeneration(generation);
        if (!target.valid()) return false;
        const auto lease = multibusRing(target.ring).acquireRead(frames);
        if (lease.frameCount != frames) return false;
        for (std::size_t span = 0U; span < lease.spanCount; ++span) {
            for (std::size_t frame = 0U; frame < lease.spans[span].frameCount; ++frame) {
                const auto &tag = lease.spans[span].tags[frame];
                if (tag.stemBusReadyMask != mask ||
                    (revision != 0U && tag.canonicalControlRevision != revision)) return false;
            }
        }
        return true;
    }
    [[nodiscard]] std::size_t unmatchedPrefixFrames(std::uint64_t generation,
        std::uint32_t expectedMask) const noexcept {
        const auto target = mRoles.ringForGeneration(generation);
        if (!target.valid()) return 0U;
        const auto lease = multibusRing(target.ring).acquireRead(kCallbackFrames);
        std::size_t prefix{};
        for (std::size_t span = 0U; span < lease.spanCount; ++span) {
            for (std::size_t frame = 0U; frame < lease.spans[span].frameCount; ++frame) {
                if (lease.spans[span].tags[frame].stemBusReadyMask == expectedMask) return prefix;
                ++prefix;
            }
        }
        return prefix;
    }
    void holdNextOriginalSubmission(SongRenderOwnership owner, std::uint64_t revision) noexcept {
        mHeldRawOwner = owner;
        mHeldRawRevision = revision;
        mOriginalSubmissionHeld.store(false, std::memory_order_relaxed);
        mRejectOriginalSubmission.store(true, std::memory_order_release);
    }
    [[nodiscard]] bool originalSubmissionHeld() const noexcept {
        return mOriginalSubmissionHeld.load(std::memory_order_acquire);
    }
    void holdManagedRevision(std::uint64_t revision) noexcept {
        mManagedSubmissionHeld.store(false, std::memory_order_relaxed);
        mHeldManagedRevision.store(revision, std::memory_order_release);
    }
    [[nodiscard]] bool managedSubmissionHeld() const noexcept {
        return mManagedSubmissionHeld.load(std::memory_order_acquire);
    }
    void setProducerHeld(bool held) noexcept {
        mProducerHeld.store(held, std::memory_order_release);
        if (!held) mRejectOriginalSubmission.store(false, std::memory_order_release);
    }
    [[nodiscard]] std::uint64_t emittedMaskBits() const noexcept { return mEmittedMaskBits.load(); }
    [[nodiscard]] bool partialProductionMaskSeen() const noexcept { return mPartialProductionMaskSeen.load(); }
    [[nodiscard]] std::size_t maximumQueuedFrames() const noexcept { return mMaximumQueuedFrames.load(); }
    [[nodiscard]] std::uint64_t lastControlRevision() const noexcept { return mLastControlRevision.load(); }
    [[nodiscard]] NativeAudioSourceMode lastControlSourceMode() const noexcept {
        return static_cast<NativeAudioSourceMode>(mLastControlSourceMode.load());
    }
    [[nodiscard]] NativeAudioAppliedControlEvidence controlEvidence() const noexcept {
        return appliedControlEvidence();
    }
    [[nodiscard]] bool invalidFinalSampleSeen() const noexcept { return mInvalidFinalSample.load(); }
    [[nodiscard]] CallbackRecord callback(std::size_t frames) noexcept {
        CallbackRecord result{};
        result.requestedFrames = frames;
        if (frames == 0U || frames > kMaximumCapturedFrames) return result;
        const auto before = appliedControlEvidence().sequence;
        result.generationBefore = mCallbackGeneration;
        mCurrentRecord = &result;
        if (!mPushEnabled.load(std::memory_order_acquire))
            static_cast<void>(configurePushConsumer(std::min<std::size_t>(kMaximumCapturedFrames, mCallbackTimelineCapacity)));
        mCallbackTestObservation = {};
        if (beginPushConsumer()) {
            const auto block = preparePushBlock(frames);
            if (block.frames != 0U) {
                std::copy_n(block.samples, block.frames * 2U, result.pcm.data());
                if (preparePushWrite(result.pcm.data(), block.frames, false) &&
                    acceptPushPrefix(block.sequence, block.frames))
                    static_cast<void>(observePushPresentation(pushSubmittedFrames() - 1U));
            } else if (!playing()) {
                const auto silence = preparePushTransportSilence(PushTransportSilenceKind::LogicalPause);
                if (silence != 0U && preparePushWrite(result.pcm.data(), frames, true) &&
                    acceptPushTransportSilence(silence, frames))
                    static_cast<void>(observePushPresentation(pushSubmittedFrames() - 1U));
            }
            static_cast<void>(quiescePushConsumer());
        }
        result.generationAfter = mCallbackGeneration;
        mCurrentRecord = nullptr;
        const auto &actual = mCallbackTestObservation;
        result.physicalFrames = actual.physicalFrames;
        result.transitionFrames = actual.transitionFrames;
        result.transitionTouched = actual.transitionTouched;
        result.read = actual.read;
        result.spans = actual.spans;
        result.spanCount = actual.spanCount;
        result.transitionFromLeft = actual.transitionFromLeft;
        result.preTransitionFirstLeft = actual.preTransitionFirstLeft;
        result.controlEvidencePublished = appliedControlEvidence().sequence != before;
        for (std::size_t sample = 0U; sample < frames * 2U; ++sample) {
            if (!std::isfinite(result.pcm[sample]) ||
                std::abs(result.pcm[sample]) > NativeAudioOutputFinalDeviceStage::kDeviceCeiling) {
                mInvalidFinalSample.store(true);
            }
        }
        return result;
    }
private:
    bool preparePushGenerationChange(std::uint64_t generation) noexcept override {
        mPushResetRequests.fetch_add(1U, std::memory_order_relaxed);
        return NativeAudioOutputCallbackCore::preparePushGenerationChange(generation);
    }
    static ClosureOutput &self(NativeAudioOutputHandle handle) noexcept {
        return *static_cast<ClosureOutput *>(static_cast<NativeAudioOutputCallbackCore *>(handle));
    }
    static std::size_t enqueueObserved(NativeAudioOutputHandle handle,
                                       const NativeRenderBusSubmission &submission) noexcept {
        auto &output = self(handle);
        if (submission.managedLoopContext.revision != 0U &&
            submission.managedLoopContext.revision == output.mHeldManagedRevision.load(std::memory_order_acquire)) {
            output.mManagedSubmissionHeld.store(true, std::memory_order_release);
            return 0U;
        }
        if (output.mRejectOriginalSubmission.load(std::memory_order_acquire) &&
            submission.generation == output.mHeldRawOwner.generation &&
            submission.selectionToken == output.mHeldRawOwner.selectionToken &&
            submission.canonicalControlRevision == output.mHeldRawRevision && submission.stemBusReadyMask == 0U) {
            output.mOriginalSubmissionHeld.store(true, std::memory_order_release);
            return 0U;
        }
        const auto accepted = stemwise::audio::enqueueRenderBusTimeline(handle, submission);
        const auto mask = submission.stemBusReadyMask;
        if (accepted != 0U) {
            output.mEmittedMaskBits.fetch_or(std::uint64_t{1U} <<
                (mask == 0U ? 0U : mask == output.mDeclaredMask ? 1U : 2U));
            if (mask != 0U && mask != output.mDeclaredMask) output.mPartialProductionMaskSeen.store(true);
            stemwise::audio::updateMaximum(output.mMaximumQueuedFrames,
                output.queuedFramesForGeneration(submission.generation));
        }
        return accepted;
    }
    static bool publishObserved(NativeAudioOutputHandle handle,
                                const NativeAudioLateMixControl &control) noexcept {
        auto &output = self(handle);
        if (!stemwise::audio::publishNativeAudioOutputLateMixControl(handle, control)) return false;
        output.mLastControlSourceMode.store(static_cast<std::uint8_t>(control.sourceMode));
        output.mLastControlRevision.store(control.canonicalControlRevision, std::memory_order_release);
        return true;
    }
    static bool commitObserved(NativeAudioOutputHandle handle, std::uint64_t generation) noexcept {
        auto &output = self(handle);
        if (output.mHeldCommitGeneration.load() == generation) {
            output.mCommitEntered.store(true);
            const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(3);
            while (output.mHeldCommitGeneration.load() == generation) {
                if (std::chrono::steady_clock::now() >= deadline) return false;
                std::this_thread::yield();
            }
        }
        const bool accepted = stemwise::audio::commitNativeAudioOutputHandoff(handle, generation);
        if (accepted) output.mCommitCount.fetch_add(1U);
        return accepted;
    }
    static std::size_t queuedObserved(NativeAudioOutputHandle handle) noexcept {
        auto &output = self(handle);
        return output.mProducerHeld.load(std::memory_order_acquire)
            ? kOutputCapacityFrames : stemwise::audio::nativeAudioOutputQueuedFrames(handle);
    }
    static std::size_t queuedGenerationObserved(NativeAudioOutputHandle handle,
                                               std::uint64_t generation) noexcept {
        auto &output = self(handle);
        return output.mProducerHeld.load(std::memory_order_acquire)
            ? kOutputCapacityFrames : output.queuedFramesForGeneration(generation);
    }
    void previewEndpoints(
        std::uint32_t role,
        std::uint64_t generation,
        std::size_t maximumFrames,
        CallbackRecord &record) const noexcept {
        const auto lease = multibusRing(role).acquireRead(maximumFrames);
        if (lease.empty()) return;
        const RenderBusFrameTag &firstTag = lease.spans[0U].tags[0U];
        LateStemMixControlSnapshot control{};
        if (!mLateControlMailbox.tryRead(role, generation, firstTag.selectionToken, control)) return;
        const float master = control.musicGain * control.mixHeadroom;
        for (std::size_t spanIndex = 0U; spanIndex < lease.spanCount; ++spanIndex) {
            const auto &span = lease.spans[spanIndex];
            for (std::size_t frame = 0U; frame < span.frameCount; ++frame) {
                const std::size_t outputFrame = span.leaseFrameOffset + frame;
                const float *payload =
                    span.interleavedPayload + frame * kStemwiseMultibusStride;
                float stemsLeft = 0.0F;
                for (std::size_t lane = 0U; lane < control.effectiveStemGains.size(); ++lane) {
                    stemsLeft += payload[stemwiseStemChannel(lane, 0U)] *
                        control.effectiveStemGains[lane];
                }
                const float click = payload[kStemwiseMultibusClickChannel];
                const float envelope = std::clamp(
                    payload[kStemwiseMultibusEnvelopeChannel],
                    0.0F,
                    1.0F);
                record.originalEndpointLeft[outputFrame] =
                    (payload[stemwiseOriginalChannel(0U)] * master + click) *
                    envelope;
                record.stemsEndpointLeft[outputFrame] =
                    (stemsLeft * master + click) * envelope;
            }
        }
    }
    CallbackRecord *mCurrentRecord{nullptr};
    const std::uint32_t mDeclaredMask;
    std::atomic<bool> mProducerHeld{false}, mPartialProductionMaskSeen{false}, mInvalidFinalSample{false};
    std::atomic<bool> mRejectOriginalSubmission{false}, mOriginalSubmissionHeld{false};
    std::atomic<std::uint64_t> mHeldManagedRevision{0U};
    std::atomic<bool> mManagedSubmissionHeld{false};
    std::atomic<bool> mHoldPushRead{false}, mPushReadEntered{false}, mReleasePushRead{false};
    std::atomic<bool> mHoldSourceFlush{false}, mSourceFlushEntered{false}, mReleaseSourceFlush{false};
    std::atomic<std::uint64_t> mHeldPushPhysical{0U};
    std::atomic<std::uint64_t> mPushResetRequests{0U};
    SongRenderOwnership mHeldRawOwner{};
    std::uint64_t mHeldRawRevision{};
    std::atomic<std::uint64_t> mCommitCount{0U}, mEmittedMaskBits{0U}, mLastControlRevision{0U};
    std::atomic<std::uint64_t> mHeldCommitGeneration{0U};
    std::atomic<bool> mCommitEntered{false};
    std::atomic<std::size_t> mMaximumQueuedFrames{0U};
    std::atomic<std::uint8_t> mLastControlSourceMode{0U};
};

[[nodiscard]] NativePlaybackRuntimeConfiguration runtimeConfiguration() {
    NativePlaybackRuntimeConfiguration result{};
    result.pipeline.maximumBlockFrames = kCallbackFrames;
    result.pipeline.laneReservoirCapacityFrames = 16'384U;
    result.pipeline.tuneTransitionFrames = 32U;
    result.targetBursts = 4U;
    result.minimumOutputReserveMicros =
        NativePlaybackRuntimeConfiguration::kMinimumOutputReserveMicros;
    result.fallbackFramesPerBurst = kCallbackFrames;
    result.ingressPullFrames = 256U;
    result.maximumPullsPerLanePerCycle = 4U;
    result.maximumOutputBlocksPerCycle = 32U;
    result.idleSleepMicros = 50U;
    return result;
}

[[nodiscard]] SongRenderControlSnapshot controls(
    std::uint64_t revision,
    MixerSourceMode sourceMode) {
    SongRenderControlSnapshot result{};
    result.revision = revision;
    result.sourceIntentRevision = revision;
    result.stemIntentRevision = revision;
    result.mixer.revision = revision;
    result.mixer.sourceMode = sourceMode;
    result.mixer.musicGain = 1.0F;
    result.mixer.mixHeadroom = 0.5F;
    result.mixer.sourceTransitionFrames = kSourceRampFrames;
    result.mixer.laneRampFrames = kSourceRampFrames;
    result.mixer.masterRampFrames = kSourceRampFrames;
    result.mixer.defaultDeclickFrames = 1U;
    result.mixer.metronome.mode = MetronomeMode::Off;
    result.tempo = 1.0F;
    result.pitchSemitones = 0.0F;
    return result;
}

[[nodiscard]] NativePlaybackCommandSnapshot selectedCommand(
    std::uint64_t revision,
    SongRenderOwnership owner,
    const std::array<NativePlaybackBankIntent, 2U> &banks,
    MixerSourceMode sourceMode) {
    NativePlaybackCommandSnapshot result{};
    result.revision = revision;
    result.outputGeneration = owner.generation;
    result.banks = banks;
    result.selectionAssigned = true;
    result.selectedBank = NativePlaybackBank::First;
    result.selectedOwnership = owner;
    result.playing = true;
    result.controls = controls(revision, sourceMode);
    return result;
}

void configureAndOffer(
    SongLaneIngressBank &bank,
    std::uint64_t generation,
    std::uint32_t stemMask) {
    for (std::size_t lane = 0U; lane < SongLaneIngressBank::kLaneCount; ++lane) {
        require(bank.configureLane(lane, kSampleRateHz, generation),
                "lane configuration failed");
    }
    for (std::size_t lane = 0U; lane < SongLaneIngressBank::kLaneCount; ++lane) {
        if (lane != 0U) {
            const std::uint32_t bit = 1U << static_cast<std::uint32_t>(lane - 1U);
            if ((stemMask & bit) == 0U) continue;
        }
        auto *ingress = bank.lane(lane);
        require(ingress != nullptr, "configured lane missing");
        const float left = lane == 0U
            ? 0.08F
            : 0.020F + 0.002F * static_cast<float>(lane);
        const float right = lane == 0U
            ? -0.06F
            : -0.016F - 0.001F * static_cast<float>(lane);
        std::vector<float> pcm(kInputFrames * 2U);
        for (std::size_t frame = 0U; frame < kInputFrames; ++frame) {
            pcm[frame * 2U] = left;
            pcm[frame * 2U + 1U] = right;
        }
        std::size_t offeredFrames = 0U;
        while (offeredFrames < kInputFrames) {
            const std::size_t count = std::min<std::size_t>(
                480U,
                kInputFrames - offeredFrames);
            LaneInputBlock block{};
            block.data = pcm.data() + offeredFrames * 2U;
            block.frameCount = count;
            block.format = LaneInputFormat{kSampleRateHz, 2U, LanePcmEncoding::Float32};
            block.generation = generation;
            block.presentationTimeUs =
                offeredFrames * 1'000'000ULL / kSampleRateHz;
            const auto result = ingress->offer(block);
            require(result.status == LaneOfferStatus::Accepted &&
                    result.inputFramesAccepted == count,
                    "lane offer failed");
            offeredFrames += count;
        }
        const auto ended = ingress->markEndOfStream(generation, kInputFrames);
        require(ended.status == LaneEndOfStreamStatus::Accepted,
                "lane end-of-stream marker failed");
    }
}

[[nodiscard]] CallbackRecord measuredCallback(
    ClosureOutput &output,
    std::size_t frames) {
    const std::uint64_t before = allocation_probe::count;
    allocation_probe::enabled = true;
    CallbackRecord result = output.callback(frames);
    allocation_probe::enabled = false;
    require(allocation_probe::count == before, "callback allocated memory");
    return result;
}

[[nodiscard]] CallbackRecord nextCallback(
    ClosureOutput &output,
    std::uint64_t generation,
    std::size_t frames) {
    waitUntil([&] {
        return output.playing() && output.callbackHasPublishedCoverage(generation, frames);
    }, "bounded callback wait expired");
    return measuredCallback(output, frames);
}

void alignQueueMask(
    ClosureOutput &output,
    SongRenderOwnership owner,
    std::uint32_t expectedMask) {
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(3);
    while (!output.nextFramesMatch(owner.generation, kSourceRampFrames, expectedMask)) {
        if (std::chrono::steady_clock::now() >= deadline) {
            throw std::runtime_error(
                "queue mask alignment timed out: expected=" +
                std::to_string(expectedMask) + " emitted-bits=" +
                std::to_string(output.emittedMaskBits()) + " queued=" +
                std::to_string(output.queuedForGeneration(owner.generation)));
        }
        const auto prefix = output.unmatchedPrefixFrames(owner.generation, expectedMask);
        if (output.playing() && prefix != 0U) {
            const auto old = measuredCallback(output, prefix);
            require(old.physicalFrames == prefix && old.read.frameCount == prefix &&
                old.read.firstTag.generation == owner.generation && old.read.lastTag.generation == owner.generation &&
                old.read.firstTag.selectionToken == owner.selectionToken &&
                old.read.lastTag.selectionToken == owner.selectionToken,
                "raw prefix callback lost its exact owner or PCM coverage");
            require(expectedMask != 0U && old.read.firstTag.actualSourceMode == FinalPcmSourceMode::Original &&
                old.read.lastTag.actualSourceMode == FinalPcmSourceMode::Original,
                "a cold old bus prefix did not preserve the audible Original");
            for (std::size_t frame = 0U; frame < prefix; ++frame) {
                requireNear(old.pcm[frame * 2U], .04F, .00002F, "old prefix changed Original left PCM");
                requireNear(old.pcm[frame * 2U + 1U], -.03F, .00002F, "old prefix changed Original right PCM");
            }
        } else {
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
    }
}

void holdProducerAndDrain(
    ClosureOutput &output,
    std::uint64_t generation) {
    output.setProducerHeld(true);
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(3);
    std::uint32_t stableEmptyObservations = 0U;
    while (stableEmptyObservations < 3U) {
        if (std::chrono::steady_clock::now() >= deadline) {
            throw std::runtime_error("bounded producer-hold drain timed out");
        }
        const std::size_t queued = output.queuedForGeneration(generation);
        if (queued == 0U) {
            ++stableEmptyObservations;
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
            continue;
        }
        stableEmptyObservations = 0U;
        static_cast<void>(measuredCallback(
            output,
            std::min<std::size_t>(queued, kCallbackFrames)));
    }
}

void waitForControlEndpoint(
    ClosureOutput &output,
    std::uint64_t revision,
    NativeAudioSourceMode sourceMode);

void releaseProducerAtControlEndpoint(
    ClosureOutput &output,
    SongRenderOwnership owner,
    std::uint64_t revision,
    std::uint32_t expectedMask,
    NativeAudioSourceMode preparedSourceMode,
    NativeAudioSourceMode finalSourceMode) {
    waitForControlEndpoint(output, revision, preparedSourceMode);
    output.setProducerHeld(false);
    // A late control publication can overtake already-rendered Original-only PCM. Consume
    // that exact prefix, stopping before the first matching bus frame so its ramp remains intact.
    alignQueueMask(output, owner, expectedMask);
    waitForControlEndpoint(output, revision, finalSourceMode);
}

void waitForControlEndpoint(
    ClosureOutput &output,
    std::uint64_t revision,
    NativeAudioSourceMode sourceMode) {
    waitUntil([&] {
        return output.lastControlRevision() >= revision &&
            output.lastControlSourceMode() == sourceMode;
    }, "late control endpoint publication timed out");
}

void assertRamp(
    const CallbackRecord &record,
    bool originalToStems,
    Digest &digest) {
    require(record.physicalFrames == kSourceRampFrames, "source ramp callback was short");
    require(!record.transitionTouched, "source ramp overlapped generation transition");
    const auto progressAt = [&](std::size_t frame) {
        const float original = record.originalEndpointLeft[frame];
        const float stems = record.stemsEndpointLeft[frame];
        const float oldEndpoint = originalToStems ? original : stems;
        const float newEndpoint = originalToStems ? stems : original;
        const float denominator = newEndpoint - oldEndpoint;
        require(std::abs(denominator) > 0.01F, "source endpoints are not distinguishable");
        return (record.pcm[frame * 2U] - oldEndpoint) / denominator;
    };
    requireNear(progressAt(0U), 0.0F, 0.0002F, "source ramp sample 0 is not old endpoint");
    require(progressAt(1U) > 0.0F, "source ramp sample 1 did not move");
    require(progressAt(228U) >= 0.95F, "source ramp was below 95 percent at frame 228");
    requireNear(progressAt(239U), 1.0F, 0.0002F,
                "source ramp frame 239 is not exact endpoint");
    for (std::size_t frame = 0U; frame < kSourceRampFrames; ++frame) {
        digest.addFloat(record.pcm[frame * 2U]);
        digest.addFloat(record.pcm[frame * 2U + 1U]);
    }
}

void assertGenerationTransition(
    const CallbackRecord &first,
    const CallbackRecord &second,
    const CallbackRecord &settled,
    SongRenderOwnership owner) {
    require(first.read.firstTag.generation == owner.generation &&
                first.read.lastTag.generation == owner.generation &&
                first.read.firstTag.selectionToken == owner.selectionToken &&
                first.read.lastTag.selectionToken == owner.selectionToken,
            "first owned callback carries the exact adopted owner");
    require(first.transitionFrames == 64U && first.transitionTouched,
            "first generation-transition callback is not 64 frames");
    require(second.transitionFrames == 32U && second.transitionTouched,
            "second generation-transition callback is not 32 frames");
    require(settled.transitionFrames == 0U && !settled.transitionTouched,
            "first untouched callback still carries generation transition");
    const float expectedFirst = first.transitionFromLeft * (95.0F / 96.0F) +
        first.preTransitionFirstLeft * (1.0F / 96.0F);
    requireNear(first.pcm[0U], expectedFirst, 0.00002F,
                "mixed PCM did not precede generation transition");
}

std::string runtimeBoundaryFacts(ClosureOutput &output, NativePlaybackRuntime &runtime,
                                 SongRenderOwnership owner) {
    const auto state = runtime.diagnostics();
    const auto ready = runtime.renderReadinessDiagnostics();
    return " commits=" + std::to_string(output.commitCount()) + " playing=" +
        std::to_string(output.playing()) + " queued=" + std::to_string(output.queuedForGeneration(owner.generation)) +
        " runtime=" + std::to_string(static_cast<unsigned>(state.status)) + " cycles=" +
        std::to_string(state.renderCycles) + " requests=" + std::to_string(state.commandSnapshotsConsumed) +
        " requested=" + std::to_string(state.requestedOwnership.generation) + "/" +
        std::to_string(state.requestedOwnership.selectionToken) + " accepted=" +
        std::to_string(state.acceptedOwnership.generation) + " committed=" +
        std::to_string(state.committedOwnership.generation) + " begins=" +
        std::to_string(state.outputHandoffBegins) + " commitRetries=" +
        std::to_string(state.outputHandoffCommitRetries) + " frames=" +
        std::to_string(state.outputFramesAccepted) + " target=" + std::to_string(state.targetQueuedFrames) +
        " need=" + std::to_string(state.outputNeedDataEvents) + " eos=" +
        std::to_string(state.outputEndOfStreamEvents) + " resources=" +
        std::to_string(state.resourceOperationsCompleted) + " cache=" +
        std::to_string(static_cast<unsigned>(ready.cacheStorageStatus)) + " bound=" +
        std::to_string(ready.currentSongCacheBoundToSelectedOwner) + " mask=" +
        std::to_string(ready.currentSongCacheCompleteLaneMask);
}

void runCell(
    std::uint32_t direction,
    std::uint32_t boundary,
    std::uint32_t stemMask,
    Digest &digest,
    std::size_t stemCount = 6U, bool upperLaneOnly = false) {
    const auto requiredMask = stemwise::audio::stemwiseRequiredStemMask(stemCount);
    const bool complete = stemCount != 0U && (stemMask & requiredMask) == requiredMask;
    constexpr SongRenderOwnership owner{0xC105EULL, 2U};
    SongLaneIngressBank first(16'384U, 480U);
    SongLaneIngressBank second(16'384U, 480U);
    configureAndOffer(first, owner.generation, stemMask);
    ClosureOutput output(requiredMask);
    NativePlaybackRuntime runtime(runtimeConfiguration(), first, second, output.port());
    bool started = false;
    try {
        const std::array<NativePlaybackBankIntent, 2U> banks{
            NativePlaybackBankIntent{
                1U,
                true,
                owner,
                0U,
                0,
                0x1111'2222'3333'4444ULL,
                0x5555'6666'7777'8888ULL,
                stemCount == 0U ? 0U : 0x9999'AAAA'BBBB'CCCCULL,
                static_cast<std::uint32_t>(stemCount),
                kInputFrames,
            },
            NativePlaybackBankIntent{},
        };
        const auto commandForCell = [&](std::uint64_t revision, SongRenderOwnership commandOwner,
            const std::array<NativePlaybackBankIntent, 2U> &commandBanks, MixerSourceMode mode) {
            auto command = selectedCommand(revision, commandOwner, commandBanks, mode);
            if (upperLaneOnly) {
                for (std::size_t lane = 0U; lane + 1U < stemCount; ++lane) command.controls.mixer.stems[lane].gain = 0.0F;
            }
            return command;
        };
        require(runtime.publishCommand(commandForCell(
                    1U,
                    owner,
                    banks,
                    MixerSourceMode::Original)) == NativePlaybackPublishStatus::Accepted,
                "initial command rejected");
        require(runtime.start(), "runtime start failed");
        started = true;
        waitUntil([&] {
            return output.commitCount() == 1U && output.playing() &&
                output.queuedForGeneration(owner.generation) >= 3U * kCallbackFrames;
        }, "initial handoff did not commit");

        const std::uint64_t evidenceBeforeGenerationTransition =
            output.controlEvidence().sequence;
        const CallbackRecord generationFirst = nextCallback(
            output,
            owner.generation,
            kCallbackFrames);
        require(output.controlEvidence().sequence == evidenceBeforeGenerationTransition,
                "transition callback published settled evidence");
        const CallbackRecord generationSecond = nextCallback(
            output,
            owner.generation,
            kCallbackFrames);
        require(output.controlEvidence().sequence == evidenceBeforeGenerationTransition,
                "second transition callback published settled evidence");
        const CallbackRecord generationSettled = nextCallback(
            output,
            owner.generation,
            kCallbackFrames);
        assertGenerationTransition(generationFirst, generationSecond, generationSettled, owner);
        require(generationSettled.controlEvidencePublished,
                "first untouched callback did not settle initial control");
        waitUntil([&] {
            return sameOwner(runtime.diagnostics().committedOwnership, owner);
        }, "runtime did not observe first owned PCM");

        if (stemCount == 0U) {
            // An Original-only package has no Stems command. Verify its real final callback PCM.
            require(generationSettled.read.firstTag.actualSourceMode == FinalPcmSourceMode::Original &&
                output.emittedMaskBits() == 1U,
                "Original-only closure fabricated a Stems endpoint");
            CallbackRecord originalSettled = generationSettled;
            // Generation transition completion does not complete the independent 240-frame control ramps.
            for (std::size_t index = 0U; index != 4U; ++index) {
                originalSettled = nextCallback(output, owner.generation, kCallbackFrames);
            }
            for (std::size_t frame = 0U; frame != originalSettled.physicalFrames; ++frame) {
                requireNear(originalSettled.pcm[frame * 2U], 0.08F * 0.5F, 0.00002F,
                    "Original-only final PCM changed through undeclared lanes");
            }
            digest.addInteger(stemCount);
            digest.addFloat(originalSettled.pcm[0U]);
            runtime.requestStop(); runtime.join();
            return;
        }

        const std::uint32_t normalizedMask = complete
            ? requiredMask
            : 0U;
        if (complete) {
            // Cache I/O is asynchronous. Establish the actual complete producer boundary
            // before this fixture consumes a 170 ms song faster than a physical callback.
            waitUntil([&] {
                const auto ready = runtime.renderReadinessDiagnostics();
                return ready.currentSongCacheBoundToSelectedOwner &&
                    ready.currentSongCacheCompleteLaneMask == ((requiredMask << 1U) | 1U);
            }, "complete stem producer did not seal its exact cache");
        }
        // Original does not demand cold optional buses. Full decoded extent alone does not
        // promise them in queued PCM; the actual Stems command below admits the required buses.
        if (!complete) alignQueueMask(output, owner, normalizedMask);

        std::uint64_t revision = 1U;
        if (direction == 1U) {
            holdProducerAndDrain(output, owner.generation);
            ++revision;
            require(runtime.publishCommand(commandForCell(
                        revision,
                        owner,
                        banks,
                        MixerSourceMode::Stems)) == NativePlaybackPublishStatus::Accepted,
                    "S2O setup command rejected");
            releaseProducerAtControlEndpoint(
                output,
                owner,
                revision,
                normalizedMask,
                stemCount == 0U ? NativeAudioSourceMode::Original : NativeAudioSourceMode::Stems,
                complete
                    ? NativeAudioSourceMode::Stems
                    : NativeAudioSourceMode::Original);
            const CallbackRecord setupRamp = nextCallback(
                output,
                owner.generation,
                kSourceRampFrames);
            require(!setupRamp.read.audibleControlTupleCoherent,
                    "S2O setup ramp was prematurely settled");
            const CallbackRecord setupSettled = nextCallback(
                output,
                owner.generation,
                kCallbackFrames);
            require(setupSettled.read.audibleControlTupleCoherent,
                    "S2O setup did not settle on next callback");
            alignQueueMask(output, owner, normalizedMask);
        }

        for (std::uint32_t index = 0U; index < boundary; ++index) {
            static_cast<void>(nextCallback(output, owner.generation, kCallbackFrames));
        }
        if (direction == 1U || !complete) {
            alignQueueMask(output, owner, normalizedMask);
        }

        if (direction == 0U && boundary == 2U && stemMask == 0x3FU && stemCount == 6U) {
            // A real rendered Original block can remain pending while the output is held.
            // Carry that exact old revision across the next late-control publication.
            output.holdNextOriginalSubmission(owner, revision);
            static_cast<void>(nextCallback(output, owner.generation, kCallbackFrames));
            waitUntil([&] { return output.originalSubmissionHeld(); },
                "Original raw block did not reach the controlled output backpressure boundary");
        }
        holdProducerAndDrain(output, owner.generation);
        ++revision;
        const MixerSourceMode target = direction == 0U
            ? MixerSourceMode::Stems
            : MixerSourceMode::Original;
        require(runtime.publishCommand(commandForCell(
                    revision,
                    owner,
                    banks,
                    target)) == NativePlaybackPublishStatus::Accepted,
                "matrix source command rejected");
        try {
            releaseProducerAtControlEndpoint(
                output,
                owner,
                revision,
                normalizedMask,
                target == MixerSourceMode::Stems && stemCount != 0U
                    ? NativeAudioSourceMode::Stems
                    : NativeAudioSourceMode::Original,
                target == MixerSourceMode::Stems &&
                        complete
                    ? NativeAudioSourceMode::Stems
                    : NativeAudioSourceMode::Original);
        } catch (const std::exception &error) {
            const auto readiness = runtime.renderReadinessDiagnostics();
            std::string evidence = std::string(error.what()) + ": count=" + std::to_string(stemCount) +
                " direction=" + std::to_string(direction) + " mode=" + std::to_string(static_cast<unsigned>(output.lastControlSourceMode())) +
                " revision=" + std::to_string(output.lastControlRevision()) + ": start=" +
                std::to_string(readiness.renderStartTimelineFrame);
            for (std::size_t lane = 0U; lane < readiness.lanes.size(); ++lane) {
                evidence += " lane" + std::to_string(lane) + "={available:" +
                    std::to_string(readiness.lanes[lane].availableFromRenderStart) +
                    ",end:" +
                    std::to_string(readiness.lanes[lane].endTimelineFrameExclusive) +
                    ",readable:" +
                    std::to_string(readiness.lanes[lane].ingressReadableSourceFrames) + "}";
            }
            throw std::runtime_error(evidence);
        }
        const std::uint64_t evidenceBeforeRamp = output.controlEvidence().sequence;
        const CallbackRecord ramp = nextCallback(
            output,
            owner.generation,
            kSourceRampFrames);
        require(!ramp.read.audibleControlTupleCoherent,
                "source-ramp callback was marked wholly settled");
        require(output.controlEvidence().sequence == evidenceBeforeRamp,
                "source-ramp callback advanced settled evidence");
        const CallbackRecord settled = nextCallback(
            output,
            owner.generation,
            kCallbackFrames);
        require(settled.read.audibleControlTupleCoherent &&
                settled.read.firstTag.sourceTransitionSettled &&
                settled.read.lastTag.sourceTransitionSettled,
                "next untouched callback did not settle source control");
        const auto evidence = output.controlEvidence();
        require(evidence.sequence > evidenceBeforeRamp &&
                evidence.controlRevision == revision,
                "settled callback evidence did not bind target revision");
        const NativeAudioSourceMode expectedActual =
            target == MixerSourceMode::Stems &&
                complete
            ? NativeAudioSourceMode::Stems
            : NativeAudioSourceMode::Original;
        require(evidence.actualSourceMode == expectedActual,
                "settled callback source endpoint mismatch");

        if (complete) {
            assertRamp(ramp, direction == 0U, digest);
        } else {
            require(ramp.read.firstTag.actualSourceMode == FinalPcmSourceMode::Original &&
                    ramp.read.lastTag.actualSourceMode == FinalPcmSourceMode::Original,
                    "incomplete stem package escaped Original fallback");
            for (std::size_t frame = 0U; frame < kSourceRampFrames; ++frame) {
                requireNear(
                    ramp.pcm[frame * 2U],
                    ramp.originalEndpointLeft[frame],
                    0.00002F,
                    "incomplete stem package changed Original PCM");
            }
        }

        require(!output.partialProductionMaskSeen(),
                "production runtime emitted a partial stem mask");
        const std::uint64_t masks = output.emittedMaskBits();
        require((masks & ~std::uint64_t{3U}) == 0U,
                "production runtime emitted an unrecognized mask");
        require((masks & (std::uint64_t{1U} << (normalizedMask == 0U ? 0U : 1U))) != 0U,
                "normalized stem mask was never emitted");
        require(output.maximumQueuedFrames() <= kOutputCapacityFrames,
                "output queue exceeded fixed capacity");
        require(!output.invalidFinalSampleSeen(),
                "final device stage emitted non-finite or over-ceiling PCM");

        digest.addInteger(direction);
        digest.addInteger(boundary);
        digest.addInteger(stemMask);
        digest.addInteger(stemCount);
        digest.addInteger(static_cast<std::uint8_t>(upperLaneOnly));
        digest.addInteger(masks);
        digest.addInteger(revision);
        digest.addInteger(static_cast<std::uint8_t>(evidence.actualSourceMode));
        digest.addFloat(generationFirst.pcm[0U]);
        digest.addFloat(settled.pcm[0U]);

        runtime.requestStop();
        runtime.join();
        started = false;
        require(runtime.status() == NativePlaybackRuntimeStatus::Stopped,
                "runtime did not reach Stopped");
        require(!output.playing(), "runtime stop did not close output playing state");
    } catch (const std::exception &error) {
        const auto failure = std::string("cell direction=") + std::to_string(direction) +
            " boundary=" + std::to_string(boundary) + " stemMask=" + std::to_string(stemMask) +
            " stemCount=" + std::to_string(stemCount) + ": " + error.what() +
            runtimeBoundaryFacts(output, runtime, owner);
        if (started) { runtime.requestStop(); runtime.join(); }
        throw std::runtime_error(failure);
    } catch (...) {
        if (started) { runtime.requestStop(); runtime.join(); }
        throw;
    }
}

[[nodiscard]] LateStemMixControlSnapshot directSnapshot(
    std::uint64_t generation,
    std::uint64_t token,
    std::uint64_t revision,
    LateStemSourceMode mode) {
    LateStemMixControlSnapshot result{};
    result.requiredStemMask = 0x3FU;
    result.publicationSequence = revision;
    result.generation = generation;
    result.selectionToken = token;
    result.canonicalControlRevision = revision;
    result.sourceIntentRevision = revision;
    result.stemIntentRevision = revision;
    result.sourceMode = mode;
    result.effectiveStemGains.fill(1.0F);
    result.musicGain = 1.0F;
    result.mixHeadroom = 0.5F;
    result.sourceRampFrames = kSourceRampFrames;
    result.laneRampFrames = kSourceRampFrames;
    result.masterRampFrames = kSourceRampFrames;
    return result;
}

[[nodiscard]] std::array<float, kStemwiseMultibusStride> directPayload() {
    std::array<float, kStemwiseMultibusStride> result{};
    result[stemwiseOriginalChannel(0U)] = 0.08F;
    result[stemwiseOriginalChannel(1U)] = -0.06F;
    for (std::size_t lane = 0U; lane < 6U; ++lane) {
        result[stemwiseStemChannel(lane, 0U)] = 0.025F;
        result[stemwiseStemChannel(lane, 1U)] = -0.020F;
    }
    result[kStemwiseMultibusEnvelopeChannel] = 1.0F;
    return result;
}

void provePartialMasksAreDownstreamRobustnessOnly(Digest &digest) {
    const auto payload = directPayload();
    for (std::size_t index = 0U; index < kPartialMasks.size(); ++index) {
        const std::uint64_t generation = 100U + index;
        const std::uint64_t token = 200U + index;
        SpscTaggedMultibusRingBuffer ring(8U);
        LateStemMixControlMailbox mailbox;
        NativeAudioOutputMultibusCallbackStage stage(kSampleRateHz);
        const auto snapshot = directSnapshot(
            generation,
            token,
            10U + index,
            LateStemSourceMode::Stems);
        require(mailbox.resetRole(0U, generation, token),
                "direct partial role bind failed");
        require(mailbox.publish(0U, snapshot), "direct partial control publish failed");
        const RenderBusFrameTag tag{
            .generation = generation,
            .selectionToken = token,
            .payloadSequence = 1U,
            .payloadHash = 2U,
            .canonicalControlRevision = snapshot.canonicalControlRevision,
            .sourceIntentRevision = snapshot.sourceIntentRevision,
            .stemIntentRevision = snapshot.stemIntentRevision,
            .stemBusReadyMask = kPartialMasks[index],
        };
        require(ring.writeTagged(payload.data(), 1U, tag) == 1U,
                "direct partial queue injection failed");
        std::array<float, 2U> output{};
        const auto read = stage.readAndMix(
            0U,
            generation,
            ring,
            mailbox,
            output.data(),
            1U);
        require(read.frameCount == 1U &&
                read.firstTag.actualSourceMode == FinalPcmSourceMode::Original,
                "direct partial tag did not fall back to Original");
        requireNear(output[0U], 0.04F, 0.00002F,
                    "direct partial tag did not preserve Original PCM");
        digest.addInteger(kPartialMasks[index]);
        digest.addFloat(output[0U]);
    }
}

enum class RejectionMutation : std::uint8_t {
    Generation,
    Token,
    CanonicalRevision,
    SourceRevision,
    StemRevision,
};

void proveWrongAndStaleOwnershipIsRejected(Digest &digest) {
    const auto payload = directPayload();
    constexpr std::array<RejectionMutation, 5U> mutations{
        RejectionMutation::Generation,
        RejectionMutation::Token,
        RejectionMutation::CanonicalRevision,
        RejectionMutation::SourceRevision,
        RejectionMutation::StemRevision,
    };
    for (std::size_t index = 0U; index < mutations.size(); ++index) {
        constexpr std::uint64_t generation = 500U;
        constexpr std::uint64_t token = 600U;
        constexpr std::uint64_t revision = 20U;
        SpscTaggedMultibusRingBuffer ring(8U);
        LateStemMixControlMailbox mailbox;
        NativeAudioOutputMultibusCallbackStage stage(kSampleRateHz);
        const auto snapshot = directSnapshot(
            generation,
            token,
            revision,
            LateStemSourceMode::Original);
        require(mailbox.resetRole(0U, generation, token), "rejection role bind failed");
        require(mailbox.publish(0U, snapshot), "rejection control publish failed");
        require(!mailbox.publish(0U, snapshot), "stale control publication was accepted");
        RenderBusFrameTag tag{
            .generation = generation,
            .selectionToken = token,
            .payloadSequence = 1U,
            .payloadHash = 2U,
            .canonicalControlRevision = revision,
            .sourceIntentRevision = revision,
            .stemIntentRevision = revision,
            .stemBusReadyMask = 0U,
        };
        std::uint64_t expectedGeneration = generation;
        switch (mutations[index]) {
            case RejectionMutation::Generation:
                expectedGeneration = generation + 1U;
                break;
            case RejectionMutation::Token:
                tag.selectionToken = token + 1U;
                break;
            case RejectionMutation::CanonicalRevision:
                tag.canonicalControlRevision = revision + 1U;
                break;
            case RejectionMutation::SourceRevision:
                tag.sourceIntentRevision = revision + 1U;
                break;
            case RejectionMutation::StemRevision:
                tag.stemIntentRevision = revision + 1U;
                break;
        }
        require(ring.writeTagged(payload.data(), 1U, tag) == 1U,
                "rejection queue injection failed");
        std::array<float, 2U> output{1.0F, 1.0F};
        const auto read = stage.readAndMix(
            0U,
            expectedGeneration,
            ring,
            mailbox,
            output.data(),
            1U);
        require(read.frameCount == 1U && read.firstTag.generation == 0U,
                "wrong owner or revision retained valid evidence");
        require(output[0U] == 0.0F && output[1U] == 0.0F,
                "wrong owner or revision reached PCM");
        digest.addInteger(static_cast<std::uint8_t>(mutations[index]));
        digest.addFloat(output[0U]);
    }
}

// Exercise tuning through the real runtime, shared callback mixer and final device stages.
void queuedNumericalTuningCannotBeAcknowledgedByANewerLateMixRevision() {
    constexpr SongRenderOwnership owner{0x715C5U, 2U};
    SongLaneIngressBank first(16'384U, 480U), second(256U, 256U);
    configureAndOffer(first, owner.generation, 0U);
    ClosureOutput output(0U);
    NativePlaybackRuntime runtime(runtimeConfiguration(), first, second, output.port());
    const std::array<NativePlaybackBankIntent, 2U> banks{
        NativePlaybackBankIntent{1U, true, owner, 0U, 0, 111U, 222U, 0U, 0U, kInputFrames},
        NativePlaybackBankIntent{}};
    auto command = selectedCommand(1U, owner, banks, MixerSourceMode::Original);
    require(runtime.publishCommand(command) == NativePlaybackPublishStatus::Accepted,
        "tuning initial command");
    require(runtime.start(), "tuning runtime starts");
    try {
        for (unsigned block = 0U; block != 8U; ++block)
            static_cast<void>(nextCallback(output, owner.generation, kCallbackFrames));
        waitUntil([&] { return output.queuedForGeneration(owner.generation) >= 3U * kCallbackFrames; },
            "unity PCM reserve exists before changing tuning");
        output.setProducerHeld(true);
        command.revision = 2U;
        command.controls = controls(2U, MixerSourceMode::Original);
        command.controls.tempo = 1.5F;
        command.controls.pitchSemitones = 3.0F;
        command.controls.mixer.sourceTransitionFrames = 1U;
        command.controls.mixer.laneRampFrames = 1U;
        command.controls.mixer.masterRampFrames = 1U;
        require(runtime.publishCommand(command) == NativePlaybackPublishStatus::Accepted,
            "new numerical command accepted");
        waitForControlEndpoint(output, 2U, NativeAudioSourceMode::Original);
        const auto queuedUnity = nextCallback(output, owner.generation, kCallbackFrames);
        require(queuedUnity.read.firstTag.controlRevision == 2U &&
            queuedUnity.read.lastTag.controlRevision == 2U,
            "late mix revision remains immediately applicable to queued PCM");
        require(queuedUnity.read.firstTag.actualTempo == 1.0F &&
            queuedUnity.read.lastTag.actualTempo == 1.0F &&
            queuedUnity.read.firstTag.actualPitchSemitones == 0.0F &&
            queuedUnity.read.lastTag.actualPitchSemitones == 0.0F,
            "new late mix revision falsely acknowledges queued unity PCM as target tuning");
        // The first callback contains the real one-frame endpoint transition. The next complete
        // queued unity callback can acknowledge source/gain while retaining its numerical tuple.
        const auto settledUnity = nextCallback(output, owner.generation, kCallbackFrames);
        require(settledUnity.read.firstTag.actualTempo == 1.0F && settledUnity.read.lastTag.actualTempo == 1.0F,
            "settled late mix must still consume the retained unity reserve");
        const auto oldProof = output.controlEvidence();
        require(oldProof.controlRevision == 2U && oldProof.actualTempo == 1.0F &&
            oldProof.actualPitchSemitones == 0.0F,
            ("late mix acknowledgement must retain the actual queued numerical tuple: revision=" +
                std::to_string(oldProof.controlRevision) + " tempo=" + std::to_string(oldProof.actualTempo) +
                " pitch=" + std::to_string(oldProof.actualPitchSemitones) +
                " coherent=" + std::to_string(queuedUnity.read.audibleTuningTupleCoherent) +
                " mixCoherent=" + std::to_string(queuedUnity.read.audibleControlTupleCoherent)).c_str());
        output.setProducerHeld(false);
        bool targetPresented = false;
        for (unsigned block = 0U; block != 80U && !targetPresented; ++block) {
            const auto next = nextCallback(output, owner.generation, kCallbackFrames);
            const auto proof = output.controlEvidence();
            targetPresented = next.read.firstTag.actualTempo == 1.5F &&
                next.read.lastTag.actualTempo == 1.5F &&
                next.read.firstTag.actualPitchSemitones == 3.0F &&
                next.read.lastTag.actualPitchSemitones == 3.0F &&
                proof.generation == owner.generation && proof.selectionToken == owner.selectionToken &&
                proof.actualTempo == 1.5F && proof.actualPitchSemitones == 3.0F;
        }
        require(targetPresented, "actual processed tuning eventually reaches the exact callback owner");
    } catch (...) {
        runtime.requestStop(); runtime.join(); throw;
    }
    runtime.requestStop(); runtime.join();
}

void publishedPitchReachesExactOwnerCallbackPcm() {
    constexpr SongRenderOwnership owner{0x715C4U, 2U};
    constexpr std::size_t inputFrames = 48'000U;
    SongLaneIngressBank first(65'536U, 480U), second(256U, 256U);
    require(first.configureLane(0U, kSampleRateHz, owner.generation), "pitch lane configuration");
    std::vector<float> input(inputFrames * 2U);
    for (std::size_t frame = 0U; frame < inputFrames; ++frame) {
        const float sample = 0.08F * static_cast<float>(std::sin(
            2.0 * 3.14159265358979323846 * 220.0 * static_cast<double>(frame) / kSampleRateHz));
        input[frame * 2U] = input[frame * 2U + 1U] = sample;
    }
    auto *lane = first.lane(0U);
    for (std::size_t offset = 0U; offset < inputFrames;) {
        const auto count = std::min<std::size_t>(480U, inputFrames - offset);
        const LaneInputBlock block{input.data() + offset * 2U, count,
            {kSampleRateHz, 2U, LanePcmEncoding::Float32}, owner.generation,
            offset * 1'000'000U / kSampleRateHz};
        require(lane->offer(block).inputFramesAccepted == count, "pitch lane offer");
        offset += count;
    }
    require(lane->markEndOfStream(owner.generation, inputFrames).status ==
        LaneEndOfStreamStatus::Accepted, "pitch lane EOS");
    ClosureOutput output(0U);
    auto configuration = runtimeConfiguration();
    configuration.pipeline.laneReservoirCapacityFrames = 65'536U;
    NativePlaybackRuntime runtime(configuration, first, second, output.port());
    const std::array<NativePlaybackBankIntent, 2U> banks{
        NativePlaybackBankIntent{1U, true, owner, 0U, 0, 111U, 222U, 0U, 0U, inputFrames},
        NativePlaybackBankIntent{}};
    auto command = selectedCommand(1U, owner, banks, MixerSourceMode::Original);
    require(runtime.publishCommand(command) == NativePlaybackPublishStatus::Accepted,
        "pitch initial command");
    require(runtime.start(), "pitch runtime starts");
    try {
        for (unsigned block = 0U; block != 8U; ++block)
            static_cast<void>(nextCallback(output, owner.generation, kCallbackFrames));
        command.revision = 2U;
        command.controls = controls(2U, MixerSourceMode::Original);
        command.controls.pitchSemitones = 12.0F;
        require(runtime.publishCommand(command) == NativePlaybackPublishStatus::Accepted,
            "pitch changed command");
        // A request/acceptance alone cannot establish that the new endpoint reached a callback.
        for (unsigned block = 0U; block != 128U; ++block)
            static_cast<void>(nextCallback(output, owner.generation, kCallbackFrames));
        const auto proof = output.controlEvidence();
        require(proof.generation == owner.generation && proof.selectionToken == owner.selectionToken &&
            proof.controlRevision == 2U && proof.pcmFrames != 0U, "pitch exact native callback proof");
        std::array<double, 2U> sine{}, cosine{};
        double energy = 0.0;
        std::size_t captured = 0U;
        for (unsigned block = 0U; block != 128U; ++block) {
            const auto callback = nextCallback(output, owner.generation, kCallbackFrames);
            require(callback.read.firstTag.controlRevision == 2U &&
                callback.read.lastTag.controlRevision == 2U, "pitch PCM carries exact control revision");
            for (std::size_t frame = 0U; frame != kCallbackFrames; ++frame, ++captured) {
                const double sample = callback.pcm[frame * 2U];
                energy += sample * sample;
                for (std::size_t bin = 0U; bin != 2U; ++bin) {
                    const double frequency = bin == 0U ? 220.0 : 440.0;
                    const double phase = 2.0 * 3.14159265358979323846 * frequency *
                        static_cast<double>(captured) / kSampleRateHz;
                    sine[bin] += sample * std::sin(phase);
                    cosine[bin] += sample * std::cos(phase);
                }
            }
        }
        const double originalMagnitude = std::hypot(sine[0], cosine[0]) / captured;
        const double shiftedMagnitude = std::hypot(sine[1], cosine[1]) / captured;
        require(energy / captured > 1.0e-4 && shiftedMagnitude > .01 &&
            shiftedMagnitude > originalMagnitude * 8.0, "published octave shift must change actual callback PCM");
        runtime.requestStop(); runtime.join();
    } catch (...) { runtime.requestStop(); runtime.join(); throw; }
}


NativeAudioLateMixControl coreControl(SongRenderOwnership owner, std::uint64_t revision,
                                      NativeAudioSourceMode source = NativeAudioSourceMode::Original) {
    NativeAudioLateMixControl control{};
    control.publicationSequence = revision;
    control.generation = owner.generation;
    control.selectionToken = owner.selectionToken;
    control.canonicalControlRevision = revision;
    control.sourceIntentRevision = revision;
    control.stemIntentRevision = revision;
    control.sourceMode = source;
    control.requiredStemMask = 1U;
    control.mixHeadroom = 1.0F;
    control.sourceRampFrames = control.laneRampFrames = control.masterRampFrames = 1U;
    return control;
}

std::vector<float> corePayload(std::size_t frames, float sample) {
    std::vector<float> payload(frames * kStemwiseMultibusStride, 0.0F);
    for (std::size_t frame = 0U; frame < frames; ++frame) {
        payload[frame * kStemwiseMultibusStride] = sample;
        payload[frame * kStemwiseMultibusStride + 1U] = sample;
        payload[frame * kStemwiseMultibusStride + kStemwiseMultibusEnvelopeChannel] = 1.0F;
    }
    return payload;
}

NativeRenderBusSubmission coreSubmission(SongRenderOwnership owner, std::uint64_t revision,
    const std::vector<float> &payload, std::uint64_t firstFrame, bool terminal = false) {
    const auto frames = payload.size() / kStemwiseMultibusStride;
    return {.generation = owner.generation, .selectionToken = owner.selectionToken,
        .payloadSequence = revision, .payloadHash = 0xAA11U + revision,
        .canonicalControlRevision = revision, .sourceIntentRevision = revision,
        .stemIntentRevision = revision, .baseControl = coreControl(owner, revision),
        .endOfStreamAfterInput = terminal,
        .sourceStartFrame = static_cast<double>(firstFrame),
        .sourceEndFrameExclusive = static_cast<double>(firstFrame + frames),
        .samples = payload.data(), .frameCount = frames};
}

void outputOwnerRestampsRuntimeLocalControlSequencesWithoutWeakeningOwnership() {
    constexpr SongRenderOwnership owner{28'303U, 2U};
    auto outputStorage = std::make_unique<ClosureOutput>(0U, kCallbackFrames * 3U, owner.generation);
    auto &output = *outputStorage;
    const auto port = output.port();
    auto control = coreControl(owner, 2U);
    control.publicationSequence = 25U;
    control.musicGain = .5F;
    require(port.publishLateMixControl(port.handle, control), "current runtime-local counter publishes");
    control.publicationSequence = 14U;
    require(port.publishLateMixControl(port.handle, control),
        "output owner accepts an earlier runtime-local counter for the same numerical endpoint");
    auto wrongOwner = control;
    ++wrongOwner.selectionToken;
    require(!port.publishLateMixControl(port.handle, wrongOwner), "restamping cannot rebind the active role to a wrong token");
    auto oldRevision = coreControl(owner, 1U);
    oldRevision.publicationSequence = 100U;
    require(!port.publishLateMixControl(port.handle, oldRevision), "restamping cannot lower canonical or intent revisions");
    const auto payload = corePayload(kCallbackFrames * 3U, .08F);
    auto submission = coreSubmission(owner, 2U, payload, 0U);
    submission.baseControl = control;
    submission.actualTempo = 1.0F;
    require(port.setPlaying(port.handle, true) && port.enqueue(port.handle, submission) == kCallbackFrames * 3U,
        "exact frozen producer base is accepted after output-owned restamping");
    const auto first = output.callback(kCallbackFrames);
    const auto second = output.callback(kCallbackFrames);
    require(first.physicalFrames == kCallbackFrames && second.physicalFrames == kCallbackFrames,
        "initial discontinuity still consumes its real owned callback");
    const auto rendered = output.callback(kCallbackFrames);
    assertGenerationTransition(first, second, rendered, owner);
    const auto evidence = output.controlEvidence();
    require(rendered.physicalFrames == kCallbackFrames, "restamped callback must consume all actual source PCM");
    require(evidence.valid() && evidence.selectionToken == owner.selectionToken &&
        evidence.generation == owner.generation && evidence.controlRevision == 2U &&
        submission.baseControl.publicationSequence == 14U && submission.baseControl.musicGain == .5F,
        "actual callback readback retains exact owner, revision and immutable producer base");
    for (std::size_t frame = 0U; frame < kCallbackFrames; ++frame)
        requireNear(rendered.pcm[frame * 2U], .04F, 1e-6F, "restamping preserves actual numerical gain");
}

void consumedFractionalBoundaryMappingRetainsOriginalAndPartialReceiptOrigins() {
    constexpr SongRenderOwnership owner{31U, 32U};
    constexpr float tempo = 1.35F;
    const double start = 30.0 * static_cast<double>(tempo);
    const auto payload = corePayload(4U, .08F);
    const auto exercise = [&](std::size_t capacity) {
        ClosureOutput output(0U, capacity, owner.generation);
        output.setDiagnosticPayloadFingerprints(true);
        const auto port = output.port();
        require(port.publishLateMixControl(port.handle, coreControl(owner, 3U)), "mapping endpoint");
        require(port.setPlaying(port.handle, true), "mapping output plays");
        auto submission = coreSubmission(owner, 3U, payload, 0U);
        submission.payloadSequence = 9U;
        submission.actualTempo = tempo;
        submission.loopPolicyRevision = 3U;
        submission.renderSourceStartFrame = start;
        submission.renderFrameCount = 4U;
        submission.sourceStartFrame = start;
        submission.sourceEndFrameExclusive = 45.0;
        require(port.enqueue(port.handle, submission) == capacity, "mapping first exact receipt");
        require(output.presentationJournal(0U).writeSequence == 0U,
            "accepted payload alone is not consumed mapping evidence");
        static_cast<void>(measuredCallback(output, capacity));
        if (capacity == 2U) {
            submission.renderFrameOffset = 2U;
            submission.sourceStartFrame = start + (45.0 - start) * .5;
            submission.samples += 2U * kStemwiseMultibusStride;
            submission.frameCount = 2U;
            require(port.enqueue(port.handle, submission) == 2U, "mapping suffix exact receipt");
            static_cast<void>(measuredCallback(output, 2U));
        }
        const auto journal = output.presentationJournal(0U);
        require(journal.coherent && journal.invalidPublicationCount == 0U &&
            journal.writerCollisionCount == 0U && journal.entries.size() == (capacity == 2U ? 2U : 1U),
            "whole released callback publishes exact mapping runs");
        for (std::size_t index = 0U; index < journal.entries.size(); ++index) {
            const auto &entry = journal.entries[index];
            require(entry.generation == owner.generation && entry.selectionToken == owner.selectionToken &&
                entry.firstFingerprintSequence == 9U && entry.lastFingerprintSequence == 9U &&
                entry.renderSourceStartFrame == start && entry.renderSourceEndFrameExclusive == 45.0 &&
                entry.renderFrameCount == 4U && entry.actualTempo == tempo &&
                entry.firstLoopPolicyRevision == 3U && entry.lastLoopPolicyRevision == 3U,
                "consumed mapping preserves full immutable render provenance");
            const auto offset = static_cast<std::uint32_t>(index * capacity);
            require(entry.firstRenderFrameOffset == offset && entry.mappingFirstRenderFrameOffset == offset &&
                entry.firstTimelineFrame == (index == 0U ? 40 : 42) &&
                entry.lastTimelineFrame == (capacity == 4U || index != 0U ? 43 : 41) &&
                entry.lastNextTimelineFrame == (capacity == 4U || index != 0U ? 45 : 42),
                "1.35 final clamp and partial retry retain the exact known source-frame answers");
        }
    };
    exercise(4U);
    exercise(2U);
}

void oneFrameRuntimePrefillKeepsTheWholeRequiredReserve() {
    constexpr SongRenderOwnership owner{8'104U, 2U};
    SongLaneIngressBank first(16'384U, 480U), second(16'384U, 480U);
    require(first.configureLane(0U, kSampleRateHz, owner.generation), "one-frame prefill real Original lane configures");
    const std::vector<float> pcm(4'096U * 2U, .08F);
    for (std::size_t offset = 0U; offset < 4'096U; offset += 256U) {
        require(first.lane(0U)->offer({pcm.data() + offset * 2U, 256U,
            {kSampleRateHz, 2U, LanePcmEncoding::Float32}, owner.generation,
            offset * 1'000'000U / kSampleRateHz}).inputFramesAccepted == 256U,
            "one-frame prefill decoder publishes contiguous real PCM");
    }
    auto output = std::make_unique<ClosureOutput>(0U);
    require(output->configurePushConsumer(512U), "one-frame producer uses the real 512-frame push sink");
    auto configuration = runtimeConfiguration();
    configuration.pipeline.maximumBlockFrames = 1U;
    configuration.minimumOutputReserveMicros = 50'000U;
    NativePlaybackRuntime runtime(configuration, first, second, output->port());
    try {
        const std::array<NativePlaybackBankIntent, 2U> banks{
            NativePlaybackBankIntent{1U, true, owner, 0U, 0, 111U, 222U, 0U, 0U, 4'096U}, {}};
        require(runtime.publishCommand(selectedCommand(1U, owner, banks, MixerSourceMode::Original)) ==
            NativePlaybackPublishStatus::Accepted && runtime.start(), "one-frame producer stages a real new owner");
        waitUntil([&] { return output->commitCount() == 1U; },
            "inactive staging must retain all 2400 legal one-frame blocks before initial handoff can commit");
        require(runtime.diagnostics().targetQueuedFrames == 2'400U && output->maximumQueuedFrames() >= 2'400U,
            "descriptor accounting cannot shrink the required 50ms PCM reserve");
        const auto presented = output->callback(512U);
        require(presented.physicalFrames == 512U && output->presentedPosition().generation == owner.generation &&
            output->presentedPosition().selectionToken == owner.selectionToken && output->presentedPosition().lastTimelineFrame == 511,
            "the full inactive prefill becomes exact accepted and presented Original PCM");
        runtime.requestStop(); runtime.join();
    } catch (...) { runtime.requestStop(); runtime.join(); throw; }
}

void coldOriginalPrerollPreservesTheRequestedSignalWithoutWaivingGaps() {
    constexpr double tau = 6.28318530717958647693;
    const auto signal = [](double seconds, std::size_t channel) {
        return channel == 0U
            ? .08 * std::sin(tau * 173.0 * seconds + .23) + .025 * std::cos(tau * 331.0 * seconds + .41)
            : .07 * std::cos(tau * 257.0 * seconds + .67) + .020 * std::sin(tau * 419.0 * seconds + .90);
    };
    const auto exercise = [&](std::uint32_t rate, std::uint64_t positionMs, bool preroll, bool resident = false) {
        const SongRenderOwnership owner{8'106U, resident ? 3U : 2U};
        const SongRenderOwnership initialOwner{owner.selectionToken, resident ? 2U : owner.generation};
        constexpr std::size_t inputFrames = 8'192U, callbackFrames = 256U;
        const auto decoderMs = preroll && positionMs != 0U ? positionMs - 1U : positionMs;
        // Independent decoder contract: the first source sample is ceil(position * source rate).
        const auto firstSource = (decoderMs * rate + 999U) / 1'000U;
        const auto initialSource = resident ? (999U * rate + 999U) / 1'000U : firstSource;
        const auto requestedFrame = positionMs * 48U;
        SongLaneIngressBank first(16'384U, 480U), second(256U, 256U);
        require(first.configureLane(0U, rate, initialOwner.generation), "Original lane configures at its real source rate");
        std::vector<float> pcm(inputFrames * 2U);
        for (std::size_t frame = 0U; frame < inputFrames; ++frame) {
            for (std::size_t channel = 0U; channel != 2U; ++channel)
                pcm[frame * 2U + channel] = static_cast<float>(signal(
                    static_cast<double>(initialSource + frame) / rate, channel));
        }
        auto *lane = first.lane(0U);
        for (std::size_t offset = 0U; offset < inputFrames;) {
            const auto count = std::min<std::size_t>(480U, inputFrames - offset);
            const LaneInputBlock block{pcm.data() + offset * 2U, count,
                {rate, 2U, LanePcmEncoding::Float32}, initialOwner.generation,
                (initialSource + offset) * 1'000'000U / rate};
            require(lane->offer(block).inputFramesAccepted == count, "cold decoder offers its actual finite source samples");
            offset += count;
        }
        require(lane->markEndOfStream(initialOwner.generation, initialSource + inputFrames).status ==
            LaneEndOfStreamStatus::Accepted, "cold decoder seals its exact source endpoint");
        auto output = std::make_unique<ClosureOutput>(0U);
        NativePlaybackRuntime runtime(runtimeConfiguration(), first, second, output->port());
        std::array<NativePlaybackBankIntent, 2U> banks{
            NativePlaybackBankIntent{1U, true, initialOwner, resident ? 48'000U : requestedFrame, 0, 111U, 222U, 0U, 0U,
                (firstSource + inputFrames) * kSampleRateHz / rate + 1U}, {}};
        const auto facts = "cold Original rate=" + std::to_string(rate) + " positionMs=" +
            std::to_string(positionMs) + " preroll=" + std::to_string(preroll) + " resident=" + std::to_string(resident);
        require(runtime.publishCommand(selectedCommand(1U, initialOwner, banks, MixerSourceMode::Original)) ==
            NativePlaybackPublishStatus::Accepted && runtime.start(), "cold Original publishes through the real runtime");
        try {
            if (resident) {
                static_cast<void>(nextCallback(*output, initialOwner.generation, callbackFrames));
                holdProducerAndDrain(*output, initialOwner.generation);
                require(measuredCallback(*output, callbackFrames).wholeRead().frameCount == 0U,
                    "deliberately starved outgoing owner cannot invent PCM");
                output->setProducerHeld(false);
                runtime.notifyIngressAvailable();
                waitUntil([&] { return output->queuedForGeneration(initialOwner.generation) >= callbackFrames; },
                    "resident seek waits for the exact outgoing owner's full four-burst reserve");
                require(lane->advanceGeneration(owner.generation), "resident seek advances its existing decoder lane");
                for (std::size_t frame = 0U; frame < inputFrames; ++frame)
                    for (std::size_t channel = 0U; channel != 2U; ++channel)
                        pcm[frame * 2U + channel] = static_cast<float>(signal(
                            static_cast<double>(firstSource + frame) / rate, channel));
                for (std::size_t offset = 0U; offset < inputFrames;) {
                    const auto count = std::min<std::size_t>(480U, inputFrames - offset);
                    require(lane->offer({pcm.data() + offset * 2U, count,
                        {rate, 2U, LanePcmEncoding::Float32}, owner.generation,
                        (firstSource + offset) * 1'000'000U / rate}).inputFramesAccepted == count,
                        "resident decoder publishes its actual prefixed source samples");
                    offset += count;
                }
                require(lane->markEndOfStream(owner.generation, firstSource + inputFrames).status ==
                    LaneEndOfStreamStatus::Accepted, "resident decoder seals the new exact source endpoint");
                banks[0U] = NativePlaybackBankIntent{2U, true, owner, requestedFrame, 0, 111U, 222U, 0U, 0U,
                    (firstSource + inputFrames) * kSampleRateHz / rate + 1U};
                output->holdCommit(owner.generation);
                require(runtime.publishCommand(selectedCommand(2U, owner, banks, MixerSourceMode::Original)) ==
                    NativePlaybackPublishStatus::Accepted, "resident seek keeps the exact requested native cursor");
                waitUntil([&] { return output->commitEntered(); }, "resident seek reaches its held handoff commit");
                require(output->commitCount() == 1U && output->queuedForGeneration(owner.generation) > 0U,
                    "staged seek PCM is not a committed callback owner");
                const auto outgoing = measuredCallback(*output, callbackFrames).wholeRead();
                require(outgoing.frameCount == callbackFrames &&
                    outgoing.firstTag.generation == initialOwner.generation &&
                    outgoing.lastTag.generation == initialOwner.generation,
                    "held seek commit must still allow exact outgoing owner PCM");
                output->releaseCommit();
                waitUntil([&] { return output->commitCount() == 2U; }, "resident seek commits its exact new generation");
            }
            if (!preroll) {
                waitUntil([&] { return runtime.diagnostics().banks[0U].lanes[0U].discontinuityRejects != 0U; },
                    (facts + " must reject the unfilled Original gap").c_str());
                require(output->commitCount() == 0U && runtime.diagnostics().outputFramesAccepted == 0U &&
                    !output->presentedPosition().valid(), "a quantized future source cannot acquire output or presentation authority");
            } else {
                stemwise::audio::engine::SampleRateConverter converter(rate, kSampleRateHz, 480U);
                // Exclude the declared full SRC filter support, mixer ramps, and 96-frame device edge.
                const auto settledAfter = std::max<std::size_t>(
                    2U * converter.diagnostics().filterOutputLatencyFrames, kSourceRampFrames) + 2U * kCallbackFrames;
                std::size_t checked = 0U;
                for (std::size_t offset = 0U; checked < 2'048U; offset += callbackFrames) {
                    const auto actual = nextCallback(*output, owner.generation, callbackFrames);
                    const auto whole = actual.wholeRead();
                    require(actual.physicalFrames == callbackFrames && whole.frameCount == callbackFrames &&
                        whole.firstTag.timelineFrame == static_cast<std::int64_t>(requestedFrame + offset) &&
                        whole.lastTag.timelineFrame == static_cast<std::int64_t>(requestedFrame + offset + callbackFrames - 1U) &&
                        whole.firstTag.selectionToken == owner.selectionToken && whole.lastTag.generation == owner.generation,
                        (facts + " must physically trim converted prefix and present the requested contiguous owner").c_str());
                    for (std::size_t frame = 0U; frame < callbackFrames; ++frame) {
                        if (offset + frame < settledAfter) continue;
                        for (std::size_t channel = 0U; channel != 2U; ++channel) {
                            const double expected = .5 * signal(
                                static_cast<double>(requestedFrame + offset + frame) / kSampleRateHz, channel);
                            // SRC resets at a source sample: floor-tag quantization is < one 48k frame.
                            // Bound signal error by its analytic derivative plus 2e-5 SRC/float error.
                            const double derivative = channel == 0U
                                ? tau * (.08 * 173.0 + .025 * 331.0)
                                : tau * (.07 * 257.0 + .020 * 419.0);
                            requireNear(actual.pcm[frame * 2U + channel], static_cast<float>(expected),
                                static_cast<float>(.5 * derivative / kSampleRateHz + .00002),
                                (facts + " independent stereo source phase differs").c_str());
                        }
                        ++checked;
                    }
                }
                require(runtime.diagnostics().banks[0U].lanes[0U].discontinuityRejects == 0U &&
                    lane->diagnostics().sourceFramesConsumed != 0U,
                    "cold success must consume real SRC input without a gap exception");
            }
            runtime.requestStop(); runtime.join();
        } catch (...) { output->releaseCommit(); runtime.requestStop(); runtime.join(); throw; }
    };
    for (const auto rate : {11'025U, 22'050U, 32'000U, 44'100U, 48'000U}) {
        for (const auto positionMs : {0U, 1U, 1'001U, 1'002U}) exercise(rate, positionMs, true);
        exercise(rate, 1'010U, true, true);
        if (rate == 11'025U || rate == 22'050U) exercise(rate, 1'001U, false);
    }
}

void compactReceiptHistoryPreservesPartialWritesWrapAndPreparationFailure() {
    using stemwise::audio::NativePushPresentationFrame;
    using stemwise::audio::NativePushPresentationHistory;
    std::array<NativePushPresentationFrame, 128U> source{};
    for (std::size_t frame = 0U; frame < source.size(); ++frame) {
        auto &value = source[frame];
        value.tag.generation = 19U; value.tag.selectionToken = 31U;
        value.tag.controlRevision = frame + 1U;
        value.tag.timelineFrame = 48'144U + frame;
        value.tag.nextTimelineFrame = 48'145U + frame;
        value.tag.stemGainPreviewGainBits[frame % value.tag.stemGainPreviewGainBits.size()] = 0x3f00'0000U;
        value.sourceWriteBoundary = 513U + frame; value.sourceEpoch = 71U;
        value.sequence = 91U; value.ring = 1U;
        value.pcm = {static_cast<float>(frame) / 256.F, -static_cast<float>(frame) / 512.F};
        value.valid = value.control = value.tuning = true;
        value.stem = frame % 2U; value.loop = frame % 3U; value.eligible = frame >= 17U;
    }
    source.back().terminal = true;
    source.back().tag.terminalWriteBoundary = 640U;
    NativePushPresentationHistory history(128U, 128U);
    allocation_probe::failNext = true;
    require(!history.stageSource(source.data(), source.size(), 91U),
        "receipt run allocation failure must precede sink admission");
    require(history.at(0U).outputFrame == (std::numeric_limits<std::uint64_t>::max)(),
        "failed preparation cannot publish acceptance or presentation");
    require(history.stageSource(source.data(), source.size(), 91U), "same exact pending PCM may retry preparation");
    const auto allocations = allocation_probe::count;
    allocation_probe::enabled = true;
    for (std::size_t frame = 0U; frame < source.size(); ++frame) {
        auto accepted = source[frame]; accepted.outputFrame = frame;
        history.acceptSource(frame, accepted);
    }
    history.releaseSource();
    allocation_probe::enabled = false;
    require(allocation_probe::count == allocations, "irreversible partial receipts must never allocate metadata");
    for (std::size_t frame = 0U; frame < source.size(); ++frame) {
        const auto actual = history.at(frame);
        require(actual.tag == source[frame].tag && actual.pcm == source[frame].pcm &&
            actual.sourceWriteBoundary == source[frame].sourceWriteBoundary && actual.sourceEpoch == 71U &&
            actual.outputFrame == frame && actual.sequence == 91U && actual.ring == 1U &&
            actual.valid && actual.control && actual.tuning && actual.terminal == source[frame].terminal &&
            actual.stem == source[frame].stem && actual.loop == source[frame].loop && actual.eligible == source[frame].eligible,
            "compact receipts must retain each exact sample, source boundary, owner and control flag");
    }
    history.retire(0U, 37U);
    require(history.at(36U).outputFrame == (std::numeric_limits<std::uint64_t>::max)() &&
        history.at(37U).tag == source[37U].tag, "presentation retires only its acknowledged prefix");
    require(history.stageSource(source.data(), 37U, 92U), "retired run slots may support the next exact suffix");
    for (std::size_t frame = 0U; frame < 37U; ++frame) {
        auto accepted = source[frame]; accepted.outputFrame = 128U + frame;
        history.acceptSource(frame, accepted);
    }
    history.releaseSource();
    require(history.at(128U).tag == source[0U].tag && history.at(127U).tag == source[127U].tag,
        "history wrap cannot overwrite any unpresented owner");
    history.retire(37U, 128U);
}

void pushMetadataAllocationAndPreparationCost() {
    compactReceiptHistoryPreservesPartialWritesWrapAndPreparationFailure();
    constexpr std::size_t capacity = 16'384U, frames = 512U, blocks = 2'000U;
    const auto footprint = ClosureOutput::storageFootprint(capacity, frames);
    const auto growth = (capacity - 64U) * (2U * sizeof(stemwise::audio::RenderBusControlRun) +
        sizeof(stemwise::audio::NativePushPresentationFrame) + sizeof(std::size_t));
    require(footprint.peakBytes - footprint.minimumBytes == growth,
        "byte admission must include every legal one-control-per-frame peak, not only the initial 64-run pages");
    footprint.admit(footprint.peakBytes);
    bool rejectedPeak = false, rejectedOverflow = false;
    try { footprint.admit(footprint.peakBytes - 1U); } catch (const std::bad_alloc &) { rejectedPeak = true; }
    try { static_cast<void>(stemwise::audio::audioMemoryBytes((std::numeric_limits<std::size_t>::max)(), 2U)); }
    catch (const std::bad_array_new_length &) { rejectedOverflow = true; }
    require(rejectedPeak && rejectedOverflow, "byte admission must fail closed on insufficient peak capacity and arithmetic overflow");
    bool rejectedConstruction = false;
    try {
        ClosureOutput insufficient(0U, capacity, 1U, kCallbackFrames, kCallbackFrames * 4U, +[]() noexcept { return std::uint64_t{0U}; });
    } catch (const std::bad_alloc &) { rejectedConstruction = true; }
    require(rejectedConstruction, "actual output construction must enforce the observed machine byte capacity");
    static std::uint64_t remainingMachineBytes = (std::numeric_limits<std::uint64_t>::max)();
    {
        ClosureOutput staged(0U, capacity, 1U, kCallbackFrames, kCallbackFrames * 4U,
            +[]() noexcept { return remainingMachineBytes; });
        const auto combinedPending = footprint.peakBytes - ClosureOutput::storageFootprint(capacity).minimumBytes;
        remainingMachineBytes = combinedPending - 1U;
        bool rejectedCombinedPeak = false;
        try { static_cast<void>(staged.configurePushConsumer(frames)); }
        catch (const std::bad_alloc &) { rejectedCombinedPeak = true; }
        require(rejectedCombinedPeak, "push setup must admit both its own and the base's still-unallocated descriptor peak");
        remainingMachineBytes = combinedPending;
        require(staged.configurePushConsumer(frames), "exact combined remaining byte capacity must admit unchanged legal frame capacity");
    }
    constexpr SongRenderOwnership owner{8'103U, 1U};
    const auto before = allocation_probe::bytes;
    allocation_probe::enabled = true;
    auto output = std::make_unique<ClosureOutput>(0U, capacity);
    const bool configured = output->configurePushConsumer(frames);
    allocation_probe::enabled = false;
    require(configured, "metadata measurement configures the production push storage");
    const auto ordinaryBytes = allocation_probe::bytes - before;
    allocation_probe::enabled = true;
    output->setDiagnosticPayloadFingerprints(true);
    allocation_probe::enabled = false;
    const auto qualificationBytes = allocation_probe::bytes - before - ordinaryBytes;
    output->setDiagnosticPayloadFingerprints(false);
    const auto port = output->port();
    const auto pcm = corePayload(frames, .08F);
    std::vector<float> written(frames * 2U);
    require(port.publishLateMixControl(port.handle, coreControl(owner, 1U)) &&
        port.setPlaying(port.handle, true) && output->beginPushConsumer(), "metadata work measurement starts the real producer and consumer");
    const auto allocations = allocation_probe::count;
    allocation_probe::enabled = true;
    const auto started = std::chrono::steady_clock::now();
    for (std::size_t block = 0U; block < blocks; ++block) {
        require(port.enqueue(port.handle, coreSubmission(owner, 1U, pcm, block * frames)) == frames,
            "metadata measurement preserves each actual source block");
        const auto prepared = output->preparePushBlock(frames);
        require(prepared.frames == frames, "metadata measurement prepares the exact complete block");
        std::copy_n(prepared.samples, frames * 2U, written.data());
        require(output->preparePushWrite(written.data(), frames, false) &&
            output->acceptPushPrefix(prepared.sequence, frames) &&
            output->observePushPresentation((block + 1U) * frames - 1U), "metadata measurement accepts and presents each real prefix once");
    }
    const auto micros = std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - started).count();
    allocation_probe::enabled = false;
    require(allocation_probe::count == allocations && output->presentedPosition().lastTimelineFrame == blocks * frames - 1U,
        "metadata compaction leaves steady-state push preparation allocation-free and source-exact");
    require(output->metadataAllocatedBytes() < 4U * 1'024U * 1'024U,
        "ordinary per-frame metadata must stay compact at the existing default frame capacity");
    constexpr auto ringPayloadBytes = 2U * capacity * stemwise::audio::kStemwiseMultibusStride * sizeof(float);
    require(ordinaryBytes >= ringPayloadBytes && ordinaryBytes - ringPayloadBytes < 4U * 1'024U * 1'024U,
        "actual ordinary allocations excluding only the two PCM ring payloads must independently fit the metadata budget");
    std::cout << "push metadata capacity=" << capacity << " metadata_bytes=" << output->metadataAllocatedBytes() << " ordinary_bytes=" << ordinaryBytes
        << " admitted_minimum_bytes=" << footprint.minimumBytes << " admitted_peak_bytes=" << footprint.peakBytes
        << " measured_non_ring_payload_bytes=" << ordinaryBytes - ringPayloadBytes
        << " qualification_bytes=" << qualificationBytes << " blocks=" << blocks << " frames=" << frames
        << " elapsed_us=" << micros << '\n';
    require(qualificationBytes >= sizeof(stemwise::audio::NativeAudioPresentationSpan) * (capacity * 2U + frames),
        "qualification-only journal and mapping allocations must remain outside ordinary output storage");
}

void qualificationDiagnosticsAreDefaultOffWithoutChangingPcm() {
    auto outputStorage = std::make_unique<ClosureOutput>();
    auto &output = *outputStorage;
    const auto port = output.port();
    const SongRenderOwnership owner{123U, 1U};
    const auto payload = corePayload(256U, .08F);
    require(port.publishLateMixControl(port.handle, coreControl(owner, 1U)), "qualification endpoint");
    require(port.setPlaying(port.handle, true), "qualification output plays");
    require(port.enqueue(port.handle, coreSubmission(owner, 1U, payload, 0U)) == 256U,
        "ordinary qualification payload");
    const auto ordinary = output.callback(256U);
    require(output.presentedPosition().valid(), "ordinary playback retains operational position evidence");
    require(output.presentationJournal(0U).writeSequence == 0U,
        "ordinary playback published qualification journal without opt-in");
    using namespace stemwise::audio::packet;
    require(output.diagnostics()[OUTPUT_DIAGNOSTICS_PCM_LEVEL_SEQUENCE] == 0,
        "ordinary playback published qualification PCM levels without opt-in");
    auto instrumentedStorage = std::make_unique<ClosureOutput>();
    auto &instrumented = *instrumentedStorage;
    const auto diagnosticPort = instrumented.port();
    require(diagnosticPort.setDiagnosticPayloadFingerprints != nullptr, "diagnostic switch production binding");
    diagnosticPort.setDiagnosticPayloadFingerprints(diagnosticPort.handle, true);
    require(diagnosticPort.publishLateMixControl(diagnosticPort.handle, coreControl(owner, 1U)),
        "instrumented qualification endpoint");
    require(diagnosticPort.setPlaying(diagnosticPort.handle, true), "instrumented output plays");
    require(diagnosticPort.enqueue(diagnosticPort.handle, coreSubmission(owner, 1U, payload, 0U)) == 256U,
        "instrumented qualification payload");
    const auto diagnostic = instrumented.callback(256U);
    require(ordinary.pcm == diagnostic.pcm, "qualification switch changed actual device PCM");
    require(instrumented.presentationJournal(0U).writeSequence == 1U &&
        instrumented.diagnostics()[OUTPUT_DIAGNOSTICS_PCM_LEVEL_SEQUENCE] > 0 &&
        instrumented.diagnostics()[OUTPUT_DIAGNOSTICS_PCM_LEVEL_PEAK] > 0,
        "explicit qualification switch did not publish journal and PCM evidence");
    diagnosticPort.setDiagnosticPayloadFingerprints(diagnosticPort.handle, false);
    const auto sequence = instrumented.diagnostics()[OUTPUT_DIAGNOSTICS_PCM_LEVEL_SEQUENCE];
    require(diagnosticPort.enqueue(diagnosticPort.handle, coreSubmission(owner, 1U, payload, 256U)) == 256U,
        "disabled qualification payload");
    static_cast<void>(instrumented.callback(256U));
    require(instrumented.presentationJournal(0U).writeSequence == 1U &&
        instrumented.diagnostics()[OUTPUT_DIAGNOSTICS_PCM_LEVEL_SEQUENCE] == sequence,
        "disabled qualification switch continued recording");
}

void failedQualificationAllocationKeepsTheJournalIncoherent() {
    auto output = std::make_unique<ClosureOutput>();
    require(output->presentationJournal(0U).coherent, "fresh journal is coherent");
    allocation_probe::failNext = true;
    output->setDiagnosticPayloadFingerprints(true);
    require(!allocation_probe::failNext, "actual qualification allocation must fail");
    auto failed = output->presentationJournal(0U);
    require(failed.invalidPublicationCount == 1U && !failed.coherent && failed.entries.empty(),
        "allocation failure must retain a readable incoherent journal");
    output->setDiagnosticPayloadFingerprints(true);
    const auto port = output->port();
    const SongRenderOwnership owner{123U, 1U};
    const auto payload = corePayload(256U, .08F);
    require(port.publishLateMixControl(port.handle, coreControl(owner, 1U)) &&
        port.setPlaying(port.handle, true) &&
        port.enqueue(port.handle, coreSubmission(owner, 1U, payload, 0U)) == 256U,
        "recovered diagnostic allocation preserves actual playback");
    static_cast<void>(output->callback(256U));
    failed = output->presentationJournal(0U);
    require(failed.invalidPublicationCount == 1U && !failed.coherent && failed.entries.size() == 1U,
        "later valid entries cannot erase a previous unavailable capture");
}

void pushSinkCannotPublishUnacceptedPreparedPcm() {
    constexpr SongRenderOwnership owner{8123U, 1U};
    auto output = std::make_unique<ClosureOutput>(0U);
    const auto port = output->port();
    require(output->configurePushConsumer(512U), "push storage configured off consumer");
    require(port.publishLateMixControl(port.handle, coreControl(owner, 1U)), "push exact endpoint");
    auto payload = corePayload(256U, .08F);
    for (std::size_t frame = 0U; frame < 256U; ++frame) {
        payload[frame * kStemwiseMultibusStride] = .02F + static_cast<float>(frame) * .0001F;
        payload[frame * kStemwiseMultibusStride + 1U] = -payload[frame * kStemwiseMultibusStride];
    }
    require(port.enqueue(port.handle, coreSubmission(owner, 1U, payload, 0U, true)) == 256U,
        "push exact source queued");
    require(port.setPlaying(port.handle, true) && output->beginPushConsumer(), "push consumer starts");
    const auto block = output->preparePushBlock(256U);
    require(block.frames == 256U && block.samples != nullptr, "one pending PCM block");
    require(!output->presentedPosition().valid() && !output->presentationEvidence().valid(),
        "rendering before sink acceptance must not publish output ownership");
    const std::vector<float> rendered(block.samples, block.samples + 256U * 2U);
    auto reference = std::make_unique<ClosureOutput>(0U);
    const auto referencePort = reference->port();
    require(referencePort.publishLateMixControl(referencePort.handle, coreControl(owner, 1U)) &&
        referencePort.enqueue(referencePort.handle, coreSubmission(owner, 1U, payload, 0U, true)) == 256U &&
        referencePort.setPlaying(referencePort.handle, true), "ordinary callback reference uses unchanged construction");
    const auto callback = reference->callback(256U);
    require(std::equal(rendered.begin(), rendered.end(), callback.pcm.begin()),
        "push preparation uses the same real mixer, transition and final-device PCM as the default callback");
    std::vector<float> accepted;
    require(output->pushSubmittedFrames() == 0U && !output->observePushPresentation(0U),
        "unaccepted frames have neither a submission receipt nor presentation authority");
    accepted.insert(accepted.end(), block.samples, block.samples + 192U * 2U);
    require(output->acceptPushPrefix(block.sequence, 192U), "sink accepts first192 frames");
    require(output->pushSubmittedFrames() == 192U && !output->presentedPosition().valid(),
        "positive acceptance advances submission only");
    require(output->flushToGenerationStatus(0U) == NativeAudioFlushStatus::StaleGeneration &&
        !output->commitHandoff(999U) && output->pushResetRequests() == 0U &&
        output->pushSubmittedFrames() == 192U,
        "stale flush and missing handoff target cannot ask the adapter to discard valid accepted or pending PCM");
    require(output->beginHandoff(2U) && !output->commitHandoff(2U) && output->pushResetRequests() == 0U &&
        output->pushSubmittedFrames() == 192U,
        "valid empty staging target must reject before asking the adapter to discard accepted192 or pending64");
    const auto suffix = output->preparePushBlock(256U);
    require(suffix.sequence == block.sequence && suffix.offset == 192U && suffix.frames == 64U,
        "partial receipt keeps the original exact suffix");
    require(output->acceptPushPrefix(block.sequence, 0U) && output->pushSubmittedFrames() == 192U,
        "zero sink progress never advances any frame ledger");
    require(!output->acceptPushPrefix(block.sequence + 1U, 64U) &&
        !output->acceptPushPrefix(block.sequence, 65U), "wrong receipt and oversized receipt fail closed");
    require(output->observePushPresentation(191U) && output->presentedPosition().lastTimelineFrame == 191 &&
        !output->presentedPosition().terminalAfterFrame, "hardware sees only accepted first192 source frames");
    require(!output->observePushPresentation(255U), "hardware cannot publish unaccepted terminal suffix");
    accepted.insert(accepted.end(), suffix.samples, suffix.samples + suffix.frames * 2U);
    require(output->acceptPushPrefix(block.sequence, 64U) && accepted == rendered,
        "192/zero/64 submits every original rendered sample exactly once without rerendering");
    require(output->observePushPresentation(255U) && output->presentedPosition().lastTimelineFrame == 255,
        "hardware consumes exact final suffix");
    require(output->quiescePushConsumer(), "push feeder acknowledges parked state");
    require(output->discardPushPending(true), "quiescent seek can retire pending and timestamp epoch");
    require(!output->presentedPosition().valid() && !output->acceptPushPrefix(block.sequence, 0U),
        "retired receipt cannot publish the discarded epoch");
}

void persistentLogicalPauseHoldsItsUnacceptedSourceSuffix(bool pauseBeforeWriteReceipt) {
    using Kind = stemwise::audio::NativeAudioOutputCallbackCore::PushTransportSilenceKind;
    auto output = std::make_unique<ClosureOutput>(0U);
    const auto port = output->port();
    constexpr SongRenderOwnership owner{8190U, 1U};
    require(output->configurePushConsumer(512U), "persistent source storage");
    require(port.publishLateMixControl(port.handle, coreControl(owner, 1U)), "persistent source endpoint");
    const auto pcm = corePayload(512U, .08F);
    require(port.enqueue(port.handle, coreSubmission(owner, 1U, pcm, 0U)) == 512U &&
        port.setPlaying(port.handle, true) && output->beginPushConsumer(), "persistent source consumer");
    const auto source = output->preparePushBlock(256U);
    const std::vector<float> suffix(source.samples + 192U * 2U, source.samples + 256U * 2U);
    require(source.frames == 256U, "persistent source prepares a real immutable block");
    std::atomic<bool> writeEntered{false}, releaseWrite{false};
    std::exception_ptr writeFailure;
    std::vector<float> written;
    std::thread sink([&] {
        try {
            written.assign(source.samples, source.samples + 192U * 2U);
            writeEntered.store(true, std::memory_order_release);
            waitUntil([&] { return releaseWrite.load(std::memory_order_acquire); },
                "bounded platform write receipt release");
            require(output->acceptPushPrefix(source.sequence, 192U),
                "a positive platform prefix remains accepted when Pause wins the race");
        } catch (...) { writeFailure = std::current_exception(); }
    });
    try {
        waitUntil([&] { return writeEntered.load(std::memory_order_acquire); },
            "actual source write enters before its positive receipt");
        if (pauseBeforeWriteReceipt) {
            require(port.setPlaying(port.handle, false), "Pause crosses the in-flight source write");
            require(output->pushSubmittedFrames() == 0U && !output->presentedPosition().valid(),
                "in-flight bytes do not become accepted or audible before their receipt");
        }
        releaseWrite.store(true, std::memory_order_release);
        sink.join();
    } catch (...) {
        releaseWrite.store(true, std::memory_order_release);
        sink.join();
        throw;
    }
    if (writeFailure) std::rethrow_exception(writeFailure);
    require(std::equal(written.begin(), written.end(), source.samples),
        "Pause cannot change any of the actual positive prefix samples");
    if (!pauseBeforeWriteReceipt)
        require(port.setPlaying(port.handle, false), "logical pause preserves physical consumer");
    require(output->preparePushBlock(256U).frames == 0U,
        "logical source pause cannot expose its held source suffix as the next transport block");
    require(output->pushSubmittedFrames() == 192U && !output->presentedPosition().valid(),
        "logical pause cannot invent accepted or presented source frames");
    const auto silence = output->preparePushTransportSilence(Kind::LogicalPause);
    require(silence != 0U && silence != source.sequence, "paused zeros own an independent pre-write receipt");
    require(port.setPlaying(port.handle, true) && output->acceptPushTransportSilence(silence, 192U) &&
        output->acceptPushTransportSilence(silence, 0U),
        "resume after a zero write starts cannot reject its irrevocable positive prefix");
    require(output->observePushPresentation(383U) && output->presentedPosition().lastTimelineFrame == 191,
        "pause silence advances sink time while accepted source time freezes at its real prefix");
    const auto resumed = output->preparePushBlock(256U);
    require(resumed.sequence == source.sequence && resumed.frames == 64U && resumed.offset == 192U &&
        std::equal(suffix.begin(), suffix.end(), resumed.samples), "resume retains the exact original unaccepted source suffix");
    require(!output->acceptPushTransportSilence(silence, 64U) && output->acceptPushPrefix(source.sequence, 64U) &&
        output->observePushPresentation(447U) && output->presentedPosition().lastTimelineFrame == 255,
        "only the current source receipt resumes the retained suffix once");
    require(output->diagnostics()[stemwise::audio::packet::OUTPUT_DIAGNOSTICS_UNDERRUN_CALLBACK_COUNT] == 0,
        "authorized pause zeros are not source starvation");
}

void persistentAcceptedEdgesRampWithoutAdvancingTheHeldSource() {
    using Kind = stemwise::audio::NativeAudioOutputCallbackCore::PushTransportSilenceKind;
    auto output = std::make_unique<ClosureOutput>(0U);
    const auto port = output->port();
    port.setDiagnosticPayloadFingerprints(port.handle, true);
    constexpr SongRenderOwnership owner{8199U, 1U};
    require(output->configurePushConsumer(512U) && port.publishLateMixControl(port.handle, coreControl(owner, 1U)),
        "edge fixture owns the real push consumer");
    const auto sourcePcm = corePayload(512U, .08F);
    require(port.enqueue(port.handle, coreSubmission(owner, 1U, sourcePcm, 0U)) == 512U &&
        port.setPlaying(port.handle, true) && output->beginPushConsumer(), "edge source starts");
    const auto source = output->preparePushBlock(256U);
    std::vector<float> prefix(source.samples, source.samples + source.frames * 2U);
    require(output->preparePushWrite(prefix.data(), source.frames, false) &&
        output->acceptPushPrefix(source.sequence, 192U), "edge prefix accepts only 192 frames");
    std::vector<float> emitted(prefix.begin(), prefix.begin() + 192U * 2U);
    require(port.setPlaying(port.handle, false), "edge logical pause");
    const auto silence = output->preparePushTransportSilence(Kind::LogicalPause);
    std::vector<float> paused(512U * 2U);
    require(silence != 0U && output->preparePushWrite(paused.data(), 512U, true), "pause edge prepares");
    const auto zeroWrite = paused;
    paused.assign(512U * 2U, 0.0F);
    require(output->preparePushWrite(paused.data(), 512U, true) && paused == zeroWrite,
        "zero write cannot advance the bounded envelope");
    require(output->acceptPushTransportSilence(silence, 32U), "partial pause edge accepts exact prefix");
    emitted.insert(emitted.end(), paused.begin(), paused.begin() + 64U);
    paused.assign(480U * 2U, 0.0F);
    require(output->preparePushWrite(paused.data(), 480U, true) &&
        output->acceptPushTransportSilence(silence, 160U), "remaining pause edge continues from accepted frame");
    emitted.insert(emitted.end(), paused.begin(), paused.begin() + 320U);
    require(output->observePushPresentation(383U) && output->presentedPosition().lastTimelineFrame == 191,
        "output ramp and pause silence never advance source time");
    require(port.setPlaying(port.handle, true), "edge resume");
    const auto suffix = output->preparePushBlock(256U);
    require(suffix.sequence == source.sequence && suffix.offset == 192U && suffix.frames == 64U,
        "resume retains exact source sequence and cursor");
    std::vector<float> resumed(suffix.samples, suffix.samples + suffix.frames * 2U);
    require(output->preparePushWrite(resumed.data(), suffix.frames, false) &&
        output->acceptPushPrefix(suffix.sequence, 64U), "resume edge accepts the held suffix once");
    emitted.insert(emitted.end(), resumed.begin(), resumed.end());
    const auto following = output->preparePushBlock(256U);
    std::vector<float> rest(following.samples, following.samples + following.frames * 2U);
    require(output->preparePushWrite(rest.data(), following.frames, false) &&
        output->acceptPushPrefix(following.sequence, following.frames) &&
        output->observePushPresentation(703U) && output->presentedPosition().lastTimelineFrame == 511,
        "resume envelope crosses block boundary without missing or duplicating source frames");
    emitted.insert(emitted.end(), rest.begin(), rest.end());
    const auto journal = output->presentationJournal(0U);
    require(journal.coherent && journal.overflowCount == 0U && journal.invalidPublicationCount == 0U,
        "accepted ramp source journal remains coherent");
    std::uint64_t nextSource = 0U;
    for (const auto &span : journal.entries) {
        require(span.generation == owner.generation && span.selectionToken == owner.selectionToken &&
            span.firstTimelineFrame == static_cast<std::int64_t>(nextSource) &&
            span.pcmFrames == static_cast<std::uint64_t>(span.lastTimelineFrame - span.firstTimelineFrame + 1) &&
            span.lastNextTimelineFrame == span.lastTimelineFrame + 1 && span.continuityViolationCount == 0U,
            "accepted output envelope must not erase, skip or repeat exact source journal frames");
        nextSource = static_cast<std::uint64_t>(span.lastNextTimelineFrame);
    }
    require(nextSource == 512U, "journal includes every accepted source frame through pause and resume envelope");
    for (std::size_t frame = 1U; frame < emitted.size() / 2U; ++frame)
        require(std::abs(emitted[frame * 2U] - emitted[(frame - 1U) * 2U]) < .002F,
            "accepted nonzero pause and resume edges must remain continuous");
    require(emitted[383U * 2U] == 0.0F && std::abs(emitted.back() - .08F) < .00001F,
        "pause reaches zero and resume reaches the unchanged source level");
}

void recreatedPhysicalOutputCannotReuseRetiredAcceptedEdges() {
    using Kind = stemwise::audio::NativeAudioOutputCallbackCore::PushTransportSilenceKind;
    for (const std::size_t accepted : {32U, 512U}) {
        for (const unsigned edge : {0U, 1U, 2U}) {
            auto output = std::make_unique<ClosureOutput>(0U);
            const auto port = output->port();
            constexpr SongRenderOwnership old{8205U, 1U}, replacement{8206U, 2U};
            const auto pcm = corePayload(1'024U, .4F);
            require(output->configurePushConsumer(512U) &&
                port.publishLateMixControl(port.handle, coreControl(old, 1U)) &&
                port.enqueue(port.handle, coreSubmission(old, 1U, pcm, 0U)) == 1'024U &&
                port.setPlaying(port.handle, true) && output->beginPushConsumer(),
                "retired edge fixture opens a real nonzero stream");
            const auto block = output->preparePushBlock(512U);
            std::vector<float> written(block.samples, block.samples + block.frames * 2U);
            require(block.frames == 512U && output->preparePushWrite(written.data(), block.frames, false) &&
                output->acceptPushPrefix(block.sequence, accepted) && written[(accepted - 1U) * 2U] > 0.F,
                "old sink accepts a nonzero full or partial prefix");
            if (edge != 0U) {
                require(port.setPlaying(port.handle, false), "old sink begins its pause edge");
                const auto silence = output->preparePushTransportSilence(Kind::LogicalPause);
                std::vector<float> paused(1'024U);
                require(silence != 0U && output->preparePushWrite(paused.data(), 512U, true) &&
                    output->acceptPushTransportSilence(silence, 16U), "old pause envelope is partially accepted");
                if (edge == 2U) {
                    require(port.setPlaying(port.handle, true), "old sink begins its resume edge");
                    const auto resumed = output->preparePushBlock(512U);
                    std::vector<float> resume(resumed.samples, resumed.samples + resumed.frames * 2U);
                    require(resumed.frames >= 16U && output->preparePushWrite(resume.data(), resumed.frames, false) &&
                        output->acceptPushPrefix(resumed.sequence, 16U), "old resume envelope is partially accepted");
                }
            }
            require(output->quiescePushConsumer() && output->retireForStop(replacement.generation),
                "acknowledged physical retirement resets the shared STOP/reopen boundary");
            const auto silence = corePayload(512U, 0.F);
            require(port.publishLateMixControl(port.handle, coreControl(replacement, 1U)) &&
                port.enqueue(port.handle, coreSubmission(replacement, 1U, silence, 0U)) == 512U &&
                port.setPlaying(port.handle, true) && output->beginPushConsumer(), "replacement sink owns exact silence");
            const auto fresh = output->preparePushBlock(512U);
            std::vector<float> actual(fresh.samples, fresh.samples + fresh.frames * 2U);
            require(fresh.frames == 512U && output->preparePushWrite(actual.data(), fresh.frames, false) &&
                output->acceptPushPrefix(fresh.sequence, fresh.frames), "replacement sink accepts its own source");
            const auto message = "AE-10 physical replacement silence inherited retired PCM/envelope: prefix=" +
                std::to_string(accepted) + " edge=" + std::to_string(edge) + " first=" + std::to_string(actual.front());
            require(std::all_of(actual.begin(), actual.end(), [](float sample) { return sample == 0.F; }), message.c_str());
        }
    }
}

void fullyAcceptedBlockResumesFromAcceptedSilence() {
    using Kind = stemwise::audio::NativeAudioOutputCallbackCore::PushTransportSilenceKind;
    auto output = std::make_unique<ClosureOutput>(0U);
    const auto port = output->port();
    constexpr SongRenderOwnership owner{8203U, 1U};
    const auto pcm = corePayload(512U, .08F);
    require(output->configurePushConsumer(512U) &&
        port.publishLateMixControl(port.handle, coreControl(owner, 1U)) &&
        port.enqueue(port.handle, coreSubmission(owner, 1U, pcm, 0U)) == 512U &&
        port.setPlaying(port.handle, true) && output->beginPushConsumer(), "complete-block resume setup");
    const auto first = output->preparePushBlock(256U);
    std::vector<float> accepted(first.samples, first.samples + first.frames * 2U);
    require(first.frames == 256U && output->preparePushWrite(accepted.data(), first.frames, false) &&
        output->acceptPushPrefix(first.sequence, first.frames) && port.setPlaying(port.handle, false),
        "sink consumes the complete source block before pause");
    const auto silence = output->preparePushTransportSilence(Kind::LogicalPause);
    std::vector<float> zeros(512U * 2U);
    require(silence != 0U && output->preparePushWrite(zeros.data(), 512U, true) &&
        output->acceptPushTransportSilence(silence, 512U) && zeros.back() == 0.F &&
        port.setPlaying(port.handle, true), "accepted pause reaches actual silence");
    const auto resumed = output->preparePushBlock(256U);
    std::vector<float> resume(resumed.samples, resumed.samples + resumed.frames * 2U);
    require(resumed.frames == 256U && output->preparePushWrite(resume.data(), 256U, false) &&
        std::abs(resume.front() - .08F / 96.F) < .000001F,
        "fresh resume block ramps from accepted silence rather than its unaccepted future endpoint");
    const auto zeroWrite = resume;
    resume.assign(resumed.samples, resumed.samples + resumed.frames * 2U);
    require(output->preparePushWrite(resume.data(), 256U, false) && resume == zeroWrite &&
        output->acceptPushPrefix(resumed.sequence, 32U), "zero write leaves the exact resume edge unadvanced");
    std::vector<float> suffix(resumed.samples + 64U, resumed.samples + 512U);
    require(output->preparePushWrite(suffix.data(), 224U, false) &&
        std::abs(suffix.front() - .08F * 33.F / 96.F) < .000001F &&
        output->acceptPushPrefix(resumed.sequence, 224U) && output->pushSubmittedFrames() == 1024U &&
        output->observePushPresentation(1023U) && output->presentedPosition().lastTimelineFrame == 511,
        "partial resume advances only accepted envelope frames and presents every source frame once");
}

void persistentSourceTransitionPreservesAcceptedTailAndRejectsStaleCompletion() {
    using Kind = stemwise::audio::NativeAudioOutputCallbackCore::PushTransportSilenceKind;
    auto output = std::make_unique<ClosureOutput>(0U);
    const auto port = output->port();
    constexpr SongRenderOwnership old{8191U, 1U}, next{8192U, 2U}, latest{8193U, 3U};
    require(output->configurePushConsumer(512U) && port.publishLateMixControl(port.handle, coreControl(old, 1U)),
        "persistent transition setup");
    const auto pcm = corePayload(256U, .08F);
    require(port.enqueue(port.handle, coreSubmission(old, 1U, pcm, 0U)) == 256U &&
        port.setPlaying(port.handle, true) && output->beginPushConsumer(), "persistent transition starts");
    const auto block = output->preparePushBlock(256U);
    require(output->acceptPushPrefix(block.sequence, 192U) && output->beginHandoff(2U) &&
        output->beginPushSourceTransition(2U), "exact source fence after old positive write receipt");
    require(!output->commitHandoff(2U) && output->preparePushBlock(256U).frames == 0U &&
        !output->acceptPushPrefix(block.sequence, 64U) && !output->discardPushPending(true),
        "empty replacement retains fenced source and never pretends the physical consumer is quiescent");
    require(port.publishLateMixControl(port.handle, coreControl(next, 1U)) &&
        port.enqueue(port.handle, coreSubmission(next, 1U, pcm, 4096U)) == 256U, "replacement becomes genuinely ready");
    const auto zero = output->preparePushTransportSilence(Kind::SourceTransition);
    require(zero != 0U && output->commitHandoff(2U) && output->acceptPushTransportSilence(zero, 64U),
        "successful producer adoption releases source fence without rejecting an in-flight zero receipt");
    require(output->preparePushTransportSilence(Kind::SourceTransition) == 0U &&
        output->observePushPresentation(255U) && output->presentedPosition().generation == 1U &&
        output->presentedPosition().lastTimelineFrame == 191, "accepted old tail keeps its identity after new source adoption");
    const auto replacement = output->preparePushBlock(256U);
    require(replacement.frames == 256U && replacement.sequence != block.sequence &&
        output->acceptPushPrefix(replacement.sequence, 64U) && output->observePushPresentation(319U) &&
        output->presentedPosition().generation == 2U && output->presentedPosition().lastTimelineFrame == 4159,
        "future source begins at its actual new sink boundary without relabeling accepted old PCM");
    require(output->beginPushSourceTransition(3U) && !output->beginPushSourceTransition(2U) &&
        output->flushToGenerationStatus(3U) == NativeAudioFlushStatus::Accepted,
        "newer source fence supersedes only forward and supports source-only administrative reset");
    require(port.publishLateMixControl(port.handle, coreControl(latest, 1U)) &&
        port.enqueue(port.handle, coreSubmission(latest, 1U, pcm, 8192U)) == 256U, "latest generation is ready");
    const auto fresh = output->preparePushBlock(256U);
    require(fresh.frames == 256U && !output->acceptPushPrefix(replacement.sequence, 64U) &&
        output->acceptPushPrefix(fresh.sequence, 64U) && output->observePushPresentation(383U) &&
        output->presentedPosition().generation == 3U && output->presentedPosition().lastTimelineFrame == 8255,
        "source-only reset preserves the continuous output frame axis and rejects retired source receipts");
    require(port.beginDrainHandoff(port.handle, 3U, 4U, 51U), "paused cancellation stage opens");
    const SongRenderOwnership unused{8194U, 4U};
    require(port.publishLateMixControl(port.handle, coreControl(unused, 1U)) &&
        port.enqueue(port.handle, coreSubmission(unused, 1U, pcm, 0U)) == 256U &&
        port.commitDrainHandoff(port.handle, 3U, 4U, 51U), "actual successor cancellation is pending");
    require(port.cancelDrainHandoff(port.handle, 3U, 4U, 51U) == NativeAudioDrainCancelStatus::CancellationRequested &&
        port.setPlaying(port.handle, false) && output->preparePushTransportSilence(Kind::LogicalPause) != 0U &&
        port.cancelDrainHandoff(port.handle, 3U, 4U, 51U) == NativeAudioDrainCancelStatus::OutgoingActive,
        "transport preparation acknowledges actual cancellation before any zero write can make progress");
}

void sameSongReplacementFencesTheFinalPartialReceiptBeforeChoosingItsCursor() {
    for (const double step : {1.0, 1.25}) {
        constexpr SongRenderOwnership old{8199U, 1U}, next{8199U, 2U};
        auto output = std::make_unique<ClosureOutput>(0U);
        const auto port = output->port();
        require(output->configurePushConsumer(512U) &&
            port.publishLateMixControl(port.handle, coreControl(old, 1U)), "replacement push owner");
        auto pcm = corePayload(256U, .08F);
        for (std::size_t i = 0U; i < 256U; ++i)
            pcm[i * kStemwiseMultibusStride] = .02F + static_cast<float>(i) * .0001F;
        auto submission = coreSubmission(old, 1U, pcm, 96'000U);
        submission.sourceEndFrameExclusive = 96'000.0 + 256.0 * step;
        require(port.enqueue(port.handle, submission) == 256U && port.setPlaying(port.handle, true) &&
            output->beginPushConsumer(), "old source enters retained device queue");
        const auto block = output->preparePushBlock(256U);
        require(block.frames == 256U && output->acceptPushPrefix(block.sequence, 64U) &&
            output->observePushPresentation(63U), "presentation trails sink acceptance");
        require(output->prepareSourceTransition(1U, old.selectionToken, 2U, -1) == -1,
            "an earlier accepted snapshot cannot settle before the sole feeder fence");
        const std::vector<float> retainedQueue(block.samples, block.samples + 192U * 2U);
        require(output->acceptPushPrefix(block.sequence, 128U) && output->beginPushSourceTransition(2U),
            "last partial old write completes immediately before its feeder fence");
        const auto nextFrame = output->prepareSourceTransition(1U, old.selectionToken, 2U, -1);
        require(nextFrame == 96'000 + static_cast<std::int64_t>(192.0 * step) &&
            output->prepareSourceTransition(1U, old.selectionToken + 1U, 2U, 0) == -1 &&
            output->prepareSourceTransition(2U, old.selectionToken, 3U, 0) == -1 &&
            !output->acceptPushPrefix(block.sequence, 1U), "cursor binds exact accepted owner and rejects stale receipts");
        require(!output->cancelPushSourceTransition(3U) && output->cancelPushSourceTransition(2U),
            "only the exact abandoned fence may resume the old source");
        const auto resumed = output->preparePushBlock(256U);
        require(resumed.sequence == block.sequence && resumed.offset == 192U && resumed.frames == 64U &&
            output->beginPushSourceTransition(2U), "cancellation preserves the unaccepted old suffix without replay");
        auto replacement = corePayload(256U, .08F);
        for (std::size_t i = 0U; i < 256U; ++i)
            replacement[i * kStemwiseMultibusStride] = .02F + static_cast<float>(192U + i) * .0001F;
        auto incoming = coreSubmission(next, 1U, replacement, static_cast<std::uint64_t>(nextFrame));
        incoming.sourceEndFrameExclusive = static_cast<double>(nextFrame) + 256.0 * step;
        require(output->beginHandoff(2U) && port.publishLateMixControl(port.handle, coreControl(next, 1U)) &&
            port.enqueue(port.handle, incoming) == 256U && output->commitHandoff(2U), "exact cursor primes replacement");
        require(output->observePushPresentation(191U) && output->presentedPosition().generation == 1U &&
            output->presentedPosition().lastNextTimelineFrame == nextFrame,
            "draining the retained old queue ends at the chosen exact next cursor");
        const auto fresh = output->preparePushBlock(256U);
        require(fresh.frames == 256U && output->acceptPushPrefix(fresh.sequence, 256U), "replacement PCM accepted once");
        for (std::size_t i = 0U; i < 256U; ++i) {
            require(output->observePushPresentation(192U + i) &&
                output->presentedPosition().lastTimelineFrame == nextFrame + static_cast<std::int64_t>(i * step),
                "every replacement source position follows the accepted tail without replay");
            if (i >= 128U) require(std::abs(fresh.samples[i * 2U] - replacement[i * kStemwiseMultibusStride]) < .000001F,
                "identifiable replacement PCM agrees with source positions after the intentional transition envelope");
        }
        require(retainedQueue.size() == 384U && output->pushSubmittedFrames() == 448U,
            "retained old PCM and replacement receipts cover the device queue exactly once");
        require(output->quiescePushConsumer() && output->discardPushPending(true) &&
            port.setPlaying(port.handle, false), "physical reset retires source receipt");
        require(output->prepareSourceTransition(2U, next.selectionToken, 3U, 0) == 0,
            "discarded physical queues cannot donate a retired accepted cursor");
    }
}

void pushEnvelopeWaitsForTheExactSourceFlush() {
    using Core = stemwise::audio::NativeAudioOutputCallbackCore;
    auto output = std::make_unique<ClosureOutput>(0U);
    const auto port = output->port();
    constexpr SongRenderOwnership old{8201U, 1U}, next{8202U, 2U};
    const auto pcm = corePayload(256U, .08F);
    require(output->configurePushConsumer(512U) &&
        port.publishLateMixControl(port.handle, coreControl(old, 1U)) &&
        port.enqueue(port.handle, coreSubmission(old, 1U, pcm, 0U)) == 256U &&
        port.setPlaying(port.handle, true) && output->beginPushConsumer(),
        "contended source flush owns the real push consumer");
    const auto oldBlock = output->preparePushBlock(256U);
    require(oldBlock.frames == 256U && output->acceptPushPrefix(oldBlock.sequence, 192U) &&
        output->beginPushSourceTransition(2U), "source fence retains the exact accepted old prefix");
    const auto silence = output->preparePushTransportSilence(Core::PushTransportSilenceKind::SourceTransition);
    require(silence != 0U, "source transition allocates an actual pre-write silence ticket");
    output->holdSourceFlush();
    NativeAudioFlushStatus flush = NativeAudioFlushStatus::InvalidState;
    std::atomic<bool> feederEntered{false}, feederFinished{false};
    bool prepared = false;
    std::vector<float> zeros(512U * 2U);
    std::thread producer([&] { flush = output->flushToGenerationStatus(2U); });
    std::thread feeder;
    try {
        waitUntil([&] { return output->sourceFlushEntered(); }, "producer holds the real source-flush operation");
        feeder = std::thread([&] {
            feederEntered.store(true, std::memory_order_release);
            prepared = output->preparePushWrite(zeros.data(), 512U, true);
            feederFinished.store(true, std::memory_order_release);
        });
        waitUntil([&] { return feederEntered.load(std::memory_order_acquire); }, "feeder attempts envelope preparation");
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
        require(!feederFinished.load(std::memory_order_acquire),
            "valid envelope preparation waits for producer source flush rather than reporting a permanent failure");
        output->releaseSourceFlush();
        producer.join();
        feeder.join();
    } catch (...) {
        output->releaseSourceFlush();
        producer.join();
        if (feeder.joinable()) feeder.join();
        throw;
    }
    require(flush == NativeAudioFlushStatus::Accepted && prepared && output->producerGeneration() == 2U &&
        output->pushSubmittedFrames() == 192U,
        "serialized source reset and envelope preparation do not invent a write receipt");
    require(output->acceptPushTransportSilence(silence, 192U), "the in-flight silence ticket accepts its exact prefix");
    zeros.assign(320U * 2U, 0.0F);
    require(output->preparePushWrite(zeros.data(), 320U, true) &&
        output->acceptPushTransportSilence(silence, 320U) &&
        !output->acceptPushTransportSilence(silence, 1U) && output->pushSubmittedFrames() == 704U &&
        output->observePushPresentation(703U) && output->presentedPosition().generation == 1U &&
        output->presentedPosition().lastTimelineFrame == 191,
        "source-only reset preserves the ticket once, and presented transport never relabels old source PCM");
    require(port.publishLateMixControl(port.handle, coreControl(next, 1U)) &&
        port.enqueue(port.handle, coreSubmission(next, 1U, pcm, 4096U)) == 256U,
        "the exact replacement owner remains usable after producer contention");
    const auto replacement = output->preparePushBlock(256U);
    require(replacement.frames == 256U && replacement.sequence != oldBlock.sequence &&
        !output->acceptPushPrefix(oldBlock.sequence, 64U) &&
        output->acceptPushPrefix(replacement.sequence, 256U) && output->pushSubmittedFrames() == 960U &&
        output->observePushPresentation(959U) && output->presentedPosition().generation == 2U &&
        output->presentedPosition().lastTimelineFrame == 4351,
        "replacement readback presents its exact source range without replaying a retired suffix");
}

void pushPreparationWaitsForTheCompleteSourceBlock() {
    using namespace stemwise::audio::packet;
    constexpr SongRenderOwnership owner{8196U, 1U};
    auto output = std::make_unique<ClosureOutput>(0U);
    output->setDiagnosticPayloadFingerprints(true);
    const auto port = output->port();
    require(output->configurePushConsumer(512U) &&
        port.publishLateMixControl(port.handle, coreControl(owner, 1U)), "partial push setup");
    const auto prefix = corePayload(416U, .08F), suffix = corePayload(96U, .12F);
    require(port.enqueue(port.handle, coreSubmission(owner, 1U, prefix, 0U)) == 416U &&
        port.setPlaying(port.handle, true) && output->beginPushConsumer(), "partial push source");
    // Runtime grants this permission during ordinary playback; it is not evidence of queued EOS.
    output->setTerminalSilenceAuthorization(owner.generation, true);
    const auto before = output->diagnostics();
    for (unsigned attempt = 0U; attempt < 3U; ++attempt) {
        const auto deferred = output->preparePushBlock(512U);
        require(deferred.frames == 0U && deferred.samples == nullptr && deferred.sequence == 0U &&
            !output->acceptPushPrefix(1U, 0U) && !output->observePushPresentation(0U),
            "incomplete push block has no bytes, ticket, acceptance or presentation authority");
        require(output->queuedForGeneration(owner.generation) == 416U &&
            output->pushSubmittedFrames() == 0U && !output->presentedPosition().valid() &&
            output->presentationJournal(0U).writeSequence == 0U,
            "deferral preserves every queued source frame and the empty submission and presentation ledgers");
        const auto after = output->diagnostics();
        for (const auto field : {OUTPUT_DIAGNOSTICS_RENDERED_FRAMES, OUTPUT_DIAGNOSTICS_CALLBACK_COUNT,
                OUTPUT_DIAGNOSTICS_CALLBACK_FRAMES, OUTPUT_DIAGNOSTICS_UNDERRUN_CALLBACK_COUNT,
                OUTPUT_DIAGNOSTICS_UNDERRUN_FRAMES, OUTPUT_DIAGNOSTICS_PROCESS_UNDERRUN_CALLBACK_COUNT,
                OUTPUT_DIAGNOSTICS_PROCESS_UNDERRUN_FRAMES, OUTPUT_DIAGNOSTICS_PCM_LEVEL_SEQUENCE,
                OUTPUT_DIAGNOSTICS_PRESENTED_OWNER_SEQUENCE})
            require(after[field] == before[field], "deferral cannot mutate callback, shortage or evidence counters");
    }
    require(port.enqueue(port.handle, coreSubmission(owner, 1U, suffix, 416U)) == 96U,
        "producer appends the exact missing source suffix");
    const auto block = output->preparePushBlock(512U);
    require(block.frames == 512U && block.samples != nullptr && block.sequence == 1U && block.offset == 0U &&
        output->queuedForGeneration(owner.generation) == 0U && output->pushSubmittedFrames() == 0U &&
        !output->presentedPosition().valid() && output->presentationJournal(0U).writeSequence == 0U,
        "first complete preparation consumes exact source once without publishing it");
    auto reference = std::make_unique<ClosureOutput>(0U);
    const auto referencePort = reference->port();
    require(referencePort.publishLateMixControl(referencePort.handle, coreControl(owner, 1U)) &&
        referencePort.enqueue(referencePort.handle, coreSubmission(owner, 1U, prefix, 0U)) == 416U &&
        referencePort.enqueue(referencePort.handle, coreSubmission(owner, 1U, suffix, 416U)) == 96U &&
        referencePort.setPlaying(referencePort.handle, true), "complete hardware callback reference");
    const auto rendered = reference->callback(512U);
    require(std::equal(block.samples, block.samples + 1'024U, rendered.pcm.begin()),
        "deferred push preserves every numerical prefix and suffix sample without invented silence");
    require(output->acceptPushPrefix(block.sequence, 416U) && output->pushSubmittedFrames() == 416U &&
        !output->presentedPosition().valid() && output->presentationJournal(0U).writeSequence == 0U &&
        !output->observePushPresentation(511U) && output->observePushPresentation(415U) &&
        output->presentedPosition().lastTimelineFrame == 415, "only accepted hardware-observed prefix becomes public");
    const auto remainder = output->preparePushBlock(512U);
    require(remainder.sequence == block.sequence && remainder.offset == 416U && remainder.frames == 96U &&
        remainder.samples == block.samples + 832U && output->acceptPushPrefix(block.sequence, 96U) &&
        output->observePushPresentation(511U), "exact original suffix completes once after its own acceptance");
    const auto position = output->presentedPosition();
    const auto journal = output->presentationJournal(0U);
    require(position.generation == owner.generation && position.selectionToken == owner.selectionToken &&
        position.lastTimelineFrame == 511 && !position.terminalAfterFrame && journal.coherent &&
        journal.invalidPublicationCount == 0U && journal.overflowCount == 0U && journal.entries.size() == 2U &&
        journal.entries[0U].pcmFrames == 416U && journal.entries[0U].firstTimelineFrame == 0 &&
        journal.entries[0U].lastTimelineFrame == 415 && journal.entries[1U].pcmFrames == 96U &&
        journal.entries[1U].firstTimelineFrame == 416 && journal.entries[1U].lastTimelineFrame == 511,
        "actual presentation journal retains the exact complete source prefix and suffix");
    require(output->diagnostics()[OUTPUT_DIAGNOSTICS_UNDERRUN_FRAMES] == 0 &&
        output->diagnostics()[OUTPUT_DIAGNOSTICS_PROCESS_UNDERRUN_FRAMES] == before[OUTPUT_DIAGNOSTICS_PROCESS_UNDERRUN_FRAMES],
        "waiting for producer input adds no synthetic source shortage");
}

void pushPreparationWaitsBeforeAFutureLoopBoundary() {
    using namespace stemwise::audio::packet;
    constexpr SongRenderOwnership owner{8198U, 1U};
    for (const bool preparedFuture : {true, false}) {
        auto output = std::make_unique<ClosureOutput>(0U);
        output->setDiagnosticPayloadFingerprints(true);
        const auto port = output->port();
        require(output->configurePushConsumer(512U) &&
            port.publishLateMixControl(port.handle, coreControl(owner, 1U)), "far-B push source setup");
        NativeAudioOutputLoopPolicy demand{.revision = 88U, .generation = owner.generation,
            .selectionToken = owner.selectionToken, .enabled = true, .physicalReady = false,
            .aTimelineFrame = 96'000U, .bTimelineFrameExclusive = 480'000U, .boundaryArmedAtPublication = true};
        require(port.publishLoopDemand(port.handle, demand, false, 0U), "current far-B logical demand");
        auto installed = demand;
        installed.physicalReady = true;
        require(port.publishLoopPolicy(port.handle, installed), "far-B installed physical policy");
        const auto submission = [&](const std::vector<float> &pcm, std::uint64_t first, std::uint64_t revision) {
            auto result = coreSubmission(owner, 1U, pcm, first);
            result.loopPolicyRevision = result.loopRevision = revision;
            result.loopEnabled = true;
            result.loopCycle = 1U;
            return result;
        };
        const auto prefix = corePayload(352U, .08F), suffix = corePayload(160U, .12F);
        require(port.enqueue(port.handle, submission(prefix, 100'000U, 88U)) == 352U,
            "current loop has an incomplete source block far before B");
        if (preparedFuture) {
            require(port.beginLoopCutover(port.handle, 1U, 89U), "future A role opens");
            const auto head = corePayload(512U, .16F);
            require(port.enqueueLoopCutover(port.handle, submission(head, 96'000U, 89U)) == 512U,
                "future A reserve is ready but B is not reachable");
            auto prepared = installed;
            prepared.cutoverTimelineFrameExclusive = 480'000U;
            prepared.continuationTimelineFrame = 96'000U;
            prepared.cutoverSourceRevision = 88U;
            prepared.continuationRevision = 89U;
            require(port.commitLoopCutover(port.handle, prepared) == NativeAudioLoopCutoverCommitStatus::Committed,
                "future same-owner loop role commits without freezing current producer writes");
        }
        require(port.setPlaying(port.handle, true) && output->beginPushConsumer(), "far-B push consumer starts");
        const auto before = output->diagnostics();
        for (unsigned attempt = 0U; attempt < 3U; ++attempt) {
            const auto block = output->preparePushBlock(512U);
            require(block.samples == nullptr && block.frames == 0U && block.sequence == 0U &&
                output->queuedForGeneration(owner.generation) == 352U && output->pushSubmittedFrames() == 0U &&
                !output->presentedPosition().valid() && output->presentationJournal(0U).writeSequence == 0U,
                "far-B loop shortage waits without consuming PCM, issuing a ticket or inventing presentation");
            const auto after = output->diagnostics();
            for (const auto field : {OUTPUT_DIAGNOSTICS_CALLBACK_COUNT, OUTPUT_DIAGNOSTICS_CALLBACK_FRAMES,
                    OUTPUT_DIAGNOSTICS_UNDERRUN_FRAMES, OUTPUT_DIAGNOSTICS_PROCESS_UNDERRUN_FRAMES})
                require(after[field] == before[field], "far-B loop deferral leaves render and shortage counters untouched");
        }
        require(port.enqueue(port.handle, submission(suffix, 100'352U, 88U)) == 160U,
            "current producer can complete its block while future loop role remains pending");
        const auto complete = output->preparePushBlock(512U);
        require(complete.frames == 512U && complete.sequence == 1U &&
            output->acceptPushPrefix(complete.sequence, 512U) && output->observePushPresentation(511U),
            "complete current-loop block progresses without taking a future A head early");
        const auto position = output->presentedPosition();
        const auto journal = output->presentationJournal(0U);
        require(position.generation == owner.generation && position.selectionToken == owner.selectionToken &&
            position.lastTimelineFrame == 100'511 && position.loopPolicyRevision == 88U &&
            journal.coherent && journal.entries.size() == 2U && journal.invalidPublicationCount == 0U &&
            journal.entries[0U].pcmFrames == 352U && journal.entries[1U].pcmFrames == 160U &&
            output->pendingPhysicalLoopRevision() == (preparedFuture ? 89U : 0U) &&
            output->diagnostics()[OUTPUT_DIAGNOSTICS_UNDERRUN_FRAMES] == 0,
            "far-B source and future loop keep their exact independent physical ownership");
    }
}

void pushPreparationWaitsForAnAdequateLoopHeadAndPreservesTerminalClear() {
    using namespace stemwise::audio::packet;
    constexpr SongRenderOwnership owner{8199U, 1U};
    for (unsigned scenario : {0U, 1U, 2U}) {
        const bool clearToTerminal = scenario != 0U;
        const bool lateEmptyEnd = scenario == 2U;
        auto output = std::make_unique<ClosureOutput>(0U, kOutputCapacityFrames, 1U,
            lateEmptyEnd ? 512 : kCallbackFrames, lateEmptyEnd ? 512 : kCallbackFrames * 4U);
        const auto port = output->port();
        require(output->configurePushConsumer(512U) &&
            port.publishLateMixControl(port.handle, coreControl(owner, 1U)), "boundary push setup");
        NativeAudioOutputLoopPolicy demand{.revision = 1U, .generation = 1U, .selectionToken = owner.selectionToken,
            .enabled = true, .physicalReady = false, .aTimelineFrame = 0U, .bTimelineFrameExclusive = 128U,
            .boundaryArmedAtPublication = true};
        require(port.publishLoopDemand(port.handle, demand, false, 0U), "boundary current loop demand");
        auto installed = demand;
        installed.physicalReady = true;
        require(port.publishLoopPolicy(port.handle, installed), "boundary installed loop");
        const auto tail = corePayload(128U, .08F);
        auto outgoing = coreSubmission(owner, 1U, tail, 0U);
        outgoing.loopPolicyRevision = outgoing.loopRevision = 1U;
        outgoing.loopEnabled = true;
        outgoing.loopCycle = 1U;
        require(port.enqueue(port.handle, outgoing) == 128U, "exact outgoing B prefix");
        auto nextDemand = demand;
        nextDemand.revision = 2U;
        nextDemand.enabled = !clearToTerminal;
        require(port.publishLoopDemand(port.handle, nextDemand, false, 0U), "next exact boundary demand");
        auto prepared = nextDemand;
        prepared.physicalReady = true;
        prepared.cutoverTimelineFrameExclusive = 128U;
        prepared.continuationTimelineFrame = clearToTerminal ? 128U : 0U;
        prepared.cutoverSourceRevision = 1U;
        const auto stageHead = [&](std::size_t frames, std::uint64_t revision) {
            require(port.beginLoopCutover(port.handle, 1U, revision), "boundary continuation opens");
            const auto head = corePayload(frames, .12F);
            auto continuation = coreSubmission(owner, 1U, head, prepared.continuationTimelineFrame, clearToTerminal && !lateEmptyEnd);
            continuation.loopPolicyRevision = revision;
            continuation.loopRevision = clearToTerminal ? 0U : revision;
            continuation.loopEnabled = !clearToTerminal;
            continuation.loopCycle = clearToTerminal ? 0U : 1U;
            require(port.enqueueLoopCutover(port.handle, continuation) == frames, "boundary continuation source queues");
            prepared.continuationRevision = revision;
            if (lateEmptyEnd) {
                require(port.commitLoopCutover(port.handle, prepared) == NativeAudioLoopCutoverCommitStatus::Retry &&
                    port.publishTerminalBoundary(port.handle, owner.generation, owner.selectionToken, revision,
                        static_cast<double>(prepared.continuationTimelineFrame + frames)),
                    "short Clear waits for its separately delivered exact EOF before becoming claimable");
            }
            require(port.commitLoopCutover(port.handle, prepared) == NativeAudioLoopCutoverCommitStatus::Committed,
                "boundary continuation commits under its actual initial callback ceiling");
        };
        stageHead(clearToTerminal ? 64U : 256U, 2U);
        output->setTerminalSilenceAuthorization(owner.generation, clearToTerminal);
        require(port.setPlaying(port.handle, true) && output->beginPushConsumer(), "boundary push consumer starts");
        if (!clearToTerminal) {
            const auto before = output->diagnostics();
            const auto pending = output->preparePushBlock(512U);
            require(pending.frames == 0U && pending.samples == nullptr && pending.sequence == 0U &&
                output->queuedForGeneration(owner.generation) == 128U && output->pushSubmittedFrames() == 0U &&
                !output->presentedPosition().valid() &&
                output->diagnostics()[OUTPUT_DIAGNOSTICS_CALLBACK_COUNT] == before[OUTPUT_DIAGNOSTICS_CALLBACK_COUNT] &&
                output->diagnostics()[OUTPUT_DIAGNOSTICS_UNDERRUN_FRAMES] == before[OUTPUT_DIAGNOSTICS_UNDERRUN_FRAMES],
                "due B with insufficient A reserve waits without consuming its outgoing prefix or inventing source");
            require(port.cancelLoopCutover(port.handle, 1U, 2U) == NativeAudioDrainCancelStatus::CancelledOpenStage,
                "waiting returns the loop claim so its exact producer can replace the incomplete head");
            stageHead(512U, 3U);
        }
        const auto block = output->preparePushBlock(512U);
        require(block.frames == 512U && block.sequence == 1U && output->acceptPushPrefix(block.sequence, 512U) &&
            !output->presentedPosition().valid() && output->observePushPresentation(511U),
            "ready B/A or short terminal Clear progresses only through actual sink acceptance and observation");
        const auto position = output->presentedPosition();
        require(position.generation == owner.generation && position.selectionToken == owner.selectionToken &&
            position.loopPolicyRevision == (clearToTerminal ? 2U : 3U) &&
            position.lastTimelineFrame == (clearToTerminal ? 191 : 383) &&
            position.terminalAfterFrame == clearToTerminal &&
            output->diagnostics()[OUTPUT_DIAGNOSTICS_RENDERED_FRAMES] == (clearToTerminal ? 192 : 512) &&
            output->diagnostics()[OUTPUT_DIAGNOSTICS_UNDERRUN_FRAMES] == 0,
            "physical boundary uses exact A ownership or genuine terminal padding with no fabricated source progress");
    }
}

void pushDrainedSuccessorWaitsForItsActualSourceSuffix() {
    using namespace stemwise::audio::packet;
    constexpr SongRenderOwnership outgoing{8200U, 1U}, successor{8201U, 2U};
    const auto oldPcm = corePayload(512U, .08F);
    const auto prefix = corePayload(480U, .12F), suffix = corePayload(32U, .16F);
    const auto stageAfterExactOutgoingEnd = [&](ClosureOutput &output, bool push, bool complete) {
        const auto port = output.port();
        if (push) require(output.configurePushConsumer(512U), "drain recovery push storage");
        require(port.publishLateMixControl(port.handle, coreControl(outgoing, 1U)) &&
            port.enqueue(port.handle, coreSubmission(outgoing, 1U, oldPcm, 0U, true)) == 512U &&
            port.setPlaying(port.handle, true), "outgoing source ends on an exact physical block boundary");
        if (push) {
            require(output.beginPushConsumer(), "drain recovery push consumer starts");
            const auto block = output.preparePushBlock(512U);
            require(block.frames == 512U && block.sequence == 1U && output.acceptPushPrefix(block.sequence, 512U) &&
                output.observePushPresentation(255U), "sink retains an accepted outgoing tail after native queue drains");
        } else {
            require(output.callback(512U).physicalFrames == 512U, "hardware reference drains exact outgoing block");
        }
        require(output.queuedForGeneration(outgoing.generation) == 0U &&
            port.beginDrainHandoff(port.handle, 1U, 2U, 91U) &&
            port.publishLateMixControl(port.handle, coreControl(successor, 1U)) &&
            port.enqueue(port.handle, coreSubmission(successor, 1U, prefix, 0U)) == 480U,
            "real drain stages a nonterminal partial successor after outgoing source exhaustion");
        if (complete) require(port.enqueue(port.handle, coreSubmission(successor, 1U, suffix, 480U)) == 32U,
            "hardware numerical reference has the complete successor source");
        require(port.commitDrainHandoff(port.handle, 1U, 2U, 91U),
            "actual drain commit preserves outgoing-zero producer recovery admission");
    };

    auto reference = std::make_unique<ClosureOutput>(0U);
    stageAfterExactOutgoingEnd(*reference, false, true);
    const auto readyHardware = reference->callback(512U);
    require(readyHardware.physicalFrames == 512U && reference->diagnostics()[OUTPUT_DIAGNOSTICS_UNDERRUN_FRAMES] == 0,
        "unchanged actual hardware callback provides complete successor PCM including its generation declick");

    auto output = std::make_unique<ClosureOutput>(0U);
    output->setDiagnosticPayloadFingerprints(true);
    stageAfterExactOutgoingEnd(*output, true, false);
    const auto port = output->port();
    const auto before = output->diagnostics();
    const auto journalBefore = output->presentationJournal(0U).writeSequence;
    for (unsigned attempt = 0U; attempt < 3U; ++attempt) {
        const auto pending = output->preparePushBlock(512U);
        require(pending.frames == 0U && pending.samples == nullptr && pending.sequence == 0U &&
            !output->acceptPushPrefix(2U, 0U) && output->queuedForGeneration(successor.generation) == 480U &&
            output->pushSubmittedFrames() == 512U && output->presentedPosition().generation == outgoing.generation &&
            output->presentedPosition().lastTimelineFrame == 255 &&
            output->presentationJournal(0U).writeSequence == journalBefore,
            "short nonterminal drain successor waits without consuming480 PCM, issuing a ticket or relabeling accepted outgoing audio");
        const auto after = output->diagnostics();
        for (const auto field : {OUTPUT_DIAGNOSTICS_CALLBACK_COUNT, OUTPUT_DIAGNOSTICS_CALLBACK_FRAMES,
                OUTPUT_DIAGNOSTICS_RENDERED_FRAMES, OUTPUT_DIAGNOSTICS_UNDERRUN_CALLBACK_COUNT,
                OUTPUT_DIAGNOSTICS_UNDERRUN_FRAMES, OUTPUT_DIAGNOSTICS_PROCESS_UNDERRUN_CALLBACK_COUNT,
                OUTPUT_DIAGNOSTICS_PROCESS_UNDERRUN_FRAMES})
            require(after[field] == before[field], "drain source wait preserves callback and actual-shortage accounting");
    }
    require(port.enqueue(port.handle, coreSubmission(successor, 1U, suffix, 480U)) == 32U,
        "acknowledged successor producer appends its actual missing suffix");
    const auto complete = output->preparePushBlock(512U);
    require(complete.frames == 512U && complete.sequence == 2U && complete.samples != nullptr &&
        std::equal(complete.samples, complete.samples + 1'024U, readyHardware.pcm.begin()) &&
        output->pushSubmittedFrames() == 512U && output->presentedPosition().generation == outgoing.generation,
        "complete successor keeps the first available ticket and unchanged generation declick after repeated waits");
    require(output->acceptPushPrefix(complete.sequence, 480U) && !output->observePushPresentation(1023U) &&
        output->observePushPresentation(991U) && output->presentedPosition().generation == successor.generation &&
        output->presentedPosition().lastTimelineFrame == 479,
        "hardware observation crosses retained outgoing tail and only the accepted successor prefix");
    const auto pendingSuffix = output->preparePushBlock(512U);
    require(pendingSuffix.sequence == complete.sequence && pendingSuffix.offset == 480U && pendingSuffix.frames == 32U &&
        pendingSuffix.samples == complete.samples + 960U && output->acceptPushPrefix(complete.sequence, 32U) &&
        output->observePushPresentation(1023U) && output->presentedPosition().lastTimelineFrame == 511 &&
        !output->presentedPosition().terminalAfterFrame, "actual successor suffix completes once through its own sink receipt");
    const auto journal = output->presentationJournal(0U);
    require(journal.coherent && journal.invalidPublicationCount == 0U && journal.overflowCount == 0U &&
        journal.entries.size() == 4U && journal.entries[0U].generation == outgoing.generation &&
        journal.entries[0U].pcmFrames == 256U && journal.entries[1U].generation == outgoing.generation &&
        journal.entries[1U].pcmFrames == 256U && journal.entries[2U].generation == successor.generation &&
        journal.entries[2U].pcmFrames == 480U && journal.entries[2U].firstTimelineFrame == 0 &&
        journal.entries[2U].lastTimelineFrame == 479 && journal.entries[3U].generation == successor.generation &&
        journal.entries[3U].pcmFrames == 32U && journal.entries[3U].firstTimelineFrame == 480 &&
        journal.entries[3U].lastTimelineFrame == 511 && output->diagnostics()[OUTPUT_DIAGNOSTICS_UNDERRUN_FRAMES] == 0,
        "presentation journal conserves exact outgoing512 and successor480+32 source frames without a synthetic gap");
}

void shortAutomaticSuccessorEndsWithoutInventingPresentation(bool deferredEnd = false, bool emptyEnd = false,
    bool cancelBeforeEnd = false) {
    using namespace stemwise::audio::packet;
    constexpr SongRenderOwnership outgoing{8210U, 2U}, successor{8211U, 3U};
    const std::size_t initialSuccessorFrames = deferredEnd ? 480U : 192U;
    const std::size_t successorFrames = deferredEnd ? (emptyEnd ? 480U : 512U) : 192U;
    SongLaneIngressBank first(4'096U, 512U), second(4'096U, 512U);
    const auto offerSource = [](SongLaneIngressBank &bank, SongRenderOwnership owner, std::size_t frames, bool ended) {
        require(bank.configureLane(0U, kSampleRateHz, owner.generation), "short successor input configuration");
        const std::vector<float> pcm(frames * 2U, .08F);
        const LaneInputBlock block{pcm.data(), frames,
            {kSampleRateHz, 2U, LanePcmEncoding::Float32}, owner.generation, 0U};
        require(bank.lane(0U)->offer(block).inputFramesAccepted == frames, "actual short source enters the real lane");
        if (ended) require(bank.lane(0U)->markEndOfStream(owner.generation, frames).status == LaneEndOfStreamStatus::Accepted,
            "exact EOS enters the real lane");
    };
    offerSource(first, outgoing, 512U, true);
    offerSource(second, successor, initialSuccessorFrames, !deferredEnd);
    auto output = std::make_unique<ClosureOutput>(0U, kOutputCapacityFrames, 1U, 256, 2'048);
    require(output->configurePushConsumer(512U), "runtime push consumer storage");
    auto configuration = runtimeConfiguration();
    configuration.pipeline.maximumBlockFrames = 480U;
    configuration.ingressPullFrames = 512U;
    NativePlaybackRuntime runtime(configuration, first, second, output->port());
    const std::array<NativePlaybackBankIntent, 2U> banks{
        NativePlaybackBankIntent{1U, true, outgoing, 0U, 0, 111U, 222U, 0U, 0U, 512U},
        NativePlaybackBankIntent{1U, true, successor, 0U, 0, 333U, 444U, 0U, 0U, successorFrames}};
    auto command = selectedCommand(1U, outgoing, banks, MixerSourceMode::Original);
    require(runtime.publishCommand(command) == NativePlaybackPublishStatus::Accepted && runtime.start(),
        "short successor actual runtime starts");
    try {
        waitUntil([&] { return output->playing() && output->queuedForGeneration(outgoing.generation) == 512U; },
            "exact outgoing source ready");
        require(output->beginPushConsumer(), "actual feeder starts after initial owner preparation");
        const auto old = output->preparePushBlock(512U);
        require(old.frames == 512U && output->acceptPushPrefix(old.sequence, 512U) &&
            output->observePushPresentation(255U), "actual sink retains half of accepted outgoing source");
        waitUntil([&] { return sameOwner(runtime.diagnostics().committedOwnership, outgoing); },
            "outgoing presentation commits through the real runtime");
        command.revision = 2U;
        command.automaticSuccessor = {true, false, 99U, outgoing, NativePlaybackBank::Second, successor};
        require(runtime.publishCommand(command) == NativePlaybackPublishStatus::Accepted,
            "unchanged playing intent stages the exact automatic successor");
        waitUntil([&] {
            const auto state = runtime.diagnostics().automaticSuccessor;
            return state.phase == stemwise::audio::engine::NativePlaybackAutomaticSuccessorPhase::DrainCommitted &&
                state.successorEndObserved == !deferredEnd && output->queuedForGeneration(successor.generation) == initialSuccessorFrames;
        }, "real runtime commits the exact short successor");
        const auto before = output->diagnostics();
        if (cancelBeforeEnd) {
            command.revision = 3U;
            command.automaticSuccessor = {};
            require(runtime.publishCommand(command) == NativePlaybackPublishStatus::Accepted, "cancel exact successor before empty EOF");
            waitUntil([&] {
                const auto state = runtime.diagnostics().automaticSuccessor;
                return state.cancellationDesired || state.phase ==
                    stemwise::audio::engine::NativePlaybackAutomaticSuccessorPhase::Idle;
            }, "runtime owns cancellation before the consumer runs again");
            waitUntil([&] {
                static_cast<void>(output->preparePushBlock(512U));
                return runtime.diagnostics().automaticSuccessor.phase ==
                    stemwise::audio::engine::NativePlaybackAutomaticSuccessorPhase::Idle;
            }, "consumer acknowledges exact automatic cancellation");
            require(second.lane(0U)->markEndOfStream(successor.generation, initialSuccessorFrames).status ==
                LaneEndOfStreamStatus::Accepted, "cancelled successor receives its late genuine EOF");
            require(!output->port().publishTerminalBoundary(output.get(), successor.generation, successor.selectionToken, 0U,
                static_cast<double>(initialSuccessorFrames)) &&
                output->terminalAuthorizationGeneration() != successor.generation &&
                sameOwner(runtime.diagnostics().committedOwnership, outgoing),
                "late EOF cannot restore a cancelled role, terminal permission or public owner");
            runtime.requestStop(); runtime.join();
            return;
        }
        if (deferredEnd) {
            const auto waiting = output->preparePushBlock(512U);
            require(waiting.frames == 0U && output->terminalAuthorizationGeneration() != successor.generation &&
                output->queuedForGeneration(successor.generation) == initialSuccessorFrames && output->pushSubmittedFrames() == 512U &&
                output->diagnostics()[OUTPUT_DIAGNOSTICS_CALLBACK_COUNT] == before[OUTPUT_DIAGNOSTICS_CALLBACK_COUNT] &&
                sameOwner(runtime.diagnostics().committedOwnership, outgoing),
                "a merely short nonterminal successor cannot obtain padding, consume PCM or replace the audible owner");
            const std::vector<float> suffix(32U * 2U, .08F);
            const LaneInputBlock block{suffix.data(), 32U, {kSampleRateHz, 2U, LanePcmEncoding::Float32},
                successor.generation, 10'000U};
            require((emptyEnd || second.lane(0U)->offer(block).inputFramesAccepted == 32U) &&
                second.lane(0U)->markEndOfStream(successor.generation, successorFrames).status == LaneEndOfStreamStatus::Accepted,
                "later real source suffix and its exact EOS resolve the wait");
        }
        waitUntil([&] { return output->terminalAuthorizationGeneration() == successor.generation; },
            "exact committed short successor receives EOS permission before first presentation");
        require(sameOwner(runtime.diagnostics().committedOwnership, outgoing) &&
            output->pushSubmittedFrames() == 512U && output->presentedPosition().generation == outgoing.generation &&
            output->presentedPosition().lastTimelineFrame == 255,
            "terminal permission alone cannot announce the successor or advance its source");
        const auto tail = output->preparePushBlock(512U);
        require(tail.frames == 512U && tail.terminal && output->pushSubmittedFrames() == 512U &&
            output->presentedPosition().generation == outgoing.generation,
            "genuine short EOS tail prepares without granting sink or presentation authority");
        require(output->acceptPushPrefix(tail.sequence, successorFrames) &&
            !output->observePushPresentation(512U + successorFrames) && output->observePushPresentation(511U + successorFrames),
            "only positively accepted terminal PCM can cross into successor presentation");
        waitUntil([&] { return sameOwner(runtime.diagnostics().committedOwnership, successor); },
            "first actual successor presentation commits its public runtime owner");
        const auto position = output->presentedPosition();
        require(position.generation == successor.generation && position.selectionToken == successor.selectionToken &&
            position.lastTimelineFrame == static_cast<std::int64_t>(successorFrames - 1U) && position.terminalAfterFrame &&
            output->diagnostics()[OUTPUT_DIAGNOSTICS_UNDERRUN_FRAMES] == before[OUTPUT_DIAGNOSTICS_UNDERRUN_FRAMES],
            "exact short successor ends without missing PCM or invented source progress");
    } catch (...) {
        std::cerr << runtimeBoundaryFacts(*output, runtime, outgoing) << '\n';
        runtime.requestStop(); runtime.join(); throw;
    }
    runtime.requestStop(); runtime.join();
}

void lateEmptyEosAfterAcceptedSourceRetainsItsExactProof(bool observed, bool partialReceipt = false) {
    using namespace stemwise::audio::packet;
    constexpr SongRenderOwnership outgoing{8212U, 2U}, owner{8213U, 3U};
    SongLaneIngressBank first(4'096U, 512U), second(4'096U, 512U);
    require(first.configureLane(0U, kSampleRateHz, outgoing.generation) &&
        second.configureLane(0U, kSampleRateHz, owner.generation), "late EOS lanes");
    const std::vector<float> pcm(512U * 2U, .08F);
    const LaneInputBlock block{pcm.data(), 512U, {kSampleRateHz, 2U, LanePcmEncoding::Float32}, owner.generation, 0U};
    auto oldInput = block;
    oldInput.generation = outgoing.generation;
    require(first.lane(0U)->offer(oldInput).inputFramesAccepted == 512U &&
        first.lane(0U)->markEndOfStream(outgoing.generation, 512U).status == LaneEndOfStreamStatus::Accepted &&
        second.lane(0U)->offer(block).inputFramesAccepted == 512U, "late EOS actual sources");
    auto output = std::make_unique<ClosureOutput>(0U, kOutputCapacityFrames, 1U, 256, 2'048);
    require(output->configurePushConsumer(512U), "late EOS push storage");
    output->setDiagnosticPayloadFingerprints(true);
    auto configuration = runtimeConfiguration();
    configuration.pipeline.maximumBlockFrames = 256U;
    configuration.ingressPullFrames = 512U;
    NativePlaybackRuntime runtime(configuration, first, second, output->port());
    const std::array<NativePlaybackBankIntent, 2U> banks{
        NativePlaybackBankIntent{1U, true, outgoing, 0U, 0, 111U, 222U, 0U, 0U, 512U},
        NativePlaybackBankIntent{1U, true, owner, 0U, 0, 333U, 444U, 0U, 0U, 512U}};
    auto command = selectedCommand(1U, outgoing, banks, MixerSourceMode::Original);
    runtime.setDiagnosticPayloadFingerprints(true);
    require(runtime.publishCommand(command) ==
        NativePlaybackPublishStatus::Accepted && runtime.start(), "late EOS actual runtime");
    try {
        waitUntil([&] { return output->playing() && output->queuedForGeneration(outgoing.generation) == 512U; },
            "late EOS outgoing ready");
        require(output->beginPushConsumer(), "late EOS sole consumer");
        const auto old = output->preparePushBlock(512U);
        require(old.frames == 512U && output->acceptPushPrefix(old.sequence, 512U) &&
            output->observePushPresentation(511U), "actual outgoing commits before successor");
        waitUntil([&] { return sameOwner(runtime.diagnostics().committedOwnership, outgoing); }, "late EOS outgoing public owner");
        command.revision = 2U;
        command.automaticSuccessor = {true, false, 99U, outgoing, NativePlaybackBank::Second, owner};
        require(runtime.publishCommand(command) == NativePlaybackPublishStatus::Accepted, "late EOS successor command");
        waitUntil([&] { return output->queuedForGeneration(owner.generation) == 512U &&
            runtime.diagnostics().automaticSuccessor.phase ==
                stemwise::audio::engine::NativePlaybackAutomaticSuccessorPhase::DrainCommitted; },
            "late EOS complete nonterminal successor queued");
        const auto accepted = output->preparePushBlock(512U);
        const auto acceptedFrames = partialReceipt ? 192U : 512U;
        require(accepted.frames == 512U && !accepted.terminal && output->acceptPushPrefix(accepted.sequence, acceptedFrames),
            "actual source accepted before separate EOF");
        const std::vector<float> retainedSuffix(accepted.samples + acceptedFrames * 2U, accepted.samples + 1'024U);
        if (observed) {
            require(output->observePushPresentation(511U + acceptedFrames), "actual source observed before separate EOF");
            waitUntil([&] { return sameOwner(runtime.diagnostics().committedOwnership, owner); }, "late EOS successor public owner");
        }
        const auto before = output->presentedPosition();
        const auto journalBefore = output->presentationJournal(0U);
        const auto statsBefore = output->diagnostics();
        const auto beforeEndEvents = runtime.diagnostics().outputEndOfStreamEvents;
        require(second.lane(0U)->markEndOfStream(owner.generation, 512U).status == LaneEndOfStreamStatus::Accepted,
            "separate empty EOF has exact existing source end");
        waitUntil([&] { return runtime.diagnostics().outputEndOfStreamEvents > beforeEndEvents &&
            output->terminalAuthorizationGeneration() == owner.generation; }, "runtime observes separate empty EOF");
        if (partialReceipt) {
            const auto pending = output->preparePushBlock(512U);
            require(pending.sequence == accepted.sequence && pending.offset == 192U && pending.frames == 320U &&
                std::equal(retainedSuffix.begin(), retainedSuffix.end(), pending.samples) &&
                output->pushSubmittedFrames() == 704U && !output->observePushPresentation(1'023U) &&
                (!observed || !output->presentedPosition().terminalAfterFrame),
                "late EOF preserves actual unaccepted suffix samples without terminal presentation authority");
            require(output->acceptPushPrefix(pending.sequence, 320U),
                "only the exact remaining positive receipt completes the terminal source");
        }
        const auto hold = output->preparePushBlock(512U);
        require(hold.frames == 512U && hold.terminal && output->pushSubmittedFrames() == 1'024U,
            "genuine EOF provides terminal metadata before a short prefilled track starts");
        if (!observed) {
            require(output->presentedPosition().generation == outgoing.generation &&
                output->presentedPosition().lastOutputFrame == before.lastOutputFrame, "EOF cannot present prefilled accepted source");
        }
        require(output->observePushPresentation(1'023U), "real head alone presents accepted source");
        const auto terminal = output->presentedPosition();
        require(terminal.generation == owner.generation && terminal.selectionToken == owner.selectionToken &&
            terminal.lastTimelineFrame == 511 && terminal.lastNextTimelineFrame == 512 &&
            terminal.lastOutputFrame == 1'023U && terminal.terminalAfterFrame,
            "late EOF amends only the exact existing source/output boundary");
        if (observed && !partialReceipt) require(terminal.callbackSequence == before.callbackSequence && terminal.pcmFrames == before.pcmFrames &&
            terminal.fingerprintSequence == before.fingerprintSequence && output->presentationJournal(0U).writeSequence == journalBefore.writeSequence,
            "terminal-only amendment never fabricates a journal span or newer sample");
        const auto repeated = output->preparePushBlock(512U);
        const auto journalAfterEnd = output->presentationJournal(0U);
        require(repeated.sequence == hold.sequence && output->observePushPresentation(1'023U) &&
            output->pushSubmittedFrames() == 1'024U && output->presentedPosition().lastOutputFrame == terminal.lastOutputFrame &&
            output->diagnostics()[OUTPUT_DIAGNOSTICS_RENDERED_FRAMES] == 1'024U &&
            output->diagnostics()[OUTPUT_DIAGNOSTICS_UNDERRUN_FRAMES] == statsBefore[OUTPUT_DIAGNOSTICS_UNDERRUN_FRAMES],
            "repeated reconciliation cannot double-count PCM or its presentation");
        require(journalAfterEnd.writeSequence != 0U &&
            output->presentationJournal(0U).writeSequence == journalAfterEnd.writeSequence,
            "repeated terminal observation leaves the actual nonempty presentation journal unchanged");
    } catch (...) {
        std::cerr << runtimeBoundaryFacts(*output, runtime, owner) << '\n';
        runtime.requestStop(); runtime.join(); throw;
    }
    runtime.requestStop(); runtime.join();
}

void hardwareCallbackReceivesLateEosWithoutReplayingItsLastFrame() {
    using namespace stemwise::audio::packet;
    constexpr SongRenderOwnership owner{8214U, 1U};
    auto output = std::make_unique<ClosureOutput>(0U);
    const auto port = output->port();
    output->setDiagnosticPayloadFingerprints(true);
    const auto pcm = corePayload(256U, .08F);
    require(port.publishLateMixControl(port.handle, coreControl(owner, 1U)) &&
        port.enqueue(port.handle, coreSubmission(owner, 1U, pcm, 0U)) == 256U &&
        port.setPlaying(port.handle, true), "hardware late EOF actual source");
    port.setTerminalSilenceAuthorization(port.handle, owner.generation, true);
    require(output->callback(256U).physicalFrames == 256U, "hardware callback actually consumes final nonterminal PCM");
    const auto before = output->presentedPosition();
    const auto stats = output->diagnostics();
    const auto journal = output->presentationJournal(0U);
    require(port.publishTerminalBoundary(port.handle, owner.generation, owner.selectionToken, 0U, 256.0),
        "hardware producer receives separate empty EOF");
    require(output->callback(256U).physicalFrames == 0U, "hardware terminal hold does not reread PCM");
    const auto terminal = output->presentedPosition();
    require(terminal.terminalAfterFrame && terminal.lastTimelineFrame == before.lastTimelineFrame &&
        terminal.lastNextTimelineFrame == 256 && terminal.lastOutputFrame == before.lastOutputFrame &&
        terminal.callbackSequence == before.callbackSequence && terminal.fingerprintSequence == before.fingerprintSequence &&
        output->diagnostics()[OUTPUT_DIAGNOSTICS_RENDERED_FRAMES] == stats[OUTPUT_DIAGNOSTICS_RENDERED_FRAMES] &&
        output->diagnostics()[OUTPUT_DIAGNOSTICS_UNDERRUN_FRAMES] == stats[OUTPUT_DIAGNOSTICS_UNDERRUN_FRAMES] &&
        output->presentationJournal(0U).writeSequence == journal.writeSequence,
        "late hardware EOF adds only terminal fact to the exact prior output/source proof");
}

void persistentTerminalSilenceCannotConcealMissingRequiredSource() {
    using Kind = stemwise::audio::NativeAudioOutputCallbackCore::PushTransportSilenceKind;
    for (bool terminal : {true, false}) {
        auto output = std::make_unique<ClosureOutput>(0U);
        const auto port = output->port();
        constexpr SongRenderOwnership owner{8195U, 1U};
        require(output->configurePushConsumer(512U) && port.publishLateMixControl(port.handle, coreControl(owner, 1U)),
            "one-frame persistent setup");
        const auto pcm = corePayload(1U, .08F);
        require(port.enqueue(port.handle, coreSubmission(owner, 1U, pcm, 0U, terminal)) == 1U &&
            port.setPlaying(port.handle, true) && output->beginPushConsumer(), "one-frame persistent source");
        output->setTerminalSilenceAuthorization(1U, terminal);
        require(output->preparePushTransportSilence(Kind::LogicalPause) == 0U &&
            output->preparePushTransportSilence(Kind::SourceTransition) == 0U,
            "required playing source cannot authorize clean transport silence without a real source fence");
        auto block = output->preparePushBlock(512U);
        if (!terminal) {
            require(block.frames == 0U && block.samples == nullptr && output->queuedForGeneration(1U) == 1U &&
                output->pushSubmittedFrames() == 0U && !output->presentedPosition().valid() &&
                output->diagnostics()[stemwise::audio::packet::OUTPUT_DIAGNOSTICS_UNDERRUN_FRAMES] == 0,
                "push preparation waits without consuming or concealing a required source suffix");
            const auto suffix = corePayload(511U, .08F);
            require(port.enqueue(port.handle, coreSubmission(owner, 1U, suffix, 1U)) == 511U,
                "required source suffix becomes available");
            block = output->preparePushBlock(512U);
        }
        require(block.frames == 512U && output->acceptPushPrefix(block.sequence, 512U) &&
            !output->presentedPosition().valid() && output->observePushPresentation(511U) &&
            output->presentedPosition().lastTimelineFrame == (terminal ? 0 : 511) &&
            output->presentedPosition().terminalAfterFrame == terminal,
            "only authorized end padding can replace a missing source suffix and exact metadata remains hardware-gated");
        const auto missing = output->diagnostics()[stemwise::audio::packet::OUTPUT_DIAGNOSTICS_UNDERRUN_FRAMES];
        require(missing == 0, "push preparation uses authorized end padding or waits for actual required source");
    }
}

void pushPrefillEndAndHistoryRequireActualPresentation() {
    using namespace stemwise::audio::packet;
    auto output = std::make_unique<ClosureOutput>(0U, 512U);
    const auto port = output->port();
    constexpr SongRenderOwnership owner{8124U, 1U};
    require(output->configurePushConsumer(512U), "bounded push history configured");
    require(port.publishLateMixControl(port.handle, coreControl(owner, 1U)), "short-source endpoint");
    const auto pcm = corePayload(100U, .08F);
    require(port.enqueue(port.handle, coreSubmission(owner, 1U, pcm, 0U, true)) == 100U,
        "short real source with actual terminal marker");
    output->setTerminalSilenceAuthorization(owner.generation, true);
    require(port.setPlaying(port.handle, true) && output->beginPushConsumer(), "short-source consumer");
    const auto block = output->preparePushBlock(512U);
    require(block.frames == 512U && block.terminal && !output->presentedPosition().valid(),
        "source shorter than prefill retains exact real prefix plus explicit terminal padding");
    require(output->acceptPushPrefix(block.sequence, 512U) && !output->presentedPosition().valid(),
        "fully accepted prefill cannot end a source before hardware presentation");
    require(output->preparePushBlock(512U).frames == 0U && output->preparePushBlock(512U).terminal,
        "accepted terminal prefill retains its metadata while hardware has not yet consumed it");
    require(output->observePushPresentation(98U) && !output->presentedPosition().terminalAfterFrame &&
        output->presentedPosition().lastTimelineFrame == 98, "end cannot precede final real hardware frame");
    require(output->observePushPresentation(99U) && output->presentedPosition().terminalAfterFrame,
        "actual terminal frame enables the existing exact end contract");
    require(output->observePushPresentation(511U) && output->presentedPosition().lastTimelineFrame == 99 &&
        output->diagnostics()[OUTPUT_DIAGNOSTICS_RENDERED_FRAMES] == 100,
        "accepted synthetic tail occupies device frames without invented source progress");
    const auto held = output->preparePushBlock(512U);
    require(held.frames == 512U && held.terminal && output->acceptPushPrefix(held.sequence, 512U) &&
        output->observePushPresentation(1023U) && output->presentedPosition().lastTimelineFrame == 99 &&
        output->diagnostics()[OUTPUT_DIAGNOSTICS_RENDERED_FRAMES] == 100,
        "existing authorized terminal hold supplies sink silence without repeated source or premature end claims");
    require(output->quiescePushConsumer(), "short-source feeder parks");

    auto bounded = std::make_unique<ClosureOutput>(0U, 512U);
    const auto boundedPort = bounded->port();
    require(bounded->configurePushConsumer(256U), "bounded metadata admission");
    require(boundedPort.publishLateMixControl(boundedPort.handle, coreControl(owner, 1U)), "bounded endpoint");
    const auto longer = corePayload(512U, .08F);
    require(boundedPort.enqueue(boundedPort.handle, coreSubmission(owner, 1U, longer, 0U)) == 512U,
        "bounded source reserve");
    require(boundedPort.setPlaying(boundedPort.handle, true) && bounded->beginPushConsumer(), "bounded writer");
    for (unsigned index = 0U; index < 2U; ++index) {
        const auto next = bounded->preparePushBlock(256U);
        require(next.frames == 256U && bounded->acceptPushPrefix(next.sequence, next.frames), "bounded accepted block");
    }
    require(bounded->preparePushBlock(256U).frames == 0U,
        "unobserved accepted metadata cannot be overwritten by another rendered block");
    require(bounded->observePushPresentation(255U) && bounded->presentedPosition().lastTimelineFrame == 255,
        "capacity backpressure preserves actual first accepted owner metadata");
    require(bounded->quiescePushConsumer(), "bounded writer parks");
}

void persistentFiniteSourcePrefillsTheFullSinkThroughAuthorizedEofBlocks() {
    using namespace stemwise::audio::packet;
    constexpr SongRenderOwnership owner{8125U, 1U};
    auto output = std::make_unique<ClosureOutput>(0U, 4'096U);
    const auto port = output->port();
    output->setDiagnosticPayloadFingerprints(true);
    require(output->configurePushConsumer(512U) &&
        port.publishLateMixControl(port.handle, coreControl(owner, 1U)), "persistent finite-source setup");
    const auto source = corePayload(256U, .08F);
    require(port.enqueue(port.handle, coreSubmission(owner, 1U, source, 0U, true)) == 256U &&
        port.setPlaying(port.handle, true) && output->beginPushConsumer(), "finite source has exact terminal PCM");
    port.setTerminalSilenceAuthorization(port.handle, owner.generation, true);
    const auto baseline = output->diagnostics();
    for (unsigned blockIndex = 0U; blockIndex != 4U; ++blockIndex) {
        const auto block = output->preparePushBlock(512U);
        require(block.frames == 512U && block.terminal && block.sequence != 0U,
            "the existing real EOF hold supplies each complete prefill block");
        if (blockIndex != 0U) require(std::all_of(block.samples, block.samples + 1'024U,
            [](float sample) { return sample == 0.0F; }), "only the initial block can contain source PCM");
        require(output->acceptPushPrefix(block.sequence, 512U) &&
            output->pushSubmittedFrames() == static_cast<std::uint64_t>(blockIndex + 1U) * 512U &&
            !output->presentedPosition().valid() && output->presentationJournal(0U).writeSequence == 0U &&
            output->diagnostics()[OUTPUT_DIAGNOSTICS_RENDERED_FRAMES] == 0U,
            "full accepted prefill cannot invent hardware presentation, source progress or a journal span");
    }
    require(output->observePushPresentation(254U) && !output->presentedPosition().terminalAfterFrame &&
        output->observePushPresentation(255U) && output->presentedPosition().terminalAfterFrame &&
        output->observePushPresentation(2'047U), "only the actual source-ending head frame can publish EOF");
    const auto position = output->presentedPosition();
    const auto journal = output->presentationJournal(0U);
    require(position.generation == owner.generation && position.selectionToken == owner.selectionToken &&
        position.lastTimelineFrame == 255 && position.lastNextTimelineFrame == 256 && position.lastOutputFrame == 255U &&
        output->diagnostics()[OUTPUT_DIAGNOSTICS_RENDERED_FRAMES] == 256U &&
        output->diagnostics()[OUTPUT_DIAGNOSTICS_UNDERRUN_FRAMES] == baseline[OUTPUT_DIAGNOSTICS_UNDERRUN_FRAMES] &&
        journal.coherent && !journal.entries.empty() && journal.entries.front().firstTimelineFrame == 0 &&
        journal.entries.back().lastTimelineFrame == 255,
        "the 2048-frame prefill conserves only the exact 256-frame source and its owner");
    std::uint64_t sourceFrames = 0U;
    for (const auto &span : journal.entries) sourceFrames += span.pcmFrames;
    require(sourceFrames == 256U && output->quiescePushConsumer(), "terminal padding adds no source frames");
}

void pushPartialWriteSplitsOwnersAndPreservesPauseUntilAuthorizedReset() {
    auto output = std::make_unique<ClosureOutput>(0U);
    const auto port = output->port();
    const SongRenderOwnership first{8125U, 1U}, next{8126U, 2U}, sought{8126U, 3U};
    require(output->configurePushConsumer(512U), "mixed-owner pending block configured");
    const auto tail = corePayload(64U, .08F), head = corePayload(256U, .12F);
    require(port.publishLateMixControl(port.handle, coreControl(first, 1U)), "outgoing exact endpoint");
    require(port.enqueue(port.handle, coreSubmission(first, 1U, tail, 0U, true)) == 64U, "outgoing terminal tail");
    require(port.beginDrainHandoff(port.handle, 1U, 2U, 71U), "prefilled successor stage");
    require(port.publishLateMixControl(port.handle, coreControl(next, 1U)), "successor exact endpoint");
    require(port.enqueue(port.handle, coreSubmission(next, 1U, head, 0U)) == 256U &&
        port.commitDrainHandoff(port.handle, 1U, 2U, 71U), "actual successor drain commits");
    require(port.setPlaying(port.handle, true) && output->beginPushConsumer(), "mixed consumer");
    const auto block = output->preparePushBlock(256U);
    require(block.frames == 256U && !output->presentedPosition().valid(), "mixed ownership stays private before acceptance");
    require(port.cancelDrainHandoff(port.handle, 1U, 2U, 71U) == NativeAudioDrainCancelStatus::SuccessorActive,
        "private consumer adoption acknowledges refill ownership without claiming sink presentation");
    require(output->acceptPushPrefix(block.sequence, 192U) && output->observePushPresentation(63U) &&
        output->presentedPosition().generation == 1U && output->presentedPosition().lastTimelineFrame == 63,
        "hardware prefix before exact owner boundary remains outgoing");
    require(output->observePushPresentation(191U) && output->presentedPosition().generation == 2U &&
        output->presentedPosition().selectionToken == next.selectionToken && output->presentedPosition().lastTimelineFrame == 127,
        "accepted prefix crossing owner boundary contains exactly128 successor frames");
    const auto suffix = output->preparePushBlock(256U);
    require(!output->discardPushPending() &&
        output->flushToGenerationStatus(3U) == NativeAudioFlushStatus::RetryAfterCallbackAcknowledgement,
        "active prefill forbids discard and producer-side reset even before a physical start");
    require(output->quiescePushConsumer() && !output->acceptPushPrefix(block.sequence, 64U),
        "parked feeder rejects late write receipt");
    require(output->beginPushConsumer(), "retained-buffer resume");
    const auto resumed = output->preparePushBlock(256U);
    require(resumed.sequence == suffix.sequence && resumed.samples == suffix.samples && resumed.frames == 64U,
        "pause and resume retain the immutable original pending suffix");
    require(output->quiescePushConsumer() && port.setPlaying(port.handle, false) && output->quiesceIfPaused(),
        "actual consumer and physical pause both acknowledged");
    require(output->discardPushPending(true) && output->flushToGenerationStatus(3U) == NativeAudioFlushStatus::Accepted,
        "authorized seek retires pending suffix and stale timestamp namespace before reset");
    require(port.publishLateMixControl(port.handle, coreControl(sought, 2U)) &&
        port.enqueue(port.handle, coreSubmission(sought, 2U, head, 4096U)) == 256U,
        "new seek producer publishes only its exact replacement");
    require(port.setPlaying(port.handle, true) && output->beginPushConsumer(), "new seek consumer");
    const auto replacement = output->preparePushBlock(256U);
    require(replacement.frames == 256U && replacement.sequence != block.sequence &&
        !output->acceptPushPrefix(block.sequence, 64U) && output->acceptPushPrefix(replacement.sequence, 256U),
        "cancelled prefill completion cannot acknowledge replacement PCM");
    require(!output->observePushPresentation(191U) && output->observePushPresentation(447U) &&
        output->presentedPosition().generation == 3U && output->presentedPosition().lastTimelineFrame == 4351,
        "new stream epoch rejects stale presentation and maps exact sought PCM");
    require(output->quiescePushConsumer(), "replacement feeder parks");
}

void pushPartialAcceptanceKeepsTheExactPhysicalLoopBoundary(bool replaceAtWrappedCursor = false) {
    auto output = std::make_unique<ClosureOutput>(0U);
    const auto port = output->port();
    constexpr SongRenderOwnership owner{8127U, 1U};
    require(output->configurePushConsumer(512U), "loop push storage");
    output->setDiagnosticPayloadFingerprints(true);
    require(port.publishLateMixControl(port.handle, coreControl(owner, 1U)), "loop push endpoint");
    auto demand = NativeAudioOutputLoopPolicy{.revision = 1U, .generation = 1U,
        .selectionToken = owner.selectionToken, .enabled = true, .physicalReady = false,
        .aTimelineFrame = 0U, .bTimelineFrameExclusive = 128U, .boundaryArmedAtPublication = true};
    require(port.publishLoopDemand(port.handle, demand, false, 0U), "loop push exact demand");
    auto installed = demand;
    installed.physicalReady = true;
    installed.continuationRevision = 1U;
    require(port.publishLoopPolicy(port.handle, installed), "initial physical1 policy");
    const auto tail = corePayload(128U, .08F), head = corePayload(256U, .12F);
    auto outgoing = coreSubmission(owner, 1U, tail, 0U);
    outgoing.loopPolicyRevision = outgoing.loopRevision = 1U;
    outgoing.loopEnabled = true;
    outgoing.loopCycle = 1U;
    require(port.enqueue(port.handle, outgoing) == 128U, "exact outgoing loop tail");
    require(port.beginLoopCutover(port.handle, 1U, 29U), "physical29 continuation opens");
    auto continuation = coreSubmission(owner, 1U, head, 0U);
    continuation.loopPolicyRevision = continuation.loopRevision = 29U;
    continuation.loopEnabled = true;
    continuation.loopCycle = 1U;
    require(port.enqueueLoopCutover(port.handle, continuation) == 256U, "physical29 head queues");
    auto prepared = demand;
    prepared.physicalReady = true;
    prepared.cutoverTimelineFrameExclusive = 128U;
    prepared.cutoverSourceRevision = 1U;
    prepared.continuationRevision = 29U;
    require(port.commitLoopCutover(port.handle, prepared) == NativeAudioLoopCutoverCommitStatus::Committed,
        "physical29 cutover commits");
    require(port.setPlaying(port.handle, true) && output->beginPushConsumer(), "loop push consumer");
    const auto block = output->preparePushBlock(256U);
    require(block.frames == 256U && !output->presentedPosition().valid() &&
        port.releaseLoopCutoverWrites(port.handle, 1U, 29U), "internal loop acknowledgment precedes push presentation");
    require(output->acceptPushPrefix(block.sequence, 192U) && output->observePushPresentation(127U) &&
        output->presentedPosition().lastTimelineFrame == 127 && output->presentedPosition().loopPolicyRevision == 1U,
        "accepted outgoing prefix reaches exact B under physical1");
    if (replaceAtWrappedCursor) {
        require(output->prepareSourceTransition(1U, owner.selectionToken, 2U, -1) == -1 &&
            output->acceptPushPrefix(block.sequence, 16U) && output->beginPushSourceTransition(2U) &&
            output->prepareSourceTransition(1U, owner.selectionToken, 2U, -1) == 80,
            "final accepted loop receipt resumes at wrapped A plus80, never presented B plus1");
        require(output->quiescePushConsumer(), "wrapped replacement feeder parks");
        return;
    }
    require(output->observePushPresentation(191U) && output->presentedPosition().lastTimelineFrame == 63 &&
        output->presentedPosition().loopPolicyRevision == 29U, "accepted prefix crosses B to exact prepared A once");
    require(output->acceptPushPrefix(block.sequence, 0U) && output->acceptPushPrefix(block.sequence, 64U) &&
        output->observePushPresentation(255U) && output->presentedPosition().lastTimelineFrame == 127,
        "last original suffix preserves exact continuation without replay");
    const auto journal = output->presentationJournal(0U);
    require(journal.coherent && journal.invalidPublicationCount == 0U && journal.overflowCount == 0U &&
        journal.entries.size() == 3U, "accepted observations coalesce mapping runs rather than publishing one entry per frame");
    require(journal.entries[0U].pcmFrames == 128U && journal.entries[0U].lastTimelineFrame == 127 &&
        journal.entries[1U].pcmFrames == 64U && journal.entries[1U].firstTimelineFrame == 0 &&
        journal.entries[2U].firstRenderFrameOffset == 64U,
        "partial sink receipts retain full immutable mapping provenance across physical loop split");
    require(output->quiescePushConsumer(), "loop feeder parks");
}

void physicallyStoppedPushPrefillStillOwnsItsExecutingConsumer() {
    auto output = std::make_unique<ClosureOutput>(0U);
    const auto port = output->port();
    constexpr SongRenderOwnership owner{8128U, 1U};
    require(output->configurePushConsumer(512U), "executing push consumer configured");
    require(port.publishLateMixControl(port.handle, coreControl(owner, 1U)), "executing endpoint");
    const auto pcm = corePayload(512U, .08F);
    require(port.enqueue(port.handle, coreSubmission(owner, 1U, pcm, 0U)) == 512U, "executing source reserve");
    output->setPushPrefillPlaying();
    require(output->beginPushConsumer(), "prefill owns the sole consumer before platform Play");
    output->holdPushRead();
    const auto beforeDeadline = output->diagnostics();
    require(beforeDeadline[stemwise::audio::packet::OUTPUT_DIAGNOSTICS_LAST_CALLBACK_DEADLINE_MISS_START_MICROS] == 0 &&
        beforeDeadline[stemwise::audio::packet::OUTPUT_DIAGNOSTICS_LAST_CALLBACK_DEADLINE_MISS_END_MICROS] == 0,
        "an owner without a missed callback has no invented interval");
    std::int64_t heldAt{}, releasedAt{};
    stemwise::audio::NativeAudioOutputCallbackCore::PushOutputBlock block;
    std::thread feeder([&] { block = output->preparePushBlock(256U); });
    try {
        waitUntil([&] { return output->pushReadEntered(); }, "real ring read enters prefill execution");
        heldAt = stemwise::audio::monotonicMicros();
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
        require(!output->quiescePushConsumer() && !output->discardPushPending(true) &&
            output->flushToGenerationStatus(2U) == NativeAudioFlushStatus::RetryAfterCallbackAcknowledgement,
            "physical stopped state cannot authorize direct reset while real consumer execution is held");
        const auto queuedBeforeRetirement = output->queuedFrames();
        const auto generationBeforeRetirement = output->producerGeneration();
        require(!output->retireForStop(3U) && output->queuedFrames() == queuedBeforeRetirement &&
            output->producerGeneration() == generationBeforeRetirement,
            "STOP retirement cannot reset a ring or owner while an exact consumer operation executes");
        require(output->beginHandoff(2U), "producer may still stage a distinct inactive role");
        require(!output->commitHandoff(2U), "producer cannot directly adopt while prefill is in flight");
        releasedAt = stemwise::audio::monotonicMicros();
        output->releasePushRead();
        feeder.join();
    } catch (...) {
        output->releasePushRead();
        feeder.join();
        throw;
    }
    require(block.frames == 256U && output->acceptPushPrefix(block.sequence, 192U) &&
        output->observePushPresentation(191U) && output->presentedPosition().generation == 1U &&
        output->presentedPosition().lastTimelineFrame == 191,
        "blocked administrative calls preserve every actually accepted original frame");
    const std::vector<float> heldSuffix(block.samples + 192U * 2U, block.samples + 256U * 2U);
    const auto pending = output->preparePushBlock(256U);
    require(pending.sequence == block.sequence && pending.offset == 192U && pending.frames == 64U &&
        std::equal(heldSuffix.begin(), heldSuffix.end(), pending.samples),
        "failed replacement and retirement leave the partially written exact old suffix intact");
    const auto deadline = output->diagnostics();
    const auto missStart = deadline[stemwise::audio::packet::OUTPUT_DIAGNOSTICS_LAST_CALLBACK_DEADLINE_MISS_START_MICROS];
    const auto missEnd = deadline[stemwise::audio::packet::OUTPUT_DIAGNOSTICS_LAST_CALLBACK_DEADLINE_MISS_END_MICROS];
    require(deadline[stemwise::audio::packet::OUTPUT_DIAGNOSTICS_CALLBACK_DEADLINE_MISS_COUNT] == 1 &&
        missStart > 0 && missStart <= heldAt && releasedAt <= missEnd && missEnd - missStart > 256'000'000 / kSampleRateHz,
        "the actual held native callback publishes its coherent measured miss interval");
    const SongRenderOwnership stagedOwner{owner.selectionToken, 2U};
    const auto stagedPcm = corePayload(64U, .12F);
    require(port.publishLateMixControl(port.handle, coreControl(stagedOwner, 1U)) &&
        port.enqueue(port.handle, coreSubmission(stagedOwner, 1U, stagedPcm, 0U)) == 64U,
        "both the outgoing active and independent staged roles retain actual PCM before STOP");
    const auto queuedBeforeRetirement = output->queuedFrames();
    const auto outgoingBeforeRetirement = output->queuedFramesForGeneration(owner.generation);
    require(queuedBeforeRetirement > 0U && outgoingBeforeRetirement > 0U &&
        !output->retireForStop(3U) && output->queuedFrames() == queuedBeforeRetirement &&
        output->queuedFramesForGeneration(owner.generation) == outgoingBeforeRetirement,
        "a returned native read is not yet the push consumer retirement acknowledgement");
    const auto positionBeforeRetirement = output->presentedPosition();
    const auto journalBeforeRetirement = output->presentationJournal(0U);
    const auto diagnosticsBeforeRetirement = output->diagnostics();
    require(output->quiescePushConsumer(), "prefill feeder retires after execution and write receipt");
    require(output->retireForStop(3U) && output->queuedFrames() == 0U &&
        output->producerGeneration() == 3U && !output->playing() &&
        !output->acceptPushPrefix(block.sequence, 64U),
        "verified consumer retirement resets both actual queued roles to a newer empty generation");
    const auto positionAfterRetirement = output->presentedPosition();
    const auto journalAfterRetirement = output->presentationJournal(0U);
    const auto diagnosticsAfterRetirement = output->diagnostics();
    require(positionAfterRetirement.generation == positionBeforeRetirement.generation &&
        positionAfterRetirement.selectionToken == positionBeforeRetirement.selectionToken &&
        positionAfterRetirement.lastTimelineFrame == positionBeforeRetirement.lastTimelineFrame &&
        journalAfterRetirement.entries.size() == journalBeforeRetirement.entries.size() &&
        journalAfterRetirement.invalidPublicationCount == journalBeforeRetirement.invalidPublicationCount &&
        diagnosticsAfterRetirement[stemwise::audio::packet::OUTPUT_DIAGNOSTICS_TOTAL_FRAMES_ACCEPTED] ==
            diagnosticsBeforeRetirement[stemwise::audio::packet::OUTPUT_DIAGNOSTICS_TOTAL_FRAMES_ACCEPTED],
        "STOP discards queued authority without erasing actual presentation, journal or accepted-frame evidence");
}

void physicalPauseDoesNotBecomeALateCallbackGap() {
    using namespace stemwise::audio::packet;
    auto output = std::make_unique<ClosureOutput>();
    const auto port = output->port();
    constexpr SongRenderOwnership owner{8'101U, 1U};
    const auto payload = corePayload(1'024U, .08F);
    require(port.publishLateMixControl(port.handle, coreControl(owner, 1U)), "timing endpoint published");
    require(port.enqueue(port.handle, coreSubmission(owner, 1U, payload, 0U)) == 1'024U,
        "timing source PCM admitted");
    require(port.setPlaying(port.handle, true), "timing stream starts");
    require(output->callback(64U).physicalFrames == 64U, "timing first callback consumes actual PCM");
    const auto firstRun = output->diagnostics()[OUTPUT_DIAGNOSTICS_PHYSICAL_RUN_SEQUENCE];
    require(firstRun > 0, "actual first callback starts a physical timing run");
    require(port.setPlaying(port.handle, false), "logical pause accepted");
    std::this_thread::sleep_for(std::chrono::milliseconds(25));
    static_cast<void>(output->callback(64U));
    require(output->pushSubmittedFrames() == 128U,
        "logical pause submits real transport silence without preparing source PCM");
    require(output->diagnostics()[OUTPUT_DIAGNOSTICS_PHYSICAL_RUN_SEQUENCE] == firstRun,
        "logical pause with continuing callbacks retains its physical run");
    require(output->quiesceIfPaused(), "physical pause is acknowledged");
    const auto faults = output->diagnostics()[OUTPUT_DIAGNOSTICS_PROCESS_UNDERRUN_CALLBACK_COUNT];
    std::this_thread::sleep_for(std::chrono::milliseconds(25));
    require(port.setPlaying(port.handle, true), "physically paused stream resumes");
    require(output->callback(64U).physicalFrames == 64U, "resume preserves exact next owned PCM");
    require(output->diagnostics()[OUTPUT_DIAGNOSTICS_CALLBACK_START_GAP_MICROS] == 0,
        "intentional physical pause must not become a late callback interval");
    require(output->diagnostics()[OUTPUT_DIAGNOSTICS_PHYSICAL_RUN_SEQUENCE] == firstRun + 1,
        "callback consumption acknowledges exactly one resumed physical run");
    std::this_thread::sleep_for(std::chrono::milliseconds(25));
    require(output->callback(64U).physicalFrames == 64U, "continuous resumed run still consumes PCM");
    require(output->diagnostics()[OUTPUT_DIAGNOSTICS_CALLBACK_START_GAP_MICROS] >= 20'000,
        "a genuine delay within the resumed run remains visible");
    require(output->diagnostics()[OUTPUT_DIAGNOSTICS_PROCESS_UNDERRUN_CALLBACK_COUNT] == faults,
        "timing reset never manufactures or erases fault counters");
}

void callbackDiagnosticsExposeActualFailuresAndSanitation() {
    ClosureOutput output(1U);
    const auto port = output.port();
    const SongRenderOwnership owner{0xD1A6U, 1U};
    auto payload = corePayload(16U, .08F);
    auto missingBase = coreSubmission(owner, 1U, payload, 0U);
    missingBase.baseControl = {};
    const auto queuedBefore = output.queuedForGeneration(owner.generation);
    require(port.enqueue(port.handle, missingBase) == 0U &&
        output.queuedForGeneration(owner.generation) == queuedBefore,
        "missing immutable base is rejected without changing queued PCM");
    require(port.enqueue(port.handle, coreSubmission(owner, 1U, payload, 0U)) == 16U,
        "valid immutable base admits PCM without a latest overlay");
    require(port.setPlaying(port.handle, true), "diagnostic callback starts");
    const auto first = output.callback(16U);
    auto diagnostic = output.diagnostics();
    using namespace stemwise::audio::packet;
    require(diagnostic[OUTPUT_DIAGNOSTICS_CALLBACK_MIX_MISSING_CONTROL_FRAMES] == 0 &&
        diagnostic[OUTPUT_DIAGNOSTICS_CALLBACK_MIX_GENERATION] == 1 &&
        diagnostic[OUTPUT_DIAGNOSTICS_CALLBACK_MIX_SELECTION_TOKEN] == static_cast<std::int64_t>(owner.selectionToken) &&
        diagnostic[OUTPUT_DIAGNOSTICS_CALLBACK_MIX_CONTROL_REVISION] == 1,
        "immutable base supplies the exact tuple while the latest overlay is absent");
    require(first.read.firstTag.generation == owner.generation,
        "valid base retains public PCM ownership");
    require(port.publishLateMixControl(port.handle, coreControl(owner, 1U)), "old endpoint published");
    auto staleBase = coreSubmission(owner, 2U, payload, 16U);
    staleBase.baseControl = coreControl(owner, 1U);
    require(port.enqueue(port.handle, staleBase) == 0U &&
        output.queuedForGeneration(owner.generation) == 0U,
        "genuinely stale base is rejected before publication");
    require(port.enqueue(port.handle, coreSubmission(owner, 2U, payload, 16U)) == 16U,
        "newer payload admitted");
    static_cast<void>(output.callback(16U));
    diagnostic = output.diagnostics();
    require(diagnostic[OUTPUT_DIAGNOSTICS_CALLBACK_MIX_STALE_CONTROL_FRAMES] == 0 &&
        diagnostic[OUTPUT_DIAGNOSTICS_CALLBACK_MIX_MISSING_CONTROL_FRAMES] == 0 &&
        diagnostic[OUTPUT_DIAGNOSTICS_CALLBACK_MIX_CONTROL_REVISION] == 2,
        "an older overlay cannot withdraw a newer block's immutable base");
    require(port.publishLateMixControl(port.handle, coreControl(owner, 2U, NativeAudioSourceMode::Stems)),
        "Stems endpoint published without fabricated readiness");
    require(port.enqueue(port.handle, coreSubmission(owner, 2U, payload, 32U)) == 16U,
        "fallback payload admitted");
    const auto fallback = output.callback(16U);
    diagnostic = output.diagnostics();
    require(diagnostic[OUTPUT_DIAGNOSTICS_CALLBACK_MIX_SAFE_ORIGINAL_FALLBACK_FRAMES] == 16 &&
        fallback.read.firstTag.actualSourceMode == FinalPcmSourceMode::Original,
        "missing stem bus selects and counts actual Original fallback");
    payload[0U] = std::numeric_limits<float>::quiet_NaN();
    for (std::size_t frame = 1U; frame < 16U; ++frame)
        payload[frame * kStemwiseMultibusStride] = payload[frame * kStemwiseMultibusStride + 1U] = 100.0F;
    require(port.enqueue(port.handle, coreSubmission(owner, 2U, payload, 48U)) == 16U,
        "nonfinite and loud samples reach actual callback sanitation boundary");
    static_cast<void>(output.callback(16U));
    diagnostic = output.diagnostics();
    require(diagnostic[OUTPUT_DIAGNOSTICS_CALLBACK_MIX_MIXER_NON_FINITE_SAMPLES_SCRUBBED] == 1 &&
        diagnostic[OUTPUT_DIAGNOSTICS_CALLBACK_MIX_MIXER_LIMITER_ACTIVE_FRAMES] >= 15 &&
        diagnostic[OUTPUT_DIAGNOSTICS_CALLBACK_MIX_SEQUENCE] != 0 &&
        diagnostic[OUTPUT_DIAGNOSTICS_CALLBACK_MIX_CALLBACK_SEQUENCE] == 4 &&
        !output.invalidFinalSampleSeen(), "actual scrub/limiter deltas publish coherently with callback");
}

void rejectedPcmFadesWithoutAcquiringPresentationAuthority() {
    using namespace stemwise::audio::packet;
    auto output = std::make_unique<ClosureOutput>();
    const auto port = output->port();
    constexpr SongRenderOwnership owner{8'102U, 1U};
    auto payload = corePayload(256U, .4F);
    require(port.enqueue(port.handle, coreSubmission(owner, 1U, payload, 0U)) == 256U,
        "continuity test admits exact valid PCM");
    require(port.setPlaying(port.handle, true), "continuity test starts");
    static_cast<void>(output->callback(256U));
    const auto before = output->presentedPosition();
    const RenderBusFrameTag unauthorized{
        .generation = owner.generation,
        .selectionToken = owner.selectionToken,
        .payloadSequence = 2U,
        .canonicalControlRevision = 2U,
        .sourceIntentRevision = 2U,
        .stemIntentRevision = 2U,
    };
    require(output->injectUnauthorizedPcm(payload.data(), 32U, unauthorized) == 32U,
        "fault injection bypasses producer admission at the actual ring boundary");
    const auto rejected = output->callback(32U);
    const auto diagnostic = output->diagnostics();
    require(rejected.physicalFrames == 32U && rejected.transitionTouched &&
        rejected.pcm[0U] > 0.0F,
        "actually consumed rejected PCM receives the synthetic continuity fade");
    require(output->presentedPosition().lastTimelineFrame == before.lastTimelineFrame &&
        rejected.read.firstTag.generation == 0U &&
        diagnostic[OUTPUT_DIAGNOSTICS_UNDERRUN_FRAMES] == 32 &&
        diagnostic[OUTPUT_DIAGNOSTICS_INVALID_OWNERSHIP_TAG_FRAMES] == 32,
        "synthetic audio cannot advance source position or hide missing owned PCM");
}

void callbackCoreCutsOverInsideCallbackAndPreservesPauseSeekOwnership() {
    ClosureOutput output(0U);
    const auto port = output.port();
    const SongRenderOwnership outgoing{101U, 1U}, successor{102U, 2U}, sought{102U, 3U};
    auto tail = corePayload(96U, .08F), head = corePayload(512U, .12F);
    require(port.publishLateMixControl(port.handle, coreControl(outgoing, 1U)), "outgoing control");
    require(port.enqueue(port.handle, coreSubmission(outgoing, 1U, tail, 0U, true)) == 96U,
        "actual terminal outgoing payload");
    require(port.setPlaying(port.handle, true), "core successor stream starts");
    const auto first = output.callback(64U);
    require(first.physicalFrames == 64U && output.presentedPosition().lastTimelineFrame == 63,
        "first callback consumes exact outgoing prefix");
    require(port.beginDrainHandoff(port.handle, 1U, 2U, 7U), "stage exact successor drain");
    require(port.publishLateMixControl(port.handle, coreControl(successor, 1U)), "successor control");
    require(port.enqueue(port.handle, coreSubmission(successor, 1U, head, 0U)) == 512U,
        "prepared successor payload");
    require(port.commitDrainHandoff(port.handle, 1U, 2U, 7U), "commit exact drain");
    const auto mixed = output.callback(64U);
    const auto position = output.presentedPosition();
    require(mixed.physicalFrames == 64U && position.generation == 2U &&
        position.selectionToken == successor.selectionToken && position.lastTimelineFrame == 31,
        "one callback drains 32 outgoing and 32 successor frames without omission");
    const auto mixedWhole = mixed.wholeRead();
    require(mixed.spanCount == 2U && mixed.read.lastTag.generation == 1U &&
        mixedWhole.frameCount == 64U && mixedWhole.firstTag.generation == 1U &&
        mixedWhole.lastTag.generation == 2U && mixedWhole.continuityViolationCount == 0U,
        "whole-callback observation includes actual successor span while first-span observation remains outgoing");
    require(output.controlEvidence().generation != 2U,
        "mixed-owner callback cannot publish whole-callback successor control proof");
    const auto queued = output.queuedForGeneration(2U);
    require(port.setPlaying(port.handle, false), "pause accepted");
    const auto paused = output.callback(37U);
    require(paused.physicalFrames == 0U && output.queuedForGeneration(2U) == queued &&
        output.presentedPosition().lastTimelineFrame == 31, "pause does not consume or rewind successor");
    const auto settledPause = output.callback(128U);
    require(settledPause.physicalFrames == 0U && output.queuedForGeneration(2U) == queued &&
        output.presentedPosition().lastTimelineFrame == 31,
        "pause declick tail cannot consume or publish source PCM");
    for (std::size_t frame = 96U; frame < 128U; ++frame)
        require(settledPause.pcm[frame * 2U] == 0.0F && settledPause.pcm[frame * 2U + 1U] == 0.0F,
            "paused callback settles to silence after its bounded declick tail");
    require(output.quiesceIfPaused() && port.setPlaying(port.handle, true), "resume after physical pause");
    const auto resumed = output.callback(37U);
    require(resumed.physicalFrames == 37U && output.presentedPosition().lastTimelineFrame == 68,
        "variable callback resume continues exact next frame");
    require(port.beginHandoff(port.handle, 3U), "seek new generation staged");
    require(port.publishLateMixControl(port.handle, coreControl(sought, 3U)), "seek control");
    require(port.enqueue(port.handle, coreSubmission(sought, 3U, head, 4'096U)) == 512U,
        "seek exact new timeline payload");
    require(port.commitHandoff(port.handle, 3U), "seek handoff committed");
    const auto seek = output.callback(53U);
    require(seek.physicalFrames == 53U && output.presentedPosition().generation == 3U &&
        output.presentedPosition().selectionToken == sought.selectionToken &&
        output.presentedPosition().lastTimelineFrame == 4'148,
        "seek callback retires old queued suffix and presents exact new timeline");
    require(output.diagnostics()[stemwise::audio::packet::OUTPUT_DIAGNOSTICS_UNDERRUN_CALLBACK_COUNT] == 0,
        "successor/pause/seek actual callback has no PCM starvation");
}




void rejectedLoopDemandReleasesTheActualCallbackFence() {
    ClosureOutput output(0U);
    const auto port = output.port();
    constexpr SongRenderOwnership owner{0xAB13U, 1U};
    const auto pcm = corePayload(512U, .08F);
    require(port.publishLateMixControl(port.handle, coreControl(owner, 1U)), "rejected demand endpoint");
    require(port.enqueue(port.handle, coreSubmission(owner, 1U, pcm, 0U)) == 512U, "rejected demand linear PCM");
    auto demand = NativeAudioOutputLoopPolicy{.revision=1U, .generation=1U, .selectionToken=owner.selectionToken,
        .enabled=true, .physicalReady=false, .aTimelineFrame=900U, .bTimelineFrameExclusive=1200U,
        .boundaryArmedAtPublication=true};
    require(port.publishLoopDemand(port.handle, demand, false, 0U), "unavailable demand published");
    port.retireLoopDemand(port.handle, 1U, 1U, owner.selectionToken);
    require(port.setPlaying(port.handle, true), "rejected demand callback starts");
    require(output.callback(64U).physicalFrames == 64U &&
        !output.presentedPosition().loopPolicyAdoptedEnabled, "actual callback retires unavailable demand");
    demand.revision = 2U;
    require(port.publishLoopDemand(port.handle, demand, false, 0U), "newer demand published after rejection");
    port.retireLoopDemand(port.handle, 1U, 1U, owner.selectionToken);
    require(output.callback(64U).physicalFrames == 64U && output.presentedPosition().loopPolicyAdoptedEnabled &&
        output.presentedPosition().loopPolicyAdoptedRevision == 2U, "stale retirement cannot cancel a newer demand");
}

void installedPhysicalLoopAuthorityCannotRegressToAnOlderInPlacePolicy() {
    constexpr SongRenderOwnership owner{2U, 6U};
    for (const bool wrongPhysicalSuffix : {false, true}) {
        auto output = std::make_unique<ClosureOutput>(0U, kOutputCapacityFrames, owner.generation);
        output->setDiagnosticPayloadFingerprints(true);
        const auto port = output->port();
        auto demand = NativeAudioOutputLoopPolicy{.revision=1U, .generation=owner.generation,
            .selectionToken=owner.selectionToken, .enabled=true, .physicalReady=false,
            .aTimelineFrame=0U, .bTimelineFrameExclusive=256U, .boundaryArmedAtPublication=true};
        require(port.publishLoopDemand(port.handle, demand, false, 0U), "physical authority logical1 demand");
        auto oldPolicy = demand;
        oldPolicy.physicalReady = true;
        oldPolicy.continuationRevision = 1U;
        require(port.publishLoopPolicy(port.handle, oldPolicy), "actual in-place physical1 publication accepted");
        const auto pcm = corePayload(256U, .08F);
        auto block = coreSubmission(owner, 1U, pcm, 0U);
        block.loopPolicyRevision = block.loopRevision = 1U;
        block.loopCycle = 1U;
        block.loopEnabled = true;
        require(port.enqueue(port.handle, block) == 256U, "actual outgoing physical1 PCM");
        require(port.beginLoopCutover(port.handle, owner.generation, 29U), "exact physical29 role begins");
        block.loopPolicyRevision = block.loopRevision = 29U;
        require(port.enqueueLoopCutover(port.handle, block) == 256U, "actual physical29 prepared head");
        auto prepared = demand;
        prepared.physicalReady = true;
        prepared.cutoverTimelineFrameExclusive = 256U;
        prepared.cutoverSourceRevision = 1U;
        prepared.continuationRevision = 29U;
        require(port.commitLoopCutover(port.handle, prepared) == NativeAudioLoopCutoverCommitStatus::Committed,
            "exact physical29 role commits under unchanged logical1");
        require(port.setPlaying(port.handle, true) && measuredCallback(*output, 256U).physicalFrames == 256U,
            "real callback consumes outgoing B and adopts physical29");
        require(port.releaseLoopCutoverWrites(port.handle, owner.generation, 29U),
            "exact callback role acknowledgement releases only physical29 writes");
        block.payloadSequence = 2U;
        block.loopCycle = 2U;
        if (wrongPhysicalSuffix) block.loopPolicyRevision = block.loopRevision = 30U;
        require(port.enqueue(port.handle, block) == 256U, "actual next-cycle PCM is queued after role adoption");
        const auto afterAdoption = output->presentationJournal(0U).writeSequence;
        require(measuredCallback(*output, 384U).physicalFrames == 384U, "real callback crosses the same-ring B-to-A boundary");
        const auto journal = output->presentationJournal(afterAdoption);
        require(journal.coherent && journal.entries.size() == 2U &&
            output->presentedPosition().loopPolicyAdoptedRevision == 1U,
            "consumed split spans preserve exact owner and logical demand");
        for (const auto &span : journal.entries) require(span.continuityViolationCount == (wrongPhysicalSuffix ? 1U : 0U),
            "callback-adopted physical29 cannot regress to stale physical1 or approve a wrong physical suffix");
        if (!wrongPhysicalSuffix) {
            demand.revision = 2U;
            require(port.publishLoopDemand(port.handle, demand, false, 0U), "new logical demand retains active physical ownership");
            auto updated = demand;
            updated.physicalReady = true;
            updated.continuationRevision = 29U;
            require(port.publishLoopPolicy(port.handle, updated), "exact physical29 in-place update remains accepted");
            require(measuredCallback(*output, 64U).physicalFrames == 64U &&
                output->presentedPosition().loopPolicyAdoptedRevision == 2U &&
                output->presentedPosition().loopPolicyRevision == 29U,
                "logical update cannot replace the callback-owned physical identity");
        }
    }
}

void rapidAlternatingExpansionCutsTheActualCallbackBeforeTheQueuedOldA() {
    using namespace stemwise::audio::packet;
    constexpr SongRenderOwnership owner{0xAB16U, 2U};
    constexpr std::uint64_t oldA = 1'024U, oldB = 6'144U, newA = oldA + 288U, newB = oldB + 288U;
    SongLaneIngressBank first(16'384U, 480U), second(16'384U, 480U);
    configureAndOffer(first, owner.generation, 0U);
    ClosureOutput output(0U, kOutputCapacityFrames, 1U, 256U, 2'048U);
    static std::atomic<bool> armed{false}, expired{false};
    static std::atomic<std::uint64_t> entered{0U}, released{0U}, cancelled{0U}, firstPhysical{0U},
        committedLogical{0U}, finalPhysical{0U}, finalCutover{0U}, finalStart{0U};
    armed.store(false); expired.store(false); entered.store(0U); released.store(0U); cancelled.store(0U);
    firstPhysical.store(0U); committedLogical.store(0U); finalPhysical.store(0U);
    finalCutover.store(0U); finalStart.store(0U);
    auto port = output.port();
    port.enqueueLoopCutover = [](NativeAudioOutputHandle handle, const NativeRenderBusSubmission &block) noexcept {
        const auto accepted = stemwise::audio::enqueueNativeAudioOutputLoopCutoverTimeline(handle, block);
        if (accepted != 0U && block.sourceStartFrame >= static_cast<double>(oldB) &&
            armed.load(std::memory_order_acquire)) {
            const auto ticket = entered.fetch_add(1U, std::memory_order_acq_rel) + 1U;
            const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(2);
            while (released.load(std::memory_order_acquire) < ticket &&
                std::chrono::steady_clock::now() < deadline) std::this_thread::yield();
            if (released.load(std::memory_order_acquire) < ticket) expired.store(true);
        }
        return accepted;
    };
    port.cancelLoopCutover = [](NativeAudioOutputHandle handle, std::uint64_t generation,
                               std::uint64_t physical) noexcept {
        const auto result = stemwise::audio::cancelNativeAudioOutputLoopCutover(handle, generation, physical);
        if (armed.load() && result == NativeAudioDrainCancelStatus::CancelledOpenStage) cancelled.fetch_add(1U);
        return result;
    };
    port.commitLoopCutover = [](NativeAudioOutputHandle handle, const NativeAudioOutputLoopPolicy &policy) noexcept {
        const auto result = stemwise::audio::commitNativeAudioOutputLoopCutover(handle, policy);
        if (result == NativeAudioLoopCutoverCommitStatus::Committed) {
            if (firstPhysical.load() == 0U) firstPhysical.store(policy.physicalRevision());
            if (policy.revision == 7U) {
                finalCutover.store(policy.cutoverTimelineFrameExclusive);
                finalStart.store(policy.continuationTimelineFrame);
                finalPhysical.store(policy.physicalRevision());
            }
            committedLogical.store(policy.revision, std::memory_order_release);
        }
        return result;
    };
    auto configuration = runtimeConfiguration();
    configuration.pipeline.maximumBlockFrames = 480U;
    configuration.minimumOutputReserveMicros = NativePlaybackRuntimeConfiguration::kDefaultOutputReserveMicros;
    NativePlaybackRuntime runtime(configuration, first, second, port);
    runtime.setDiagnosticPayloadFingerprints(true);
    const std::array<NativePlaybackBankIntent, 2U> banks{
        NativePlaybackBankIntent{1U, true, owner, 0U, 0U, 111U, 222U, 0U, 0U, kInputFrames}, {}};
    require(runtime.publishCommand(selectedCommand(1U, owner, banks, MixerSourceMode::Original)) ==
        NativePlaybackPublishStatus::Accepted && runtime.start(), "burst starts actual Runtime/Core integration");
    try {
        waitUntil([&] { return output.commitCount() == 1U && output.playing(); }, "burst real source commits");
        static_cast<void>(nextCallback(output, owner.generation, 512U));
        waitUntil([&] { const auto ready = runtime.renderReadinessDiagnostics();
            return ready.currentSongCacheBoundToSelectedOwner && ready.currentSongCacheCompleteLaneMask == 1U;
        }, "burst canonical Original PCM completes");
        require(runtime.publishDirectLoopPolicy(1U, owner.selectionToken, owner.generation,
            true, oldA, oldB, 0U, true) == NativePlaybackPublishStatus::Accepted, "burst initial loop demand");
        waitUntil([&] { return firstPhysical.load() != 0U; }, "burst actual first physical role commits");
        const auto adoptionDeadline = std::chrono::steady_clock::now() + std::chrono::seconds(3);
        while (output.presentedPosition().loopPolicyRevision != firstPhysical.load()) {
            require(std::chrono::steady_clock::now() < adoptionDeadline, "burst initial physical adoption is bounded");
            require(nextCallback(output, owner.generation, 512U).physicalFrames == 512U, "burst consumes actual initial loop PCM");
        }
        while (output.presentedPosition().lastTimelineFrame < static_cast<std::int64_t>(oldB - 1'025U)) {
            require(std::chrono::steady_clock::now() < adoptionDeadline, "burst queued old-B placement is bounded");
            const auto frames = std::min<std::uint64_t>(512U,
                oldB - 1'025U - output.presentedPosition().lastTimelineFrame);
            require(nextCallback(output, owner.generation, frames).physicalFrames == frames,
                "burst advances actual physical1 toward its queued old B");
        }
        waitUntil([&] { return output.queuedForGeneration(owner.generation) >= 2'400U &&
            port.loopBoundaryCoverage(port.handle, owner.generation, owner.selectionToken,
                oldB, firstPhysical.load()).boundaryQueued;
        }, "burst actual old-B-to-old-A discontinuity is already queued");
        armed.store(true, std::memory_order_release);
        for (std::uint64_t revision = 2U; revision <= 7U; ++revision) {
            require(runtime.publishDirectLoopPolicy(revision, owner.selectionToken, owner.generation, true,
                oldA + ((revision - 1U) / 2U) * 96U, oldB + (revision / 2U) * 96U, 0U, true) ==
                NativePlaybackPublishStatus::Accepted, "burst alternating actual runtime demand");
            if (revision != 2U) released.store(revision - 2U, std::memory_order_release);
            waitUntil([&] {
                if (entered.load(std::memory_order_acquire) >= revision - 1U) return true;
                // A published dormant spare cancellation is acknowledged by the actual consumer.
                if (output.presentedPosition().lastTimelineFrame < static_cast<std::int64_t>(oldB - 512U))
                    static_cast<void>(measuredCallback(output, 1U));
                return false;
            }, "burst each intermediate stage accepts actual PCM under its owner epoch");
        }
        armed.store(false, std::memory_order_release);
        released.store(UINT64_MAX, std::memory_order_release);
        runtime.notifyIngressAvailable();
        waitUntil([&] { return committedLogical.load(std::memory_order_acquire) == 7U; },
            "burst latest logical7 stage actually commits");
        require(!expired.load() && cancelled.load() >= 2U && finalPhysical.load() > firstPhysical.load() &&
            finalCutover.load() == oldB && finalStart.load() == oldB,
            "actual cancellation/retarget epochs preserve the exact old-B linear continuation");
        bool linearContinuation = false, newWrap = false;
        auto expected = output.presentedPosition().lastTimelineFrame + 1;
        const auto boundaryDeadline = std::chrono::steady_clock::now() + std::chrono::seconds(3);
        while (!newWrap) {
            require(std::chrono::steady_clock::now() < boundaryDeadline, "burst both physical boundaries are bounded");
            const auto journalStart = output.presentationJournal(0U).writeSequence;
            require(nextCallback(output, owner.generation, 256U).physicalFrames == 256U,
                "burst actual callback retains complete source coverage");
            const auto journal = output.presentationJournal(journalStart);
            require(journal.coherent && journal.overflowCount == 0U && journal.invalidPublicationCount == 0U,
                "burst actual presentation journal remains complete");
            std::size_t frames = 0U;
            for (const auto &span : journal.entries) {
                if (expected == static_cast<std::int64_t>(newB)) { expected = newA; newWrap = true; }
                require(span.firstTimelineFrame == expected && span.continuityViolationCount == 0U,
                    "actual stage epochs must never replay old A before the expanded B");
                if (span.firstTimelineFrame == static_cast<std::int64_t>(oldB)) {
                    linearContinuation = true;
                    require(span.firstLoopPolicyRevision == finalPhysical.load() &&
                        span.lastLoopPolicyRevision == finalPhysical.load(), "linear old-B PCM has the committed final role");
                }
                expected = span.lastTimelineFrame + 1;
                frames += span.pcmFrames;
            }
            require(frames == 256U, "burst every accepted frame has actual presentation evidence");
        }
        require(linearContinuation && output.presentedPosition().loopPolicyAdoptedRevision == 7U &&
            !output.invalidFinalSampleSeen() && output.diagnostics()[OUTPUT_DIAGNOSTICS_UNDERRUN_CALLBACK_COUNT] == 0,
            "real runtime/core preserves logical7, linear old B and new-B-to-new-A without starvation");
        runtime.requestStop(); runtime.join();
    } catch (...) {
        armed.store(false); released.store(UINT64_MAX); runtime.notifyIngressAvailable();
        std::cerr << runtimeBoundaryFacts(output, runtime, owner) << " entered=" << entered.load()
            << " cancelled=" << cancelled.load() << " logical=" << committedLogical.load() << '\n';
        runtime.requestStop(); runtime.join(); throw;
    }
}

void activePolicyRetargetCannotRetireAnExactPublishedContinuation() {
    ClosureOutput output(0U);
    const auto port = output.port();
    constexpr SongRenderOwnership owner{0xAB12U, 1U};
    const auto pcm = corePayload(512U, .08F);
    require(port.publishLateMixControl(port.handle, coreControl(owner, 1U)), "retirement Original endpoint");
    require(port.enqueue(port.handle, coreSubmission(owner, 1U, pcm, 0U)) == 512U,
        "retirement initial linear PCM");
    auto demand = NativeAudioOutputLoopPolicy{.revision = 1U, .generation = 1U,
        .selectionToken = owner.selectionToken, .enabled = true, .physicalReady = false,
        .aTimelineFrame = 0U, .bTimelineFrameExclusive = 256U, .boundaryArmedAtPublication = true};
    require(port.publishLoopDemand(port.handle, demand, false, 0U), "retirement logical A/B");
    const auto prepare = [&](std::uint64_t physicalRevision, std::uint64_t sourceRevision) {
        require(port.beginLoopCutover(port.handle, 1U, physicalRevision), "retirement exact stage opens");
        require(port.publishLateMixControl(port.handle, coreControl(owner, 1U)), "retirement stage endpoint");
        auto submission = coreSubmission(owner, 1U, pcm, 0U);
        submission.loopPolicyRevision = physicalRevision;
        submission.loopRevision = physicalRevision;
        submission.loopEnabled = true;
        require(port.enqueueLoopCutover(port.handle, submission) == 512U,
            "retirement exact prepared A PCM");
        auto policy = demand;
        policy.physicalReady = true;
        policy.cutoverTimelineFrameExclusive = 256U;
        policy.cutoverSourceRevision = sourceRevision;
        policy.continuationRevision = physicalRevision;
        require(port.commitLoopCutover(port.handle, policy) == NativeAudioLoopCutoverCommitStatus::Committed,
            "retirement physical stage commits");
    };
    prepare(10U, 0U);
    require(port.setPlaying(port.handle, true), "retirement callback starts");
    require(output.callback(256U).physicalFrames == 256U, "retirement first exact B callback");
    require(port.releaseLoopCutoverWrites(port.handle, 1U, 10U), "retirement first physical role acknowledged");
    require(output.callback(64U).physicalFrames == 64U &&
        output.presentedPosition().loopPolicyRevision == 10U, "retirement first A role actually presented");
    prepare(11U, 10U);
    require(output.callback(64U).physicalFrames == 64U, "retirement spare is observed before B");
    demand.revision = 2U;
    require(port.publishLoopDemand(port.handle, demand, false, 0U), "retirement same-endpoint newer logical intent");
    auto inPlace = demand;
    inPlace.physicalReady = true;
    inPlace.continuationRevision = 10U;
    require(port.publishLoopPolicy(port.handle, inPlace), "retirement active physical role retargeted in place");
    require(output.callback(128U).physicalFrames == 128U, "retirement exact remaining outgoing prefix");
    const auto replacement = output.callback(64U);
    const auto position = output.presentedPosition();
    require(replacement.physicalFrames == 64U && position.generation == 1U &&
        position.selectionToken == owner.selectionToken && position.loopPolicyRevision == 11U &&
        position.lastTimelineFrame == 63 && position.loopPolicyAdoptedRevision == 2U,
        "new logical revision cannot retire independently pending physical continuation");
    require(!output.invalidFinalSampleSeen() && replacement.pcm[63U * 2U] > .001F &&
        output.diagnostics()[stemwise::audio::packet::OUTPUT_DIAGNOSTICS_UNDERRUN_CALLBACK_COUNT] == 0,
        "retargeted physical boundary preserves audible PCM and zero starvation");
}

void exactCaptureCanClaimASameRevisionSpareAfterANaturalWrap() {
    using namespace stemwise::audio::packet;
    constexpr SongRenderOwnership owner{0xAB13U, 1U};
    constexpr std::uint64_t a = 1'024U, capturedB = 1'408U;
    for (unsigned scenario = 0U; scenario != 8U; ++scenario) {
        const bool capture = scenario >= 2U;
        const bool expanded = scenario == 1U || scenario == 3U || scenario == 4U;
        const bool startsAtA = scenario != 3U;
        const bool futureSpare = scenario == 5U;
        const bool wrapAfterCapture = scenario >= 6U;
        const bool inferredCapture = scenario == 7U;
        ClosureOutput output(0U);
        output.setDiagnosticPayloadFingerprints(true);
        const auto port = output.port();
        require(port.publishLateMixControl(port.handle, coreControl(owner, 1U)), "capture Original endpoint");
        auto demand = NativeAudioOutputLoopPolicy{.revision = 392U, .generation = owner.generation,
            .selectionToken = owner.selectionToken, .enabled = true, .physicalReady = false,
            .aTimelineFrame = a, .bTimelineFrameExclusive = capturedB, .heldBGestureId = 2U,
            .boundaryArmedAtPublication = true};
        require(port.publishLoopDemand(port.handle, demand, false, 0U), "capture initial logical loop");
        auto installed = demand;
        installed.physicalReady = true;
        installed.continuationRevision = 10U;
        require(port.publishLoopPolicy(port.handle, installed), "capture installed physical owner");
        const auto submission = [&](const std::vector<float> &pcm, std::uint64_t first,
                                    std::uint64_t physical, std::uint64_t cycle) {
            auto block = coreSubmission(owner, 1U, pcm, first);
            block.loopPolicyRevision = block.loopRevision = physical;
            block.loopCycle = cycle;
            block.loopEnabled = true;
            return block;
        };
        const auto tail = corePayload(64U, .08F), current = corePayload(256U, .08F);
        require(port.enqueue(port.handle, submission(tail, capturedB - 64U, 10U, 1U)) == 64U &&
            port.enqueue(port.handle, submission(current, a, 10U, 2U)) == 256U &&
            port.setPlaying(port.handle, true), "capture exact tail and natural wrapped current PCM");
        const auto wrapped = measuredCallback(output, wrapAfterCapture ? 64U : 128U).wholeRead();
        require(wrapped.frameCount == (wrapAfterCapture ? 64U : 128U) &&
            wrapped.firstTag.timelineFrame == 1'344 &&
            wrapped.lastTag.timelineFrame == (wrapAfterCapture ? 1'407 : 1'087) &&
            wrapped.lastTag.loopPolicyRevision == 10U,
            "capture retains the actual current producer position around its natural wrap");

        demand.revision = 393U;
        if (expanded) demand.bTimelineFrameExclusive = 1'536U;
        if (wrapAfterCapture) demand.bTimelineFrameExclusive = capturedB - 48U;
        if (capture && !wrapAfterCapture) demand.captureReferenceTimelineFrame = 1'600;
        const auto captureEdge = wrapAfterCapture ? demand.bTimelineFrameExclusive : capturedB;
        require(port.publishLoopDemand(port.handle, demand, capture && !inferredCapture,
                capture && !inferredCapture ? captureEdge : 0U),
            "capture exact new demand retains its crossing independently of the wrapped cursor");
        if (wrapAfterCapture) {
            const auto natural = measuredCallback(output, 64U).wholeRead();
            require(natural.frameCount == 64U && natural.firstTag.timelineFrame == 1'024 &&
                natural.lastTag.timelineFrame == 1'087 && natural.lastTag.loopPolicyRevision == 10U,
                "the old producer naturally wraps before captured A is physically ready");
        }
        installed = demand;
        installed.physicalReady = true;
        installed.continuationRevision = 10U;
        require(port.publishLoopPolicy(port.handle, installed), "capture logical update retains installed physical owner");
        require(port.beginLoopCutover(port.handle, owner.generation, 11U), "capture same-revision spare opens");
        const auto head = corePayload(256U, .16F);
        const auto continuation = startsAtA ? a : capturedB;
        require(port.enqueueLoopCutover(port.handle, submission(head, continuation, 11U, 1U)) == 256U,
            "capture spare has actual independently identifiable continuation PCM");
        auto prepared = demand;
        prepared.physicalReady = true;
        prepared.cutoverTimelineFrameExclusive = capturedB;
        prepared.continuationTimelineFrame = continuation;
        prepared.cutoverSourceRevision = 10U;
        prepared.continuationRevision = 11U;
        prepared.nextRevisionOnly = true;
        if (futureSpare) ++prepared.revision;
        require(port.commitLoopCutover(port.handle, prepared) == NativeAudioLoopCutoverCommitStatus::Committed,
            "capture ready spare belongs to the same exact demand revision");
        const auto owed = port.pendingLoopCapture(port.handle, demand);
        require(owed.available && owed.revision == (capture ? 393U : 0U),
            "the actual output distinguishes an empty capture ledger from an accepted owed revision");

        const bool cutoverExpected = capture && startsAtA && !futureSpare;
        const auto firstCallback = measuredCallback(output, 64U);
        const auto first = firstCallback.wholeRead();
        require(first.frameCount == 64U && first.firstTag.generation == owner.generation &&
            first.firstTag.selectionToken == owner.selectionToken &&
            first.lastTag.generation == owner.generation && first.lastTag.selectionToken == owner.selectionToken &&
            first.firstTag.timelineFrame == (cutoverExpected ? 1'024 : 1'088) &&
            first.lastTag.timelineFrame == (cutoverExpected ? 1'087 : 1'151) &&
            first.firstTag.loopPolicyRevision == (cutoverExpected ? 11U : 10U) &&
            first.lastTag.loopPolicyRevision == (cutoverExpected ? 11U : 10U) &&
            first.continuityViolationCount == 0U,
            "only an exact capture may take the same-revision spare, and its next PCM must begin at A");
        requireNear(firstCallback.preTransitionFirstLeft, cutoverExpected ? .16F : .08F, 1.e-6F,
            "capture emits the independently known prepared A samples only when its exact role is authorized");
        const auto observed = output.diagnostics();
        const auto remaining = port.pendingLoopCapture(port.handle, demand);
        require(remaining.available && remaining.revision == (capture && !cutoverExpected ? 393U : 0U),
            "only the actual matching A cutover settles the output's authoritative capture query");
        require(observed[OUTPUT_DIAGNOSTICS_ACCEPTED_CAPTURE_COUNT] == (capture ? 1 : 0) &&
            observed[OUTPUT_DIAGNOSTICS_SUCCESSFUL_CAPTURE_CUTOVER_COUNT] == (cutoverExpected ? 1 : 0),
            "capture admission is distinct from exactly one matching physical A cutover");
        if (cutoverExpected) {
            require(observed[OUTPUT_DIAGNOSTICS_LATEST_CUTOVER_CAPTURE_REVISION] == 393 &&
                observed[OUTPUT_DIAGNOSTICS_LATEST_CUTOVER_GESTURE_ID] == 2 &&
                port.releaseLoopCutoverWrites(port.handle, owner.generation, 11U),
                "capture cutover acknowledges the exact revision, gesture and physical role");
        }
        const auto second = measuredCallback(output, 64U).wholeRead();
        const auto after = output.diagnostics();
        require(second.frameCount == 64U && second.firstTag.timelineFrame == first.lastTag.timelineFrame + 1 &&
            second.lastTag.generation == owner.generation && second.lastTag.selectionToken == owner.selectionToken &&
            second.lastTag.loopPolicyRevision == first.lastTag.loopPolicyRevision &&
            second.continuityViolationCount == 0U &&
            after[OUTPUT_DIAGNOSTICS_ACCEPTED_CAPTURE_COUNT] == observed[OUTPUT_DIAGNOSTICS_ACCEPTED_CAPTURE_COUNT] &&
            after[OUTPUT_DIAGNOSTICS_SUCCESSFUL_CAPTURE_CUTOVER_COUNT] ==
                observed[OUTPUT_DIAGNOSTICS_SUCCESSFUL_CAPTURE_CUTOVER_COUNT] &&
            after[OUTPUT_DIAGNOSTICS_UNDERRUN_FRAMES] == 0,
            "the next callback continues exact PCM without duplicate capture acceptance, cutover or starvation");
    }
}

void pendingCaptureCannotAcknowledgeAnOlderExpansionBody() {
    using namespace stemwise::audio::packet;
    constexpr SongRenderOwnership owner{0xAB14U, 1U};
    constexpr std::uint64_t a = 1'024U, oldB = 1'408U, aheadB = 1'792U, capturedB = 1'344U;
    ClosureOutput output(0U);
    const auto port = output.port();
    require(port.publishLateMixControl(port.handle, coreControl(owner, 1U)), "expansion capture endpoint");
    auto demand = NativeAudioOutputLoopPolicy{.revision = 392U, .generation = owner.generation,
        .selectionToken = owner.selectionToken, .enabled = true, .physicalReady = false,
        .aTimelineFrame = a, .bTimelineFrameExclusive = oldB, .heldBGestureId = 2U,
        .boundaryArmedAtPublication = true};
    require(port.publishLoopDemand(port.handle, demand, false, 0U), "expansion capture original demand");
    auto installed = demand;
    installed.physicalReady = true;
    installed.continuationRevision = 10U;
    require(port.publishLoopPolicy(port.handle, installed), "expansion capture installed physical owner");
    const auto current = corePayload(256U, .08F), body = corePayload(256U, .16F);
    auto submission = coreSubmission(owner, 1U, current, 1'280U);
    submission.loopPolicyRevision = submission.loopRevision = 10U;
    submission.loopEnabled = true;
    submission.loopCycle = 1U;
    require(port.enqueue(port.handle, submission) == 256U && port.setPlaying(port.handle, true) &&
        measuredCallback(output, 64U).wholeRead().lastTag.timelineFrame == 1'343,
        "expansion capture has an exact current cursor before inward B");
    require(port.beginLoopCutover(port.handle, owner.generation, 11U), "ordinary expansion body opens");
    submission = coreSubmission(owner, 1U, body, oldB);
    submission.loopPolicyRevision = submission.loopRevision = 11U;
    submission.loopEnabled = true;
    submission.loopCycle = 1U;
    require(port.enqueueLoopCutover(port.handle, submission) == 256U, "ordinary expansion body has real old-B PCM");
    auto prepared = installed;
    prepared.bTimelineFrameExclusive = aheadB;
    prepared.cutoverTimelineFrameExclusive = oldB;
    prepared.continuationTimelineFrame = oldB;
    prepared.cutoverSourceRevision = 10U;
    prepared.continuationRevision = 11U;
    require(port.commitLoopCutover(port.handle, prepared) == NativeAudioLoopCutoverCommitStatus::Committed,
        "older ordinary expansion body commits before capture publication");
    demand.revision = 393U;
    demand.bTimelineFrameExclusive = capturedB;
    demand.captureReferenceTimelineFrame = 1'400;
    require(port.publishLoopDemand(port.handle, demand, true, capturedB), "inward capture is accepted");
    demand.revision = 394U;
    demand.bTimelineFrameExclusive = aheadB;
    demand.captureReferenceTimelineFrame = -1;
    require(port.publishLoopDemand(port.handle, demand, false, 0U), "new visible B moves ahead while capture remains owed");
    const auto pending = port.pendingLoopCapture(port.handle, demand);
    require(pending.available && pending.revision == 393U,
        "the actual output retains the captured edge across newer ahead demand");
    auto wrong = demand;
    ++wrong.selectionToken;
    require(port.pendingLoopCapture(port.handle, wrong).revision == 0U,
        "an unrelated owner cannot inherit the retained capture");
    wrong = demand;
    --wrong.revision;
    require(!port.pendingLoopCapture(port.handle, wrong).available,
        "a stale demand cannot infer completion from an unavailable current publication");
    const auto emitted = measuredCallback(output, 32U).wholeRead();
    require(emitted.frameCount == 32U && emitted.firstTag.generation == owner.generation &&
        emitted.firstTag.selectionToken == owner.selectionToken && emitted.firstTag.loopPolicyRevision == 10U &&
        emitted.firstTag.timelineFrame == 1'344 &&
        output.diagnostics()[OUTPUT_DIAGNOSTICS_SUCCESSFUL_CAPTURE_CUTOVER_COUNT] == 0,
        "an older non-A expansion body cannot consume or acknowledge the pending A capture");
    require(port.pendingLoopCapture(port.handle, demand).revision == 393U,
        "rejecting a wrong continuation preserves the accepted pending capture for repair");
}

void callbackRoleAcknowledgementPrimesTheNextABeforeHardwarePresentation() {
    using namespace stemwise::audio::packet;
    constexpr SongRenderOwnership owner{0xAB15U, 2U};
    constexpr std::uint64_t a = 1'024U, b = 6'144U;
    SongLaneIngressBank first(16'384U, 480U), second(16'384U, 480U);
    configureAndOffer(first, owner.generation, 0U);
    ClosureOutput output(0U, kOutputCapacityFrames, 1U, 256U, 2'048U);
    require(output.configurePushConsumer(512U), "early role adoption uses the real push512 consumer");
    static std::atomic<std::uint64_t> firstPhysical{0U}, latestCommittedPhysical{0U}, releasedPhysical{0U};
    firstPhysical.store(0U); latestCommittedPhysical.store(0U); releasedPhysical.store(0U);
    auto port = output.port();
    port.commitLoopCutover = [](NativeAudioOutputHandle handle, const NativeAudioOutputLoopPolicy &policy) noexcept {
        const auto result = stemwise::audio::commitNativeAudioOutputLoopCutover(handle, policy);
        if (result == NativeAudioLoopCutoverCommitStatus::Committed) {
            if (firstPhysical.load(std::memory_order_acquire) == 0U)
                firstPhysical.store(policy.physicalRevision(), std::memory_order_release);
            latestCommittedPhysical.store(policy.physicalRevision(), std::memory_order_release);
        }
        return result;
    };
    port.releaseLoopCutoverWrites = [](NativeAudioOutputHandle handle, std::uint64_t generation,
                                      std::uint64_t physical) noexcept {
        const bool result = stemwise::audio::releaseNativeAudioOutputLoopCutoverWrites(handle, generation, physical);
        if (result) releasedPhysical.store(physical, std::memory_order_release);
        return result;
    };
    auto configuration = runtimeConfiguration();
    configuration.pipeline.maximumBlockFrames = 480U;
    configuration.minimumOutputReserveMicros = NativePlaybackRuntimeConfiguration::kDefaultOutputReserveMicros;
    NativePlaybackRuntime runtime(configuration, first, second, port);
    runtime.setDiagnosticPayloadFingerprints(true);
    const std::array<NativePlaybackBankIntent, 2U> banks{
        NativePlaybackBankIntent{1U, true, owner, 0U, 0U, 111U, 222U, 0U, 0U, kInputFrames}, {}};
    require(runtime.publishCommand(selectedCommand(1U, owner, banks, MixerSourceMode::Original)) ==
        NativePlaybackPublishStatus::Accepted && runtime.start(), "early role adoption starts the actual native runtime");
    try {
        waitUntil([&] { return output.commitCount() == 1U && output.playing(); }, "early role source commits");
        require(output.beginPushConsumer(), "early role push consumer starts");
        const auto tick = [&] {
            waitUntil([&] { return output.callbackHasPublishedCoverage(owner.generation, 512U); },
                "early role source or prepared continuation has real512-frame coverage");
            const auto block = output.preparePushBlock(512U);
            require(block.frames == 512U && output.acceptPushPrefix(block.sequence, 512U) &&
                output.observePushPresentation(output.pushSubmittedFrames() - 1U),
                "only accepted old source receives hardware presentation authority");
        };
        tick();
        waitUntil([&] { const auto ready = runtime.renderReadinessDiagnostics();
            return ready.currentSongCacheBoundToSelectedOwner && ready.currentSongCacheCompleteLaneMask == 1U;
        }, "early role canonical Original PCM is ready");
        require(runtime.publishDirectLoopPolicy(1U, owner.selectionToken, owner.generation,
            true, a, b, 0U, true) == NativePlaybackPublishStatus::Accepted, "early role exact loop demand");
        waitUntil([&] { return firstPhysical.load(std::memory_order_acquire) != 0U; }, "early role first A head commits");
        while (output.pushSubmittedFrames() + 512U < b) tick();
        require(output.pushSubmittedFrames() == b - 512U &&
            releasedPhysical.load(std::memory_order_acquire) == 0U,
            "an unclaimed prepared A cannot release producer writes before exact outgoing B");
        tick();
        const auto publicBefore = output.presentedPosition();
        const auto journalBefore = output.presentationJournal(0U).writeSequence;
        require(publicBefore.lastTimelineFrame == static_cast<std::int64_t>(b - 1U) &&
            publicBefore.loopPolicyRevision == 0U,
            "outgoing B remains public until actual hardware A presentation");
        waitUntil([&] { return releasedPhysical.load(std::memory_order_acquire) == firstPhysical.load(std::memory_order_acquire); },
            "exact outgoing B releases adopted producer writes before hardware A presentation");
        const auto acceptedA = output.preparePushBlock(512U);
        require(acceptedA.frames == 512U && output.acceptPushPrefix(acceptedA.sequence, 256U),
            "the actual A role prepares and receives one positive partial platform write");
        const std::array<float, 2U> suffix{acceptedA.samples[512U], acceptedA.samples[513U]};
        waitUntil([&] { return releasedPhysical.load(std::memory_order_acquire) == firstPhysical.load(std::memory_order_acquire); },
            "callback role acknowledgement must release the adopted producer before hardware A presentation");
        waitUntil([&] { return latestCommittedPhysical.load(std::memory_order_acquire) > firstPhysical.load(std::memory_order_acquire) &&
            output.queuedForGeneration(owner.generation) >= 2'400U;
        }, "the adopted producer refills current PCM and commits the next A head before hardware presentation");
        const auto stillPublic = output.presentedPosition();
        require(stillPublic.sequence == publicBefore.sequence && stillPublic.generation == publicBefore.generation &&
            stillPublic.selectionToken == publicBefore.selectionToken && stillPublic.lastTimelineFrame == publicBefore.lastTimelineFrame &&
            stillPublic.loopPolicyRevision == publicBefore.loopPolicyRevision &&
            output.presentationJournal(0U).writeSequence == journalBefore,
            "private producer adoption cannot advance public hardware source position or journal");
        const auto remainder = output.preparePushBlock(512U);
        require(remainder.sequence == acceptedA.sequence && remainder.offset == 256U && remainder.frames == 256U &&
            remainder.samples == acceptedA.samples + 512U && remainder.samples[0U] == suffix[0U] &&
            remainder.samples[1U] == suffix[1U] && !output.acceptPushPrefix(acceptedA.sequence + 1U, 1U) &&
            output.acceptPushPrefix(remainder.sequence, remainder.frames) &&
            !output.acceptPushPrefix(remainder.sequence, 1U) &&
            output.observePushPresentation(output.pushSubmittedFrames() - 1U),
            "early adoption preserves the exact unaccepted A suffix and rejects unrelated or duplicate receipts");
        const auto presentedA = output.presentedPosition();
        const auto journal = output.presentationJournal(journalBefore);
        require(presentedA.generation == owner.generation && presentedA.selectionToken == owner.selectionToken &&
            presentedA.loopPolicyRevision == firstPhysical.load(std::memory_order_acquire) &&
            presentedA.lastTimelineFrame == static_cast<std::int64_t>(a + 511U) && journal.coherent &&
            journal.overflowCount == 0U && journal.invalidPublicationCount == 0U &&
            std::any_of(journal.entries.begin(), journal.entries.end(), [&](const auto &span) {
                return span.generation == owner.generation && span.selectionToken == owner.selectionToken &&
                    span.firstTimelineFrame == static_cast<std::int64_t>(a) && span.continuityViolationCount == 0U;
            }) && output.diagnostics()[OUTPUT_DIAGNOSTICS_UNDERRUN_FRAMES] == 0,
            "only actual hardware observation publishes the exact accepted A source under its physical owner");
        require(output.quiescePushConsumer(), "early role push consumer retires");
        runtime.requestStop(); runtime.join();
    } catch (...) {
        output.quiescePushConsumer(); runtime.requestStop(); runtime.join(); throw;
    }
}

void fragmentedPreparedTraversalRetainsEveryActualPcmBlock() {
    using namespace stemwise::audio::engine;
    ReusableLoopContinuation subBlock(256U, 192U);
    PendingOutputPcm wholeBlock(256U);
    wholeBlock.totalFrames = wholeBlock.remainingFrames = 256U;
    subBlock.begin(false, true, 0U);
    subBlock.append(wholeBlock);
    DirectLoopCutoverPlan subBlockPlan{}; subBlockPlan.stagedFrames = 256U;
    subBlock.finishStage(subBlockPlan, 256U, true);
    require(subBlock.ready(), "a sub-block reserve preserves the original full-block prepared-head budget");
    ReusableLoopContinuation cache(480U, 2'400U);
    PendingOutputPcm pending(480U), replay(480U);
    cache.begin(false, true, 96U);
    for (std::size_t block = 0U; block != 24U; ++block) {
        pending.ownership = {91U, 2U}; pending.totalFrames = pending.remainingFrames = 100U;
        pending.sourceStartFrame = static_cast<double>(block * 200U);
        pending.sourceEndFrameExclusive = pending.sourceStartFrame + 200.;
        pending.actualTempo = 2.F; pending.actualPitchSemitones = 0.F;
        for (std::size_t sample = 0U; sample != 100U * kStemwiseMultibusStride; ++sample)
            pending.samples[sample] = static_cast<float>(block * 100U * kStemwiseMultibusStride + sample) / 100'000.F;
        const auto before = allocation_probe::count; allocation_probe::enabled = true;
        cache.append(pending); allocation_probe::enabled = false;
        require(allocation_probe::count == before, "prepared fragment collection cannot allocate");
    }
    DirectLoopCutoverPlan plan{};
    plan.policy = {.revision=1U, .generation=2U, .selectionToken=91U, .enabled=true,
        .physicalReady=true, .aTimelineFrame=0U, .bTimelineFrameExclusive=4'800U};
    plan.stagedFrames = 2'400U;
    cache.finishStage(plan, 960U, true);
    require(cache.ready(), "all2400 prepared frames must remain reusable despite24actual render fragments");
    require(cache.completeTraversal(2.F, 0.F) && !cache.completeTraversal(1.F, 0.F) &&
        !cache.completeTraversal(2.F, 7.F), "complete traversal reuse requires the actual unchanged numerical tuple");
    cache.begin(true, false, 96U);
    for (std::size_t block = 0U; block != 24U; ++block) {
        require(cache.hasReplayBlock(), "every exact prepared fragment replays");
        const auto before = allocation_probe::count; allocation_probe::enabled = true;
        cache.takeNext(replay); allocation_probe::enabled = false;
        require(allocation_probe::count == before && replay.offsetFrames == 0U && replay.remainingFrames == 100U &&
            replay.sourceStartFrame == static_cast<double>(block * 200U) &&
            replay.sourceEndFrameExclusive == static_cast<double>((block + 1U) * 200U),
            "fragment replay preserves source provenance without allocating");
        for (std::size_t sample = 0U; sample != 100U * kStemwiseMultibusStride; ++sample)
            require(replay.samples[sample] == static_cast<float>(block * 100U * kStemwiseMultibusStride + sample) / 100'000.F,
                "fragment replay preserves every exact cached multibus float");
    }
    require(!cache.hasReplayBlock(), "fragment replay ends at its exact cached extent");
    cache.begin(false, true, 96U);
    pending.offsetFrames = 1U;
    --pending.remainingFrames;
    cache.append(pending);
    cache.finishStage(plan, 960U, true);
    require(!cache.ready(), "a partial pending receipt cannot be silently truncated into reusable source PCM");
}

void headOnlyLoopAdoptionReconcilesItsLaterCanonicalReader() {
    using namespace stemwise::audio::engine;
    constexpr SongRenderOwnership owner{0xCA30U, 2U}, builder{owner.selectionToken, 1U};
    constexpr std::uint64_t a = 0U, b = 4'800U, firstFrame = 512U;
    SongLaneIngressBank first(16'384U, 480U), second(16'384U, 480U);
    std::vector<float> pcm(kInputFrames * 2U);
    for (std::size_t lane = 0U; lane != 7U; ++lane) {
        for (std::size_t frame = 0U; frame != kInputFrames; ++frame) {
            pcm[frame * 2U] = lane == 0U ? .08F : .020F + .002F * static_cast<float>(lane);
            pcm[frame * 2U + 1U] = lane == 0U ? -.06F : -.016F - .001F * static_cast<float>(lane);
        }
        require(first.configureLane(lane, kSampleRateHz, owner.generation), "nonzero decoder owns its real lane epoch");
        for (std::size_t offset = firstFrame; offset < kInputFrames;) {
            const auto count = std::min<std::size_t>(480U, kInputFrames - offset);
            require(first.lane(lane)->offer({pcm.data(), count,
                {kSampleRateHz, 2U, LanePcmEncoding::Float32}, owner.generation,
                offset * 1'000'000U / kSampleRateHz}).inputFramesAccepted == count,
                "nonzero decoder supplies only its actual suffix");
            offset += count;
        }
        require(first.lane(lane)->markEndOfStream(owner.generation, kInputFrames).status == LaneEndOfStreamStatus::Accepted,
            "nonzero decoder publishes actual EOF");
    }
    configureAndOffer(second, builder.generation, 0x3fU);
    ClosureOutput output(0x3fU, kOutputCapacityFrames, 1U, 256U, 512U);
    require(output.configurePushConsumer(512U), "adoption fixture configures the actual push512 consumer");
    static std::atomic<std::uint64_t> firstPhysical{0U}, adoptedPhysical{0U};
    firstPhysical.store(0U); adoptedPhysical.store(0U);
    auto port = output.port();
    port.commitLoopCutover = [](NativeAudioOutputHandle handle, const NativeAudioOutputLoopPolicy &policy) noexcept {
        const auto result = stemwise::audio::commitNativeAudioOutputLoopCutover(handle, policy);
        if (result == NativeAudioLoopCutoverCommitStatus::Committed && firstPhysical.load() == 0U)
            firstPhysical.store(policy.physicalRevision());
        return result;
    };
    port.releaseLoopCutoverWrites = [](NativeAudioOutputHandle handle, std::uint64_t generation,
                                      std::uint64_t physical) noexcept {
        const bool result = stemwise::audio::releaseNativeAudioOutputLoopCutoverWrites(handle, generation, physical);
        if (result) adoptedPhysical.store(physical);
        return result;
    };
    auto configuration = runtimeConfiguration();
    configuration.pipeline.maximumBlockFrames = 480U;
    configuration.minimumOutputReserveMicros = NativePlaybackRuntimeConfiguration::kDefaultOutputReserveMicros;
    NativePlaybackRuntime runtime(configuration, first, second, port);
    runtime.setDiagnosticPayloadFingerprints(true);
    const LoopHeadContentKey head{111U, 222U, 333U, 1U, 1U, a, kInputFrames};
    require(runtime.beginLoopHeadPreparation(head, 127U, LoopHeadBoundaryMode::RetainedWindow, owner) ==
        LoopHeadPreparationBeginStatus::Accepted, "head-only continuation starts from actual retained PCM");
    for (std::size_t lane = 0U; lane != 7U; ++lane) {
        require(runtime.configureLoopHeadPreparationLane(1U, lane, kSampleRateHz, 71U + lane) ==
            LoopHeadPreparationConfigureStatus::Accepted, "retained head decoder configures");
        for (std::size_t frame = 0U; frame != kInputFrames; ++frame) {
            pcm[frame * 2U] = lane == 0U ? .08F : .020F + .002F * static_cast<float>(lane);
            pcm[frame * 2U + 1U] = lane == 0U ? -.06F : -.016F - .001F * static_cast<float>(lane);
        }
        for (std::size_t offset = 0U; offset != kInputFrames;) {
            const auto count = std::min<std::size_t>(480U, kInputFrames - offset);
            const auto offered = runtime.offerLoopHeadPreparationPcm(1U, lane,
                {pcm.data(), count, {kSampleRateHz, 2U, LanePcmEncoding::Float32}, 71U + lane,
                    offset * 1'000'000U / kSampleRateHz});
            require(offered.inputFramesAccepted != 0U, "retained head consumes actual known PCM");
            offset += offered.inputFramesAccepted;
        }
    }
    std::array<NativePlaybackBankIntent, 2U> banks{
        NativePlaybackBankIntent{1U, true, owner, firstFrame, 0U, 111U, 222U, 333U, 6U, kInputFrames}, {}};
    auto command = selectedCommand(1U, owner, banks, MixerSourceMode::Original);
    command.controls.tempo = .5F;
    require(runtime.publishCommand(command) == NativePlaybackPublishStatus::Accepted && runtime.start(),
        "nonzero selected owner starts without a canonical frame-zero producer");
    try {
        waitUntil([&] { return output.commitCount() == 1U && output.playing(); }, "head-only owner reserve commits");
        require(!runtime.renderReadinessDiagnostics().currentSongCacheBoundToSelectedOwner,
            "the initial owner genuinely has no canonical reader");
        require(output.beginPushConsumer(), "push consumer starts without pulling the pending loop yet");
        require(runtime.publishDirectLoopPolicy(1U, owner.selectionToken, owner.generation, true, a, b, 0U, true) ==
            NativePlaybackPublishStatus::Accepted, "head-only tuned continuation is requested");
        waitUntil([&] { return firstPhysical.load() != 0U; }, "head-only continuation commits before canonical arrival");
        banks[1] = NativePlaybackBankIntent{1U, true, builder, 0U, 0U, 111U, 222U, 333U, 6U, kInputFrames};
        command.revision = 2U; command.banks = banks;
        require(runtime.publishCommand(command) == NativePlaybackPublishStatus::Accepted,
            "matching frame-zero producer arrives while head-only role waits for adoption");
        waitUntil([&] { const auto ready = runtime.renderReadinessDiagnostics();
            return ready.currentSongCacheBoundToSelectedOwner && ready.currentSongCacheCompleteLaneMask == 127U;
        }, "canonical PCM installs on the old active pipeline before its prepared role adopts");
        cache_test::readConsumerAdmissionDenials.store(0U);
        cache_test::denyReadConsumerGeneration.store(owner.generation);
        const auto tick = [&] {
            waitUntil([&] { return output.callbackHasPublishedCoverage(owner.generation, 512U); },
                "real source PCM must be available to the accepted512 block");
            const auto previous = output.presentationJournal(0U).writeSequence;
            const auto block = output.preparePushBlock(512U);
            require(block.frames == 512U && output.acceptPushPrefix(block.sequence, 512U) &&
                output.observePushPresentation(output.pushSubmittedFrames() - 1U),
                "positive sink receipt and hardware observation publish the actual block");
            const auto journal = output.presentationJournal(previous);
            require(journal.coherent && journal.overflowCount == 0U, "adoption journal remains complete");
            std::size_t accepted{};
            for (const auto &span : journal.entries) {
                require(span.selectionToken == owner.selectionToken && span.generation == owner.generation &&
                    span.continuityViolationCount == 0U, "cache arrival preserves the exact PCM owner and continuity");
                accepted += span.pcmFrames;
            }
            if (accepted != 512U) throw std::runtime_error("each accepted frame has real source presentation evidence: frames=" +
                std::to_string(accepted) + " spans=" + std::to_string(journal.entries.size()) +
                " physical=" + std::to_string(output.presentedPosition().loopPolicyRevision) +
                " firstPhysical=" + std::to_string(firstPhysical.load()) +
                " underruns=" + std::to_string(output.diagnostics()[stemwise::audio::packet::OUTPUT_DIAGNOSTICS_UNDERRUN_CALLBACK_COUNT]));
        };
        const auto firstAdoptionDeadline = std::chrono::steady_clock::now() + std::chrono::seconds(4);
        while (output.presentedPosition().loopPolicyRevision != firstPhysical.load()) {
            require(std::chrono::steady_clock::now() < firstAdoptionDeadline, "head-only first physical adoption is bounded");
            tick();
        }
        waitUntil([&] { return adoptedPhysical.load() == firstPhysical.load(); }, "worker adopts the head-only callback role");
        waitUntil([&] { return cache_test::readConsumerAdmissionDenials.load() != 0U; },
            "adopted head-only pipeline must attempt binding the later canonical reader");
        waitUntil([&] { return !runtime.renderReadinessDiagnostics().currentSongCacheBoundToSelectedOwner; },
            "held reader admission withholds installed readiness after the role swap");
        cache_test::denyReadConsumerGeneration.store(0U); runtime.notifyIngressAvailable();
        waitUntil([&] { return runtime.renderReadinessDiagnostics().currentSongCacheBoundToSelectedOwner; },
            "adopted canonical binding retries after temporary denial");
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(4);
        while (adoptedPhysical.load() < firstPhysical.load() + 3U) {
            require(std::chrono::steady_clock::now() < deadline, "three following tuned cycles prepare and adopt without redecode");
            tick();
        }
        require(output.diagnostics()[stemwise::audio::packet::OUTPUT_DIAGNOSTICS_UNDERRUN_CALLBACK_COUNT] == 0,
            "canonical rebinding preserves each push source block");
        output.quiescePushConsumer(); runtime.requestStop(); runtime.join();
    } catch (...) {
        cache_test::denyReadConsumerGeneration.store(0U);
        output.quiescePushConsumer(); runtime.requestStop(); runtime.join(); throw;
    }
}

void adoptedShortTunedLoopWaitsForItsNextPreparedContinuation(bool finalActionOnly = false) {
    using namespace stemwise::audio::engine;
    constexpr SongRenderOwnership owner{0xBA29U, 2U};
    constexpr std::uint64_t a = 0U, b = 4'800U;
    SongLaneIngressBank first(16'384U, 480U), second(16'384U, 480U);
    configureAndOffer(first, owner.generation, 0x3fU);
    ClosureOutput output(0x3fU, kOutputCapacityFrames, owner.generation - 1U, 256, 512);
    require(output.configurePushConsumer(512U), "real push512 output storage configures");
    static std::atomic<std::uint64_t> firstPhysical{0U}, blockedAttempts{0U}, adoptedQueue{0U};
    static std::atomic<bool> holdFollowing{true}, replayDeadlineArmed{false}, replayEnqueueHeld{false},
        releaseReplayEnqueue{false}, yieldedBeforeCommit{false}, releaseYield{false};
    static std::atomic<std::uint64_t> replayPhysical{0U}, committedPhysical{0U};
    static std::atomic<std::size_t> replayAccepted{0U}, holdAfterFrames{2'160U};
    static std::atomic<bool> adoptedBeforePresentation{false}, coldWrappedAdoptedProducer{false};
    static NativePlaybackOutputPort::EnqueueFunction observedEnqueue{nullptr};
    firstPhysical.store(0U); blockedAttempts.store(0U); adoptedQueue.store(0U); holdFollowing.store(true);
    replayDeadlineArmed.store(false); replayEnqueueHeld.store(false); releaseReplayEnqueue.store(false);
    yieldedBeforeCommit.store(false); releaseYield.store(false); replayPhysical.store(0U);
    committedPhysical.store(0U); replayAccepted.store(0U); holdAfterFrames.store(finalActionOnly ? 2'400U : 2'160U);
    adoptedBeforePresentation.store(false); coldWrappedAdoptedProducer.store(false);
    auto port = output.port();
    observedEnqueue = port.enqueue;
    port.enqueue = [](NativeAudioOutputHandle handle, const NativeRenderBusSubmission &block) noexcept {
        if (block.loopPolicyRevision == firstPhysical.load() && adoptedQueue.load() != 0U &&
            block.sourceStartFrame == 0.0) coldWrappedAdoptedProducer.store(true);
        return observedEnqueue(handle, block);
    };
    port.beginLoopCutover = [](NativeAudioOutputHandle handle, std::uint64_t generation,
                              std::uint64_t physical) noexcept {
        const auto initial = firstPhysical.load(std::memory_order_acquire);
        if (initial != 0U && physical != initial && holdFollowing.load(std::memory_order_acquire)) {
            blockedAttempts.fetch_add(1U, std::memory_order_relaxed);
            return false;
        }
        const bool accepted = stemwise::audio::beginNativeAudioOutputLoopCutover(handle, generation, physical);
        if (accepted && initial == 0U) firstPhysical.store(physical, std::memory_order_release);
        if (accepted && replayDeadlineArmed.load()) { replayPhysical.store(physical); replayAccepted.store(0U); }
        return accepted;
    };
    port.enqueueLoopCutover = [](NativeAudioOutputHandle handle, const NativeRenderBusSubmission &block) noexcept {
        const auto accepted = stemwise::audio::enqueueNativeAudioOutputLoopCutoverTimeline(handle, block);
        if (replayDeadlineArmed.load() && accepted != 0U && replayPhysical.load() != block.loopPolicyRevision) {
            replayPhysical.store(block.loopPolicyRevision); replayAccepted.store(0U);
        }
        if (replayDeadlineArmed.load() && accepted != 0U &&
            replayAccepted.fetch_add(accepted) + accepted >= holdAfterFrames.load() &&
            block.loopPolicyRevision > firstPhysical.load() + 1U && !replayEnqueueHeld.exchange(true)) {
            const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(2);
            while (!releaseReplayEnqueue.load() && std::chrono::steady_clock::now() < deadline) std::this_thread::yield();
        }
        return accepted;
    };
    port.commitLoopCutover = [](NativeAudioOutputHandle handle, const NativeAudioOutputLoopPolicy &policy) noexcept {
        const auto result = stemwise::audio::commitNativeAudioOutputLoopCutover(handle, policy);
        if (result == NativeAudioLoopCutoverCommitStatus::Committed) {
            committedPhysical.store(policy.physicalRevision());
            if (policy.physicalRevision() == firstPhysical.load())
                static_cast<ClosureOutput *>(static_cast<stemwise::audio::NativeAudioOutputCallbackCore *>(handle))->holdPushReadForPhysical(policy.physicalRevision());
        }
        return result;
    };
    port.queuedFramesForGeneration = [](NativeAudioOutputHandle handle, std::uint64_t generation) noexcept {
        auto &output = *static_cast<ClosureOutput *>(static_cast<stemwise::audio::NativeAudioOutputCallbackCore *>(handle));
        if (holdFollowing.load() && blockedAttempts.load() != 0U && output.pushReadEntered() &&
            !adoptedBeforePresentation.load()) {
            adoptedBeforePresentation.store(output.presentedPosition().loopPolicyRevision != firstPhysical.load());
            output.releasePushRead();
        }
        if (replayDeadlineArmed.load() && releaseReplayEnqueue.load() && replayEnqueueHeld.load() &&
            committedPhysical.load() != replayPhysical.load()) {
            yieldedBeforeCommit.store(true);
            const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(2);
            while (!releaseYield.load() && std::chrono::steady_clock::now() < deadline) std::this_thread::yield();
        }
        return stemwise::audio::nativeAudioOutputQueuedFramesForGeneration(handle, generation);
    };
    port.releaseLoopCutoverWrites = [](NativeAudioOutputHandle handle, std::uint64_t generation,
                                      std::uint64_t physical) noexcept {
        const bool accepted = stemwise::audio::releaseNativeAudioOutputLoopCutoverWrites(handle, generation, physical);
        if (accepted && physical == firstPhysical.load(std::memory_order_acquire))
            adoptedQueue.store(stemwise::audio::nativeAudioOutputQueuedFrames(handle), std::memory_order_release);
        return accepted;
    };
    auto configuration = runtimeConfiguration();
    configuration.pipeline.maximumBlockFrames = 480U;
    configuration.minimumOutputReserveMicros = NativePlaybackRuntimeConfiguration::kDefaultOutputReserveMicros;
    if (finalActionOnly) configuration.maximumOutputBlocksPerCycle = 1U;
    NativePlaybackRuntime runtime(configuration, first, second, port);
    runtime.setDiagnosticPayloadFingerprints(true);
    const LoopHeadContentKey key{111U, 222U, 333U, 1U, 1U, a, kInputFrames};
    require(runtime.beginLoopHeadPreparation(key, 127U, LoopHeadBoundaryMode::RetainedWindow, owner) ==
        LoopHeadPreparationBeginStatus::Accepted, "short tuned loop begins its exact Original head");
    std::array<float, 480U * 2U> pcm{};
    for (std::size_t lane = 0U; lane != 7U; ++lane) {
        require(runtime.configureLoopHeadPreparationLane(1U, lane, kSampleRateHz, 71U + lane) ==
            LoopHeadPreparationConfigureStatus::Accepted, "short tuned all-Stems head decoder configures");
        for (std::size_t frame = 0U; frame != 480U; ++frame) {
            pcm[frame * 2U] = lane == 0U ? .08F : .02F;
            pcm[frame * 2U + 1U] = lane == 0U ? -.06F : -.015F;
        }
        for (std::size_t offset = 0U; offset != kInputFrames;) {
            const auto count = std::min<std::size_t>(480U, kInputFrames - offset);
            const auto offered = runtime.offerLoopHeadPreparationPcm(1U, lane,
                {pcm.data(), count, {kSampleRateHz, 2U, LanePcmEncoding::Float32}, 71U + lane,
                    offset * 1'000'000U / kSampleRateHz});
            require(offered.inputFramesAccepted != 0U, "short tuned all-Stems head accepts real PCM");
            offset += offered.inputFramesAccepted;
        }
    }
    const std::array<NativePlaybackBankIntent, 2U> banks{
        NativePlaybackBankIntent{1U, true, owner, 0U, 0U, 111U, 222U, 333U, 6U,
            kInputFrames}, NativePlaybackBankIntent{}};
    auto command = selectedCommand(1U, owner, banks, MixerSourceMode::Stems);
    command.controls.tempo = 2.F;
    require(runtime.publishCommand(command) == NativePlaybackPublishStatus::Accepted && runtime.start(),
        "short tuned loop starts through the real Runtime/Core port");
    try {
        waitUntil([&] { return output.commitCount() == 1U && output.playing(); }, "short tuned owner commits");
        require(output.beginPushConsumer(), "push512 sole consumer starts after the initial owner commits");
        bool actualTunedStemsSeen = false;
        std::uint64_t firstPhysicalFramesPresented = 0U;
        const auto tick = [&](std::size_t frames, bool awaitCoverage = true) {
            if (awaitCoverage) waitUntil([&] { return output.callbackHasPublishedCoverage(owner.generation, frames); },
                "actual prepared512 output or exact physical successor coverage must publish");
            const auto journalBefore = output.presentationJournal(0U).writeSequence;
            const auto block = output.preparePushBlock(frames);
            if (block.frames != frames || block.offset != 0U) {
                const auto position = output.presentedPosition();
                throw std::runtime_error("one exact push source block prepares: requested=" + std::to_string(frames) +
                    " actual=" + std::to_string(block.frames) + " offset=" + std::to_string(block.offset) +
                    " awaitCoverage=" + std::to_string(awaitCoverage) +
                    " queued=" + std::to_string(output.queuedForGeneration(owner.generation)) +
                    " pendingPhysical=" + std::to_string(output.pendingPhysicalLoopRevision()) +
                    " frame=" + std::to_string(position.lastTimelineFrame) +
                    " next=" + std::to_string(position.lastNextTimelineFrame) +
                    " physical=" + std::to_string(position.loopPolicyRevision) +
                    " replayArmed=" + std::to_string(replayDeadlineArmed.load()) +
                    " enqueueHeld=" + std::to_string(replayEnqueueHeld.load()) +
                    " enqueueReleased=" + std::to_string(releaseReplayEnqueue.load()) +
                    " yielded=" + std::to_string(yieldedBeforeCommit.load()) +
                    " finalActionOnly=" + std::to_string(finalActionOnly));
            }
            for (std::size_t sample = 0U; sample != frames * 2U; ++sample)
                require(std::isfinite(block.samples[sample]), "push Stems PCM remains finite");
            require(output.acceptPushPrefix(block.sequence, frames) &&
                output.observePushPresentation(output.pushSubmittedFrames() - 1U),
                "only accepted512 PCM receives hardware presentation authority");
            require(output.diagnostics()[stemwise::audio::packet::OUTPUT_DIAGNOSTICS_UNDERRUN_CALLBACK_COUNT] == 0,
                "late-ready successor must supply real PCM without an empty callback");
            const auto journal = output.presentationJournal(journalBefore);
            std::size_t validFrames{};
            require(journal.coherent && journal.overflowCount == 0U, "push512 source journal remains complete");
            for (const auto &span : journal.entries) {
                require(span.generation == owner.generation && span.selectionToken == owner.selectionToken &&
                    span.continuityViolationCount == 0U, "actual all-Stems accepted spans retain exact owner and continuity");
                validFrames += span.pcmFrames;
                if (firstPhysical.load() != 0U && span.firstLoopPolicyRevision == firstPhysical.load() &&
                    span.lastLoopPolicyRevision == firstPhysical.load()) firstPhysicalFramesPresented += span.pcmFrames;
            }
            require(validFrames == frames, "every accepted frame has actual source presentation evidence");
            const auto controlsApplied = output.appliedControlEvidence();
            actualTunedStemsSeen = actualTunedStemsSeen || (controlsApplied.generation == owner.generation &&
                controlsApplied.selectionToken == owner.selectionToken &&
                controlsApplied.actualSourceMode == NativeAudioSourceMode::Stems && controlsApplied.actualTempo == 2.F);
            const auto position = output.presentedPosition();
            require(position.generation == owner.generation && position.selectionToken == owner.selectionToken,
                "every accepted source block preserves the actual owner");
        };
        tick(512U);
        waitUntil([&] { const auto ready = runtime.renderReadinessDiagnostics();
            return ready.currentSongCacheBoundToSelectedOwner && ready.currentSongCacheCompleteLaneMask == 127U;
        }, "real all-Stems canonical cache completes before repeated same-owner traversal");
        require(runtime.publishDirectLoopPolicy(1U, owner.selectionToken, owner.generation, true, a, b, 0U, true) ==
            NativePlaybackPublishStatus::Accepted, "short tuned loop demand accepted");
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(4);
        while (output.presentedPosition().loopPolicyRevision == 0U) {
            require(std::chrono::steady_clock::now() < deadline, "first prepared short loop physically adopts");
            tick(512U);
        }
        waitUntil([&] { return blockedAttempts.load(std::memory_order_acquire) != 0U; },
            "next physical continuation reaches its controlled admission boundary");
        const auto before = runtime.renderReadinessDiagnostics().sequence;
        NativePlaybackRenderReadinessDiagnostics readiness{};
        waitUntil([&] { readiness = runtime.renderReadinessDiagnostics(); return readiness.sequence > before; },
            "worker publishes adopted producer cursor while the replacement is held");
        require(adoptedBeforePresentation.load(), "the real callback role must acknowledge before its held first presentation");
        require(!coldWrappedAdoptedProducer.load(),
            "acknowledged tuned producer at B cannot cold-wrap before its first physical presentation");
        // Adoption precedes the held callback read. Its exact presented frames have
        // legitimately left the queue by the time the readiness snapshot publishes.
        if (!sameOwner(readiness.ownership, owner) || adoptedQueue.load() == 0U ||
            readiness.outputQueuedFrames + firstPhysicalFramesPresented != adoptedQueue.load()) {
            const auto position = output.presentedPosition();
            const auto coverage = port.loopBoundaryCoverage(port.handle, owner.generation,
                owner.selectionToken, b, firstPhysical.load());
            throw std::runtime_error(
                "adopted tuned producer must preserve its queued B tail instead of cold-wrapping active PCM before the next prepared role: adopted=" +
                std::to_string(adoptedQueue.load()) + " readiness=" + std::to_string(readiness.outputQueuedFrames) +
                " presented=" + std::to_string(firstPhysicalFramesPresented) +
                " current=" + std::to_string(output.queuedForGeneration(owner.generation)) +
                " renderStart=" + std::to_string(readiness.renderStartTimelineFrame) +
                " pending=" + std::to_string(readiness.pendingFrames) +
                " pendingStart=" + std::to_string(readiness.pendingSourceStartTimelineFrame) +
                " pendingEnd=" + std::to_string(readiness.pendingSourceEndTimelineFrameExclusive) +
                " frame=" + std::to_string(position.lastTimelineFrame) +
                " next=" + std::to_string(position.lastNextTimelineFrame) +
                " physical=" + std::to_string(position.loopPolicyRevision) +
                " expected=" + std::to_string(firstPhysical.load()) +
                " boundaryQueued=" + std::to_string(coverage.boundaryQueued) +
                " finalActionOnly=" + std::to_string(finalActionOnly));
        }
        // An empty producer ring is only a refill observation, not proof that B was
        // presented. Drain admitted PCM until this exact physical role proves B.
        const auto drainDeadline = std::chrono::steady_clock::now() + std::chrono::seconds(4);
        for (;;) {
            const auto position = output.presentedPosition();
            require(position.loopPolicyRevision == firstPhysical.load() &&
                position.lastNextTimelineFrame <= static_cast<std::int64_t>(b),
                "held successor cannot replace or overshoot the old physical B");
            if (position.lastNextTimelineFrame == static_cast<std::int64_t>(b)) break;
            require(std::chrono::steady_clock::now() < drainDeadline,
                "adopted physical producer must publish its remaining real B tail");
            const auto queued = output.queuedForGeneration(owner.generation);
            if (queued == 0U) {
                runtime.notifyIngressAvailable();
                std::this_thread::yield();
                continue;
            }
            tick(std::min<std::size_t>(512U, queued));
        }
        const auto boundary = output.presentedPosition();
        if (boundary.lastTimelineFrame != static_cast<std::int64_t>(b - 2U) ||
            boundary.lastNextTimelineFrame != static_cast<std::int64_t>(b) ||
            boundary.loopPolicyRevision != firstPhysical.load()) throw std::runtime_error(
                "hardware observed wrong old physical B before replacement readiness: frame=" +
                std::to_string(boundary.lastTimelineFrame) + " physical=" + std::to_string(boundary.loopPolicyRevision) +
                " expected=" + std::to_string(firstPhysical.load()) + " queued=" + std::to_string(output.queuedForGeneration(owner.generation)));

        auto renderedBlocksBeforeReplay = runtime.diagnostics().outputBlocksRendered;
        replayDeadlineArmed.store(true);
        holdFollowing.store(false, std::memory_order_release);
        runtime.notifyIngressAvailable();
        bool deadlineScenarioCompleted = false;
        std::size_t adoptions = 1U;
        auto physical = boundary.loopPolicyRevision;
        const auto nextDeadline = std::chrono::steady_clock::now() + std::chrono::seconds(4);
        while (adoptions < 12U) {
            require(std::chrono::steady_clock::now() < nextDeadline, "twelve unheld all-Stems physical successors adopt automatically");
            if (adoptions == 2U && replayEnqueueHeld.load() && !releaseReplayEnqueue.load()) {
                while (output.queuedForGeneration(owner.generation) > 32U)
                    tick(std::min<std::size_t>(512U, output.queuedForGeneration(owner.generation) - 32U));
                require(output.queuedForGeneration(owner.generation) == 32U,
                    "independent consumer leaves exactly32real old-B frames while cached next role is partial");
                releaseReplayEnqueue.store(true);
                waitUntil([&] { return yieldedBeforeCommit.load() || committedPhysical.load() == replayPhysical.load(); },
                    "cached complete replay either commits or yields its refill turn");
                // No readiness wait can hide the actual512-frame consumer deadline.
                tick(512U, false);
                replayDeadlineArmed.store(false); releaseYield.store(true);
                deadlineScenarioCompleted = true;
            } else tick(512U);
            const auto next = output.presentedPosition();
            require(next.lastTimelineFrame >= static_cast<std::int64_t>(a) &&
                next.lastTimelineFrame < static_cast<std::int64_t>(b), "all-Stems source stays inside exact A/B");
            if (next.loopPolicyRevision != physical) {
                require(next.loopPolicyRevision > physical, "physical successor identity advances exactly forward");
                physical = next.loopPolicyRevision;
                ++adoptions;
                // The initial linear producer can already be past B when the first loop is installed.
                if (adoptions == 2U) {
                    renderedBlocksBeforeReplay = runtime.diagnostics().outputBlocksRendered;
                    waitUntil([&] { return replayEnqueueHeld.load(); }, "actual cached replay reaches the controlled partial publication");
                }
            }
        }
        require(deadlineScenarioCompleted, "the independent32/512 cached-replay deadline was actually exercised");
        require(runtime.diagnostics().outputBlocksRendered == renderedBlocksBeforeReplay,
            "complete unchanged same-owner cycles must replay prepared PCM without another DSP render or prime");
        require(actualTunedStemsSeen, "real accepted all-Stems PCM proves the requested2x numerical source");
        // Capture actual metronome-on continuations, then turn it off without changing owner/tuning.
        auto metronome = command;
        metronome.revision = 2U;
        metronome.controls = controls(2U, MixerSourceMode::Stems);
        metronome.controls.tempo = 2.F;
        metronome.controls.mixer.metronome.mode = MetronomeMode::FreeBpm;
        metronome.controls.mixer.metronome.bpm = 400.F;
        metronome.controls.mixer.metronome.subdivision = 4U;
        auto physicalBeforeMetronome = output.presentedPosition().loopPolicyRevision;
        const auto renderedBeforeMetronome = runtime.diagnostics().outputBlocksRendered;
        require(runtime.publishCommand(metronome) == NativePlaybackPublishStatus::Accepted,
            "same-owner metronome-on intent reaches the real runtime");
        std::size_t metronomeAdoptions{};
        const auto metronomeDeadline = std::chrono::steady_clock::now() + std::chrono::seconds(4);
        while (metronomeAdoptions < 2U) {
            require(std::chrono::steady_clock::now() < metronomeDeadline, "actual metronome-on producers adopt");
            tick(512U);
            const auto nextPhysical = output.presentedPosition().loopPolicyRevision;
            if (nextPhysical != physicalBeforeMetronome) { ++metronomeAdoptions; physicalBeforeMetronome = nextPhysical; }
        }
        require(runtime.diagnostics().outputBlocksRendered > renderedBeforeMetronome,
            "metronome-on cannot reuse the former off PCM");
        if (finalActionOnly) {
            // Repeat the final-action deadline with metronome PCM, which cannot use cached replay.
            replayEnqueueHeld.store(false); releaseReplayEnqueue.store(false);
            yieldedBeforeCommit.store(false); releaseYield.store(false);
            holdAfterFrames.store(2'400U); replayDeadlineArmed.store(true);
            const auto publicationDeadline = std::chrono::steady_clock::now() + std::chrono::seconds(4);
            while (!replayEnqueueHeld.load()) {
                require(std::chrono::steady_clock::now() < publicationDeadline,
                    "noncached continuation reaches its final enqueue within the deadline");
                waitUntil([&] { return replayEnqueueHeld.load() ||
                    output.callbackHasPublishedCoverage(owner.generation, 512U); },
                    "noncached final enqueue or exact source coverage becomes observable");
                if (!replayEnqueueHeld.load()) {
                    const auto queued = output.queuedForGeneration(owner.generation);
                    if (queued > 32U) {
                        // The worker can reach the held enqueue during this read. Keep
                        // its outgoing32-frame deadline precondition intact either way.
                        tick(std::min<std::size_t>(512U, queued - 32U), false);
                    } else if (output.pendingPhysicalLoopRevision() != 0U &&
                        output.callbackHasPublishedCoverage(owner.generation, 512U)) {
                        tick(512U, false);
                    } else {
                        runtime.notifyIngressAvailable();
                        std::this_thread::yield();
                    }
                }
            }
            while (output.queuedForGeneration(owner.generation) > 32U)
                tick(std::min<std::size_t>(512U, output.queuedForGeneration(owner.generation) - 32U), false);
            require(output.queuedForGeneration(owner.generation) == 32U,
                "noncached final enqueue retains exactly 32 real old-B frames");
            releaseReplayEnqueue.store(true);
            waitUntil([&] { return yieldedBeforeCommit.load() || committedPhysical.load() == replayPhysical.load(); },
                "noncached final budget action either publishes its role or leaves the refill turn");
            // The consumer deadline is independent of the worker's next turn or readiness probe.
            try {
                tick(512U, false);
            } catch (const std::exception &error) {
                throw std::runtime_error(std::string("Noncached final-action boundary: ") + error.what());
            }
            replayDeadlineArmed.store(false); releaseYield.store(true);
            physicalBeforeMetronome = output.presentedPosition().loopPolicyRevision;
        }
        metronome.revision = 3U;
        metronome.controls = controls(3U, MixerSourceMode::Stems);
        metronome.controls.tempo = 2.F;
        const auto renderedBeforeOff = runtime.diagnostics().outputBlocksRendered;
        require(runtime.publishCommand(metronome) == NativePlaybackPublishStatus::Accepted,
            "same-owner metronome-off intent supersedes captured on PCM");
        const auto offDeadline = std::chrono::steady_clock::now() + std::chrono::seconds(4);
        do {
            require(std::chrono::steady_clock::now() < offDeadline, "metronome-off replacement adopts");
            tick(512U);
        } while (output.presentedPosition().loopPolicyRevision == physicalBeforeMetronome ||
            output.appliedControlEvidence().controlRevision != 3U);
        require(runtime.diagnostics().outputBlocksRendered > renderedBeforeOff,
            "captured metronome-on PCM must rebuild rather than replay after current Off");
        require(output.quiescePushConsumer(), "push512 consumer retires before the runtime fixture");
        runtime.requestStop(); runtime.join();
    } catch (...) {
        holdFollowing.store(false); replayDeadlineArmed.store(false); releaseReplayEnqueue.store(true); releaseYield.store(true);
        runtime.requestStop(); runtime.join(); throw;
    }
}

void inactiveBankLoopCannotStarveReusedCallbackOwner() {
    using namespace stemwise::audio::engine;
    constexpr SongRenderOwnership old{2U, 17U}, outgoing{2U, 19U}, owner{2U, 20U};
    constexpr std::size_t callbackFrames = 256U, releaseTick = 128U, totalTicks = 256U;
    constexpr std::uint64_t firstFrame = 512U, b = 4'800U;
    SongLaneIngressBank first(32'768U, 480U), second(32'768U, 480U);
    std::vector<float> original(32'768U * 2U);
    for (std::size_t frame = 0U; frame != original.size() / 2U; ++frame) {
        original[frame * 2U] = .08F; original[frame * 2U + 1U] = -.06F;
    }
    const auto offerOriginal = [&](SongLaneIngressBank &bank, SongRenderOwnership source, std::uint64_t start) {
        if (source.generation != owner.generation)
            for (std::size_t lane = 0U; lane != 7U; ++lane)
                require(bank.configureLane(lane, kSampleRateHz, source.generation), "real whole-bank decoder epoch configures");
        for (std::size_t offset = 0U; offset < original.size() / 2U;) {
            const auto count = std::min<std::size_t>(480U, original.size() / 2U - offset);
            require(bank.lane(0U)->offer({original.data() + offset * 2U, count,
                {kSampleRateHz, 2U, LanePcmEncoding::Float32}, source.generation,
                (start + offset) * 1'000'000U / kSampleRateHz}).inputFramesAccepted == count,
                "actual decoder supplies only its truthful Original source interval");
            offset += count;
        }
    };
    offerOriginal(first, old, 0U);
    offerOriginal(second, outgoing, 48'000U);
    ClosureOutput output(0x3fU, kOutputCapacityFrames, 16U, callbackFrames, 512U);
    auto configuration = runtimeConfiguration();
    configuration.pipeline.maximumBlockFrames = 480U;
    configuration.minimumOutputReserveMicros = NativePlaybackRuntimeConfiguration::kDefaultOutputReserveMicros;
    configuration.pipeline.laneReservoirCapacityFrames = 32'768U;
    configuration.ingressPullFrames = 480U;
    NativePlaybackRuntime runtime(configuration, first, second, output.port());
    const auto assignment = [&](SongRenderOwnership source, std::uint64_t revision, std::uint64_t start) {
        return NativePlaybackBankIntent{revision, true, source, start, 0, 111U, 222U, 333U, 6U,
            (std::numeric_limits<std::uint64_t>::max)()};
    };
    const auto prepareHead = [&](SongRenderOwnership source, std::uint64_t revision, std::uint64_t start) {
        const LoopHeadContentKey key{111U, 222U, 333U, 1U, revision, start, start + LoopHeadCache::kCapacityFrames};
        require(runtime.beginLoopHeadPreparation(key, 127U, LoopHeadBoundaryMode::RetainedWindow, source) ==
            LoopHeadPreparationBeginStatus::Accepted, "historical owner's exact all-lane head begins");
        std::vector<float> pcm(LoopHeadCache::kCapacityFrames * 2U);
        for (std::size_t lane = 0U; lane != 7U; ++lane) {
            for (std::size_t frame = 0U; frame != LoopHeadCache::kCapacityFrames; ++frame) {
                pcm[frame * 2U] = lane == 0U ? .08F : .020F + .002F * static_cast<float>(lane);
                pcm[frame * 2U + 1U] = lane == 0U ? -.06F : -.016F - .001F * static_cast<float>(lane);
            }
            const auto epoch = 700U + revision * 10U + lane;
            require(runtime.configureLoopHeadPreparationLane(revision, lane, kSampleRateHz, epoch) ==
                LoopHeadPreparationConfigureStatus::Accepted, "historical head decoder configures");
            for (std::size_t offset = 0U; offset != LoopHeadCache::kCapacityFrames;) {
                const auto count = std::min<std::size_t>(480U, LoopHeadCache::kCapacityFrames - offset);
                const auto offered = runtime.offerLoopHeadPreparationPcm(revision, lane,
                    {pcm.data() + offset * 2U, count, {kSampleRateHz, 2U, LanePcmEncoding::Float32}, epoch,
                        (start + offset) * 1'000'000U / kSampleRateHz});
                require(offered.inputFramesAccepted != 0U, "historical head consumes actual known PCM");
                offset += offered.inputFramesAccepted;
            }
        }
    };
    prepareHead(old, 1U, 0U);
    std::array<NativePlaybackBankIntent, 2U> banks{assignment(old, 1U, 0U), NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(1U, old, banks, MixerSourceMode::Original)) ==
        NativePlaybackPublishStatus::Accepted && runtime.start(), "bank0/17 starts through the real output port");
    try {
        waitUntil([&] { return output.commitCount() == 1U && output.playing(); }, "owner17 reserve commits");
        static_cast<void>(measuredCallback(output, callbackFrames));
        require(runtime.publishDirectLoopPolicy(1U, old.selectionToken, old.generation, true, 0U, b, 0U, true) ==
            NativePlaybackPublishStatus::Accepted, "owner17 installs a real 0..4800 loop");
        std::uint64_t acceptedBeforeLoopCallback = 0U;
        const auto installDeadline = std::chrono::steady_clock::now() + std::chrono::seconds(3);
        while (output.presentedPosition().loopPolicyRevision == 0U) {
            require(std::chrono::steady_clock::now() < installDeadline, "owner17 loop callback adoption is bounded");
            acceptedBeforeLoopCallback = runtime.diagnostics().outputFramesAccepted;
            static_cast<void>(nextCallback(output, old.generation, callbackFrames));
        }
        waitUntil([&] { return runtime.diagnostics().outputFramesAccepted > acceptedBeforeLoopCallback; },
            "worker adopts and refills the physically installed owner17 loop producer");
        banks[1] = assignment(outgoing, 1U, 48'000U);
        auto otherBank = selectedCommand(2U, outgoing, banks, MixerSourceMode::Original);
        otherBank.selectedBank = NativePlaybackBank::Second;
        require(runtime.publishCommand(otherBank) == NativePlaybackPublishStatus::Accepted, "bank1/19 becomes selected");
        waitUntil([&] { return output.commitCount() == 2U; }, "owner19 reserve commits");
        const auto otherCallback = measuredCallback(output, callbackFrames);
        require(otherCallback.generationAfter == outgoing.generation && otherCallback.physicalFrames == callbackFrames,
            "callback proves owner19 before bank0 is reused");
        waitUntil([&] { return sameOwner(runtime.diagnostics().committedOwnership, outgoing); }, "owner19 publication is acknowledged");
        prepareHead(outgoing, 2U, 48'000U);
        // A stale inactive-bank request must not mutate the accepted bank revision or the audible owner.
        const auto rejectedBefore = runtime.diagnostics().banks[0].rejectedBankIntents;
        auto stale = otherBank;
        stale.revision = 3U;
        stale.banks[0] = assignment({old.selectionToken, old.generation - 1U}, 2U, firstFrame);
        require(runtime.publishCommand(stale) == NativePlaybackPublishStatus::Accepted, "higher intent revision reaches normal stale-generation rejection");
        waitUntil([&] { return runtime.diagnostics().banks[0].rejectedBankIntents > rejectedBefore; }, "stale inactive generation is rejected");
        require(runtime.diagnostics().banks[0].appliedIntentRevision == 1U, "stale generation preserves accepted bank0 identity");
        const auto preserved = nextCallback(output, outgoing.generation, callbackFrames);
        require(preserved.physicalFrames == callbackFrames && preserved.generationAfter == outgoing.generation,
            "stale bank intent preserves the independently audible owner19 PCM");
        for (std::size_t frame = 0U; frame != callbackFrames; ++frame) {
            requireNear(preserved.pcm[frame * 2U], .04F, .00002F, "stale bank intent changed known owner19 left PCM");
            requireNear(preserved.pcm[frame * 2U + 1U], -.03F, .00002F, "stale bank intent changed known owner19 right PCM");
        }
        offerOriginal(first, owner, firstFrame);
        banks[0] = assignment(owner, 3U, firstFrame);
        require(runtime.publishCommand(selectedCommand(4U, owner, banks, MixerSourceMode::Original)) ==
            NativePlaybackPublishStatus::Accepted, "inactive bank0 is normally reused for exact owner20 at512");
        waitUntil([&] { return output.commitCount() == 3U; }, "owner20 ordinary reserve commits");
        const auto firstCallback = measuredCallback(output, callbackFrames);
        require(firstCallback.generationAfter == owner.generation && firstCallback.read.firstTag.timelineFrame ==
            static_cast<std::int64_t>(firstFrame), "actual callback adopts owner20 at truthful source512");
        auto desired = selectedCommand(5U, owner, banks, MixerSourceMode::Original);
        desired.controls.tempo = .5F;
        require(runtime.publishCommand(desired) == NativePlaybackPublishStatus::Accepted &&
            runtime.publishDirectLoopPolicy(2U, owner.selectionToken, owner.generation, true, 0U, b, 0U, true) ==
                NativePlaybackPublishStatus::Accepted, "desired Stems uses Native Original/.5 while the new head is pending");
        banks[1] = NativePlaybackBankIntent{};
        banks[1].revision = 2U;
        auto retired = desired;
        retired.revision = 6U;
        retired.banks = banks;
        require(runtime.publishCommand(retired) == NativePlaybackPublishStatus::Accepted,
            "only callback-proven owner20 authorizes retirement of outgoing19 and its old read lease");
        waitUntil([&] { const auto state = runtime.diagnostics();
            return !state.banks[1].assigned && state.banks[1].appliedIntentRevision == 2U;
        }, "outgoing19 exact bank unassignment retires before its slot is reused");
        std::atomic<std::size_t> ticks{0U};
        std::atomic<bool> headReady{false};
        std::exception_ptr preparationError;
        std::jthread preparation([&](std::stop_token stop) {
            try {
                while (ticks.load(std::memory_order_acquire) < releaseTick && !stop.stop_requested())
                    std::this_thread::sleep_for(std::chrono::milliseconds(1));
                if (stop.stop_requested()) return;
                prepareHead(owner, 3U, 0U);
                auto readySource = selectedCommand(7U, owner, banks, MixerSourceMode::Stems);
                readySource.controls.tempo = .5F;
                require(runtime.publishCommand(readySource) == NativePlaybackPublishStatus::Accepted,
                    "native Stems is published only after the actual all-lane head commits");
                headReady.store(true, std::memory_order_release);
            } catch (...) { preparationError = std::current_exception(); }
        });
        std::size_t missingCallbacks = 0U, missingFrames = 0U, firstMissingTick = totalTicks;
        std::uint64_t missingOwnerBefore = 0U, missingOwnerAfter = 0U;
        std::int64_t lastTimeline = -1, firstMissingTimeline = -1;
        std::uint64_t firstMissingInput = 0U;
        bool stemsSeen = false;
        std::size_t knownOriginalFrames = 0U, wrongOwners = 0U;
        const auto origin = std::chrono::steady_clock::now();
        for (std::size_t tick = 0U; tick != totalTicks; ++tick) {
            std::this_thread::sleep_until(origin + std::chrono::nanoseconds(
                (tick + 1U) * callbackFrames * 1'000'000'000ULL / kSampleRateHz));
            ticks.store(tick + 1U, std::memory_order_release);
            const auto record = measuredCallback(output, callbackFrames); // Never waits for producer readiness.
            const auto ready = runtime.renderReadinessDiagnostics();
            if (record.generationBefore != owner.generation || record.generationAfter != owner.generation) ++wrongOwners;
            if (record.physicalFrames < callbackFrames) {
                if (missingCallbacks++ == 0U) {
                    firstMissingTick = tick; missingOwnerBefore = record.generationBefore; missingOwnerAfter = record.generationAfter;
                    firstMissingTimeline = record.read.frameCount ? record.read.lastTag.timelineFrame : lastTimeline;
                    firstMissingInput = ready.renderStartTimelineFrame;
                }
                missingFrames += callbackFrames - record.physicalFrames;
            }
            if (record.read.frameCount != 0U) lastTimeline = record.read.lastTag.timelineFrame;
            stemsSeen = stemsSeen || (record.read.frameCount != 0U && record.read.lastTag.actualSourceMode == FinalPcmSourceMode::Stems &&
                record.read.lastTag.sourceTransitionSettled && record.read.lastTag.actualTempo == .5F);
            if (!record.transitionTouched && record.read.frameCount == callbackFrames &&
                record.read.lastTag.actualSourceMode == FinalPcmSourceMode::Original && record.read.lastTag.actualTempo == 1.0F) {
                for (std::size_t frame = 0U; frame != callbackFrames; ++frame) {
                    requireNear(record.pcm[frame * 2U], .04F, .00002F, "paced fallback changed known Original left PCM");
                    requireNear(record.pcm[frame * 2U + 1U], -.03F, .00002F, "paced fallback changed known Original right PCM");
                }
                knownOriginalFrames += callbackFrames;
            }
        }
        preparation.request_stop(); preparation.join();
        if (preparationError) std::rethrow_exception(preparationError);
        require(headReady.load() && knownOriginalFrames != 0U && wrongOwners == 0U,
            "paced case must complete actual head preparation and classify callback owner20 with known fallback PCM");
        if (missingFrames != 0U) throw std::runtime_error("paced late-head owner20 starvation: callbacks=" +
            std::to_string(missingCallbacks) + " missingFrames=" + std::to_string(missingFrames) + " firstTick=" +
            std::to_string(firstMissingTick) + " owner=" + std::to_string(missingOwnerBefore) + "/" +
            std::to_string(missingOwnerAfter) + " firstMissingTimeline=" + std::to_string(firstMissingTimeline) +
            " firstMissingInput=" + std::to_string(firstMissingInput) + " lastTimeline=" + std::to_string(lastTimeline) +
            " settledStemsHalf=" + std::to_string(stemsSeen) + runtimeBoundaryFacts(output, runtime, owner));
        require(stemsSeen, "post-head callbacks must prove the requested settled Stems half-speed numerical tuple");
        runtime.requestStop(); runtime.join();
    } catch (...) { runtime.requestStop(); runtime.join(); throw; }
}

void managedLoopSubmissionRequiresItsExactInstalledContext() {
    constexpr SongRenderOwnership owner{0xAE9EU, 2U};
    for (unsigned fault = 0U; fault != 5U; ++fault) {
        auto output = std::make_unique<ClosureOutput>(0U, kOutputCapacityFrames, owner.generation);
        const auto port = output->port();
        const auto pcm = corePayload(4U, .08F);
        auto block = coreSubmission(owner, 1U, pcm, 104U);
        block.loopEnabled = true;
        block.loopRevision = 52U;
        block.managedLoopContext = {52U, 100U, 108U};
        if (fault == 1U) ++block.managedLoopContext.revision;
        if (fault == 2U) block.managedLoopContext.startTimelineFrame = 108U;
        if (fault == 3U) block.managedLoopContext.endTimelineFrameExclusive = 100U;
        if (fault == 4U) block.managedLoopContext = {};
        require(port.enqueue(port.handle, block) == (fault == 0U ? 4U : 0U),
            "managed producer admission rejects mismatched revision, invalid A/B or missing authority");
        if (fault != 0U) {
            require(output->queuedForGeneration(owner.generation) == 0U,
                "rejected managed authority leaves the actual output ring unchanged");
            continue;
        }
        ++block.payloadSequence;
        block.loopCycle = 1U;
        block.sourceStartFrame = block.renderSourceStartFrame = 100.0;
        block.sourceEndFrameExclusive = 104.0;
        require(port.enqueue(port.handle, block) == 4U && port.setPlaying(port.handle, true),
            "exact managed head follows the admitted tail");
        const auto read = measuredCallback(*output, 8U);
        require(read.physicalFrames == 8U && read.read.continuityViolationCount == 0U,
            "Core consumes the admitted managed B-to-A context without a latest-policy echo");
    }
}

void repeatOnePublishesActualCallbackCycleEvidence() {
    using namespace stemwise::audio::engine;
    constexpr SongRenderOwnership owner{0xAE9EU, 2U};
    constexpr std::size_t frames = 2'048U;
    SongLaneIngressBank first(8'192U, 480U), second(256U, 256U);
    require(first.configureLane(0U, kSampleRateHz, owner.generation), "Repeat Original configure");
    const std::vector<float> pcm(frames * 2U, .08F);
    for (std::size_t offset = 0U; offset < frames;) {
        const auto count = std::min<std::size_t>(480U, frames - offset);
        require(first.lane(0U)->offer({pcm.data() + offset * 2U, count,
            {kSampleRateHz, 2U, LanePcmEncoding::Float32}, owner.generation,
            offset * 1'000'000U / kSampleRateHz}).inputFramesAccepted == count, "Repeat ingress");
        offset += count;
    }
    require(first.lane(0U)->markEndOfStream(owner.generation, frames).status ==
        LaneEndOfStreamStatus::Accepted, "Repeat real Original EOS");
    ClosureOutput output(0U);
    NativePlaybackRuntime runtime(runtimeConfiguration(), first, second, output.port());
    const LoopCycleIntent repeat{.revision = 1U, .enabled = true,
        .owner = {owner.selectionToken, owner.generation},
        .cacheKey = {111U, 222U, 0U, 1U, 1U, 0U, LoopHeadCache::kCapacityFrames},
        .requiredLaneMask = 1U, .declickFrames = 8U,
        .boundaryMode = LoopHeadBoundaryMode::OriginalEos};
    require(runtime.beginLoopHeadPreparation(repeat.cacheKey, 1U) == LoopHeadPreparationBeginStatus::Accepted,
        "Repeat actual preparation begins");
    require(runtime.configureLoopHeadPreparationLane(1U, 0U, kSampleRateHz, 100U) ==
        LoopHeadPreparationConfigureStatus::Accepted, "Repeat preparation decoder configured");
    for (std::size_t offset = 0U; offset < frames;) {
        const auto count = std::min<std::size_t>(480U, frames - offset);
        const auto offered = runtime.offerLoopHeadPreparationPcm(1U, 0U,
            {pcm.data() + offset * 2U, count, {kSampleRateHz, 2U, LanePcmEncoding::Float32},
             100U, offset * 1'000'000U / kSampleRateHz});
        require(offered.inputFramesAccepted != 0U && offered.inputFramesAccepted <= count,
            "Repeat bounded preparation progresses");
        offset += offered.inputFramesAccepted;
    }
    require(runtime.markLoopHeadPreparationEndOfStream(1U, 0U, 100U, frames) ==
        LoopHeadPreparationEndStatus::CacheCommitted, "Repeat exact short decoded head commits");
    const std::array<NativePlaybackBankIntent, 2U> banks{
        NativePlaybackBankIntent{1U, true, owner, 0U, 0, 111U, 222U, 0U, 0U, frames}, NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(1U, owner, banks, MixerSourceMode::Original)) ==
        NativePlaybackPublishStatus::Accepted && runtime.start(), "Repeat starts selected real owner");
    const char *phase = "direct installation";
    const auto crossHeldManagedBlock = [&] {
        for (unsigned block = 0U; block != 80U && !output.managedSubmissionHeld(); ++block) {
            waitUntil([&] { return output.managedSubmissionHeld() ||
                output.callbackHasPublishedCoverage(owner.generation, 64U); }, "managed revision or old PCM plus one retained frame must become observable");
            if (!output.managedSubmissionHeld()) static_cast<void>(measuredCallback(output, 63U));
        }
        require(output.managedSubmissionHeld(), "actual new managed render waits behind its queued predecessor");
        const auto drainDeadline = std::chrono::steady_clock::now() + std::chrono::seconds(3);
        for (;;) {
            const auto beforeDrain = output.queuedForGeneration(owner.generation);
            if (beforeDrain <= 1U) break;
            require(std::chrono::steady_clock::now() < drainDeadline, "held managed prefix drain is bounded");
            const auto framesToRead = std::min<std::size_t>(512U, beforeDrain - 1U);
            const auto drained = measuredCallback(output, framesToRead);
            require(drained.physicalFrames == framesToRead,
                "retain exactly one real old-revision frame before releasing the new block");
            const auto afterDrain = output.queuedForGeneration(owner.generation);
            const auto whole = drained.wholeRead();
            require(afterDrain != 0U, ("prefix drain changed physical role and left no old frame before=" +
                std::to_string(beforeDrain) + " read=" + std::to_string(framesToRead) +
                " spans=" + std::to_string(drained.spanCount) + " source=" + std::to_string(whole.firstTag.timelineFrame) +
                "/" + std::to_string(whole.lastTag.timelineFrame) + " physical=" +
                std::to_string(whole.firstTag.loopPolicyRevision) + "/" + std::to_string(whole.lastTag.loopPolicyRevision)).c_str());
        }
        require(output.queuedForGeneration(owner.generation) == 1U,
            "held managed revision must retain one actual old frame before releasing its producer");
        output.holdManagedRevision(0U);
        return nextCallback(output, owner.generation, 63U);
    };
    try {
        static_cast<void>(nextCallback(output, owner.generation, 63U));
        require(runtime.publishDirectLoopPolicy(1U, owner.selectionToken, owner.generation,
            true, 0U, 512U, 0U, true) == NativePlaybackPublishStatus::Accepted,
            "same owner first installs a real direct loop");
        waitUntil([&] { return runtime.renderReadinessDiagnostics().fixedLoopHeadPrepared; },
            "same owner direct continuation is prepared");
        for (unsigned block = 0U; block != 40U; ++block)
            static_cast<void>(nextCallback(output, owner.generation, 63U));
        require(output.presentedPosition().loopPolicyAdoptedEnabled,
            "actual callback adopted the direct loop before Clear");
        phase = "direct Clear";
        require(runtime.publishDirectLoopPolicy(2U, owner.selectionToken, owner.generation,
            false, 0U, 512U, 0U, false) == NativePlaybackPublishStatus::Accepted,
            "same owner clears the direct loop");
        for (unsigned block = 0U; block != 40U && output.presentedPosition().loopPolicyAdoptedRevision != 2U; ++block)
            static_cast<void>(nextCallback(output, owner.generation, 63U));
        const auto cleared = output.presentedPosition();
        require(cleared.loopPolicyAdoptedRevision == 2U && !cleared.loopPolicyAdoptedEnabled &&
            cleared.loopPolicyRevision != 0U, "Clear retains its actual disabled physical role");
        phase = "managed activation after Clear";
        waitUntil([&] { return output.queuedForGeneration(owner.generation) != 0U; },
            "managed activation begins with actual queued linear PCM before its intent is published");
        output.holdManagedRevision(1U);
        require(runtime.publishLoopCycleIntent(repeat) == NativePlaybackPublishStatus::Accepted,
            "same owner enables managed Repeat on the retained Clear role");
        const auto activation = crossHeldManagedBlock();
        const auto activationWhole = activation.wholeRead();
        require(activation.physicalFrames == 63U && activationWhole.frameCount == 63U &&
            activationWhole.firstTag.loopRevision == 0U && activationWhole.lastTag.loopRevision == 1U &&
            activationWhole.firstTag.generation == owner.generation && activationWhole.lastTag.generation == owner.generation &&
            activationWhole.firstTag.selectionToken == owner.selectionToken && activationWhole.lastTag.selectionToken == owner.selectionToken &&
            activationWhole.firstTag.timelineFrame >= 0 && activationWhole.firstTag.timelineFrame < static_cast<std::int64_t>(frames) &&
            activationWhole.lastTag.timelineFrame == (activationWhole.firstTag.timelineFrame + 62) % static_cast<std::int64_t>(frames) &&
            activationWhole.continuityViolationCount == 0U,
            ("actual Repeat activation preserves the queued linear tail and exact managed continuation first=" +
                std::to_string(activationWhole.firstTag.timelineFrame) + "/rev" + std::to_string(activationWhole.firstTag.loopRevision) +
                " last=" + std::to_string(activationWhole.lastTag.timelineFrame) + "/rev" + std::to_string(activationWhole.lastTag.loopRevision) +
                " frames=" + std::to_string(activationWhole.frameCount) + " spans=" + std::to_string(activation.spanCount) +
                " continuity=" + std::to_string(activationWhole.continuityViolationCount)).c_str());
        for (unsigned block = 0U; block < 120U; ++block) {
            // A non-divisor forces the exact Repeat boundary inside one real callback read.
            const auto callback = nextCallback(output, owner.generation, 63U);
            require(callback.read.continuityViolationCount == 0U,
                "actual installed Repeat must reach the callback with authoritative loop context");
            require(callback.physicalFrames == 63U && output.presentedPosition().generation == owner.generation &&
                output.presentedPosition().lastTimelineFrame < static_cast<std::int64_t>(frames),
                "Repeat callback remains in exact decoded Original interval");
        }
        const auto evidence = output.audibleLoopEvidence();
        require(evidence.valid() && evidence.generation == owner.generation &&
            evidence.selectionToken == owner.selectionToken && evidence.loopRevision == 1U &&
            evidence.loopCycle >= 2U && runtime.diagnostics().audibleEnd.sequence == 0U,
            "actual output callback proves repeated cycles without natural-end publication");
        require(output.diagnostics()[stemwise::audio::packet::OUTPUT_DIAGNOSTICS_UNDERRUN_CALLBACK_COUNT] == 0,
            "Repeat callback cycles remain fully fed");
        auto retargeted = repeat;
        phase = "managed warm retarget";
        retargeted.revision = 2U;
        const auto beforeRetarget = runtime.loopProtocolSnapshot();
        require(beforeRetarget.state.latestRevision == 1U && beforeRetarget.state.renderInstalled,
            "warm retarget starts from the actually installed previous managed revision");
        waitUntil([&] { return output.queuedForGeneration(owner.generation) != 0U; },
            "managed retarget begins with actual queued old PCM before its intent is published");
        output.holdManagedRevision(2U);
        require(runtime.publishLoopCycleIntent(retargeted) == NativePlaybackPublishStatus::Accepted,
            "new managed revision retains the actual installed decoded head");
        const auto crossing = crossHeldManagedBlock();
        const auto crossingWhole = crossing.wholeRead();
        const auto afterRetarget = runtime.loopProtocolSnapshot();
        // A new managed revision explicitly clears the old directive before installing its replacement.
        const bool previousQueuedRevision = crossingWhole.firstTag.loopRevision == 1U &&
            crossingWhole.firstTag.loopEnabled;
        const bool actualInterimClear = crossingWhole.firstTag.loopRevision == 0U &&
            !crossingWhole.firstTag.loopEnabled && afterRetarget.state.latestRevision == 2U &&
            afterRetarget.state.renderInstalled && afterRetarget.state.owner.selectionToken == owner.selectionToken &&
            afterRetarget.state.owner.renderGeneration == owner.generation &&
            afterRetarget.diagnostics.renderCommits >= beforeRetarget.diagnostics.renderCommits + 2U &&
            afterRetarget.diagnostics.deadlineFallbacks == beforeRetarget.diagnostics.deadlineFallbacks;
        std::string spanFacts = " spanCount=" + std::to_string(crossing.spanCount);
        for (std::size_t index = 0U; index < crossing.spanCount; ++index) {
            const auto &span = crossing.spans[index];
            spanFacts += " [frames=" + std::to_string(span.frameCount) + " rev=" +
                std::to_string(span.firstTag.loopRevision) + "/" + std::to_string(span.lastTag.loopRevision) +
                " source=" + std::to_string(span.firstTag.timelineFrame) + "/" + std::to_string(span.lastTag.timelineFrame) +
                " physical=" + std::to_string(span.firstTag.loopPolicyRevision) + "/" + std::to_string(span.lastTag.loopPolicyRevision) + "]";
        }
        require(crossing.physicalFrames == 63U && crossingWhole.frameCount == 63U &&
            (previousQueuedRevision || actualInterimClear) && crossingWhole.lastTag.loopRevision == 2U &&
            crossingWhole.lastTag.loopEnabled &&
            crossingWhole.firstTag.generation == owner.generation && crossingWhole.lastTag.generation == owner.generation &&
            crossingWhole.firstTag.selectionToken == owner.selectionToken && crossingWhole.lastTag.selectionToken == owner.selectionToken &&
            crossingWhole.firstTag.timelineFrame >= 0 && crossingWhole.firstTag.timelineFrame < static_cast<std::int64_t>(frames) &&
            crossingWhole.lastTag.timelineFrame == (crossingWhole.firstTag.timelineFrame + 62) % static_cast<std::int64_t>(frames),
            ("one actual Core read spans both admitted managed revisions" + spanFacts).c_str());
        require(crossingWhole.continuityViolationCount == 0U,
            ("actual warm managed retarget preserves source boundary first=" + std::to_string(crossingWhole.firstTag.timelineFrame) +
                "/" + std::to_string(crossingWhole.firstTag.loopCycle) + " last=" +
                std::to_string(crossingWhole.lastTag.timelineFrame) + "/" + std::to_string(crossingWhole.lastTag.loopCycle)).c_str());
        for (unsigned block = 0U; block != 80U; ++block) {
            const auto callback = nextCallback(output, owner.generation, 63U);
            require(callback.physicalFrames == 63U && callback.read.continuityViolationCount == 0U,
                "actual warm managed retarget must preserve callback continuity across queued revisions");
        }
        require(output.audibleLoopEvidence().loopRevision == 2U,
            "new managed revision reaches the actual callback");
        runtime.requestStop(); runtime.join();
    } catch (const std::exception &error) {
        const auto facts = std::string(phase) + ": " + error.what() + runtimeBoundaryFacts(output, runtime, owner);
        runtime.requestStop(); runtime.join(); throw std::runtime_error(facts);
    } catch (...) { runtime.requestStop(); runtime.join(); throw; }
}


void trimmedRepeatResumesAfterPlayingDirectLoopClear() {
    using namespace stemwise::audio::engine;
    constexpr SongRenderOwnership owner{0xAE9FU, 2U};
    constexpr std::size_t start = 48'000U, end = 240'000U, sourceEnd = 288'000U;
    SongLaneIngressBank first(524'288U, 480U), second(256U, 256U);
    require(first.configureLane(0U, kSampleRateHz, owner.generation), "trimmed Repeat Original configures");
    std::vector<float> pcm(sourceEnd * 2U);
    for (std::size_t frame = 0U; frame < sourceEnd; ++frame) {
        pcm[frame * 2U] = .02F + static_cast<float>(frame % 997U) / 1'000'000.F;
        pcm[frame * 2U + 1U] = -pcm[frame * 2U];
    }
    for (std::size_t offset = 0U; offset < sourceEnd;) {
        const auto count = std::min<std::size_t>(480U, sourceEnd - offset);
        require(first.lane(0U)->offer({pcm.data() + offset * 2U, count,
            {kSampleRateHz, 2U, LanePcmEncoding::Float32}, owner.generation,
            offset * 1'000'000U / kSampleRateHz}).inputFramesAccepted == count,
            "trimmed Repeat receives its actual full six-second recording");
        offset += count;
    }
    require(first.lane(0U)->markEndOfStream(owner.generation, sourceEnd).status ==
        LaneEndOfStreamStatus::Accepted, "active Original decoder seals its exact admitted extent");
    auto storage = std::make_unique<ClosureOutput>(0U);
    auto &output = *storage;
    auto configuration = runtimeConfiguration();
    configuration.pipeline.maximumBlockFrames = 480U;
    NativePlaybackRuntime runtime(configuration, first, second, output.port());
    LoopCycleIntent repeat{.revision = 1U, .enabled = true,
        .owner = {owner.selectionToken, owner.generation},
        .cacheKey = {111U, 222U, 0U, 1U, 1U, start, end},
        .requiredLaneMask = 1U, .declickFrames = 0U,
        .boundaryMode = LoopHeadBoundaryMode::OriginalEos};
    require(runtime.beginLoopHeadPreparation(repeat.cacheKey, 1U, repeat.boundaryMode, owner) ==
        LoopHeadPreparationBeginStatus::Accepted, "nonzero trimmed head preparation begins");
    require(runtime.configureLoopHeadPreparationLane(1U, 0U, kSampleRateHz, 100U) ==
        LoopHeadPreparationConfigureStatus::Accepted, "nonzero trimmed head decoder configures");
    LoopHeadPreparationOfferStatus lastHeadStatus = LoopHeadPreparationOfferStatus::NoPreparation;
    for (std::size_t offset = start; offset < end;) {
        const auto count = std::min<std::size_t>(480U, end - offset);
        const auto offered = runtime.offerLoopHeadPreparationPcm(1U, 0U,
            {pcm.data() + offset * 2U, count, {kSampleRateHz, 2U, LanePcmEncoding::Float32},
             100U, offset * 1'000'000U / kSampleRateHz});
        require(offered.inputFramesAccepted != 0U, "nonzero trimmed head consumes actual sample-corresponding PCM");
        lastHeadStatus = offered.status;
        offset += offered.inputFramesAccepted;
    }
    require(lastHeadStatus == LoopHeadPreparationOfferStatus::CacheCommitted,
        "exact four-second trimmed head commits without inventing a recording EOF");
    const std::array<NativePlaybackBankIntent, 2U> banks{
        NativePlaybackBankIntent{1U, true, owner, start, 0U, 111U, 222U, 0U, 0U, sourceEnd, end}, {}};
    require(runtime.publishLoopCycleIntent(repeat) == NativePlaybackPublishStatus::Accepted &&
        runtime.publishCommand(selectedCommand(1U, owner, banks, MixerSourceMode::Original)) ==
            NativePlaybackPublishStatus::Accepted, "trimmed Repeat publishes its exact owner");
    require(runtime.start(), "trimmed Repeat starts its exact owner");
    const char *phase = "initial trimmed Repeat";
    std::size_t checkedSamples = 0U;
    const auto collectTwoWraps = [&](std::uint64_t managedRevision, bool direct, bool requireClear = false) {
        unsigned wraps = 0U;
        std::int64_t previous = -1;
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(20);
        while (wraps < 2U && std::chrono::steady_clock::now() < deadline) {
            const auto callback = nextCallback(output, owner.generation, 479U);
            require(callback.physicalFrames == 479U && callback.wholeRead().continuityViolationCount == 0U,
                "trimmed Repeat and direct loop callbacks remain fully fed and continuous");
            std::size_t callbackOffset = 0U;
            for (std::size_t index = 0U; index < callback.spanCount; ++index) {
                const auto &span = callback.spans[index];
                require(span.firstTag.generation == owner.generation && span.lastTag.generation == owner.generation &&
                    span.firstTag.selectionToken == owner.selectionToken && span.lastTag.selectionToken == owner.selectionToken &&
                    span.firstTag.timelineFrame >= static_cast<std::int64_t>(start) &&
                    span.lastTag.timelineFrame < static_cast<std::int64_t>(end),
                    "every actual callback remains inside the exact nonzero trim owner");
                if (!callback.transitionTouched && span.lastTag.timelineFrame - span.firstTag.timelineFrame ==
                        static_cast<std::int64_t>(span.frameCount - 1U)) {
                    for (std::size_t frame = 0U; frame < span.frameCount; ++frame) {
                        const auto sourceFrame = static_cast<std::size_t>(span.firstTag.timelineFrame) + frame;
                        if (sourceFrame > start + 512U && sourceFrame + 512U < end &&
                            (sourceFrame < 71'488U || sourceFrame > 72'512U) &&
                            (sourceFrame < 119'488U || sourceFrame > 120'512U)) {
                            requireNear(callback.pcm[(callbackOffset + frame) * 2U], pcm[sourceFrame * 2U] * .5F,
                                .000002F, "retained direct/Repeat PCM must match its independent source sample curve");
                            ++checkedSamples;
                        }
                    }
                }
                callbackOffset += span.frameCount;
            }
            const auto tag = callback.wholeRead().lastTag;
            const auto position = output.presentedPosition();
            const bool currentPolicy = direct ? position.loopPolicyAdoptedRevision == 1U &&
                position.loopPolicyAdoptedEnabled : tag.loopRevision == managedRevision && tag.loopEnabled &&
                    (!requireClear || (position.loopPolicyAdoptedRevision == 2U && !position.loopPolicyAdoptedEnabled));
            if (currentPolicy) {
                if (previous >= 0 && tag.timelineFrame < previous) ++wraps;
                previous = tag.timelineFrame;
            }
        }
        require(wraps == 2U, ("exact callback owner must physically wrap twice under its requested current loop policy; wraps=" +
            std::to_string(wraps) + " last=" + std::to_string(previous)).c_str());
    };
    try {
        collectTwoWraps(1U, false);
        phase = "direct A/B";
        require(runtime.publishDirectLoopPolicy(1U, owner.selectionToken, owner.generation,
            true, 72'000U, 120'000U, 0U, true) == NativePlaybackPublishStatus::Accepted,
            "playing direct A/B starts inside the selected trim");
        collectTwoWraps(0U, true);
        auto otherHead = repeat.cacheKey;
        otherHead.loopRevision = 2U;
        otherHead.startTimelineFrame = 60'000U;
        otherHead.endTimelineFrameExclusive = 252'000U;
        require(runtime.beginLoopHeadPreparation(otherHead, 1U, repeat.boundaryMode, owner) ==
            LoopHeadPreparationBeginStatus::Accepted &&
            runtime.configureLoopHeadPreparationLane(2U, 0U, kSampleRateHz, 101U) ==
                LoopHeadPreparationConfigureStatus::Accepted,
            "a separately prepared head begins while the accepted Repeat head remains retained");
        for (std::size_t offset = otherHead.startTimelineFrame; offset < otherHead.endTimelineFrameExclusive;) {
            const auto count = std::min<std::size_t>(480U, otherHead.endTimelineFrameExclusive - offset);
            const auto offered = runtime.offerLoopHeadPreparationPcm(2U, 0U,
                {pcm.data() + offset * 2U, count, {kSampleRateHz, 2U, LanePcmEncoding::Float32},
                 101U, offset * 1'000'000U / kSampleRateHz});
            require(offered.inputFramesAccepted == count, "replacement head accepts its exact recording samples");
            lastHeadStatus = offered.status;
            offset += count;
        }
        require(lastHeadStatus == LoopHeadPreparationOfferStatus::CacheCommitted,
            "a new publication cannot silently replace the already accepted Repeat head");
        phase = "Repeat after direct Clear";
        require(runtime.publishDirectLoopPolicy(2U, owner.selectionToken, owner.generation,
            false, 72'000U, 120'000U, 0U, false) == NativePlaybackPublishStatus::Accepted,
            "playing direct A/B Clear is accepted");
        collectTwoWraps(1U, false, true);
        require(checkedSamples > 100'000U && runtime.diagnostics().audibleEnd.sequence == 0U,
            "real source samples and repeated trim callbacks survive Clear without a natural end");
        runtime.requestStop(); runtime.join();
    } catch (const std::exception &error) {
        const auto loop = runtime.loopProtocolSnapshot();
        const auto facts = std::string(phase) + ": " + error.what() + runtimeBoundaryFacts(output, runtime, owner) +
            " managedRevision=" + std::to_string(loop.state.latestRevision) +
            " phase=" + std::to_string(static_cast<unsigned>(loop.state.phase)) +
            " accepted=" + std::to_string(loop.state.cacheAccepted) + " installed=" +
            std::to_string(loop.state.renderInstalled) + " end=" +
            std::to_string(loop.state.resolvedLoopEndTimelineFrameExclusive);
        runtime.requestStop(); runtime.join(); throw std::runtime_error(facts);
    } catch (...) { runtime.requestStop(); runtime.join(); throw; }
}

void unpublishedOriginalRangeCannotOccupyTheIoSlotWithOptionalWork() {
    constexpr SongRenderOwnership owner{0xF1A57U, 2U};
    constexpr std::size_t firstFrames = 1'024U, totalFrames = 8'192U;
    SongLaneIngressBank first(16'384U, 480U), second(256U, 256U);
    for (std::size_t lane = 0U; lane < 3U; ++lane)
        require(first.configureLane(lane, kSampleRateHz, owner.generation), "range admission lane configure");
    const auto offer = [&](std::size_t lane, std::size_t firstFrame, std::size_t count) {
        const std::vector<float> pcm(count * 2U, lane == 0U ? .08F : .02F);
        for (std::size_t offset = 0U; offset < count;) {
            const auto frames = std::min<std::size_t>(480U, count - offset);
            const LaneInputBlock block{pcm.data() + offset * 2U, frames,
                {kSampleRateHz, 2U, LanePcmEncoding::Float32}, owner.generation,
                (firstFrame + offset) * 1'000'000U / kSampleRateHz};
            require(first.lane(lane)->offer(block).inputFramesAccepted == frames,
                "range admission exact decoder input");
            offset += frames;
        }
    };
    offer(0U, 0U, firstFrames);
    ClosureOutput output(3U);
    NativePlaybackRuntime runtime(runtimeConfiguration(), first, second, output.port());
    const std::array<NativePlaybackBankIntent, 2U> banks{
        NativePlaybackBankIntent{1U, true, owner, 0U, 0, 111U, 222U, 333U, 2U, totalFrames}, NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(1U, owner, banks, MixerSourceMode::Original)) ==
        NativePlaybackPublishStatus::Accepted && runtime.start(), "range admission owner starts");
    try {
        // Establish a retained but unpublished Original prefix before either optional producer arrives.
        waitUntil([&] {
            const auto ingress = first.lane(0U)->diagnostics();
            const auto state = runtime.diagnostics();
            return ingress.sourceFramesConsumed == firstFrames && ingress.emptyPulls != 0U &&
                state.banks[0U].lanes[0U].appendedFrames == ingress.outputFramesProduced &&
                state.banks[0U].lanes[0U].retainedSuffixFrames == 0U;
        }, "range admission retains the initial Original prefix");
        const auto baseline = runtime.diagnostics();
        require(output.commitCount() == 0U && output.queuedForGeneration(owner.generation) == 0U,
            "unpublished Original cannot fabricate committed PCM");
        offer(1U, 0U, firstFrames);
        offer(2U, 0U, firstFrames);
        runtime.notifyIngressAvailable();
        waitUntil([&] {
            const auto state = runtime.diagnostics();
            return state.banks[0U].lanes[1U].pulledFrames != 0U &&
                state.banks[0U].lanes[2U].pulledFrames != 0U &&
                state.renderCycles >= baseline.renderCycles + 32U;
        }, "range admission optional PCM reaches its bounded pending slots");
        const auto afterOptional = runtime.diagnostics();
        const auto unexpectedIo = std::string("unpublished Original range must not enqueue known-unready optional I/O") +
            " operations=" + std::to_string(baseline.resourceOperationsCompleted) + "/" +
            std::to_string(afterOptional.resourceOperationsCompleted) + " diskReads=" +
            std::to_string(baseline.canonicalPcmIo.readOperations) + "/" +
            std::to_string(afterOptional.canonicalPcmIo.readOperations);
        require(afterOptional.resourceOperationsCompleted == baseline.resourceOperationsCompleted,
            unexpectedIo.c_str());
        for (std::size_t lane = 0U; lane < 3U; ++lane)
            offer(lane, firstFrames, totalFrames - firstFrames);
        runtime.notifyIngressAvailable();
        waitUntil([&] {
            return output.commitCount() == 1U && output.playing() &&
                output.queuedForGeneration(owner.generation) >= 3U * kCallbackFrames;
        }, "published Original prefix must unblock exact initial handoff");
        const auto callback = nextCallback(output, owner.generation, 64U);
        require(callback.physicalFrames == 64U && output.presentedPosition().generation == owner.generation &&
            output.presentedPosition().selectionToken == owner.selectionToken &&
            runtime.diagnostics().banks[0U].lanes[0U].discontinuityRejects == 0U &&
            !output.invalidFinalSampleSeen(), "retained range admission publishes real exact-owner PCM");
        runtime.requestStop(); runtime.join();
    } catch (const std::exception &error) {
        const auto failure = std::string(error.what()) + runtimeBoundaryFacts(output, runtime, owner);
        runtime.requestStop(); runtime.join();
        throw std::runtime_error(failure);
    } catch (...) { runtime.requestStop(); runtime.join(); throw; }
}

void expectedContinuationCannotSilenceARealTerminalShortage() {
    using namespace stemwise::audio::packet;
    constexpr SongRenderOwnership owner{0xE0F2U, 1U};
    for (const bool loopExpected : {false, true}) {
        auto output = std::make_unique<ClosureOutput>(0U);
        const auto port = output->port();
        const auto tail = corePayload(1U, .08F);
        require(port.enqueue(port.handle, coreSubmission(owner, 1U, tail, 0U, true)) == 1U,
            "expected continuation retains its actual final outgoing frame");
        if (loopExpected) {
            const NativeAudioOutputLoopPolicy demand{.revision=1U, .generation=1U,
                .selectionToken=owner.selectionToken, .enabled=true, .physicalReady=false,
                .aTimelineFrame=0U, .bTimelineFrameExclusive=1U, .boundaryArmedAtPublication=true};
            require(port.publishLoopDemand(port.handle, demand, false, 0U), "expected loop demand accepted");
            // Even authorization cannot override the callback's exact outstanding loop demand.
            port.setTerminalSilenceAuthorization(port.handle, owner.generation, true);
        } else {
            require(port.beginDrainHandoff(port.handle, owner.generation, 2U, 1U), "expected successor stages");
            port.setTerminalSilenceAuthorization(port.handle, owner.generation, false);
        }
        require(port.setPlaying(port.handle, true), "expected continuation starts real callback");
        const auto record = measuredCallback(*output, 256U);
        if (loopExpected) {
            require(record.physicalFrames == 1U && output->presentedPosition().lastTimelineFrame == 0 &&
                output->terminalHoldGeneration() == 0U &&
                output->diagnostics()[OUTPUT_DIAGNOSTICS_UNDERRUN_CALLBACK_COUNT] == 1 &&
                output->diagnostics()[OUTPUT_DIAGNOSTICS_UNDERRUN_FRAMES] == 255,
                "an outstanding loop rejects terminal padding and reports every actually prepared missing frame");
        } else {
            require(record.physicalFrames == 0U && !output->presentedPosition().valid() && output->pushSubmittedFrames() == 0U &&
                output->queuedForGeneration(owner.generation) == 1U && output->terminalHoldGeneration() == 0U &&
                output->diagnostics()[OUTPUT_DIAGNOSTICS_UNDERRUN_CALLBACK_COUNT] == 0,
                "a nonterminal successor shortage waits without inventing acceptance or terminal silence");
        }
    }
}

void clearingALoopAtActualEofRetainsTheTerminalCallbackBoundary() {
    using namespace stemwise::audio::engine;
    constexpr SongRenderOwnership owner{0xE0F1U, 2U};
    constexpr std::size_t frames = 8'192U, callbackFrames = 256U;
    SongLaneIngressBank first(16'384U, 480U), second(256U, 256U);
    require(first.configureLane(0U, kSampleRateHz, owner.generation), "EOF Clear Original configured");
    const std::vector<float> pcm(frames * 2U, .08F);
    for (std::size_t offset = 0U; offset != frames;) {
        const auto count = std::min<std::size_t>(480U, frames - offset);
        require(first.lane(0U)->offer({pcm.data() + offset * 2U, count,
            {kSampleRateHz, 2U, LanePcmEncoding::Float32}, owner.generation,
            offset * 1'000'000U / kSampleRateHz}).inputFramesAccepted == count, "EOF Clear real decoder input");
        offset += count;
    }
    require(first.lane(0U)->markEndOfStream(owner.generation, frames).status == LaneEndOfStreamStatus::Accepted,
        "EOF Clear real decoder EOF");
    auto storage = std::make_unique<ClosureOutput>(0U, kOutputCapacityFrames, 1U, 256, 512);
    auto &output = *storage;
    auto configuration = runtimeConfiguration();
    configuration.pipeline.maximumBlockFrames = callbackFrames;
    configuration.minimumOutputReserveMicros = NativePlaybackRuntimeConfiguration::kDefaultOutputReserveMicros;
    NativePlaybackRuntime runtime(configuration, first, second, output.port());
    const LoopHeadContentKey headKey{111U, 222U, 0U, 1U, 1U, 0U, LoopHeadCache::kCapacityFrames};
    require(runtime.beginLoopHeadPreparation(headKey, 1U, LoopHeadBoundaryMode::OriginalEos, owner) ==
        LoopHeadPreparationBeginStatus::Accepted, "EOF Clear exact finite head begins");
    require(runtime.configureLoopHeadPreparationLane(1U, 0U, kSampleRateHz, 100U) ==
        LoopHeadPreparationConfigureStatus::Accepted, "EOF Clear head decoder configures");
    for (std::size_t offset = 0U; offset != frames;) {
        const auto count = std::min<std::size_t>(480U, frames - offset);
        const auto offered = runtime.offerLoopHeadPreparationPcm(1U, 0U,
            {pcm.data() + offset * 2U, count, {kSampleRateHz, 2U, LanePcmEncoding::Float32}, 100U,
                offset * 1'000'000U / kSampleRateHz});
        require(offered.inputFramesAccepted != 0U, "EOF Clear head consumes actual known PCM");
        offset += offered.inputFramesAccepted;
    }
    require(runtime.markLoopHeadPreparationEndOfStream(1U, 0U, 100U, frames) ==
        LoopHeadPreparationEndStatus::CacheCommitted, "EOF Clear exact head EOF commits");
    const std::array<NativePlaybackBankIntent, 2U> banks{
        NativePlaybackBankIntent{1U, true, owner, 0U, 0, 111U, 222U, 0U, 0U, frames}, NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(1U, owner, banks, MixerSourceMode::Original)) ==
        NativePlaybackPublishStatus::Accepted && runtime.start(), "EOF Clear runtime starts");
    try {
        static_cast<void>(nextCallback(output, owner.generation, callbackFrames));
        waitUntil([&] { return output.playing() && output.queuedForGeneration(owner.generation) >= 2'400U; },
            "EOF Clear live PCM ready");
        require(runtime.publishDirectLoopPolicy(1U, owner.selectionToken, owner.generation,
            true, 4'096U, frames, 0U, true) == NativePlaybackPublishStatus::Accepted, "EOF loop accepted");
        waitUntil([&] { return runtime.renderReadinessDiagnostics().fixedLoopHeadPrepared; }, "EOF loop head prepared");
        bool cleared = false;
        std::size_t finalFramesSeen = 0U;
        std::size_t wraps = 0U;
        std::int64_t previous = -1;
        auto origin = std::chrono::steady_clock::now();
        std::size_t clearTicks = 0U;
        for (std::size_t tick = 0U; tick != 160U; ++tick) {
            if (cleared) std::this_thread::sleep_until(origin + std::chrono::nanoseconds(
                (++clearTicks) * callbackFrames * 1'000'000'000ULL / kSampleRateHz));
            // Setup may wait for the two prior wraps; every callback after Clear follows only its clock.
            const auto record = cleared ? measuredCallback(output, callbackFrames) : nextCallback(output, owner.generation, callbackFrames);
            const auto position = output.presentedPosition();
            require(output.diagnostics()[stemwise::audio::packet::OUTPUT_DIAGNOSTICS_UNDERRUN_CALLBACK_COUNT] == 0,
                ("EOF Clear callback shortage tick=" + std::to_string(tick) + " cleared=" + std::to_string(cleared) +
                    " physical=" + std::to_string(record.physicalFrames) + " position=" + std::to_string(position.lastTimelineFrame) +
                    " terminal=" + std::to_string(position.terminalAfterFrame) + " hold=" + std::to_string(output.terminalHoldGeneration()) +
                    " authorization=" + std::to_string(output.terminalAuthorizationGeneration())).c_str());
            if (!cleared && position.loopPolicyRevision != 0U && previous >= 0 && position.lastTimelineFrame < previous) ++wraps;
            previous = position.lastTimelineFrame;
            if (!cleared && wraps >= 2U) {
                require(output.terminalAuthorizationGeneration() == 0U,
                    "the installed enabled loop must retain its natural-end fence");
                require(runtime.publishDirectLoopPolicy(2U, owner.selectionToken, owner.generation,
                    false, 4'096U, frames, 0U, false) == NativePlaybackPublishStatus::Accepted, "EOF loop Clear accepted");
                cleared = true;
                origin = std::chrono::steady_clock::now();
            }
            if (cleared && record.physicalFrames != 0U && position.generation == owner.generation &&
                position.selectionToken == owner.selectionToken && position.loopPolicyAdoptedRevision == 2U &&
                !position.loopPolicyAdoptedEnabled && position.terminalAfterFrame &&
                position.lastTimelineFrame == static_cast<std::int64_t>(frames - 1U)) ++finalFramesSeen;
            if (cleared && output.terminalHoldGeneration() == owner.generation) break;
        }
        const auto end = output.presentedPosition();
        require(cleared && finalFramesSeen == 1U && output.terminalHoldGeneration() == owner.generation,
            ("EOF Clear must present its final real frame and latch exact terminal hold cleared=" + std::to_string(cleared) +
                " final=" + std::to_string(finalFramesSeen) + " position=" + std::to_string(end.lastTimelineFrame) +
                " physical=" + std::to_string(end.loopPolicyRevision) + " logical=" + std::to_string(end.loopPolicyAdoptedRevision) +
                " terminal=" + std::to_string(end.terminalAfterFrame) + " authorization=" + std::to_string(output.terminalAuthorizationGeneration()) +
                " hold=" + std::to_string(output.terminalHoldGeneration())).c_str());
        for (unsigned tick = 0U; tick != 4U; ++tick) {
            require(measuredCallback(output, callbackFrames).physicalFrames == 0U &&
                output.presentedPosition().lastTimelineFrame == static_cast<std::int64_t>(frames - 1U),
                "terminal hold must neither duplicate final PCM nor advance the source");
        }
        require(output.diagnostics()[stemwise::audio::packet::OUTPUT_DIAGNOSTICS_UNDERRUN_CALLBACK_COUNT] == 0,
            ("EOF Clear terminal callbacks must not owe nonexistent PCM authorization=" +
                std::to_string(output.terminalAuthorizationGeneration()) + " hold=" + std::to_string(output.terminalHoldGeneration())).c_str());
        runtime.requestStop(); runtime.join();
    } catch (const std::exception &error) {
        const auto facts = std::string(error.what()) + runtimeBoundaryFacts(output, runtime, owner);
        runtime.requestStop(); runtime.join(); throw std::runtime_error(facts);
    } catch (...) { runtime.requestStop(); runtime.join(); throw; }
}

void longRecordingPrefixSupportsShortLiveLoopsOnActualCallback(bool verifyPhysicalPolicy = false) {
    constexpr SongRenderOwnership owner{0x10A6U, 2U};
    constexpr std::size_t prefixFrames = 32'768U;
    SongLaneIngressBank first(65'536U, 480U), second(256U, 256U);
    for (std::size_t lane = 0U; lane < 3U; ++lane) {
        require(first.configureLane(lane, kSampleRateHz, owner.generation), "long prefix lane configure");
        auto pcm = std::vector<float>(prefixFrames * 2U, .02F * static_cast<float>(lane + 1U));
        for (std::size_t offset = 0U; offset < prefixFrames;) {
            const auto count = std::min<std::size_t>(480U, prefixFrames - offset);
            const LaneInputBlock block{pcm.data() + offset * 2U, count,
                {kSampleRateHz, 2U, LanePcmEncoding::Float32}, owner.generation,
                offset * 1'000'000U / kSampleRateHz};
            require(first.lane(lane)->offer(block).inputFramesAccepted == count, "long prefix exact offer");
            offset += count;
        }
        // The rest of the ten-minute recording is deliberately not decoded, and no EOF is sent.
    }
    ClosureOutput output(3U);
    auto configuration = runtimeConfiguration();
    configuration.pipeline.laneReservoirCapacityFrames = 65'536U;
    NativePlaybackRuntime runtime(configuration, first, second, output.port());
    if (verifyPhysicalPolicy) runtime.setDiagnosticPayloadFingerprints(true);
    const std::array<NativePlaybackBankIntent, 2U> banks{
        NativePlaybackBankIntent{1U, true, owner, 0U, 0, 111U, 222U, 333U, 2U, 600U * kSampleRateHz},
        NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(1U, owner, banks, MixerSourceMode::Stems)) ==
        NativePlaybackPublishStatus::Accepted, "long recording source selected");
    require(runtime.start(), "long recording runtime starts");
    try {
        static_cast<void>(nextCallback(output, owner.generation, 64U));
        require(runtime.publishDirectLoopPolicy(1U, owner.selectionToken, owner.generation,
            true, 0U, 4'800U, 0U, true) == NativePlaybackPublishStatus::Accepted, "100ms prefix loop accepted");
        waitUntil([&] {
            return runtime.renderReadinessDiagnostics().fixedLoopHeadPrepared;
        }, "100ms loop physical continuation ready without recording EOF");
        bool wrapped = false;
        std::int64_t previous = -1;
        for (std::size_t block = 0U; block < 160U; ++block) {
            const auto frames = std::array<std::size_t, 5U>{64U, 96U, 53U, 127U, 32U}[block % 5U];
            static_cast<void>(nextCallback(output, owner.generation, frames));
            const auto position = output.presentedPosition();
            require(position.generation == owner.generation && position.selectionToken == owner.selectionToken,
                "short loop never substitutes callback owner");
            if (position.loopPolicyAdoptedEnabled && position.loopPolicyAdoptedRevision == 1U &&
                previous >= 0 && position.lastTimelineFrame < previous) wrapped = true;
            previous = position.lastTimelineFrame;
        }
        require(wrapped && output.presentedPosition().loopPolicyAdoptedBFrameExclusive == 4'800U,
            "actual callback wraps 100ms range without unrelated recording EOF");
        waitUntil([&] {
            const auto ready = runtime.renderReadinessDiagnostics();
            return ready.currentSongCacheBoundToSelectedOwner && ready.currentSongCacheCompleteLaneMask == 0U;
        }, "short-loop readiness never fabricates complete long-recording cache");
        if (verifyPhysicalPolicy) {
            auto tuned = selectedCommand(2U, owner, banks, MixerSourceMode::Stems);
            tuned.controls.tempo = 1.35F;
            tuned.controls.pitchSemitones = 7.0F;
            require(runtime.publishCommand(tuned) == NativePlaybackPublishStatus::Accepted,
                "same logical loop accepts fractional tuning");
            for (unsigned block = 0U; block != 256U && output.presentedPosition().loopPolicyRevision < 3U; ++block)
                static_cast<void>(nextCallback(output, owner.generation, 256U));
            require(output.presentedPosition().loopPolicyRevision >= 3U &&
                output.presentedPosition().loopPolicyAdoptedRevision == 1U,
                "real Runtime cycles advance physical ownership without changing logical A/B");
            tuned.revision = 3U;
            tuned.controls = controls(3U, MixerSourceMode::Stems);
            require(runtime.publishCommand(tuned) == NativePlaybackPublishStatus::Accepted,
                "same logical loop returns to unity");
            const auto afterTuning = output.presentationJournal(0U).writeSequence;
            for (unsigned block = 0U; block != 160U; ++block)
                static_cast<void>(nextCallback(output, owner.generation, 256U));
            const auto journal = output.presentationJournal(afterTuning);
            require(journal.coherent && journal.overflowCount == 0U && !journal.entries.empty(),
                "physical loop authority is checked through actual consumed callback spans");
            for (const auto &span : journal.entries) require(span.continuityViolationCount == 0U,
                ("same logical loop must validate the callback-adopted physical revision=" +
                    std::to_string(span.firstLoopPolicyRevision) + " source=" + std::to_string(span.firstTimelineFrame)).c_str());
            runtime.requestStop(); runtime.join();
            return;
        }
        for (std::uint64_t revision = 2U; revision <= 4U; ++revision)
            require(runtime.publishDirectLoopPolicy(revision, owner.selectionToken, owner.generation,
                true, 0U, revision == 4U ? 4'800U : 4'800U + revision * 480U, 0U, true) ==
                    NativePlaybackPublishStatus::Accepted, "rapid B edit accepted");
        for (unsigned block = 0U; block < 100U; ++block)
            static_cast<void>(nextCallback(output, owner.generation, 64U));
        require(output.presentedPosition().loopPolicyAdoptedRevision == 4U &&
            output.presentedPosition().loopPolicyAdoptedBFrameExclusive == 4'800U &&
            !output.invalidFinalSampleSeen(), "latest B wins with finite exact-owner callback PCM");
        require(output.diagnostics()[stemwise::audio::packet::OUTPUT_DIAGNOSTICS_UNDERRUN_CALLBACK_COUNT] == 0,
            "range preparation and rapid edits do not starve actual callback");
        runtime.requestStop(); runtime.join();
    } catch (const std::exception &error) {
        const auto readiness = runtime.renderReadinessDiagnostics();
        const auto position = output.presentedPosition();
        const auto boundaryFacts = runtimeBoundaryFacts(output, runtime, owner);
        runtime.requestStop(); runtime.join();
        throw std::runtime_error(std::string(error.what()) + " bound=" +
            std::to_string(readiness.currentSongCacheBoundToSelectedOwner) + " mask=" +
            std::to_string(readiness.currentSongCacheCompleteLaneMask) + " status=" +
            std::to_string(static_cast<unsigned>(readiness.cacheStorageStatus)) + " prepared=" +
            std::to_string(readiness.fixedLoopHeadPrepared) + " position=" +
            std::to_string(position.lastTimelineFrame) + " policy=" +
            std::to_string(position.loopPolicyAdoptedRevision) + boundaryFacts);
    }
}

[[nodiscard]] std::string runDigestSuite() {
    outputOwnerRestampsRuntimeLocalControlSequencesWithoutWeakeningOwnership();
    physicalPauseDoesNotBecomeALateCallbackGap();
    consumedFractionalBoundaryMappingRetainsOriginalAndPartialReceiptOrigins();
    qualificationDiagnosticsAreDefaultOffWithoutChangingPcm();
    managedLoopSubmissionRequiresItsExactInstalledContext();
    repeatOnePublishesActualCallbackCycleEvidence();
    longRecordingPrefixSupportsShortLiveLoopsOnActualCallback();
    callbackDiagnosticsExposeActualFailuresAndSanitation();
    rejectedPcmFadesWithoutAcquiringPresentationAuthority();
    callbackCoreCutsOverInsideCallbackAndPreservesPauseSeekOwnership();
    queuedNumericalTuningCannotBeAcknowledgedByANewerLateMixRevision();
    publishedPitchReachesExactOwnerCallbackPcm();
    Digest digest;
    std::uint32_t cells = 0U;
    for (std::uint32_t direction = 0U; direction < 2U; ++direction) {
        for (std::uint32_t boundary = 0U; boundary < 3U; ++boundary) {
            for (const std::uint32_t configuration : kConfigurations) {
                try {
                    runCell(direction, boundary, configuration, digest);
                } catch (const std::exception &error) {
                    throw std::runtime_error(
                        "matrix cell direction=" + std::to_string(direction) +
                        " boundary=" + std::to_string(boundary) +
                        " mask=" + std::to_string(configuration) +
                        ": " + error.what());
                }
                ++cells;
            }
        }
    }
    require(cells == 48U, "matrix did not execute exactly 48 cells");
    runCell(0U, 0U, stemwise::audio::kStemwiseAllStemBusesReadyMask, digest, 0U);
    ++cells;
    for (const std::size_t count : {2U, 6U, 9U}) {
        for (std::uint32_t direction = 0U; direction != 2U; ++direction) {
            // Offer undeclared lanes too: the selected bank declaration alone owns audibility.
            runCell(direction, 0U, stemwise::audio::kStemwiseAllStemBusesReadyMask, digest, count, count == 9U);
            ++cells;
        }
    }
    for (std::uint32_t direction = 0U; direction != 2U; ++direction) {
        runCell(direction, 0U, 1U << 8U, digest, 9U);
        ++cells;
    }
    require(cells == 57U, "dynamic closure matrix omitted a declared-lane or partial-package cell");
    provePartialMasksAreDownstreamRobustnessOnly(digest);
    proveWrongAndStaleOwnershipIsRejected(digest);
    return "DIGEST=" + digest.hex() + ";CELLS=57";
}

#ifdef _WIN32
[[nodiscard]] std::string currentExecutablePath() {
    std::array<char, 32'768U> path{};
    const DWORD length = GetModuleFileNameA(
        nullptr,
        path.data(),
        static_cast<DWORD>(path.size()));
    if (length == 0U || length >= path.size()) {
        throw std::runtime_error("GetModuleFileNameA failed");
    }
    return std::string(path.data(), length);
}

[[nodiscard]] std::string runFreshChild(const std::string &executable) {
    return stemwise::test::runChild(executable, "--digest");
}
#else
[[nodiscard]] std::string currentExecutablePath(const char *argumentZero) {
    return argumentZero == nullptr ? std::string{} : std::string(argumentZero);
}

[[nodiscard]] std::string runFreshChild(const std::string &executable) {
    return stemwise::test::runChild(executable, "--digest");
}
#endif

}  // namespace

int main(int argc, char **argv) {
    try {
        if (argc == 2 && std::string(argv[1]) == "--negative-push-contracts") {
            pushSinkCannotPublishUnacceptedPreparedPcm();
            return 0;
        }
        if (argc == 2 && std::string(argv[1]) == "--negative-consumer-lifetime") {
            physicallyStoppedPushPrefillStillOwnsItsExecutingConsumer();
            return 0;
        }
        if (argc == 2 && std::string(argv[1]) == "--managed-loop-continuity") {
            managedLoopSubmissionRequiresItsExactInstalledContext();
            repeatOnePublishesActualCallbackCycleEvidence();
            trimmedRepeatResumesAfterPlayingDirectLoopClear();
            return 0;
        }
        if (argc == 2 && std::string(argv[1]) == "--callback-timing") {
            physicalPauseDoesNotBecomeALateCallbackGap();
            return 0;
        }
        if (argc == 2 && std::string(argv[1]) == "--inactive-bank-loop-reuse") {
            inactiveBankLoopCannotStarveReusedCallbackOwner();
            return 0;
        }
        if (argc == 2 && std::string(argv[1]) == "--loop-clear-eof") {
            clearingALoopAtActualEofRetainsTheTerminalCallbackBoundary();
            expectedContinuationCannotSilenceARealTerminalShortage();
            return 0;
        }
        if (argc == 2 && std::string(argv[1]) == "--loop-physical-policy") {
            installedPhysicalLoopAuthorityCannotRegressToAnOlderInPlacePolicy();
            fragmentedPreparedTraversalRetainsEveryActualPcmBlock();
            headOnlyLoopAdoptionReconcilesItsLaterCanonicalReader();
            adoptedShortTunedLoopWaitsForItsNextPreparedContinuation();
            adoptedShortTunedLoopWaitsForItsNextPreparedContinuation(true);
            longRecordingPrefixSupportsShortLiveLoopsOnActualCallback(true);
            return 0;
        }
        if (argc == 2 && std::string(argv[1]) == "--callback-continuity") {
            rejectedPcmFadesWithoutAcquiringPresentationAuthority();
            return 0;
        }
        if (argc == 2 && std::string(argv[1]) == "--source-mapping") {
            consumedFractionalBoundaryMappingRetainsOriginalAndPartialReceiptOrigins();
            return 0;
        }
        if (argc == 2 && std::string(argv[1]) == "--source-transition-cursor") {
            sameSongReplacementFencesTheFinalPartialReceiptBeforeChoosingItsCursor();
            pushPartialAcceptanceKeepsTheExactPhysicalLoopBoundary(true);
            return 0;
        }
        if (argc == 2 && std::string(argv[1]) == "--numerical-tuning") {
            queuedNumericalTuningCannotBeAcknowledgedByANewerLateMixRevision();
            return 0;
        }
        if (argc == 2 && std::string(argv[1]) == "--cold-original-preroll") {
            coldOriginalPrerollPreservesTheRequestedSignalWithoutWaivingGaps();
            return 0;
        }
        if (argc == 2 && std::string(argv[1]) == "--push-output-acceptance") {
            oneFrameRuntimePrefillKeepsTheWholeRequiredReserve();
            pushPreparationWaitsForTheCompleteSourceBlock();
            pushPreparationWaitsBeforeAFutureLoopBoundary();
            pushPreparationWaitsForAnAdequateLoopHeadAndPreservesTerminalClear();
            pushDrainedSuccessorWaitsForItsActualSourceSuffix();
            shortAutomaticSuccessorEndsWithoutInventingPresentation();
            shortAutomaticSuccessorEndsWithoutInventingPresentation(true);
            shortAutomaticSuccessorEndsWithoutInventingPresentation(true, true);
            shortAutomaticSuccessorEndsWithoutInventingPresentation(true, true, true);
            lateEmptyEosAfterAcceptedSourceRetainsItsExactProof(false);
            lateEmptyEosAfterAcceptedSourceRetainsItsExactProof(true);
            lateEmptyEosAfterAcceptedSourceRetainsItsExactProof(false, true);
            lateEmptyEosAfterAcceptedSourceRetainsItsExactProof(true, true);
            hardwareCallbackReceivesLateEosWithoutReplayingItsLastFrame();
            persistentLogicalPauseHoldsItsUnacceptedSourceSuffix(false);
            persistentLogicalPauseHoldsItsUnacceptedSourceSuffix(true);
            persistentAcceptedEdgesRampWithoutAdvancingTheHeldSource();
            recreatedPhysicalOutputCannotReuseRetiredAcceptedEdges();
            fullyAcceptedBlockResumesFromAcceptedSilence();
            persistentSourceTransitionPreservesAcceptedTailAndRejectsStaleCompletion();
            sameSongReplacementFencesTheFinalPartialReceiptBeforeChoosingItsCursor();
            pushEnvelopeWaitsForTheExactSourceFlush();
            pushSinkCannotPublishUnacceptedPreparedPcm();
            pushPrefillEndAndHistoryRequireActualPresentation();
            persistentFiniteSourcePrefillsTheFullSinkThroughAuthorizedEofBlocks();
            pushPartialWriteSplitsOwnersAndPreservesPauseUntilAuthorizedReset();
            pushPartialAcceptanceKeepsTheExactPhysicalLoopBoundary();
            physicallyStoppedPushPrefillStillOwnsItsExecutingConsumer();
            persistentTerminalSilenceCannotConcealMissingRequiredSource();
            return 0;
        }
        if (argc == 2 && std::string(argv[1]) == "--late-empty-eos") {
            shortAutomaticSuccessorEndsWithoutInventingPresentation(true, true);
            shortAutomaticSuccessorEndsWithoutInventingPresentation(true, true, true);
            lateEmptyEosAfterAcceptedSourceRetainsItsExactProof(false);
            lateEmptyEosAfterAcceptedSourceRetainsItsExactProof(true);
            lateEmptyEosAfterAcceptedSourceRetainsItsExactProof(false, true);
            lateEmptyEosAfterAcceptedSourceRetainsItsExactProof(true, true);
            hardwareCallbackReceivesLateEosWithoutReplayingItsLastFrame();
            return 0;
        }
        if (argc == 2 && std::string(argv[1]) == "--qualification-diagnostics") {
            failedQualificationAllocationKeepsTheJournalIncoherent();
            oneFrameRuntimePrefillKeepsTheWholeRequiredReserve();
            pushMetadataAllocationAndPreparationCost();
            outputOwnerRestampsRuntimeLocalControlSequencesWithoutWeakeningOwnership();
            qualificationDiagnosticsAreDefaultOffWithoutChangingPcm();
            return 0;
        }
        if (argc == 2 && std::string(argv[1]) == "--unpublished-original-range") {
            unpublishedOriginalRangeCannotOccupyTheIoSlotWithOptionalWork();
            return 0;
        }
        if (argc == 2 && std::string(argv[1]) == "--callback-owner-retirement") {
            rapidAlternatingExpansionCutsTheActualCallbackBeforeTheQueuedOldA();
            activePolicyRetargetCannotRetireAnExactPublishedContinuation();
            rejectedLoopDemandReleasesTheActualCallbackFence();
            exactCaptureCanClaimASameRevisionSpareAfterANaturalWrap();
            pendingCaptureCannotAcknowledgeAnOlderExpansionBody();
            callbackRoleAcknowledgementPrimesTheNextABeforeHardwarePresentation();
            return 0;
        }
        if (argc == 2 && std::string(argv[1]) == "--digest") {
            std::cout << runDigestSuite() << '\n';
            return 0;
        }
#ifdef _WIN32
        const std::string executable = currentExecutablePath();
#else
        const std::string executable = currentExecutablePath(argc == 0 ? nullptr : argv[0]);
#endif
        require(!executable.empty(), "current executable path is empty");
        const std::string first = runFreshChild(executable);
        const std::string second = runFreshChild(executable);
        require(first.rfind("DIGEST=", 0U) == 0U && first.find(";CELLS=57") != std::string::npos,
                "first process did not return the sealed matrix digest");
        require(first == second, "two fresh processes produced different PCM digests");
        std::cout << "PASS: native playback PCM closure " << first << '\n';
        return 0;
    } catch (const std::exception &error) {
        allocation_probe::enabled = false;
        std::cerr << "FAIL: " << error.what() << '\n';
        return 1;
    }
}
