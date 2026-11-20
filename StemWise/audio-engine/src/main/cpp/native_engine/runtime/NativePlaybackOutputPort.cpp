#include "NativePlaybackRuntime.h"

namespace stemwise::audio::engine {
NativePlaybackOutputPort borrowNativeAudioOutputPort(
    NativeAudioOutputHandle handle) noexcept {
    return {
        .handle = handle,
        .enqueue = &enqueueRenderBusTimeline,
        .publishLateMixControl = &publishNativeAudioOutputLateMixControl,
        .publishLoopDemand = &publishNativeAudioOutputLoopDemand,
        .retireLoopDemand = &retireNativeAudioOutputLoopDemand,
        .pendingLoopCapture = &nativeAudioOutputPendingLoopCapture,
        .publishLoopPolicy = &publishNativeAudioOutputLoopPolicy,
        .beginLoopCutover = &beginNativeAudioOutputLoopCutover,
        .cancelLoopCutover = &cancelNativeAudioOutputLoopCutover,
        .enqueueLoopCutover = &enqueueNativeAudioOutputLoopCutoverTimeline,
        .commitLoopCutover = &commitNativeAudioOutputLoopCutover,
        .releaseLoopCutoverWrites = &releaseNativeAudioOutputLoopCutoverWrites,
        .loopBoundaryCoverage = &nativeAudioOutputLoopBoundaryCoverage,
        .beginHandoff = &beginNativeAudioOutputHandoff,
        .commitHandoff = &commitNativeAudioOutputHandoff,
        .flush = &flushNativeAudioOutputStatus,
        .setPlaying = &setNativeAudioOutputPlaying,
        .setTerminalSilenceAuthorization = &setNativeAudioOutputTerminalSilenceAuthorization,
        .publishTerminalBoundary = &publishNativeAudioOutputTerminalBoundary,
        .queuedFrames = &nativeAudioOutputQueuedFrames,
        .writableFrames = &nativeAudioOutputWritableFrames,
        .framesPerBurst = &nativeAudioOutputFramesPerBurst,
        .loopCutoverRequiredFrames = &nativeAudioOutputLoopCutoverRequiredFrames,
        .presentationEvidence = &nativeAudioOutputPresentationEvidence,
        .presentedPosition = &nativeAudioOutputPresentedPosition,
        .appliedControlEvidence = &nativeAudioOutputAppliedControlEvidence,
        .stemGainCallbackEvidence = &nativeAudioOutputStemGainCallbackEvidence,
        .audibleLoopEvidence = &nativeAudioOutputAudibleLoopEvidence,
        .beginDrainHandoff = &beginNativeAudioOutputDrainHandoff,
        .commitDrainHandoff = &commitNativeAudioOutputDrainHandoff,
        .cancelDrainHandoff = &cancelNativeAudioOutputDrainHandoff,
        .queuedFramesForGeneration = &nativeAudioOutputQueuedFramesForGeneration,
        .writableFramesForGeneration = &nativeAudioOutputWritableFramesForGeneration,
        .setDiagnosticPayloadFingerprints = &setNativeAudioOutputDiagnosticPayloadFingerprints,
    };
}

}  // namespace stemwise::audio::engine
