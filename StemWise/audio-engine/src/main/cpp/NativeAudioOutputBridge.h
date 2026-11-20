#pragma once

#include "StemwiseMultibus.h"
#include "NativeRenderBusSubmission.h"
#include "LateStemMixer.h"

#include <array>
#include <cstddef>
#include <cstdint>

namespace stemwise::audio {

struct NativeAudioOutputLoopPolicy;

/** revision zero means no capture is owed; unavailable observations never settle accepted work. */
struct NativeAudioPendingLoopCapture final {
    bool available{false};
    std::uint64_t revision{0U};
};

struct NativeAudioLoopBoundaryCoverage final {
    bool boundaryQueued{false};
    std::size_t unreadPrefixFrames{0U};
    std::size_t queuedFrames{0U};
    std::uint64_t boundaryLoopPolicyRevision{0U};
};

/**
 * Opaque in-process handle borrowed by the native render runtime.
 *
 * Kotlin owns the corresponding NativeAudioOutput lifetime and must stop/join the render runtime
 * before closing it. These calls cross no JNI boundary and are never invoked by the output consumer.
 */
using NativeAudioOutputHandle = void *;

using NativeAudioSourceMode = LateStemSourceMode;

/**
 * Exact producer-side result of cancelling one automatic drain handoff.
 *
 * The distinction between AdoptionInFlight and SuccessorActive is essential: once the callback
 * has claimed a role swap it is too late to cancel, but the producer must not wait for audible PCM
 * evidence when Pause can make that newly adopted role silent.
 */
enum class NativeAudioDrainCancelStatus : std::uint8_t {
    NotRequested = 0,
    CancelledOpenStage = 1,
    CancellationRequested = 2,
    OutgoingActive = 3,
    SuccessorActive = 4,
    AdoptionInFlight = 5,
    NoMatchingDrain = 6,
};

/** Exact result of an administrative empty-generation invalidation. */
enum class NativeAudioFlushStatus : std::uint8_t {
    Accepted = 0,
    RetryAfterCallbackAcknowledgement = 1,
    StaleGeneration = 2,
    InvalidState = 3,
};

/** Exact outcome of publishing a prepared same-owner loop continuation. */
enum class NativeAudioLoopCutoverCommitStatus : std::uint8_t {
    Committed = 0,
    Retry = 1,
    Retired = 2,
};

/** Immutable first-callback proof for the most recently adopted audible selection token. */
struct NativeAudioPresentationEvidence final {
    std::uint64_t sequence{0};
    std::uint64_t generation{0};
    std::uint64_t selectionToken{0};
    std::uint64_t fingerprintSequence{0};
    std::int64_t firstTimelineFrame{0};
    std::uint64_t firstOutputFrame{0};
    std::uint64_t callbackSequence{0};
    std::uint64_t pcmFrames{0};

    [[nodiscard]] constexpr bool valid() const noexcept {
        return sequence != 0U && generation != 0U && selectionToken != 0U &&
            fingerprintSequence != 0U && firstTimelineFrame >= 0 &&
            callbackSequence != 0U && pcmFrames != 0U;
    }
};

/** Coherent callback-authoritative position for the newest actually emitted owned PCM span. */
struct NativeAudioPresentedPosition final {
    std::uint64_t sequence{0};
    std::uint64_t generation{0};
    std::uint64_t selectionToken{0};
    std::uint64_t fingerprintSequence{0};
    /** Direct fixed-loop policy stamped on the newest emitted frame; zero before first policy. */
    std::uint64_t loopPolicyRevision{0};
    std::int64_t lastTimelineFrame{0};
    std::uint64_t lastOutputFrame{0};
    std::uint64_t callbackSequence{0};
    std::uint64_t pcmFrames{0};
    /** True only when this exact callback consumed the authoritative final PCM boundary. */
    bool terminalAfterFrame{false};
    /** Latest canonical fixed-loop policy observed by a device callback. */
    std::uint64_t loopPolicyAdoptedRevision{0};
    bool loopPolicyAdoptedEnabled{false};
    std::uint64_t loopPolicyAdoptedAFrame{0};
    std::uint64_t loopPolicyAdoptedBFrameExclusive{0};
    /** Native admission-to-callback observation time for that policy. */
    std::uint64_t loopPolicyAdoptionLatencyMicros{0};
    /** Android input-to-native atomic publication time; excludes callback and device/DAC time. */
    std::uint64_t loopPolicyInputToAtomicPublicationLatencyMicros{0};
    /** Android input-to-callback adoption time; excludes downstream device/DAC time. */
    std::uint64_t loopPolicyInputToCallbackAdoptionLatencyMicros{0};

    [[nodiscard]] constexpr bool valid() const noexcept {
        return sequence != 0U && generation != 0U && selectionToken != 0U &&
            fingerprintSequence != 0U && lastTimelineFrame >= 0 &&
            callbackSequence != 0U && pcmFrames != 0U;
    }
    /** Exact next source cursor from actually consumed PCM; not serialized to the Android packet. */
    std::int64_t lastNextTimelineFrame{0};

};

/** Best available device-presented timeline frame, mapped from the output's hardware timestamp. */
struct NativeAudioAudiblePosition final {
    std::uint64_t generation{0};
    std::uint64_t selectionToken{0};
    std::int64_t timelineFrame{-1};
    std::uint64_t outputFrame{0};

    [[nodiscard]] constexpr bool valid() const noexcept {
        return generation != 0U && selectionToken != 0U && timelineFrame >= 0;
    }
};

/**
 * Callback-authoritative proof that one complete device callback consumed only PCM carrying the
 * same settled native control revision and source endpoint.
 *
 * This sequence is monotonic for the lifetime of one NativeAudioOutput, including route reopen.
 * Reopen never republishes old evidence: a newer generation must first deliver a wholly coherent
 * callback span. Consumers must still require the exact currently committed owner.
 */
struct NativeAudioAppliedControlEvidence final {
    std::uint64_t sequence{0};
    std::uint64_t generation{0};
    std::uint64_t selectionToken{0};
    std::uint64_t controlRevision{0};
    NativeAudioSourceMode actualSourceMode{NativeAudioSourceMode::Original};
    /** Numerical DSP tuple represented by this payload; zero tempo means transition/unknown. */
    float actualTempo{0.0F};
    float actualPitchSemitones{0.0F};
    std::uint64_t firstFingerprintSequence{0};
    std::uint64_t lastFingerprintSequence{0};
    std::uint64_t callbackSequence{0};
    std::uint64_t pcmFrames{0};

    [[nodiscard]] constexpr bool valid() const noexcept {
        return sequence != 0U && generation != 0U && selectionToken != 0U &&
            controlRevision != 0U && firstFingerprintSequence != 0U &&
            lastFingerprintSequence != 0U && callbackSequence != 0U && pcmFrames != 0U;
    }
};

inline constexpr std::size_t kNativeAudioStemGainLaneCount = kStemwiseStemBusCount;

/**
 * Latest exact callback endpoint for one owned multibus generation.
 *
 * Effective stem gains already include mute/solo policy. This is a fixed primitive payload: it
 * crosses no JNI boundary and is published by the validated native render worker.
 */
using NativeAudioLateMixControl = LateStemMixControlSnapshot;

/**
 * Callback-authoritative proof that one complete device callback consumed only final PCM tagged
 * with one exact Stems gain-overlay publication. The declared lanes' IEEE-754 bit patterns preserve the exact
 * values admitted by Kotlin without callback-side float conversion or allocation.
 */
struct NativeAudioStemGainCallbackEvidence final {
    std::uint64_t sequence{0};
    std::uint64_t generation{0};
    std::uint64_t selectionToken{0};
    std::uint64_t publishedSequence{0};
    std::uint64_t offeredSequence{0};
    std::uint32_t activeMask{0};
    std::array<std::uint32_t, kNativeAudioStemGainLaneCount> gainBits{};
    std::uint64_t callbackSequence{0};
    std::int64_t firstTaggedFrame{0};
    std::int64_t lastTaggedFrame{0};
    std::uint64_t taggedFrameCount{0};
    std::uint64_t nonZeroPcmFrameCount{0};
    /** CLOCK_MONOTONIC nanoseconds when the callback accepted this exact coherent tuple. */
    std::uint64_t callbackObservedAtNs{0};

    [[nodiscard]] constexpr bool valid() const noexcept {
        constexpr std::uint32_t kAllLanes =
            (1U << static_cast<std::uint32_t>(kNativeAudioStemGainLaneCount)) - 1U;
        return sequence != 0U && generation != 0U && selectionToken != 0U &&
            publishedSequence != 0U && offeredSequence != 0U &&
            (activeMask & ~kAllLanes) == 0U && callbackSequence != 0U &&
            firstTaggedFrame >= 0 && lastTaggedFrame >= firstTaggedFrame &&
            taggedFrameCount != 0U && nonZeroPcmFrameCount <= taggedFrameCount &&
            callbackObservedAtNs != 0U;
    }
};

/** Whole-device-callback proof for one exact managed-loop revision and automatic cycle. */
struct NativeAudioAudibleLoopEvidence final {
    std::uint64_t sequence{0};
    std::uint64_t generation{0};
    std::uint64_t selectionToken{0};
    std::uint64_t loopRevision{0};
    std::uint64_t loopCycle{0};
    std::uint64_t firstFingerprintSequence{0};
    std::uint64_t lastFingerprintSequence{0};
    std::uint64_t callbackSequence{0};
    std::uint64_t pcmFrames{0};

    [[nodiscard]] constexpr bool valid() const noexcept {
        return sequence != 0U && generation != 0U && selectionToken != 0U &&
            loopRevision != 0U && firstFingerprintSequence != 0U &&
            lastFingerprintSequence != 0U && callbackSequence != 0U && pcmFrames != 0U;
    }
};

/**
 * Enqueues the bounded aligned stereo music buses plus click/envelope auxiliaries.
 *
 * The payload is not final PCM: source/stem/master control and limiting happen in the device
 * callback. Every non-timeline tag value applies to the complete accepted prefix.
 */
[[nodiscard]] std::size_t enqueueRenderBusTimeline(
    NativeAudioOutputHandle handle,
    const engine::NativeRenderBusSubmission &submission) noexcept;

/** Publishes one newer exact endpoint to the role that owns generation. */
[[nodiscard]] bool publishNativeAudioOutputLateMixControl(
    NativeAudioOutputHandle handle,
    const NativeAudioLateMixControl &control) noexcept;

/** Publishes one exact fixed-loop policy directly to the device-callback boundary gate. */
[[nodiscard]] bool publishNativeAudioOutputLoopPolicy(
    NativeAudioOutputHandle handle,
    const NativeAudioOutputLoopPolicy &policy) noexcept;

/** Publishes newest logical A/B immediately; this mailbox has no render-worker producer. */
[[nodiscard]] bool publishNativeAudioOutputLoopDemand(
    NativeAudioOutputHandle handle,
    const NativeAudioOutputLoopPolicy &policy,
    bool boundaryCrossedAtPublication = false,
    std::uint64_t capturedBTimelineFrameExclusive = 0U) noexcept;

/** Render-worker rejection of an exact terminally unavailable logical demand. */
void retireNativeAudioOutputLoopDemand(NativeAudioOutputHandle handle,
    std::uint64_t revision, std::uint64_t generation, std::uint64_t selectionToken) noexcept;

[[nodiscard]] NativeAudioPendingLoopCapture nativeAudioOutputPendingLoopCapture(
    NativeAudioOutputHandle handle, const NativeAudioOutputLoopPolicy &demand) noexcept;

/** Producer-side immutable scan proving one exact-owner C boundary remains unread. */
[[nodiscard]] NativeAudioLoopBoundaryCoverage nativeAudioOutputLoopBoundaryCoverage(
    NativeAudioOutputHandle handle,
    std::uint64_t generation,
    std::uint64_t selectionToken,
    std::uint64_t cutoverTimelineFrameExclusive,
    std::uint64_t expectedLoopPolicyRevision) noexcept;

/** Opens the already-allocated inactive ring for an exact same-owner loop cutover. */
[[nodiscard]] bool beginNativeAudioOutputLoopCutover(
    NativeAudioOutputHandle handle,
    std::uint64_t generation,
    std::uint64_t loopPolicyRevision) noexcept;

[[nodiscard]] NativeAudioDrainCancelStatus cancelNativeAudioOutputLoopCutover(
    NativeAudioOutputHandle handle,
    std::uint64_t generation,
    std::uint64_t loopPolicyRevision) noexcept;

/** Enqueues prepared replacement buses into the explicit same-generation loop stage. */
[[nodiscard]] std::size_t enqueueNativeAudioOutputLoopCutoverTimeline(
    NativeAudioOutputHandle handle,
    const engine::NativeRenderBusSubmission &submission) noexcept;

/** Publishes a non-empty prepared stage and its matching callback-visible policy. */
[[nodiscard]] NativeAudioLoopCutoverCommitStatus commitNativeAudioOutputLoopCutover(
    NativeAudioOutputHandle handle,
    const NativeAudioOutputLoopPolicy &policy) noexcept;

/** Releases producer writes after callback adoption and the exact continuation-pipeline swap. */
[[nodiscard]] bool releaseNativeAudioOutputLoopCutoverWrites(
    NativeAudioOutputHandle handle,
    std::uint64_t generation,
    std::uint64_t loopPolicyRevision) noexcept;

/**
 * Selects and clears the inactive ring for a newer producer generation.
 *
 * Repeated calls before commit supersede the previous staged generation in bounded space. A call
 * made while the preceding commit still awaits its callback acknowledgement returns false; the
 * caller should conflate intent and retry rather than queue another owner.
 */
[[nodiscard]] bool beginNativeAudioOutputHandoff(
    NativeAudioOutputHandle handle,
    std::uint64_t generation) noexcept;

/** Publishes a non-empty staged generation for one atomic callback-boundary role swap. */
[[nodiscard]] bool commitNativeAudioOutputHandoff(
    NativeAudioOutputHandle handle,
    std::uint64_t generation) noexcept;

/**
 * Opens an inactive successor ring while preserving writes to the exact active generation.
 * transitionRevision is an external automatic-end plan identity and must be non-zero.
 */
[[nodiscard]] bool beginNativeAudioOutputDrainHandoff(
    NativeAudioOutputHandle handle,
    std::uint64_t expectedActiveGeneration,
    std::uint64_t stagedGeneration,
    std::uint64_t transitionRevision) noexcept;

/** Arms callback-side adoption only after the exact outgoing generation has fully drained. */
[[nodiscard]] bool commitNativeAudioOutputDrainHandoff(
    NativeAudioOutputHandle handle,
    std::uint64_t expectedActiveGeneration,
    std::uint64_t stagedGeneration,
    std::uint64_t transitionRevision) noexcept;

/**
 * Cancels an open or published exact drain stage. A published cancellation becomes reusable only
 * after callback acknowledgement. The exact result distinguishes a pending cancellation from a
 * callback-claimed or already-applied successor so the runtime never waits for impossible audible
 * evidence while paused.
 */
[[nodiscard]] NativeAudioDrainCancelStatus cancelNativeAudioOutputDrainHandoff(
    NativeAudioOutputHandle handle,
    std::uint64_t expectedActiveGeneration,
    std::uint64_t stagedGeneration,
    std::uint64_t transitionRevision) noexcept;

/**
 * Flushes to an empty generation. A pending callback acknowledgement is normal bounded
 * backpressure, not a native audio fault; stale/invalid requests remain distinct.
 */
[[nodiscard]] NativeAudioFlushStatus flushNativeAudioOutputStatus(
    NativeAudioOutputHandle handle,
    std::uint64_t generation) noexcept;

void setNativeAudioOutputDiagnosticPayloadFingerprints(
    NativeAudioOutputHandle handle, bool enabled) noexcept;

[[nodiscard]] bool setNativeAudioOutputPlaying(
    NativeAudioOutputHandle handle,
    bool playing) noexcept;

/** Pauses the existing device stream only after its logical render gate is already closed. */
[[nodiscard]] bool quiesceNativeAudioOutputIfPaused(
    NativeAudioOutputHandle handle) noexcept;

/**
 * Authorizes intentional terminal silence for one exact active generation. Passing false revokes
 * every generation. Source EOS remains tagged independently so a missing expected successor stays
 * in the raw underrun domain.
 */
void setNativeAudioOutputTerminalSilenceAuthorization(
    NativeAudioOutputHandle handle,
    std::uint64_t generation,
    bool authorized) noexcept;

/** Sole PCM producer publishes separately delivered EOS at its exact last accepted source end. */
[[nodiscard]] bool publishNativeAudioOutputTerminalBoundary(NativeAudioOutputHandle handle,
    std::uint64_t generation, std::uint64_t selectionToken, std::uint64_t physicalRevision,
    double sourceEndFrameExclusive) noexcept;

[[nodiscard]] std::size_t nativeAudioOutputQueuedFrames(
    NativeAudioOutputHandle handle) noexcept;

[[nodiscard]] std::size_t nativeAudioOutputWritableFrames(
    NativeAudioOutputHandle handle) noexcept;

/** Exact active-or-staged generation queries used by concurrent automatic-successor prefill. */
[[nodiscard]] std::size_t nativeAudioOutputQueuedFramesForGeneration(
    NativeAudioOutputHandle handle,
    std::uint64_t generation) noexcept;

[[nodiscard]] std::size_t nativeAudioOutputWritableFramesForGeneration(
    NativeAudioOutputHandle handle,
    std::uint64_t generation) noexcept;

[[nodiscard]] std::int32_t nativeAudioOutputFramesPerBurst(
    NativeAudioOutputHandle handle) noexcept;

/** Stable device-buffer span used as the hard callback cutover safety margin. */
[[nodiscard]] std::size_t nativeAudioOutputStableBufferFrames(
    NativeAudioOutputHandle handle) noexcept;

/** Prepared same-owner loop span required by the largest callback observed on this route. */
[[nodiscard]] std::size_t nativeAudioOutputLoopCutoverRequiredFrames(
    NativeAudioOutputHandle handle) noexcept;

/** Allocation-free control/render-thread read; never called by or through JNI. */
[[nodiscard]] NativeAudioPresentationEvidence nativeAudioOutputPresentationEvidence(
    NativeAudioOutputHandle handle) noexcept;

/** Allocation-free coherent ongoing position read; never called by or through the callback. */
[[nodiscard]] NativeAudioPresentedPosition nativeAudioOutputPresentedPosition(
    NativeAudioOutputHandle handle) noexcept;

/** Off-callback device timestamp query; returns invalid when timestamp/history is unavailable. */
[[nodiscard]] NativeAudioAudiblePosition nativeAudioOutputAudiblePosition(
    NativeAudioOutputHandle handle) noexcept;

/** Allocation-free coherent read of the newest whole-callback audible-control proof. */
[[nodiscard]] NativeAudioAppliedControlEvidence nativeAudioOutputAppliedControlEvidence(
    NativeAudioOutputHandle handle) noexcept;

[[nodiscard]] NativeAudioStemGainCallbackEvidence
nativeAudioOutputStemGainCallbackEvidence(NativeAudioOutputHandle handle) noexcept;

/** Allocation-free coherent read of the newest whole-callback managed-loop proof. */
[[nodiscard]] NativeAudioAudibleLoopEvidence nativeAudioOutputAudibleLoopEvidence(
    NativeAudioOutputHandle handle) noexcept;

}  // namespace stemwise::audio
