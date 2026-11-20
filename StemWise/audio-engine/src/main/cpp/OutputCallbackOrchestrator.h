#pragma once

#include "NativeAudioOutputFinalDeviceStage.h"
#include "NativeAudioOutputMultibusCallbackStage.h"
#include "OutputGenerationTransition.h"
#include "OutputRingRoleCoordinator.h"
#include <limits>
#include <span>

namespace stemwise::audio {

// Callback-local operations share the production ordering without owning another copy of state.
[[nodiscard]] inline bool callbackAcknowledgeCancellation(
    OutputRingRoleCoordinator &roles, std::uint64_t &appliedSequence) noexcept {
    const auto cancellation = roles.callbackCancellation(appliedSequence);
    if (!cancellation.valid()) return false;
    appliedSequence = cancellation.sequence;
    roles.acknowledgeCancellation(cancellation);
    return true;
}

[[nodiscard]] inline bool callbackAdoptRoleSwap(
    OutputRingRoleCoordinator &roles, const OutputRoleSwap &swap,
    std::uint32_t &ring, std::uint64_t &generation,
    std::uint64_t &appliedSequence) noexcept {
    if (!swap.valid() || swap.expectedActiveGeneration != generation ||
        !roles.commitCallbackSwap(swap)) return false;
    ring = swap.ring;
    generation = swap.generation;
    appliedSequence = swap.sequence;
    roles.acknowledge(swap);
    return true;
}

[[nodiscard]] inline TaggedStereoReadResult callbackReadMultibus(
    NativeAudioOutputMultibusCallbackStage &stage,
    std::uint32_t role, std::uint64_t generation, SpscTaggedMultibusRingBuffer &ring,
    const LateStemMixControlMailbox &mailbox, float *output, std::size_t maximumFrames,
    OutputTimelineFrameTag *tags = nullptr, OutputTimelineLoopPolicy policy = {},
    NativeAudioPresentationSpan *mappingRuns = nullptr,
    FinalPcmFrameTag *finalFrameTags = nullptr) noexcept {
    return stage.readAndMix(role, generation, ring, mailbox, output, maximumFrames, tags, policy, mappingRuns, finalFrameTags);
}

// A short read has at most one successor. Never consume that role before the exact handoff commits.
template <typename Adopt, typename Read>
[[nodiscard]] std::size_t callbackReadDrainRemainder(
    std::size_t remaining, Adopt &&adopt, Read &&read) noexcept {
    return remaining != 0U && adopt() ? read(remaining) : 0U;
}

[[nodiscard]] inline bool callbackControlEvidenceEligible(
    const TaggedStereoReadResult &read, bool wholeCallbackOwnedPcm,
    bool transitionTouchedOwnedCallback) noexcept {
    return !transitionTouchedOwnedCallback && wholeCallbackOwnedPcm &&
        read.audibleControlTupleCoherent && read.firstTag.sourceTransitionSettled &&
        read.lastTag.sourceTransitionSettled && read.firstTag.controlRevision != 0U &&
        read.lastTag.controlRevision == read.firstTag.controlRevision &&
        read.lastTag.actualSourceMode == read.firstTag.actualSourceMode;
}

struct OutputCallbackFinalization final {
    OutputTransitionCoverage coverage{};
    bool complete{true};
};

struct OutputCallbackRecovery final {
    bool shortage{false};
    void reset() noexcept { shortage = false; }
};

[[nodiscard]] inline OutputCallbackFinalization finalizeOutputCallback(
    float *output, std::size_t requestedFrames, std::span<const OutputOwnedFrameSpan> ownedSpans,
    bool playing, std::size_t generationTransitionOffset, float lastLeft, float lastRight,
    std::int32_t sampleRate, OutputGenerationTransition &transition,
    NativeAudioOutputFinalDeviceStage &finalStage, OutputCallbackRecovery &recovery) noexcept {
    OutputCallbackFinalization result{};
    std::size_t spanIndex = 0U;
    for (std::size_t offset = 0U; offset < requestedFrames;) {
        while (spanIndex < ownedSpans.size() &&
            ownedSpans[spanIndex].outputOffset + ownedSpans[spanIndex].frameCount <= offset) {
            ++spanIndex;
        }
        const bool owned = spanIndex < ownedSpans.size() &&
            ownedSpans[spanIndex].outputOffset <= offset;
        std::size_t end = spanIndex == ownedSpans.size() ? requestedFrames :
            std::min(requestedFrames, owned ?
                ownedSpans[spanIndex].outputOffset + ownedSpans[spanIndex].frameCount :
                ownedSpans[spanIndex].outputOffset);
        if (generationTransitionOffset > offset && generationTransitionOffset < end)
            end = generationTransitionOffset;
        const bool shortage = playing && !owned;
        if ((playing && shortage != recovery.shortage) || generationTransitionOffset == offset) {
            const float fromLeft = offset == 0U ? lastLeft : output[(offset - 1U) * 2U];
            const float fromRight = offset == 0U ? lastRight : output[(offset - 1U) * 2U + 1U];
            transition.begin(fromLeft, fromRight,
                OutputGenerationTransition::framesForSampleRate(sampleRate));
        }
        recovery.shortage = shortage;
        const auto transformed = transition.apply(output + offset * 2U, end - offset);
        result.complete = result.coverage.record(offset, transformed) && result.complete;
        offset = end;
    }

    finalStage.finalize(output, requestedFrames);
    return result;
}

}  // namespace stemwise::audio
