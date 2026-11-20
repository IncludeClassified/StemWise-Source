#include "native_engine/runtime/NativePlaybackRuntime.h"
#include "NativeAudioOutputLoopBoundaryGate.h"
#include <array>
#include <cstdlib>
#include <iostream>

using stemwise::audio::NativeAudioOutputHandle;
using stemwise::audio::engine::NativeRenderBusSubmission;

namespace {
const NativeRenderBusSubmission *expectedSubmission{};
NativeAudioOutputHandle expectedHandle{};
const stemwise::audio::NativeAudioOutputLoopPolicy *expectedDemand{};
unsigned ordinaryCalls{}, loopCalls{};
void require(bool value, const char *message) {
    if (!value) { std::cerr << message << '\n'; std::exit(1); }
}
std::size_t observe(NativeAudioOutputHandle handle, const NativeRenderBusSubmission &submission, bool loop) {
    require(handle == expectedHandle && &submission == expectedSubmission,
        "production output port must forward the original immutable submission reference");
    const std::uint64_t offset = loop ? 10U : 0U;
    require(submission.generation == 101U + offset && submission.selectionToken == 202U + offset &&
            submission.payloadSequence == 303U + offset && submission.payloadHash == 404U + offset &&
            submission.canonicalControlRevision == 505U + offset &&
            submission.sourceIntentRevision == 606U + offset && submission.stemIntentRevision == 707U + offset &&
            submission.baseControl.generation == submission.generation &&
            submission.baseControl.selectionToken == submission.selectionToken &&
            submission.baseControl.canonicalControlRevision == submission.canonicalControlRevision &&
            submission.baseControl.sourceIntentRevision == submission.sourceIntentRevision &&
            submission.baseControl.stemIntentRevision == submission.stemIntentRevision &&
            submission.baseControl.musicGain == (loop ? .5F : .25F) &&
            submission.stemBusReadyMask == 0x15U && submission.loopPolicyRevision == 808U + offset &&
            submission.loopRevision == 909U + offset && submission.loopCycle == 1001U + offset &&
            submission.loopEnabled == !loop && submission.endOfStreamAfterInput == !loop &&
            submission.sourceStartFrame == (loop ? 456.5 : 123.25) &&
            submission.sourceEndFrameExclusive == (loop ? 460.5 : 130.75) &&
            submission.samples != nullptr && submission.samples[0] == (loop ? -0.5F : 0.25F) &&
            submission.frameCount == (loop ? 19U : 17U),
        "output boundary lost or reordered ownership, control, loop, terminal, span or PCM fields");
    if (loop) ++loopCalls; else ++ordinaryCalls;
    return loop ? 7U : 3U;
}
}

namespace stemwise::audio {
std::size_t enqueueRenderBusTimeline(NativeAudioOutputHandle handle,
    const NativeRenderBusSubmission &submission) noexcept { return observe(handle, submission, false); }
std::size_t enqueueNativeAudioOutputLoopCutoverTimeline(NativeAudioOutputHandle handle,
    const NativeRenderBusSubmission &submission) noexcept { return observe(handle, submission, true); }
// Unused platform boundaries keep normal production port construction real on the host.
bool publishNativeAudioOutputLateMixControl(NativeAudioOutputHandle, const NativeAudioLateMixControl &) noexcept { return {}; }
bool publishNativeAudioOutputLoopPolicy(NativeAudioOutputHandle, const NativeAudioOutputLoopPolicy &) noexcept { return {}; }
bool publishNativeAudioOutputLoopDemand(NativeAudioOutputHandle, const NativeAudioOutputLoopPolicy &, bool, std::uint64_t) noexcept { return {}; }
void retireNativeAudioOutputLoopDemand(NativeAudioOutputHandle, std::uint64_t, std::uint64_t, std::uint64_t) noexcept {}
NativeAudioPendingLoopCapture nativeAudioOutputPendingLoopCapture(NativeAudioOutputHandle handle,
    const NativeAudioOutputLoopPolicy &demand) noexcept {
    require(handle == expectedHandle && &demand == expectedDemand, "capture query preserves exact handle and demand");
    return {true, 37U};
}
NativeAudioLoopBoundaryCoverage nativeAudioOutputLoopBoundaryCoverage(NativeAudioOutputHandle, std::uint64_t, std::uint64_t, std::uint64_t, std::uint64_t) noexcept { return {}; }
bool beginNativeAudioOutputLoopCutover(NativeAudioOutputHandle, std::uint64_t, std::uint64_t) noexcept { return {}; }
NativeAudioDrainCancelStatus cancelNativeAudioOutputLoopCutover(NativeAudioOutputHandle, std::uint64_t, std::uint64_t) noexcept { return {}; }
NativeAudioLoopCutoverCommitStatus commitNativeAudioOutputLoopCutover(NativeAudioOutputHandle, const NativeAudioOutputLoopPolicy &) noexcept { return {}; }
bool releaseNativeAudioOutputLoopCutoverWrites(NativeAudioOutputHandle, std::uint64_t, std::uint64_t) noexcept { return {}; }
bool beginNativeAudioOutputHandoff(NativeAudioOutputHandle, std::uint64_t) noexcept { return {}; }
bool commitNativeAudioOutputHandoff(NativeAudioOutputHandle, std::uint64_t) noexcept { return {}; }
bool beginNativeAudioOutputDrainHandoff(NativeAudioOutputHandle, std::uint64_t, std::uint64_t, std::uint64_t) noexcept { return {}; }
bool commitNativeAudioOutputDrainHandoff(NativeAudioOutputHandle, std::uint64_t, std::uint64_t, std::uint64_t) noexcept { return {}; }
NativeAudioDrainCancelStatus cancelNativeAudioOutputDrainHandoff(NativeAudioOutputHandle, std::uint64_t, std::uint64_t, std::uint64_t) noexcept { return {}; }
NativeAudioFlushStatus flushNativeAudioOutputStatus(NativeAudioOutputHandle, std::uint64_t) noexcept { return {}; }
void setNativeAudioOutputDiagnosticPayloadFingerprints(NativeAudioOutputHandle, bool) noexcept {}
bool setNativeAudioOutputPlaying(NativeAudioOutputHandle, bool) noexcept { return {}; }
void setNativeAudioOutputTerminalSilenceAuthorization(NativeAudioOutputHandle, std::uint64_t, bool) noexcept {}
bool publishNativeAudioOutputTerminalBoundary(NativeAudioOutputHandle handle, std::uint64_t generation,
    std::uint64_t token, std::uint64_t physicalRevision, double sourceEnd) noexcept {
    require(handle == expectedHandle && generation == 17U && token == 29U && physicalRevision == 37U && sourceEnd == 48.5,
        "terminal boundary port preserves exact handle, owner, physical policy and source end");
    return true;
}
std::size_t nativeAudioOutputQueuedFrames(NativeAudioOutputHandle) noexcept { return {}; }
std::size_t nativeAudioOutputWritableFrames(NativeAudioOutputHandle) noexcept { return {}; }
std::size_t nativeAudioOutputQueuedFramesForGeneration(NativeAudioOutputHandle, std::uint64_t) noexcept { return {}; }
std::size_t nativeAudioOutputWritableFramesForGeneration(NativeAudioOutputHandle, std::uint64_t) noexcept { return {}; }
std::int32_t nativeAudioOutputFramesPerBurst(NativeAudioOutputHandle) noexcept { return {}; }
std::size_t nativeAudioOutputLoopCutoverRequiredFrames(NativeAudioOutputHandle) noexcept { return {}; }
NativeAudioPresentationEvidence nativeAudioOutputPresentationEvidence(NativeAudioOutputHandle) noexcept { return {}; }
NativeAudioPresentedPosition nativeAudioOutputPresentedPosition(NativeAudioOutputHandle) noexcept { return {}; }
NativeAudioAppliedControlEvidence nativeAudioOutputAppliedControlEvidence(NativeAudioOutputHandle) noexcept { return {}; }
NativeAudioStemGainCallbackEvidence nativeAudioOutputStemGainCallbackEvidence(NativeAudioOutputHandle) noexcept { return {}; }
NativeAudioAudibleLoopEvidence nativeAudioOutputAudibleLoopEvidence(NativeAudioOutputHandle) noexcept { return {}; }
}

int main() {
    int outputOwner{};
    expectedHandle = &outputOwner;
    const auto port = stemwise::audio::engine::borrowNativeAudioOutputPort(expectedHandle);
    const stemwise::audio::NativeAudioOutputLoopPolicy demand{.revision = 41U};
    expectedDemand = &demand;
    const auto capture = port.pendingLoopCapture(port.handle, demand);
    require(capture.available && capture.revision == 37U, "capture query forwards availability and exact owed revision");
    require(port.publishTerminalBoundary(port.handle, 17U, 29U, 37U, 48.5), "terminal boundary result is forwarded");
    require(port.valid() && port.enqueue == &stemwise::audio::enqueueRenderBusTimeline &&
            port.enqueueLoopCutover == &stemwise::audio::enqueueNativeAudioOutputLoopCutoverTimeline,
        "normal output port must bind both typed production endpoints directly");
    std::array<float, 19U * stemwise::audio::kStemwiseMultibusStride> ordinaryPcm{}, loopPcm{};
    ordinaryPcm[0] = 0.25F;
    loopPcm[0] = -0.5F;
    const NativeRenderBusSubmission ordinary{
        .generation=101U, .selectionToken=202U, .payloadSequence=303U, .payloadHash=404U,
        .canonicalControlRevision=505U, .sourceIntentRevision=606U, .stemIntentRevision=707U,
        .stemBusReadyMask=0x15U,
        .baseControl={.publicationSequence=1U, .generation=101U, .selectionToken=202U,
            .canonicalControlRevision=505U, .sourceIntentRevision=606U, .stemIntentRevision=707U, .musicGain=.25F},
        .loopPolicyRevision=808U, .loopRevision=909U, .loopCycle=1001U,
        .loopEnabled=true, .endOfStreamAfterInput=true, .sourceStartFrame=123.25,
        .sourceEndFrameExclusive=130.75, .samples=ordinaryPcm.data(), .frameCount=17U};
    const NativeRenderBusSubmission loop{
        .generation=111U, .selectionToken=212U, .payloadSequence=313U, .payloadHash=414U,
        .canonicalControlRevision=515U, .sourceIntentRevision=616U, .stemIntentRevision=717U,
        .stemBusReadyMask=0x15U,
        .baseControl={.publicationSequence=2U, .generation=111U, .selectionToken=212U,
            .canonicalControlRevision=515U, .sourceIntentRevision=616U, .stemIntentRevision=717U, .musicGain=.5F},
        .loopPolicyRevision=818U, .loopRevision=919U, .loopCycle=1011U,
        .loopEnabled=false, .endOfStreamAfterInput=false, .sourceStartFrame=456.5,
        .sourceEndFrameExclusive=460.5, .samples=loopPcm.data(), .frameCount=19U};
    expectedSubmission = &ordinary;
    require(port.enqueue(expectedHandle, ordinary) == 3U, "ordinary accepted-prefix result must survive forwarding");
    expectedSubmission = &loop;
    require(port.enqueueLoopCutover(expectedHandle, loop) == 7U, "loop accepted-prefix result must survive forwarding");
    require(ordinaryCalls == 1U && loopCalls == 1U, "each selected output route is invoked exactly once");
    std::cout << "Typed native output port forwarding passed\n";
}
