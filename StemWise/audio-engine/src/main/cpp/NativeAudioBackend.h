#pragma once

#include <cstdint>

namespace stemwise::audio {

/** Output adapter identity; independent of any platform library's enum. */
enum class NativeAudioBackend : std::int32_t {
    Unspecified = 0,
    AudioTrack = 3,
};

/** Platform observations use this app-owned wire vocabulary. */
enum class NativeAudioStreamState : std::int32_t {
    Uninitialized = 0, Unknown = 1, Open = 2, Started = 4,
    Paused = 6, Closed = 12, Disconnected = 13,
};
enum class NativeAudioOutputError : std::int32_t {
    ErrorInternal = -1, ErrorInvalidState = -2, ErrorInvalidFormat = -3, ErrorUnavailable = -4,
};

} // namespace stemwise::audio
