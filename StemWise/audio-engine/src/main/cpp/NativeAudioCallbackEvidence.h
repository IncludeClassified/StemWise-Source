#pragma once

#include <cstddef>

namespace stemwise::audio {

/**
 * Whole-callback diagnostic evidence is valid only when every requested device frame came from
 * one owned PCM span. A coherent tagged prefix followed by invalid or synthetic underrun silence
 * must not establish continuity for controls, stem gains, or loops.
 */
[[nodiscard]] constexpr bool completeOwnedCallbackPcm(
    bool mixedOwnerCallback,
    std::size_t validPcmFrames,
    std::size_t requestedFrames) noexcept {
    return !mixedOwnerCallback && requestedFrames != 0U &&
        validPcmFrames == requestedFrames;
}

/** A silent callback cannot prove that a newly tagged gain has influenced audible signal. */
[[nodiscard]] constexpr bool stemGainCallbackCanEstablishContinuity(
    bool mixedOwnerCallback,
    std::size_t validPcmFrames,
    std::size_t requestedFrames,
    std::size_t nonZeroPcmFrameCount) noexcept {
    return completeOwnedCallbackPcm(
        mixedOwnerCallback,
        validPcmFrames,
        requestedFrames) && nonZeroPcmFrameCount != 0U;
}

}  // namespace stemwise::audio
