#pragma once

#include <cstdint>
#include <wavpack.h>

namespace stemwise::audio {

// One decoder worker owns this seekable descriptor slice; never called on the audio callback.
class WavPackReader final {
public:
    WavPackReader(int descriptor, std::int64_t offset, std::int64_t length);
    ~WavPackReader();
    WavPackReader(const WavPackReader &) = delete;
    WavPackReader &operator=(const WavPackReader &) = delete;
    [[nodiscard]] std::int64_t frames() const noexcept { return frames_; }
    std::uint32_t read(std::int64_t firstFrame, std::int32_t *destination, std::uint32_t maximumFrames);

private:
    int descriptor_ = -1;
    std::int64_t offset_, length_, position_ = 0, frames_ = 0;
    WavpackContext *context_ = nullptr;
    static std::int32_t readBytes(void *id, void *data, std::int32_t count);
    static std::int64_t getPosition(void *id);
    static int setPosition(void *id, std::int64_t position);
    static int seekRelative(void *id, std::int64_t delta, int mode);
    static int pushBack(void *id, int byte);
    static std::int64_t getLength(void *id);
};
}
