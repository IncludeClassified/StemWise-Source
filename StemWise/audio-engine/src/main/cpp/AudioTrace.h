#pragma once

#if defined(STEMWISE_CACHE_IO_PROFILING)
#include <android/trace.h>
#include <cstdint>

namespace stemwise::audio {

/** Explicit audit profiling only; static names, no allocation or formatting. */
class AudioTrace final {
public:
    explicit AudioTrace(const char *name) noexcept : mEnabled(ATrace_isEnabled()) {
        if (mEnabled) ATrace_beginSection(name);
    }
    ~AudioTrace() { if (mEnabled) ATrace_endSection(); }
    AudioTrace(const AudioTrace &) = delete;
    AudioTrace &operator=(const AudioTrace &) = delete;

    [[nodiscard]] bool enabled() const noexcept { return mEnabled; }
    void counter(const char *name, std::int64_t value) const noexcept {
        if (mEnabled) ATrace_setCounter(name, value);
    }
private:
    const bool mEnabled;
};

} // namespace stemwise::audio
#endif
