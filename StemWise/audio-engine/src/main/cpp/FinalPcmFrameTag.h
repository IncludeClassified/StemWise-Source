#pragma once

#include "StemwiseMultibus.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <limits>

namespace stemwise::audio {

inline constexpr std::size_t kFinalPcmStemGainLaneCount = kStemwiseStemBusCount;

enum class FinalPcmSourceMode : std::uint8_t {
    Original = 0,
    Stems = 1,
};

/** Immutable ownership and audible-control evidence attached to device-bound PCM. */
struct FinalPcmFrameTag final {
    std::uint64_t generation = 0;
    std::uint64_t selectionToken = 0;
    std::uint64_t fingerprintSequence = 0;
    std::uint64_t controlRevision = 0;
    /** Actual numerical render tuple; zero tempo means transition/unknown. */
    float actualTempo = 0.0F;
    float actualPitchSemitones = 0.0F;
    FinalPcmSourceMode actualSourceMode = FinalPcmSourceMode::Original;
    bool sourceTransitionSettled = false;
    std::uint64_t stemGainPreviewPublicationSequence = 0;
    std::uint64_t stemGainPreviewCoversOfferSequence = 0;
    std::uint32_t stemGainPreviewActiveMask = 0;
    std::array<std::uint32_t, kFinalPcmStemGainLaneCount> stemGainPreviewGainBits{};
    std::uint64_t loopPolicyRevision = 0;
    std::uint64_t loopRevision = 0;
    std::uint64_t loopCycle = 0;
    bool loopEnabled = false;
    std::uint64_t terminalWriteBoundary = 0;
    std::int64_t timelineFrame = 0;
    /** Exact producer mapping after this frame; zero is unknown. */
    std::int64_t nextTimelineFrame = 0;

    [[nodiscard]] bool operator==(const FinalPcmFrameTag &) const noexcept = default;
};

/** Minimal per-device-frame ownership needed to map a device timestamp back to song time. */
struct OutputTimelineFrameTag final {
    std::uint64_t generation = 0;
    std::uint64_t selectionToken = 0;
    std::int64_t timelineFrame = -1;

    [[nodiscard]] constexpr bool valid() const noexcept {
        return generation != 0U && selectionToken != 0U && timelineFrame >= 0;
    }
};

/** Exact physical loop range governing one callback read, or invalid for linear playback. */
struct OutputTimelineLoopPolicy final {
    std::uint64_t generation = 0;
    std::uint64_t selectionToken = 0;
    std::uint64_t physicalRevision = 0;
    bool enabled = false;
    std::uint64_t aTimelineFrame = 0;
    std::uint64_t bTimelineFrameExclusive = 0;

    [[nodiscard]] constexpr bool valid() const noexcept {
        return generation != 0U && selectionToken != 0U && physicalRevision != 0U && enabled &&
            aTimelineFrame < bTimelineFrameExclusive &&
            bTimelineFrameExclusive <= static_cast<std::uint64_t>(
                std::numeric_limits<std::int64_t>::max());
    }
};

/** Actual installed managed-loop authority retained once with its rendered PCM block. */
struct ManagedLoopRenderContext final {
    std::uint64_t revision{0U};
    std::uint64_t startTimelineFrame{0U};
    std::uint64_t endTimelineFrameExclusive{0U};

    [[nodiscard]] constexpr bool empty() const noexcept {
        return revision == 0U && startTimelineFrame == 0U && endTimelineFrameExclusive == 0U;
    }
    [[nodiscard]] constexpr bool valid() const noexcept {
        return revision != 0U && startTimelineFrame < endTimelineFrameExclusive &&
            endTimelineFrameExclusive <= static_cast<std::uint64_t>(
                std::numeric_limits<std::int64_t>::max());
    }
    [[nodiscard]] constexpr bool operator==(const ManagedLoopRenderContext &) const noexcept = default;
};

struct TaggedStereoReadResult final {
    std::size_t frameCount = 0;
    std::uint64_t absoluteFirstFrame = 0;
    FinalPcmFrameTag firstTag{};
    FinalPcmFrameTag lastTag{};
    bool audibleControlTupleCoherent = false;
    bool audibleTuningTupleCoherent = false;
    bool audibleStemGainTupleCoherent = false;
    bool audibleLoopTupleCoherent = false;
    std::uint64_t continuityViolationCount = 0;
};

}  // namespace stemwise::audio
