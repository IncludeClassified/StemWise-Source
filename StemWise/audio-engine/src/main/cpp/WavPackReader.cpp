#include "WavPackReader.h"

#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <system_error>
#include <limits>
#include <stdexcept>
#include <string>
#ifdef _WIN32
#include <io.h>
#define dup _dup
#define close _close
#else
#include <unistd.h>
#endif

namespace stemwise::audio {
WavPackReader::WavPackReader(int descriptor, std::int64_t offset, std::int64_t length)
    : offset_(offset), length_(length) {
    if (offset < 0 || length < 32 || offset > std::numeric_limits<std::int64_t>::max() - length)
        throw std::runtime_error("Invalid WavPack slice");
    if (descriptor < 0) throw std::runtime_error("WavPack descriptor: invalid descriptor");
    descriptor_ = dup(descriptor);
    if (descriptor_ < 0) throw std::runtime_error(std::string("WavPack descriptor: ") + std::error_code(errno, std::generic_category()).message());
    static WavpackStreamReader64 reader = {
        readBytes, nullptr, getPosition, setPosition, seekRelative, pushBack,
        getLength, [](void *) { return 1; }, nullptr, nullptr
    };
    char error[80]{};
    // Deliberately omit OPEN_NORMALIZE: unpacked integers contain the original IEEE Float32 bits.
    context_ = WavpackOpenFileInputEx64(&reader, this, nullptr, error, 0, 0);
    char failure[256]{};
    std::snprintf(failure, sizeof(failure), "WavPack open: %.79s", error[0] ? error : "vendor rejected input");
    if (context_) {
        const int mode = WavpackGetMode(context_);
        frames_ = WavpackGetNumSamples64(context_);
        if ((mode & (MODE_FLOAT | MODE_LOSSLESS | MODE_HYBRID)) != (MODE_FLOAT | MODE_LOSSLESS) ||
            WavpackGetNumChannels(context_) != 2 || WavpackGetSampleRate(context_) != 48000 ||
            WavpackGetBitsPerSample(context_) != 32 || frames_ <= 0) {
            std::snprintf(failure, sizeof(failure),
                "WavPack format: requires lossless stereo Float32 at 48 kHz; mode=%d, channels=%d, rate=%u, bits=%d",
                mode, WavpackGetNumChannels(context_), WavpackGetSampleRate(context_), WavpackGetBitsPerSample(context_));
            context_ = WavpackCloseFile(context_);
        }
    }
    if (!context_) {
        close(descriptor_);
        descriptor_ = -1;
        throw std::runtime_error(failure);
    }
}

WavPackReader::~WavPackReader() {
    if (context_) WavpackCloseFile(context_);
    if (descriptor_ >= 0) close(descriptor_);
}

std::uint32_t WavPackReader::read(std::int64_t firstFrame, std::int32_t *destination,
                                std::uint32_t maximumFrames) {
    if (!context_ || !destination || maximumFrames == 0 || maximumFrames > 65536 ||
        firstFrame < 0 || firstFrame > frames_) throw std::runtime_error("Invalid WavPack read");
    const auto count = static_cast<std::uint32_t>(std::min<std::int64_t>(maximumFrames, frames_ - firstFrame));
    if (count == 0) return 0;
    if (WavpackGetSampleIndex64(context_) != firstFrame && !WavpackSeekSample64(context_, firstFrame)) {
        context_ = WavpackCloseFile(context_);
        throw std::runtime_error("WavPack seek failed");
    }
    if (WavpackUnpackSamples(context_, destination, count) != count ||
        WavpackGetNumErrors(context_) != 0 || WavpackLossyBlocks(context_) != 0)
        throw std::runtime_error("WavPack payload is truncated or corrupt");
    for (std::uint32_t i = 0; i < count * 2; ++i)
        if ((static_cast<std::uint32_t>(destination[i]) & 0x7f800000U) == 0x7f800000U)
            throw std::runtime_error("WavPack contains nonfinite PCM");
    return count;
}

std::int32_t WavPackReader::readBytes(void *id, void *data, std::int32_t count) {
    auto &self = *static_cast<WavPackReader *>(id);
    if (count <= 0 || count > 4 * 1024 * 1024) return 0;
    count = static_cast<std::int32_t>(std::min<std::int64_t>(count, self.length_ - self.position_));
    if (count == 0) return 0;
    std::int32_t done = 0;
    while (done < count) {
        auto *target = static_cast<char *>(data) + done;
#ifdef _WIN32
        if (_lseeki64(self.descriptor_, self.offset_ + self.position_ + done, SEEK_SET) < 0) break;
        const auto result = _read(self.descriptor_, target, static_cast<unsigned>(count - done));
#else
        ssize_t result;
        do { result = pread(self.descriptor_, target, static_cast<std::size_t>(count - done),
                            self.offset_ + self.position_ + done); }
        while (result < 0 && errno == EINTR);
#endif
        if (result <= 0) break;
        done += static_cast<std::int32_t>(result);
    }
    self.position_ += done;
    return done;
}
std::int64_t WavPackReader::getPosition(void *id) { return static_cast<WavPackReader *>(id)->position_; }
int WavPackReader::setPosition(void *id, std::int64_t position) {
    auto &self = *static_cast<WavPackReader *>(id);
    if (position < 0 || position > self.length_) return -1;
    self.position_ = position;
    return 0;
}
int WavPackReader::seekRelative(void *id, std::int64_t delta, int mode) {
    auto &self = *static_cast<WavPackReader *>(id);
    const auto base = mode == SEEK_SET ? 0 : mode == SEEK_CUR ? self.position_ : mode == SEEK_END ? self.length_ : -1;
    if (base < 0 || delta < -base || delta > self.length_ - base) return -1;
    return setPosition(id, base + delta);
}
int WavPackReader::pushBack(void *id, int byte) { return seekRelative(id, -1, SEEK_CUR) == 0 ? byte : EOF; }
std::int64_t WavPackReader::getLength(void *id) { return static_cast<WavPackReader *>(id)->length_; }
}
