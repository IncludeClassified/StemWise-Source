#include "NativeAudioOutputCallbackCore.h"

namespace stemwise::audio {

std::size_t enqueueRenderBusTimeline(
    NativeAudioOutputHandle handle,
    const NativeRenderBusSubmission &submission) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    return engine == nullptr ? 0U : engine->enqueueRenderBusTimeline(submission);
}

bool publishNativeAudioOutputLateMixControl(
    NativeAudioOutputHandle handle,
    const NativeAudioLateMixControl &control) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    return engine != nullptr && engine->publishLateMixControl(control);
}

bool publishNativeAudioOutputLoopPolicy(
    NativeAudioOutputHandle handle,
    const NativeAudioOutputLoopPolicy &policy) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    return engine != nullptr && engine->publishLoopPolicy(policy);
}

bool publishNativeAudioOutputLoopDemand(
    NativeAudioOutputHandle handle,
    const NativeAudioOutputLoopPolicy &policy,
    bool boundaryCrossedAtPublication,
    std::uint64_t capturedBTimelineFrameExclusive) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    return engine != nullptr &&
        engine->publishLoopDemand(
            policy,
            boundaryCrossedAtPublication,
            capturedBTimelineFrameExclusive);
}

void retireNativeAudioOutputLoopDemand(NativeAudioOutputHandle handle,
    std::uint64_t revision, std::uint64_t generation, std::uint64_t selectionToken) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    if (engine) engine->retireLoopDemand(revision, generation, selectionToken);
}

NativeAudioPendingLoopCapture nativeAudioOutputPendingLoopCapture(
    NativeAudioOutputHandle handle, const NativeAudioOutputLoopPolicy &demand) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    return engine == nullptr ? NativeAudioPendingLoopCapture{} : engine->pendingLoopCapture(demand);
}

NativeAudioLoopBoundaryCoverage nativeAudioOutputLoopBoundaryCoverage(
    NativeAudioOutputHandle handle,
    std::uint64_t generation,
    std::uint64_t selectionToken,
    std::uint64_t cutoverTimelineFrameExclusive,
    std::uint64_t expectedLoopPolicyRevision) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    return engine == nullptr
        ? NativeAudioLoopBoundaryCoverage{}
        : engine->loopBoundaryCoverage(
            generation,
            selectionToken,
            cutoverTimelineFrameExclusive,
            expectedLoopPolicyRevision);
}

bool beginNativeAudioOutputLoopCutover(
    NativeAudioOutputHandle handle,
    std::uint64_t generation,
    std::uint64_t loopPolicyRevision) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    return engine != nullptr && engine->beginLoopCutover(generation, loopPolicyRevision);
}

NativeAudioDrainCancelStatus cancelNativeAudioOutputLoopCutover(
    NativeAudioOutputHandle handle,
    std::uint64_t generation,
    std::uint64_t loopPolicyRevision) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    return engine == nullptr
        ? NativeAudioDrainCancelStatus::NoMatchingDrain
        : engine->cancelLoopCutover(generation, loopPolicyRevision);
}

std::size_t enqueueNativeAudioOutputLoopCutoverTimeline(
    NativeAudioOutputHandle handle,
    const NativeRenderBusSubmission &submission) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    return engine == nullptr ? 0U : engine->enqueueLoopCutoverTimeline(submission);
}

NativeAudioLoopCutoverCommitStatus commitNativeAudioOutputLoopCutover(
    NativeAudioOutputHandle handle,
    const NativeAudioOutputLoopPolicy &policy) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    return engine == nullptr
        ? NativeAudioLoopCutoverCommitStatus::Retired
        : engine->commitLoopCutover(policy);
}

bool releaseNativeAudioOutputLoopCutoverWrites(
    NativeAudioOutputHandle handle,
    std::uint64_t generation,
    std::uint64_t loopPolicyRevision) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    return engine != nullptr &&
        engine->releaseLoopCutoverWrites(generation, loopPolicyRevision);
}

bool beginNativeAudioOutputHandoff(
    NativeAudioOutputHandle handle,
    std::uint64_t generation) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    return engine != nullptr && engine->beginHandoff(generation);
}

bool commitNativeAudioOutputHandoff(
    NativeAudioOutputHandle handle,
    std::uint64_t generation) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    return engine != nullptr && engine->commitHandoff(generation);
}

bool beginNativeAudioOutputDrainHandoff(
    NativeAudioOutputHandle handle,
    std::uint64_t expectedActiveGeneration,
    std::uint64_t stagedGeneration,
    std::uint64_t transitionRevision) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    return engine != nullptr && engine->beginDrainHandoff(
        expectedActiveGeneration,
        stagedGeneration,
        transitionRevision);
}

bool commitNativeAudioOutputDrainHandoff(
    NativeAudioOutputHandle handle,
    std::uint64_t expectedActiveGeneration,
    std::uint64_t stagedGeneration,
    std::uint64_t transitionRevision) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    return engine != nullptr && engine->commitDrainHandoff(
        expectedActiveGeneration,
        stagedGeneration,
        transitionRevision);
}

NativeAudioDrainCancelStatus cancelNativeAudioOutputDrainHandoff(
    NativeAudioOutputHandle handle,
    std::uint64_t expectedActiveGeneration,
    std::uint64_t stagedGeneration,
    std::uint64_t transitionRevision) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    return engine != nullptr
        ? engine->cancelDrainHandoff(
              expectedActiveGeneration,
              stagedGeneration,
              transitionRevision)
        : NativeAudioDrainCancelStatus::NoMatchingDrain;
}

NativeAudioFlushStatus flushNativeAudioOutputStatus(
    NativeAudioOutputHandle handle,
    std::uint64_t generation) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    return engine == nullptr
        ? NativeAudioFlushStatus::InvalidState
        : engine->flushToGenerationStatus(generation);
}

void setNativeAudioOutputDiagnosticPayloadFingerprints(
    NativeAudioOutputHandle handle, bool enabled) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    if (engine != nullptr) engine->setDiagnosticPayloadFingerprints(enabled);
}

bool setNativeAudioOutputPlaying(
    NativeAudioOutputHandle handle,
    bool playing) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    return engine != nullptr && engine->setPlaying(playing);
}

bool quiesceNativeAudioOutputIfPaused(NativeAudioOutputHandle handle) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    return engine != nullptr && engine->quiesceIfPaused();
}

void setNativeAudioOutputTerminalSilenceAuthorization(
    NativeAudioOutputHandle handle,
    std::uint64_t generation,
    bool authorized) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    if (engine != nullptr) {
        engine->setTerminalSilenceAuthorization(generation, authorized);
    }
}

bool publishNativeAudioOutputTerminalBoundary(NativeAudioOutputHandle handle,
    std::uint64_t generation, std::uint64_t selectionToken, std::uint64_t physicalRevision,
    double sourceEndFrameExclusive) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    return engine != nullptr && engine->publishTerminalBoundary(
        generation, selectionToken, physicalRevision, sourceEndFrameExclusive);
}

std::size_t nativeAudioOutputQueuedFrames(NativeAudioOutputHandle handle) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    return engine == nullptr ? 0U : engine->queuedFrames();
}

std::size_t nativeAudioOutputWritableFrames(NativeAudioOutputHandle handle) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    return engine == nullptr ? 0U : engine->writableFrames();
}

std::size_t nativeAudioOutputQueuedFramesForGeneration(
    NativeAudioOutputHandle handle,
    std::uint64_t generation) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    return engine == nullptr ? 0U : engine->queuedFramesForGeneration(generation);
}

std::size_t nativeAudioOutputWritableFramesForGeneration(
    NativeAudioOutputHandle handle,
    std::uint64_t generation) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    return engine == nullptr ? 0U : engine->writableFramesForGeneration(generation);
}

std::int32_t nativeAudioOutputFramesPerBurst(NativeAudioOutputHandle handle) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    return engine == nullptr ? 0 : engine->framesPerBurst();
}

std::size_t nativeAudioOutputStableBufferFrames(NativeAudioOutputHandle handle) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    return engine == nullptr ? 0U : engine->stableBufferFrames();
}

std::size_t nativeAudioOutputLoopCutoverRequiredFrames(
    NativeAudioOutputHandle handle) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    return engine == nullptr ? 0U : engine->loopCutoverRequiredFrames();
}

NativeAudioPresentationEvidence nativeAudioOutputPresentationEvidence(
    NativeAudioOutputHandle handle) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    return engine == nullptr
        ? NativeAudioPresentationEvidence{}
        : engine->presentationEvidence();
}

NativeAudioPresentedPosition nativeAudioOutputPresentedPosition(
    NativeAudioOutputHandle handle) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    return engine == nullptr
        ? NativeAudioPresentedPosition{}
        : engine->presentedPosition();
}

NativeAudioAudiblePosition nativeAudioOutputAudiblePosition(
    NativeAudioOutputHandle handle) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    return engine == nullptr ? NativeAudioAudiblePosition{} : engine->audiblePosition();
}

NativeAudioAppliedControlEvidence nativeAudioOutputAppliedControlEvidence(
    NativeAudioOutputHandle handle) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    return engine == nullptr
        ? NativeAudioAppliedControlEvidence{}
        : engine->appliedControlEvidence();
}

NativeAudioStemGainCallbackEvidence nativeAudioOutputStemGainCallbackEvidence(
    NativeAudioOutputHandle handle) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    return engine == nullptr
        ? NativeAudioStemGainCallbackEvidence{}
        : engine->stemGainCallbackEvidence();
}

NativeAudioAudibleLoopEvidence nativeAudioOutputAudibleLoopEvidence(
    NativeAudioOutputHandle handle) noexcept {
    auto *engine = static_cast<NativeAudioOutputCallbackCore *>(handle);
    return engine == nullptr
        ? NativeAudioAudibleLoopEvidence{}
        : engine->audibleLoopEvidence();
}

} // namespace stemwise::audio
