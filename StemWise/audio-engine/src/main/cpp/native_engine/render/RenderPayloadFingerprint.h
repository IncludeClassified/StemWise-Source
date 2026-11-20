#pragma once
#include "StemwiseMultibus.h"
#include <cmath>
#include <cstdint>
#include <cstddef>
#include <algorithm>
#include <limits>
namespace stemwise::audio::engine {
namespace payload_fingerprint {
inline constexpr std::uint64_t kFnvOffset = 14'695'981'039'346'656'037ULL;
inline constexpr std::uint64_t kFnvPrime = 1'099'511'628'211ULL;
inline std::int16_t quantizeFingerprintSample(float sample, std::uint64_t &nonFiniteSamples) noexcept {
    if (!std::isfinite(sample)) {
        if (nonFiniteSamples != std::numeric_limits<std::uint64_t>::max()) ++nonFiniteSamples;
        sample = 0.0F;
    }
    const float clamped = std::clamp(sample, -1.0F, 1.0F);
    const float scaled = clamped * 32'767.0F;
    const std::int32_t rounded = scaled >= 0.0F
        ? static_cast<std::int32_t>(scaled + 0.5F)
        : static_cast<std::int32_t>(scaled - 0.5F);
    return static_cast<std::int16_t>(rounded);
}

inline void hashFingerprintValue(std::uint64_t &hash, std::int16_t value) noexcept {
    const std::uint16_t bits = static_cast<std::uint16_t>(value);
    hash ^= static_cast<std::uint8_t>(bits & 0xFFU);
    hash *= kFnvPrime;
    hash ^= static_cast<std::uint8_t>((bits >> 8U) & 0xFFU);
    hash *= kFnvPrime;
}

inline std::uint64_t fingerprintRenderPayload(
    const float *interleavedPayload,
    std::uint32_t frameCount,
    std::uint64_t &nonFiniteSamples) noexcept {
    std::uint64_t hash = kFnvOffset;
    for (std::uint32_t frame = 0U; frame < frameCount; ++frame) {
        const float *payload = interleavedPayload +
            static_cast<std::size_t>(frame) * kStemwiseMultibusStride;
        for (std::size_t channel = 0U; channel < kStemwiseMultibusStride; ++channel) {
            hashFingerprintValue(hash, quantizeFingerprintSample(payload[channel], nonFiniteSamples));
        }
    }
    return hash == 0U ? kFnvOffset : hash;
}

}
}
