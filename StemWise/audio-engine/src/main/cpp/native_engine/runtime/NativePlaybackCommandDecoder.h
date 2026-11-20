#pragma once

#include "../../NativePacketSchema.h"
#include "NativePlaybackRuntime.h"
#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <limits>
#include <tuple>

namespace stemwise::audio::engine::command_codec {

inline constexpr std::int64_t kCommandSchemaVersion = stemwise::audio::packet::COMMAND_VERSION;
inline constexpr std::size_t kCommandLongCount = stemwise::audio::packet::COMMAND_LONG_COUNT;
inline constexpr std::size_t kCommandFloatCount = stemwise::audio::packet::COMMAND_FLOAT_COUNT;
inline constexpr std::size_t kCommandBankCount = 2U;
static_assert(kCommandBankCount == std::tuple_size_v<decltype(NativePlaybackCommandSnapshot::banks)>);
inline constexpr std::size_t kCommandBankIntentBase = stemwise::audio::packet::COMMAND_LONG_BANK_0_REVISION;
inline constexpr std::size_t kCommandBankIntentStride = 6U;
inline constexpr std::size_t kCommandStemControlBase = stemwise::audio::packet::COMMAND_LONG_LANE_0_MUTED;
inline constexpr std::size_t kCommandBankHashBase = stemwise::audio::packet::COMMAND_LONG_BANK_0_SONG_HASH;
inline constexpr std::size_t kCommandBankHashStride = 3U;
inline constexpr std::size_t kCommandStemLaneCountBase = kCommandBankHashBase + kCommandBankCount * kCommandBankHashStride;
inline constexpr std::size_t kCommandCacheFrameBoundBase = kCommandStemLaneCountBase + kCommandBankCount;
static_assert(kCommandBankIntentBase + kCommandBankCount * kCommandBankIntentStride <= kCommandLongCount);
static_assert(kCommandStemControlBase + stemwise::audio::kStemLaneCount * 2U <= kCommandBankHashBase);
static_assert(kCommandBankHashBase + kCommandBankCount * kCommandBankHashStride <= kCommandLongCount);
static_assert(kCommandStemLaneCountBase + kCommandBankCount <= kCommandLongCount);
inline constexpr std::size_t kCommandPlaybackEndBase = kCommandCacheFrameBoundBase + kCommandBankCount;
static_assert(kCommandPlaybackEndBase + kCommandBankCount == kCommandLongCount);

inline bool positiveUnsigned(std::int64_t value, std::uint64_t &result) noexcept {
    if (value <= 0) return false;
    result = static_cast<std::uint64_t>(value);
    return true;
}

inline bool nonNegativeUnsigned(std::int64_t value, std::uint64_t &result) noexcept {
    if (value < 0) return false;
    result = static_cast<std::uint64_t>(value);
    return true;
}

inline bool nonNegativeUint32(std::int64_t value, std::uint32_t &result) noexcept {
    if (value < 0 || static_cast<std::uint64_t>(value) >
            std::numeric_limits<std::uint32_t>::max()) {
        return false;
    }
    result = static_cast<std::uint32_t>(value);
    return true;
}

inline bool positiveUint32(std::int64_t value, std::uint32_t &result) noexcept {
    return value > 0 && nonNegativeUint32(value, result);
}

inline bool strictBoolean(std::int64_t value, bool &result) noexcept {
    if (value == 0) {
        result = false;
        return true;
    }
    if (value == 1) {
        result = true;
        return true;
    }
    return false;
}

[[nodiscard]] inline std::uint64_t rawUnsignedFromLong(std::int64_t value) noexcept {
    return std::bit_cast<std::uint64_t>(static_cast<std::int64_t>(value));
}

inline bool finiteBetween(float value, float minimum, float maximum) noexcept {
    return std::isfinite(value) && value >= minimum && value <= maximum;
}

template <typename Integer>
bool decodeCommand(
    const std::array<Integer, kCommandLongCount> &longs,
    const std::array<float, kCommandFloatCount> &floats,
    NativePlaybackCommandSnapshot &snapshot) noexcept {
    if (longs[stemwise::audio::packet::COMMAND_LONG_SCHEMA] != kCommandSchemaVersion ||
        !positiveUnsigned(longs[stemwise::audio::packet::COMMAND_LONG_REVISION], snapshot.revision) ||
        !positiveUnsigned(longs[stemwise::audio::packet::COMMAND_LONG_OUTPUT_GENERATION], snapshot.outputGeneration)) {
        return false;
    }

    for (std::size_t index = 0; index < snapshot.banks.size(); ++index) {
        const std::size_t base = kCommandBankIntentBase + index * kCommandBankIntentStride;
        auto &intent = snapshot.banks[index];
        if (!positiveUnsigned(longs[base], intent.revision) ||
            !strictBoolean(longs[base + 1U], intent.assigned)) {
            return false;
        }
        if (!nonNegativeUint32(longs[kCommandStemLaneCountBase + index], intent.stemLaneCount) ||
            intent.stemLaneCount > stemwise::audio::kStemLaneCount || intent.stemLaneCount == 1U) return false;
        if (!nonNegativeUnsigned(longs[kCommandCacheFrameBoundBase + index], intent.cacheFrameUpperBound) ||
            !nonNegativeUnsigned(longs[kCommandPlaybackEndBase + index], intent.playbackEndFrameExclusive)) return false;
        if (!intent.assigned) {
            if (longs[base + 2U] != 0 || longs[base + 3U] != 0 ||
                longs[base + 4U] != 0 || longs[base + 5U] != 0 || intent.stemLaneCount != 0U ||
                longs[kCommandBankHashBase + index * kCommandBankHashStride] != 0 ||
                longs[kCommandBankHashBase + 1U + index * kCommandBankHashStride] != 0 ||
                longs[kCommandBankHashBase + 2U + index * kCommandBankHashStride] != 0 || intent.cacheFrameUpperBound != 0U || intent.playbackEndFrameExclusive != 0U) {
                return false;
            }
            continue;
        }
        if (!positiveUnsigned(longs[base + 2U], intent.ownership.selectionToken) ||
            !positiveUnsigned(longs[base + 3U], intent.ownership.generation) ||
            !nonNegativeUnsigned(longs[base + 4U], intent.firstTimelineFrame) ||
            longs[base + 5U] < 0) {
            return false;
        }
        if (intent.playbackEndFrameExclusive != 0U && intent.firstTimelineFrame >= intent.playbackEndFrameExclusive) return false;
        intent.outputClockStartFrame = static_cast<std::int64_t>(longs[base + 5U]);
        intent.songIdentityHash = rawUnsignedFromLong(longs[kCommandBankHashBase + index * kCommandBankHashStride]);
        intent.originalContentHash = rawUnsignedFromLong(longs[kCommandBankHashBase + 1U + index * kCommandBankHashStride]);
        intent.stemPackageHash = rawUnsignedFromLong(longs[kCommandBankHashBase + 2U + index * kCommandBankHashStride]);
        if (intent.songIdentityHash == 0U || intent.originalContentHash == 0U ||
            ((intent.stemPackageHash == 0U) != (intent.stemLaneCount == 0U))) return false;
    }

    if (!strictBoolean(longs[stemwise::audio::packet::COMMAND_LONG_SELECTED], snapshot.selectionAssigned) ||
        !strictBoolean(longs[stemwise::audio::packet::COMMAND_LONG_PLAYING], snapshot.playing)) {
        return false;
    }
    if (snapshot.selectionAssigned) {
        if (longs[stemwise::audio::packet::COMMAND_LONG_SELECTED_BANK] < 0 || longs[stemwise::audio::packet::COMMAND_LONG_SELECTED_BANK] > 1 ||
            !positiveUnsigned(longs[stemwise::audio::packet::COMMAND_LONG_SELECTED_TOKEN], snapshot.selectedOwnership.selectionToken) ||
            !positiveUnsigned(longs[stemwise::audio::packet::COMMAND_LONG_SELECTED_GENERATION], snapshot.selectedOwnership.generation)) {
            return false;
        }
        snapshot.selectedBank = static_cast<NativePlaybackBank>(longs[stemwise::audio::packet::COMMAND_LONG_SELECTED_BANK]);
    } else if (longs[stemwise::audio::packet::COMMAND_LONG_SELECTED_BANK] != 0 || longs[stemwise::audio::packet::COMMAND_LONG_SELECTED_TOKEN] != 0 || longs[stemwise::audio::packet::COMMAND_LONG_SELECTED_GENERATION] != 0 || snapshot.playing) {
        return false;
    }

    auto &controls = snapshot.controls;
    if (!positiveUnsigned(longs[stemwise::audio::packet::COMMAND_LONG_CONTROL_REVISION], controls.revision)) return false;
    controls.mixer.revision = controls.revision;
    if (longs[stemwise::audio::packet::COMMAND_LONG_SOURCE_MODE] < 0 || longs[stemwise::audio::packet::COMMAND_LONG_SOURCE_MODE] > 1) return false;
    controls.mixer.sourceMode = static_cast<MixerSourceMode>(longs[stemwise::audio::packet::COMMAND_LONG_SOURCE_MODE]);
    if (!positiveUint32(longs[stemwise::audio::packet::COMMAND_LONG_SOURCE_RAMP], controls.mixer.sourceTransitionFrames) ||
        !positiveUint32(longs[stemwise::audio::packet::COMMAND_LONG_LANE_RAMP], controls.mixer.laneRampFrames) ||
        !positiveUint32(longs[stemwise::audio::packet::COMMAND_LONG_MASTER_RAMP], controls.mixer.masterRampFrames) ||
        !positiveUint32(longs[stemwise::audio::packet::COMMAND_LONG_DECLICK], controls.mixer.defaultDeclickFrames) ||
        controls.mixer.sourceTransitionFrames > 96'000U ||
        controls.mixer.laneRampFrames > 96'000U ||
        controls.mixer.masterRampFrames > 96'000U ||
        controls.mixer.defaultDeclickFrames > 96'000U) {
        return false;
    }

    for (std::size_t index = 0; index < controls.mixer.stems.size(); ++index) {
        const std::size_t base = kCommandStemControlBase + index * 2U;
        auto &stem = controls.mixer.stems[index];
        if (!finiteBetween(floats[index], 0.0F, 2.0F) ||
            !strictBoolean(longs[base], stem.muted) ||
            !strictBoolean(longs[base + 1U], stem.soloed)) {
            return false;
        }
        stem.gain = floats[index];
    }

    if (!finiteBetween(floats[stemwise::audio::packet::COMMAND_FLOAT_MUSIC_GAIN], 0.0F, 2.0F) ||
        !finiteBetween(floats[stemwise::audio::packet::COMMAND_FLOAT_HEADROOM], 0.0F, 1.0F) ||
        longs[stemwise::audio::packet::COMMAND_LONG_METRONOME_MODE] < 0 || longs[stemwise::audio::packet::COMMAND_LONG_METRONOME_MODE] > 2 ||
        (longs[stemwise::audio::packet::COMMAND_LONG_METRONOME_SUBDIVISION] != 1 && longs[stemwise::audio::packet::COMMAND_LONG_METRONOME_SUBDIVISION] != 2 && longs[stemwise::audio::packet::COMMAND_LONG_METRONOME_SUBDIVISION] != 4) ||
        longs[stemwise::audio::packet::COMMAND_LONG_METRONOME_METER] < 1 || longs[stemwise::audio::packet::COMMAND_LONG_METRONOME_METER] > 32 ||
        (longs[stemwise::audio::packet::COMMAND_LONG_METRONOME_UNIT] != 1 && longs[stemwise::audio::packet::COMMAND_LONG_METRONOME_UNIT] != 2 && longs[stemwise::audio::packet::COMMAND_LONG_METRONOME_UNIT] != 4 && longs[stemwise::audio::packet::COMMAND_LONG_METRONOME_UNIT] != 8 &&
         longs[stemwise::audio::packet::COMMAND_LONG_METRONOME_UNIT] != 16 && longs[stemwise::audio::packet::COMMAND_LONG_METRONOME_UNIT] != 32) ||
        !finiteBetween(floats[stemwise::audio::packet::COMMAND_FLOAT_METRONOME_BPM], 20.0F, 400.0F) ||
        !finiteBetween(floats[stemwise::audio::packet::COMMAND_FLOAT_METRONOME_GAIN], 0.0F, 1.0F)) {
        return false;
    }
    controls.mixer.musicGain = floats[stemwise::audio::packet::COMMAND_FLOAT_MUSIC_GAIN];
    controls.mixer.mixHeadroom = floats[stemwise::audio::packet::COMMAND_FLOAT_HEADROOM];
    controls.mixer.metronome.mode = static_cast<MetronomeMode>(longs[stemwise::audio::packet::COMMAND_LONG_METRONOME_MODE]);
    controls.mixer.metronome.subdivision = static_cast<std::uint8_t>(longs[stemwise::audio::packet::COMMAND_LONG_METRONOME_SUBDIVISION]);
    controls.mixer.metronome.beatsPerBar = static_cast<std::uint8_t>(longs[stemwise::audio::packet::COMMAND_LONG_METRONOME_METER]);
    controls.mixer.metronome.beatUnitDenominator =
        static_cast<std::uint8_t>(longs[stemwise::audio::packet::COMMAND_LONG_METRONOME_UNIT]);
    controls.mixer.metronome.bpm = floats[stemwise::audio::packet::COMMAND_FLOAT_METRONOME_BPM];
    controls.mixer.metronome.gain = floats[stemwise::audio::packet::COMMAND_FLOAT_METRONOME_GAIN];

    if (!finiteBetween(floats[stemwise::audio::packet::COMMAND_FLOAT_TEMPO], 0.5F, 2.0F) ||
        !finiteBetween(floats[stemwise::audio::packet::COMMAND_FLOAT_PITCH], -12.0F, 12.0F)) return false;
    controls.tempo = floats[stemwise::audio::packet::COMMAND_FLOAT_TEMPO];
    controls.pitchSemitones = floats[stemwise::audio::packet::COMMAND_FLOAT_PITCH];
    if (!nonNegativeUnsigned(longs[stemwise::audio::packet::COMMAND_LONG_SOURCE_INTENT], controls.sourceIntentRevision) ||
        !nonNegativeUnsigned(longs[stemwise::audio::packet::COMMAND_LONG_STEM_INTENT], controls.stemIntentRevision)) {
        return false;
    }

    bool automaticSuccessorPresent = false;
    if (!strictBoolean(longs[stemwise::audio::packet::COMMAND_LONG_SUCCESSOR_PRESENT], automaticSuccessorPresent)) return false;
    if (!automaticSuccessorPresent) {
        for (std::size_t index = stemwise::audio::packet::COMMAND_LONG_SUCCESSOR_REVISION;
             index <= stemwise::audio::packet::COMMAND_LONG_SUCCESSOR_INHERIT; ++index) {
            if (longs[index] != 0) return false;
        }
        return true;
    }

    NativePlaybackAutomaticSuccessorIntent automaticSuccessor{};
    automaticSuccessor.enabled = true;
    if (!positiveUnsigned(longs[stemwise::audio::packet::COMMAND_LONG_SUCCESSOR_REVISION], automaticSuccessor.revision) ||
        !positiveUnsigned(
            longs[stemwise::audio::packet::COMMAND_LONG_OUTGOING_TOKEN], automaticSuccessor.outgoingOwnership.selectionToken) ||
        !positiveUnsigned(longs[stemwise::audio::packet::COMMAND_LONG_OUTGOING_GENERATION], automaticSuccessor.outgoingOwnership.generation) ||
        longs[stemwise::audio::packet::COMMAND_LONG_SUCCESSOR_BANK] < 0 || longs[stemwise::audio::packet::COMMAND_LONG_SUCCESSOR_BANK] >= static_cast<Integer>(snapshot.banks.size()) ||
        !positiveUnsigned(
            longs[stemwise::audio::packet::COMMAND_LONG_SUCCESSOR_TOKEN], automaticSuccessor.successorOwnership.selectionToken) ||
        !positiveUnsigned(longs[stemwise::audio::packet::COMMAND_LONG_SUCCESSOR_GENERATION], automaticSuccessor.successorOwnership.generation) ||
        !strictBoolean(longs[stemwise::audio::packet::COMMAND_LONG_SUCCESSOR_INHERIT], automaticSuccessor.inheritOutgoingControls)) {
        return false;
    }
    automaticSuccessor.successorBank = static_cast<NativePlaybackBank>(longs[stemwise::audio::packet::COMMAND_LONG_SUCCESSOR_BANK]);

    const auto sameOwnership = [](const auto &left, const auto &right) noexcept {
        return left.selectionToken == right.selectionToken &&
            left.generation == right.generation;
    };
    const std::size_t selectedIndex = static_cast<std::size_t>(snapshot.selectedBank);
    const std::size_t successorIndex =
        static_cast<std::size_t>(automaticSuccessor.successorBank);
    if (!snapshot.selectionAssigned || !snapshot.playing ||
        selectedIndex >= snapshot.banks.size() || successorIndex >= snapshot.banks.size() ||
        automaticSuccessor.successorBank == snapshot.selectedBank ||
        !sameOwnership(automaticSuccessor.outgoingOwnership, snapshot.selectedOwnership) ||
        snapshot.outputGeneration != automaticSuccessor.outgoingOwnership.generation ||
        !snapshot.banks[selectedIndex].assigned ||
        !sameOwnership(
            snapshot.banks[selectedIndex].ownership,
            automaticSuccessor.outgoingOwnership) ||
        automaticSuccessor.successorOwnership.generation <=
            automaticSuccessor.outgoingOwnership.generation ||
        !snapshot.banks[successorIndex].assigned ||
        !sameOwnership(
            snapshot.banks[successorIndex].ownership,
            automaticSuccessor.successorOwnership)) {
        return false;
    }
    snapshot.automaticSuccessor = automaticSuccessor;
    return true;
}

} // namespace stemwise::audio::engine::command_codec
