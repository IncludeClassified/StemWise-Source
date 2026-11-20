#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <new>
#if defined(_WIN32)
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#else
#include <unistd.h>
#endif

namespace stemwise::audio {
[[nodiscard]] inline std::size_t audioMemoryBytes(std::size_t count, std::size_t elementBytes) {
    if (elementBytes != 0U && count > (std::numeric_limits<std::size_t>::max)() / elementBytes)
        throw std::bad_array_new_length();
    return count * elementBytes;
}
[[nodiscard]] inline std::size_t addAudioMemoryBytes(std::size_t first, std::size_t second) {
    if (first > (std::numeric_limits<std::size_t>::max)() - second) throw std::bad_array_new_length();
    return first + second;
}
struct AudioMemoryFootprint final {
    std::size_t minimumBytes{}, peakBytes{};
    void admit(std::uint64_t availableBytes) const {
        if (minimumBytes > peakBytes || peakBytes > availableBytes) throw std::bad_alloc();
    }
};

/** Setup/control only. This is a machine-capacity snapshot, not a developer RAM quota or reservation.
 * Later allocation failures still reject before PCM publication; the audio consumer never probes RAM. */
[[nodiscard]] inline std::uint64_t availableAudioMemoryBytes() noexcept {
#if defined(_WIN32)
    MEMORYSTATUSEX state{};
    state.dwLength = sizeof(state);
    return GlobalMemoryStatusEx(&state) ? std::min(state.ullAvailPhys, state.ullAvailPageFile) : 0U;
#else
    const auto pages = ::sysconf(_SC_AVPHYS_PAGES), pageBytes = ::sysconf(_SC_PAGESIZE);
    if (pages <= 0 || pageBytes <= 0) return 0U;
    const auto count = static_cast<std::uint64_t>(pages), size = static_cast<std::uint64_t>(pageBytes);
    return count > (std::numeric_limits<std::uint64_t>::max)() / size
        ? (std::numeric_limits<std::uint64_t>::max)() : count * size;
#endif
}
} // namespace stemwise::audio
