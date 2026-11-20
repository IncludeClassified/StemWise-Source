#pragma once

#include "StemwiseMultibus.h"

#include <array>
#include <cstddef>
#include <cstdint>

// Small dyadic operands: every product and sum used here is exactly representable in binary32.
namespace merger_known_answer {
constexpr std::size_t kLanes = 9U;
static_assert(kLanes == stemwise::audio::kStemwiseStemBusCount);
using QuarterGains = std::array<std::int32_t, kLanes>;

inline std::int32_t numerator(std::uint64_t frame, std::size_t lane, std::size_t channel) {
    const auto value = static_cast<std::int32_t>(
        (lane + 1U) * 31U + (frame * (lane * 2U + 3U) + channel * 17U) % 251U);
    return channel == 0U ? value : -value;
}

inline float sample(std::uint64_t frame, std::size_t lane, std::size_t channel) {
    return static_cast<float>(numerator(frame, lane, channel)) / 65'536.0F;
}

inline float expected(std::uint64_t frame, std::size_t channel, const QuarterGains &gains) {
    std::int32_t sum = 0;
    for (std::size_t lane = 0U; lane < kLanes; ++lane)
        sum += numerator(frame, lane, channel) * gains[lane];
    return static_cast<float>(sum) / 262'144.0F;
}

inline void payloadFrame(float *destination, std::uint64_t frame) {
    using namespace stemwise::audio;
    for (std::size_t channel = 0U; channel < kStemwiseMultibusStride; ++channel)
        destination[channel] = 0.0F;
    destination[stemwiseOriginalChannel(0U)] = 0.375F;
    destination[stemwiseOriginalChannel(1U)] = -0.25F;
    for (std::size_t lane = 0U; lane < kLanes; ++lane)
        for (std::size_t channel = 0U; channel < 2U; ++channel)
            destination[stemwiseStemChannel(lane, channel)] = sample(frame, lane, channel);
    destination[kStemwiseMultibusEnvelopeChannel] = 1.0F;
}
}  // namespace merger_known_answer
