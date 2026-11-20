#pragma once

#include "StemRoleLimits.h"
#include <array>
#include <cstddef>
#include <cstdint>

namespace stemwise::audio {

template <typename T, std::size_t Count>
[[nodiscard]] constexpr std::array<T, Count> stemwiseFilledArray(T value) noexcept {
    std::array<T, Count> values{};
    values.fill(value);
    return values;
}

/** Bounded transport capacity. Only a song's declared source lanes carry music. */
inline constexpr std::size_t kStemwiseStemBusCount = kStemwiseMaximumParts;
inline constexpr std::size_t kStemwiseMusicBusCount = kStemwiseStemBusCount + 1U;
inline constexpr std::size_t kStemwiseMusicChannelCount = kStemwiseMusicBusCount * 2U;
inline constexpr std::size_t kStemwiseMultibusClickChannel = kStemwiseMusicChannelCount;
inline constexpr std::size_t kStemwiseMultibusEnvelopeChannel =
    kStemwiseMultibusClickChannel + 1U;
inline constexpr std::size_t kStemwiseMultibusStride =
    kStemwiseMultibusEnvelopeChannel + 1U;
inline constexpr std::uint32_t kStemwiseAllStemBusesReadyMask =
    (1U << static_cast<std::uint32_t>(kStemwiseStemBusCount)) - 1U;

[[nodiscard]] constexpr std::size_t stemwiseOriginalChannel(std::size_t stereoChannel) noexcept {
    return stereoChannel;
}

[[nodiscard]] constexpr std::size_t stemwiseStemChannel(
    std::size_t stemLane,
    std::size_t stereoChannel) noexcept {
    return 2U + stemLane * 2U + stereoChannel;
}

[[nodiscard]] constexpr std::uint32_t stemwiseRequiredStemMask(std::size_t count) noexcept {
    return count <= kStemwiseStemBusCount ? (1U << static_cast<std::uint32_t>(count)) - 1U : 0U;
}

[[nodiscard]] constexpr std::uint32_t stemwiseRequiredSongMask(std::size_t count) noexcept {
    return count <= kStemwiseStemBusCount ? (stemwiseRequiredStemMask(count) << 1U) | 1U : 0U;
}

static_assert(kStemwiseMultibusStride == (kStemwiseMaximumTargets + 2U) * 2U + 2U);

}  // namespace stemwise::audio
