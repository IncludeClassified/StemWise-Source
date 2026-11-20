#include "NativeAudioOutputMultibusCallbackStage.h"
#include "AudioTrace.h"

#include <algorithm>
#include <bit>
#include <cstring>
#include <limits>

namespace stemwise::audio {
namespace {

void saturatingAdd(std::uint64_t &value, std::size_t amount) noexcept {
    const std::uint64_t maximum = std::numeric_limits<std::uint64_t>::max();
    const std::uint64_t converted = static_cast<std::uint64_t>(amount);
    value = converted > maximum - value ? maximum : value + converted;
}

void saturatingIncrement(std::uint64_t &value) noexcept {
    if (value != std::numeric_limits<std::uint64_t>::max()) ++value;
}

[[nodiscard]] bool validRenderTag(
    const RenderBusFrameTag &tag,
    std::uint64_t expectedGeneration) noexcept {
    return expectedGeneration != 0U && tag.generation == expectedGeneration &&
        tag.selectionToken != 0U && tag.payloadSequence != 0U &&
        tag.canonicalControlRevision != 0U && tag.sourceIntentRevision != 0U &&
        tag.stemIntentRevision != 0U &&
        (tag.stemBusReadyMask & ~kStemwiseAllStemBusesReadyMask) == 0U &&
        (!tag.loopEnabled || tag.loopRevision != 0U);
}

}  // namespace

NativeAudioOutputMultibusCallbackStage::NativeAudioOutputMultibusCallbackStage(
    std::uint32_t sampleRateHz)
    : mMixer(sampleRateHz) {}

void NativeAudioOutputMultibusCallbackStage::reset() noexcept {
    mMixer.reset();
    mOverlay = {};
    mHasOverlay = false;
    mControl = {};
    mHasControl = false;
    mMixerApplicationSequence = 0U;
    mSettlementFramesRemaining = 0U;
    mLastStemBusReadyMask = 0U;
}

bool NativeAudioOutputMultibusCallbackStage::cachedControlCovers(
    const RenderBusFrameTag &tag) const noexcept {
    return mHasOverlay && lateControlCoversRenderTag(mOverlay, tag);
}

bool NativeAudioOutputMultibusCallbackStage::validateAndApplyControl(
    std::uint32_t role,
    const RenderBusFrameTag &tag,
    const LateStemMixControlSnapshot *baseControl,
    const LateStemMixControlMailbox &mailbox,
    std::size_t frameCount) noexcept {
    if (baseControl != nullptr && !lateControlCoversRenderTag(*baseControl, tag)) {
        saturatingAdd(mDiagnostics.staleControlFrames, frameCount);
        return false;
    }
    LateStemMixControlSnapshot candidate{};
    const bool read = mailbox.tryRead(
        role,
        tag.generation,
        tag.selectionToken,
        candidate);
    if (read) {
        if (!mHasOverlay || candidate.generation != mOverlay.generation ||
            candidate.selectionToken != mOverlay.selectionToken ||
            candidate.publicationSequence > mOverlay.publicationSequence) {
            mOverlay = candidate;
            mHasOverlay = true;
        }
    }
    // An unavailable/incompatible latest endpoint cannot withdraw a committed block's base.
    // A base never enters the global overlay cache or competes in its publication namespace.
    const auto *selected = cachedControlCovers(tag) ? &mOverlay : baseControl;
    if (selected == nullptr) {
        saturatingAdd(mHasOverlay ? mDiagnostics.staleControlFrames : mDiagnostics.missingControlFrames, frameCount);
        return false;
    }
    if (!mHasControl || mControl != *selected) {
            const bool sameOwner = mHasControl &&
                mControl.generation == selected->generation &&
                mControl.selectionToken == selected->selectionToken;
            if (mHasControl && !sameOwner) mMixer.reset();
            if (mMixerApplicationSequence == std::numeric_limits<std::uint64_t>::max()) {
                saturatingAdd(mDiagnostics.mixerRejectedFrames, frameCount);
                return false;
            }
            auto mixerControl = *selected;
            mixerControl.publicationSequence = ++mMixerApplicationSequence;
            if (!mMixer.applyControl(mixerControl, tag.stemBusReadyMask)) {
                saturatingAdd(mDiagnostics.mixerRejectedFrames, frameCount);
                return false;
            }
            if (sameOwner) {
                mSettlementFramesRemaining = std::max({
                    mSettlementFramesRemaining,
                    selected->sourceRampFrames,
                    selected->laneRampFrames,
                    selected->masterRampFrames,
                });
            } else {
                // LateStemMixer initializes a new exact owner without an artificial fade.
                mSettlementFramesRemaining = 0U;
                mLastStemBusReadyMask = tag.stemBusReadyMask;
            }
            mControl = *selected;
            mHasControl = true;
    }
    return true;
}

void NativeAudioOutputMultibusCallbackStage::accountSettlementForPayload(
    const RenderBusFrameTag &tag) noexcept {
    if (!mHasControl || tag.stemBusReadyMask == mLastStemBusReadyMask) return;
    if (mControl.sourceMode == LateStemSourceMode::Stems) {
        // LateStemMixer sees the new mask in mix() and ramps between the always-ready Original
        // lane and complete Stems. Keep the exact owner and limiter history intact.
        mSettlementFramesRemaining = std::max(
            mSettlementFramesRemaining,
            mControl.sourceRampFrames);
    }
    mLastStemBusReadyMask = tag.stemBusReadyMask;
}

void NativeAudioOutputMultibusCallbackStage::consumeSettlementFrames(
    std::size_t frameCount) noexcept {
    const std::uint32_t consumed = frameCount > std::numeric_limits<std::uint32_t>::max()
        ? std::numeric_limits<std::uint32_t>::max()
        : static_cast<std::uint32_t>(frameCount);
    mSettlementFramesRemaining = consumed >= mSettlementFramesRemaining
        ? 0U
        : mSettlementFramesRemaining - consumed;
}

FinalPcmFrameTag NativeAudioOutputMultibusCallbackStage::finalTag(
    const RenderBusFrameTag &tag,
    bool transitionSettled) const noexcept {
    FinalPcmFrameTag result{};
    if (!mHasControl) return result;
    const bool stemsEndpoint = mControl.sourceMode == LateStemSourceMode::Stems &&
        mControl.requiredStemMask != 0U &&
        (tag.stemBusReadyMask & mControl.requiredStemMask) == mControl.requiredStemMask;
    result.generation = tag.generation;
    result.selectionToken = tag.selectionToken;
    // This remains a monotonic payload identity. Final sample-level amplitude proof is published
    // later, after global gain and generation-transition treatment, by NativeAudioOutput.
    result.fingerprintSequence = tag.payloadSequence;
    result.controlRevision = mControl.canonicalControlRevision;
    result.actualTempo = tag.actualTempo;
    result.actualPitchSemitones = tag.actualPitchSemitones;
    result.actualSourceMode = stemsEndpoint
        ? FinalPcmSourceMode::Stems
        : FinalPcmSourceMode::Original;
    result.sourceTransitionSettled = transitionSettled;
    result.stemGainPreviewPublicationSequence =
        mControl.stemGainPreviewPublicationSequence;
    result.stemGainPreviewCoversOfferSequence =
        mControl.stemGainPreviewCoversOfferSequence;
    result.stemGainPreviewActiveMask = mControl.stemGainPreviewActiveMask;
    result.stemGainPreviewGainBits = mControl.stemGainPreviewGainBits;
    result.loopPolicyRevision = tag.loopPolicyRevision;
    result.loopRevision = tag.loopEnabled ? tag.loopRevision : 0U;
    result.loopCycle = tag.loopEnabled ? tag.loopCycle : 0U;
    result.loopEnabled = tag.loopEnabled && tag.loopRevision != 0U;
    result.terminalWriteBoundary = tag.terminalWriteBoundary;
    result.timelineFrame = tag.timelineFrame;
    result.nextTimelineFrame = tag.nextTimelineFrame;
    return result;
}

TaggedStereoReadResult NativeAudioOutputMultibusCallbackStage::readAndMix(
    std::uint32_t role,
    std::uint64_t expectedGeneration,
    SpscTaggedMultibusRingBuffer &ring,
    const LateStemMixControlMailbox &mailbox,
    float *interleavedStereo,
    std::size_t maximumFrames,
    OutputTimelineFrameTag *outputTimelineTags,
    OutputTimelineLoopPolicy loopPolicy,
    NativeAudioPresentationSpan *mappingRuns,
    FinalPcmFrameTag *finalFrameTags) noexcept {
    if (interleavedStereo == nullptr || maximumFrames == 0U ||
        role >= LateStemMixControlMailbox::kRoleCount || expectedGeneration == 0U) {
        return {};
    }
    const TaggedMultibusReadLease lease = ring.acquireRead(maximumFrames);
    if (lease.empty()) return {};
    if (mappingRuns != nullptr) std::fill_n(mappingRuns, lease.frameCount, NativeAudioPresentationSpan{});
    if (finalFrameTags != nullptr) std::fill_n(finalFrameTags, lease.frameCount, FinalPcmFrameTag{});
    if (outputTimelineTags != nullptr) {
        std::fill_n(outputTimelineTags, lease.frameCount, OutputTimelineFrameTag{});
    }

    saturatingIncrement(mDiagnostics.callbackLeases);
    TaggedStereoReadResult result{
        .frameCount = lease.frameCount,
        .absoluteFirstFrame = lease.absoluteFirstFrame,
    };
    bool allRunsValid = true;
    bool firstRun = true;
    bool controlCoherent = true;
    bool tuningCoherent = true;
    bool stemCoherent = true;
    bool loopCoherent = true;
    FinalPcmFrameTag coherentFirst{};
    FinalPcmFrameTag coherentLast{};
    RenderBusFrameTag previousTimelineTag{};
    ManagedLoopRenderContext previousManagedContext{};
    bool hasPreviousTimelineTag = false;
    std::uint64_t continuityViolationCount = 0U;

    const bool completeBlocks = ring.forEachTagRun(
        lease,
        [this,
         role,
         expectedGeneration,
         &mailbox,
         interleavedStereo,
         &allRunsValid,
         &firstRun,
         &controlCoherent,
         &tuningCoherent,
         &stemCoherent,
         &loopCoherent,
         &coherentFirst,
         &coherentLast,
         &previousTimelineTag,
         &previousManagedContext,
         &hasPreviousTimelineTag,
         &continuityViolationCount,
         outputTimelineTags,
         mappingRuns,
         finalFrameTags,
         loopPolicy](const RenderBusTagRun &run) noexcept {
            saturatingIncrement(mDiagnostics.tagRuns);
            const RenderBusFrameTag &firstRenderTag = run.tags[0U];
            const RenderBusFrameTag &lastRenderTag = run.tags[run.frameCount - 1U];
            mAttemptedGeneration = firstRenderTag.generation;
            mAttemptedToken = firstRenderTag.selectionToken;
            mAttemptedControlRevision = firstRenderTag.canonicalControlRevision;
            const bool renderTagValid =
                validRenderTag(firstRenderTag, expectedGeneration) &&
                validRenderTag(lastRenderTag, expectedGeneration);
            const bool controlValid = renderTagValid &&
                validateAndApplyControl(role, firstRenderTag, run.baseControl, mailbox, run.frameCount);
            float *destination = interleavedStereo + run.leaseFrameOffset * 2U;
            if (!controlValid) {
                std::memset(destination, 0, run.frameCount * 2U * sizeof(float));
                allRunsValid = false;
                if (!renderTagValid) {
                    saturatingAdd(mDiagnostics.invalidTagFrames, run.frameCount);
                }
                firstRun = false;
                return;
            }

            accountSettlementForPayload(firstRenderTag);
            const bool settledBeforeRun = mSettlementFramesRemaining == 0U;
            if (!mMixer.mix(
                    run.interleavedPayload,
                    run.frameCount,
                    firstRenderTag.stemBusReadyMask,
                    destination)) {
                std::memset(destination, 0, run.frameCount * 2U * sizeof(float));
                allRunsValid = false;
                saturatingAdd(mDiagnostics.mixerRejectedFrames, run.frameCount);
                firstRun = false;
                return;
            }
            const ManagedLoopRenderContext managedContext = run.managedLoopContext != nullptr
                ? *run.managedLoopContext : ManagedLoopRenderContext{};
            for (std::size_t frame = 0U; frame < run.frameCount; ++frame) {
                const RenderBusFrameTag &tag = run.tags[frame];
                if (hasPreviousTimelineTag) {
                    const bool sameOwner =
                        tag.generation == previousTimelineTag.generation &&
                        tag.selectionToken == previousTimelineTag.selectionToken;
                    const bool samePhysicalRevision =
                        tag.loopPolicyRevision == previousTimelineTag.loopPolicyRevision;
                    const bool sameLoopState =
                        tag.loopRevision == previousTimelineTag.loopRevision &&
                        tag.loopCycle == previousTimelineTag.loopCycle &&
                        tag.loopEnabled == previousTimelineTag.loopEnabled;
                    const std::int64_t expectedNext = previousTimelineTag.nextTimelineFrame >= 0
                        ? previousTimelineTag.nextTimelineFrame
                        : previousTimelineTag.timelineFrame < std::numeric_limits<std::int64_t>::max()
                            ? previousTimelineTag.timelineFrame + 1
                            : -1;
                    const auto &previousContext = frame == 0U ? previousManagedContext : managedContext;
                    const bool contextsCoverTags = managedLoopContextCoversRenderTag(previousContext, previousTimelineTag) &&
                        managedLoopContextCoversRenderTag(managedContext, tag);
                    const bool managedStateChange = tag.loopCycle == 0U &&
                        ((previousTimelineTag.loopEnabled != tag.loopEnabled &&
                          (managedContext.valid() || previousContext.valid())) ||
                         (managedContext.valid() && previousContext.valid() &&
                          managedContext.revision > previousContext.revision));
                    const bool linear = sameOwner && samePhysicalRevision && contextsCoverTags &&
                        (!loopPolicy.valid() || (managedContext.empty() && previousContext.empty())) &&
                        (sameLoopState || managedStateChange) && expectedNext >= 0 && tag.timelineFrame == expectedNext;
                    const bool exactWrap = loopPolicy.valid() && contextsCoverTags &&
                        managedContext.empty() && previousContext.empty() && sameOwner &&
                        tag.generation == loopPolicy.generation &&
                        tag.selectionToken == loopPolicy.selectionToken &&
                        samePhysicalRevision &&
                        tag.loopPolicyRevision == loopPolicy.physicalRevision &&
                        previousTimelineTag.loopEnabled && tag.loopEnabled &&
                        previousTimelineTag.loopRevision == loopPolicy.physicalRevision &&
                        tag.loopRevision == loopPolicy.physicalRevision &&
                        expectedNext == static_cast<std::int64_t>(
                            loopPolicy.bTimelineFrameExclusive) &&
                        tag.timelineFrame == static_cast<std::int64_t>(
                            loopPolicy.aTimelineFrame) &&
                        previousTimelineTag.loopCycle !=
                            std::numeric_limits<std::uint64_t>::max() &&
                        tag.loopCycle == previousTimelineTag.loopCycle + 1U;
                    const bool managedColdActivation = !previousTimelineTag.loopEnabled &&
                        previousContext.empty() && previousTimelineTag.terminalWriteBoundary != 0U &&
                        tag.loopCycle == 1U;
                    const bool managedWrap = !loopPolicy.valid() && sameOwner && samePhysicalRevision &&
                        contextsCoverTags && managedContext.valid() &&
                        (previousContext == managedContext || managedColdActivation) &&
                        expectedNext == static_cast<std::int64_t>(managedContext.endTimelineFrameExclusive) &&
                        tag.timelineFrame == static_cast<std::int64_t>(managedContext.startTimelineFrame) &&
                        (managedColdActivation ||
                         (previousTimelineTag.loopCycle != std::numeric_limits<std::uint64_t>::max() &&
                          tag.loopCycle == previousTimelineTag.loopCycle + 1U));
                    if (!linear && !exactWrap && !managedWrap) {
#if defined(STEMWISE_CACHE_IO_PROFILING)
                        AudioTrace trace("StemWise.audio.continuity_fault");
                        trace.counter("StemWise.audio.continuity.prev_frame", previousTimelineTag.timelineFrame);
                        trace.counter("StemWise.audio.continuity.prev_next", previousTimelineTag.nextTimelineFrame);
                        trace.counter("StemWise.audio.continuity.prev_generation", std::bit_cast<std::int64_t>(previousTimelineTag.generation));
                        trace.counter("StemWise.audio.continuity.prev_token", std::bit_cast<std::int64_t>(previousTimelineTag.selectionToken));
                        trace.counter("StemWise.audio.continuity.prev_physical", std::bit_cast<std::int64_t>(previousTimelineTag.loopPolicyRevision));
                        trace.counter("StemWise.audio.continuity.prev_revision", std::bit_cast<std::int64_t>(previousTimelineTag.loopRevision));
                        trace.counter("StemWise.audio.continuity.prev_cycle", std::bit_cast<std::int64_t>(previousTimelineTag.loopCycle));
                        trace.counter("StemWise.audio.continuity.prev_enabled", previousTimelineTag.loopEnabled ? 1 : 0);
                        trace.counter("StemWise.audio.continuity.frame", tag.timelineFrame);
                        trace.counter("StemWise.audio.continuity.next", tag.nextTimelineFrame);
                        trace.counter("StemWise.audio.continuity.generation", std::bit_cast<std::int64_t>(tag.generation));
                        trace.counter("StemWise.audio.continuity.token", std::bit_cast<std::int64_t>(tag.selectionToken));
                        trace.counter("StemWise.audio.continuity.physical", std::bit_cast<std::int64_t>(tag.loopPolicyRevision));
                        trace.counter("StemWise.audio.continuity.revision", std::bit_cast<std::int64_t>(tag.loopRevision));
                        trace.counter("StemWise.audio.continuity.cycle", std::bit_cast<std::int64_t>(tag.loopCycle));
                        trace.counter("StemWise.audio.continuity.enabled", tag.loopEnabled ? 1 : 0);
                        trace.counter("StemWise.audio.continuity.policy_enabled", loopPolicy.enabled ? 1 : 0);
                        trace.counter("StemWise.audio.continuity.policy_physical", std::bit_cast<std::int64_t>(loopPolicy.physicalRevision));
                        trace.counter("StemWise.audio.continuity.policy_a", std::bit_cast<std::int64_t>(loopPolicy.aTimelineFrame));
                        trace.counter("StemWise.audio.continuity.policy_b", std::bit_cast<std::int64_t>(loopPolicy.bTimelineFrameExclusive));
                        trace.counter("StemWise.audio.continuity.policy_generation", std::bit_cast<std::int64_t>(loopPolicy.generation));
                        trace.counter("StemWise.audio.continuity.policy_token", std::bit_cast<std::int64_t>(loopPolicy.selectionToken));
#endif
                        saturatingIncrement(continuityViolationCount);
                    }
                }
                previousTimelineTag = tag;
                hasPreviousTimelineTag = true;
                if (outputTimelineTags != nullptr) {
                    outputTimelineTags[run.leaseFrameOffset + frame] = {
                        .generation = tag.generation,
                        .selectionToken = tag.selectionToken,
                        .timelineFrame = tag.timelineFrame,
                    };
                }
            }
            previousManagedContext = managedContext;
            consumeSettlementFrames(run.frameCount);
            const bool wholeRunSettled =
                settledBeforeRun && mSettlementFramesRemaining == 0U;
            const FinalPcmFrameTag firstFinal = finalTag(
                firstRenderTag,
                wholeRunSettled);
            const FinalPcmFrameTag lastFinal = finalTag(
                lastRenderTag,
                wholeRunSettled);
            if (finalFrameTags != nullptr) {
                for (std::size_t frame = 0U; frame < run.frameCount; ++frame) {
                    const auto index = run.leaseFrameOffset + frame;
                    const auto &tag = run.tags[frame];
                    finalFrameTags[index] = finalTag(tag, wholeRunSettled);
                    if (mappingRuns != nullptr) {
                        mappingRuns[index] = {
                            .generation = tag.generation, .selectionToken = tag.selectionToken,
                            .firstFingerprintSequence = tag.payloadSequence, .lastFingerprintSequence = tag.payloadSequence,
                            .pcmFrames = 1U, .firstLoopPolicyRevision = tag.loopPolicyRevision,
                            .lastLoopPolicyRevision = tag.loopPolicyRevision,
                            .firstTimelineFrame = tag.timelineFrame, .lastTimelineFrame = tag.timelineFrame,
                            .renderSourceStartFrame = tag.renderSourceStartFrame,
                            .renderSourceEndFrameExclusive = tag.renderSourceEndFrameExclusive,
                            .renderFrameCount = tag.renderFrameCount, .firstRenderFrameOffset = tag.renderFrameOffset,
                            .mappingFirstRenderFrameOffset = tag.mappingFirstRenderFrameOffset,
                            .actualTempo = tag.actualTempo, .lastNextTimelineFrame = tag.nextTimelineFrame,
                        };
                    }
                }
            }
            if (mappingRuns != nullptr && finalFrameTags == nullptr) {
                auto &mapping = mappingRuns[run.leaseFrameOffset];
                mapping.generation = firstRenderTag.generation;
                mapping.selectionToken = firstRenderTag.selectionToken;
                mapping.firstFingerprintSequence = firstRenderTag.payloadSequence;
                mapping.lastFingerprintSequence = lastRenderTag.payloadSequence;
                mapping.pcmFrames = run.frameCount;
                mapping.firstLoopPolicyRevision = firstRenderTag.loopPolicyRevision;
                mapping.lastLoopPolicyRevision = lastRenderTag.loopPolicyRevision;
                mapping.firstTimelineFrame = firstRenderTag.timelineFrame;
                mapping.lastTimelineFrame = lastRenderTag.timelineFrame;
                mapping.renderSourceStartFrame = firstRenderTag.renderSourceStartFrame;
                mapping.renderSourceEndFrameExclusive = firstRenderTag.renderSourceEndFrameExclusive;
                mapping.renderFrameCount = firstRenderTag.renderFrameCount;
                mapping.firstRenderFrameOffset = firstRenderTag.renderFrameOffset;
                mapping.mappingFirstRenderFrameOffset = firstRenderTag.mappingFirstRenderFrameOffset;
                mapping.actualTempo = firstRenderTag.actualTempo;
                mapping.lastNextTimelineFrame = lastRenderTag.nextTimelineFrame;
            }
            tuningCoherent = tuningCoherent && firstFinal.actualTempo > 0.0F &&
                std::isfinite(firstFinal.actualTempo) && std::isfinite(firstFinal.actualPitchSemitones) &&
                firstFinal.actualTempo == lastFinal.actualTempo &&
                firstFinal.actualPitchSemitones == lastFinal.actualPitchSemitones &&
                (firstRun || (firstFinal.actualTempo == coherentFirst.actualTempo &&
                    firstFinal.actualPitchSemitones == coherentFirst.actualPitchSemitones));
            if (firstRun) {
                coherentFirst = firstFinal;
                controlCoherent = wholeRunSettled;
                stemCoherent = wholeRunSettled &&
                    firstFinal.actualSourceMode == FinalPcmSourceMode::Stems;
                loopCoherent = firstFinal.loopEnabled;
                firstRun = false;
            } else {
                controlCoherent = controlCoherent && wholeRunSettled &&
                    firstFinal.generation == coherentFirst.generation &&
                    firstFinal.selectionToken == coherentFirst.selectionToken &&
                    firstFinal.controlRevision == coherentFirst.controlRevision &&
                    firstFinal.actualSourceMode == coherentFirst.actualSourceMode;
                stemCoherent = stemCoherent && wholeRunSettled &&
                    firstFinal.generation == coherentFirst.generation &&
                    firstFinal.selectionToken == coherentFirst.selectionToken &&
                    firstFinal.actualSourceMode == FinalPcmSourceMode::Stems &&
                    firstFinal.stemGainPreviewPublicationSequence ==
                        coherentFirst.stemGainPreviewPublicationSequence &&
                    firstFinal.stemGainPreviewCoversOfferSequence ==
                        coherentFirst.stemGainPreviewCoversOfferSequence &&
                    firstFinal.stemGainPreviewActiveMask ==
                        coherentFirst.stemGainPreviewActiveMask &&
                    firstFinal.stemGainPreviewGainBits ==
                        coherentFirst.stemGainPreviewGainBits;
                loopCoherent = loopCoherent && firstFinal.loopEnabled &&
                    firstFinal.generation == coherentFirst.generation &&
                    firstFinal.selectionToken == coherentFirst.selectionToken &&
                    firstFinal.loopRevision == coherentFirst.loopRevision &&
                    firstFinal.loopCycle == coherentFirst.loopCycle;
            }
            coherentLast = lastFinal;
        });

    // Source storage remains leased until every run has finished reading and mixing.
    if (!ring.advanceRead(lease)) {
        std::memset(interleavedStereo, 0, lease.frameCount * 2U * sizeof(float));
        if (outputTimelineTags != nullptr) {
            std::fill_n(outputTimelineTags, lease.frameCount, OutputTimelineFrameTag{});
        }
        saturatingIncrement(mDiagnostics.advanceFailures);
        return {};
    }
    if (!completeBlocks || !allRunsValid || firstRun) {
        // A partially controlled callback cannot publish valid ownership/evidence. Conservative
        // whole-lease silence also prevents a malformed run from causing an audible discontinuity.
        std::memset(interleavedStereo, 0, lease.frameCount * 2U * sizeof(float));
        if (outputTimelineTags != nullptr) {
            std::fill_n(outputTimelineTags, lease.frameCount, OutputTimelineFrameTag{});
        }
        return result;
    }

    result.firstTag = coherentFirst;
    result.lastTag = coherentLast;
    result.audibleControlTupleCoherent = controlCoherent;
    result.audibleTuningTupleCoherent = tuningCoherent;
    result.audibleStemGainTupleCoherent = stemCoherent;
    result.audibleLoopTupleCoherent = loopCoherent;
    result.continuityViolationCount = continuityViolationCount;
    return result;
}

const NativeAudioOutputMultibusStageDiagnostics &
NativeAudioOutputMultibusCallbackStage::diagnostics() const noexcept {
    return mDiagnostics;
}

}  // namespace stemwise::audio
