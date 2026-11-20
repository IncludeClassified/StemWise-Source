#pragma once

#include "LateStemMixer.h"
#include "FinalPcmFrameTag.h"

#include <cstddef>
#include <cstdint>

namespace stemwise::audio::engine {

/** Borrowed immutable render payload; enqueue never retains the sample view. */
struct NativeRenderBusSubmission final {
    std::uint64_t generation{};
    std::uint64_t selectionToken{};
    std::uint64_t payloadSequence{};
    std::uint64_t payloadHash{};
    std::uint64_t canonicalControlRevision{};
    std::uint64_t sourceIntentRevision{};
    std::uint64_t stemIntentRevision{};
    std::uint32_t stemBusReadyMask{};
    /** Immutable compatible endpoint owned by every accepted PCM block. */
    LateStemMixControlSnapshot baseControl{};
    /** Numerical DSP tuple represented by this payload; zero tempo means transition/unknown. */
    float actualTempo{0.0F};
    float actualPitchSemitones{0.0F};

    std::uint64_t loopPolicyRevision{};
    std::uint64_t loopRevision{};
    std::uint64_t loopCycle{};
    bool loopEnabled{};
    ManagedLoopRenderContext managedLoopContext{};
    bool endOfStreamAfterInput{};
    // Full rendered origin remains immutable across partial enqueue receipts.
    double renderSourceStartFrame{};
    std::uint32_t renderFrameCount{};
    std::uint32_t renderFrameOffset{};
    double sourceStartFrame{};
    double sourceEndFrameExclusive{};
    const float * samples{};
    std::size_t frameCount{};
};

} // namespace stemwise::audio::engine
