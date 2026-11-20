#if defined(STEMWISE_CACHE_IO_TEST_HOOKS)
#include "CurrentSongPcmCacheTestHooks.h"
#endif
#include "CanonicalPcmStore.h"
#include "../../StemwiseMultibus.h"
#include "../../AudioTrace.h"
#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <cstdio>
#include <filesystem>
#include <limits>
#include <mutex>
#include <utility>
#include <vector>
#if defined(_WIN32)
#include <fcntl.h>
#include <io.h>
#include <process.h>
#include <share.h>
#include <sys/stat.h>
#else
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#endif

namespace stemwise::audio::engine {
namespace {
constexpr std::uint64_t kBytesPerFrame = 2U * sizeof(float);
std::mutex budgetMutex;
std::uint64_t totalReservedBytes{}, totalFileBytes{};
[[nodiscard]] std::uint64_t availableStorageBytes(const std::string &directory) noexcept {
    if (directory.empty()) return 0U;
#if defined(STEMWISE_CACHE_IO_TEST_HOOKS)
    static_cast<void>(directory);
    return cache_test::availableStorageBytes();
#else
    try {
        std::error_code error;
        const auto path = std::filesystem::path(std::u8string_view(
            reinterpret_cast<const char8_t *>(directory.data()), directory.size()));
        const auto space = std::filesystem::space(path, error);
        return error ? 0U : space.available;
    } catch (...) { return 0U; }
#endif
}

[[nodiscard]] bool allZeroBytes(const void *source, std::size_t byteCount) noexcept {
    const auto *bytes = static_cast<const unsigned char *>(source);
    return std::all_of(bytes, bytes + byteCount, [](unsigned char value) {
        return value == 0U;
    });
}

class TemporaryPcmFile final {
public:
    // Path/buffer allocation must reach offer()/sealLane()'s cache-failure recovery.
    explicit TemporaryPcmFile(const std::string &cacheDirectory) {
        if (cacheDirectory.empty()) return;
#if defined(_WIN32)
        static std::atomic<std::uint64_t> nextFile{0U};
        const auto directory = std::filesystem::path(std::u8string_view(
            reinterpret_cast<const char8_t *>(cacheDirectory.data()), cacheDirectory.size()));
        for (unsigned attempt = 0U; attempt < 32U; ++attempt) {
            const auto path = directory / ("stemwise-pcm-" + std::to_string(_getpid()) + "-" +
                std::to_string(nextFile.fetch_add(1U, std::memory_order_relaxed)));
            int opened = -1;
            const auto result = _wsopen_s(&opened, path.c_str(),
                _O_CREAT | _O_EXCL | _O_TEMPORARY | _O_RDWR | _O_BINARY | _O_NOINHERIT,
                _SH_DENYRW, _S_IREAD | _S_IWRITE);
            if (result == EEXIST) continue;
            if (result != 0) return;
            file = _fdopen(opened, "w+b");
            if (file == nullptr) _close(opened);
            return;
        }
#else
        std::string pattern = cacheDirectory;
        if (!pattern.empty() && pattern.back() != '/') pattern.push_back('/');
        pattern.append("stemwise-pcm-XXXXXX");
        std::vector<char> mutablePattern(pattern.begin(), pattern.end());
        mutablePattern.push_back('\0');
        descriptor = ::mkstemp(mutablePattern.data());
        if (descriptor < 0) return;
        const int descriptorFlags = ::fcntl(descriptor, F_GETFD);
        const bool closeOnExec = descriptorFlags >= 0 &&
            ::fcntl(descriptor, F_SETFD, descriptorFlags | FD_CLOEXEC) >= 0;
        // Do not short-circuit unlink on fcntl failure: even an empty mkstemp file must retire.
        int unlinked = -1;
        do { unlinked = ::unlink(mutablePattern.data()); } while (unlinked != 0 && errno == EINTR);
        if (!closeOnExec || unlinked != 0) {
            ::close(descriptor);
            descriptor = -1;
            if (unlinked != 0) {
                do { unlinked = ::unlink(mutablePattern.data()); }
                while (unlinked != 0 && errno == EINTR);
            }
        }
#endif
    }

    ~TemporaryPcmFile() {
#if defined(_WIN32)
        if (file != nullptr) std::fclose(file);
#else
        if (descriptor >= 0) ::close(descriptor);
#endif
    }

    TemporaryPcmFile(const TemporaryPcmFile &) = delete;
    TemporaryPcmFile &operator=(const TemporaryPcmFile &) = delete;

    [[nodiscard]] bool valid() const noexcept {
#if defined(_WIN32)
        return file != nullptr;
#else
        return descriptor >= 0;
#endif
    }
    [[nodiscard]] std::uint64_t size() const noexcept {
#if defined(_WIN32)
        const auto result = file ? _filelengthi64(_fileno(file)) : -1;
        return result < 0 ? 0U : static_cast<std::uint64_t>(result);
#else
        struct stat result {};
        return descriptor >= 0 && ::fstat(descriptor, &result) == 0 && result.st_size >= 0
            ? static_cast<std::uint64_t>(result.st_size) : 0U;
#endif
    }

    [[nodiscard]] bool writeExact(
        std::uint64_t offset,
        const void *source,
        std::size_t byteCount) noexcept {
#if defined(STEMWISE_CACHE_IO_PROFILING)
        const AudioTrace trace("StemWise.cache.write");
#endif
        if (!valid() || source == nullptr || byteCount == 0U ||
            offset > static_cast<std::uint64_t>(
                std::numeric_limits<std::int64_t>::max()) - byteCount) {
            return false;
        }
#if defined(_WIN32)
        const std::scoped_lock lock(ioMutex);
        if (_fseeki64(file, static_cast<__int64>(offset), SEEK_SET) != 0) return false;
#endif
#if !defined(_WIN32)
        if (allZeroBytes(source, byteCount)) {
            struct stat status {};
            const std::uint64_t end = offset + byteCount;
            if (::fstat(descriptor, &status) == 0 &&
                status.st_size >= 0 &&
                static_cast<std::uint64_t>(status.st_size) == offset &&
                ::ftruncate(descriptor, static_cast<off_t>(end)) == 0) {
                return true;
            }
        }
#endif
        const auto *bytes = static_cast<const unsigned char *>(source);
        std::size_t written = 0U;
        while (written < byteCount) {
            std::size_t requested = byteCount - written;
#if defined(STEMWISE_CACHE_IO_TEST_HOOKS)
            requested = cache_test::beforeIo(true, requested);
            if (requested == 0U) return false;
#endif
#if defined(_WIN32)
            const auto result = std::fwrite(bytes + written, 1U, requested, file);
#else
            const ssize_t result = ::pwrite(descriptor, bytes + written, requested,
                static_cast<off_t>(offset + written));
            if (result < 0 && errno == EINTR) continue;
#endif
            if (result <= 0) return false;
            written += static_cast<std::size_t>(result);
        }
#if defined(_WIN32)
        return std::fflush(file) == 0;
#else
        return true;
#endif
    }

    [[nodiscard]] bool readExact(
        std::uint64_t offset,
        void *destination,
        std::size_t byteCount) const noexcept {
#if defined(STEMWISE_CACHE_IO_PROFILING)
        const AudioTrace trace("StemWise.cache.read");
#endif
        if (!valid() || destination == nullptr || byteCount == 0U ||
            offset > static_cast<std::uint64_t>(
                std::numeric_limits<std::int64_t>::max()) - byteCount) {
            return false;
        }
#if defined(_WIN32)
        const std::scoped_lock lock(ioMutex);
        if (_fseeki64(file, static_cast<__int64>(offset), SEEK_SET) != 0) return false;
#endif
        auto *bytes = static_cast<unsigned char *>(destination);
        std::size_t read = 0U;
        while (read < byteCount) {
            std::size_t requested = byteCount - read;
#if defined(STEMWISE_CACHE_IO_TEST_HOOKS)
            requested = cache_test::beforeIo(false, requested);
            if (requested == 0U) return false;
#endif
#if defined(_WIN32)
            const auto result = std::fread(bytes + read, 1U, requested, file);
#else
            const ssize_t result = ::pread(descriptor, bytes + read, requested,
                static_cast<off_t>(offset + read));
            if (result < 0 && errno == EINTR) continue;
#endif
            if (result <= 0) return false;
            read += static_cast<std::size_t>(result);
        }
        return true;
    }

private:
#if defined(_WIN32)
    std::FILE *file{nullptr};
    mutable std::mutex ioMutex;
#else
    int descriptor{-1};
#endif
};

} // namespace

class CanonicalPcmStore::Impl final {
public:
    explicit Impl(std::string path) : directory([&path] {
        if (!path.empty()) return std::move(path);
        std::error_code error;
        const auto temporary = std::filesystem::temp_directory_path(error);
        if (error) return std::string{};
        const auto resolved = temporary.u8string();
        return std::string(reinterpret_cast<const char *>(resolved.data()), resolved.size());
    }()) {}
    ~Impl() { retireLanes(0U); }

    CurrentSongCacheStorageStatus reserve(std::uint64_t required) noexcept {
        const std::lock_guard lock(budgetMutex);
        const auto old = reservedBytes.load(std::memory_order_relaxed);
        if (required < fileBytes.load(std::memory_order_relaxed) ||
            required > std::numeric_limits<std::uint64_t>::max() - (totalReservedBytes - old))
            return fail(CurrentSongCacheStorageStatus::LimitExceeded);
        const auto available = availableStorageBytes(directory);
        const auto newTotal = totalReservedBytes - old + required;
        const auto outstanding = newTotal - std::min(newTotal, totalFileBytes);
        if (available < CanonicalPcmStorageBudget::freeSpaceFloorBytes ||
            outstanding > available - CanonicalPcmStorageBudget::freeSpaceFloorBytes)
            return fail(CurrentSongCacheStorageStatus::InsufficientSpace);
        totalReservedBytes = newTotal;
        reservedBytes.store(required, std::memory_order_release);
        storageStatus.store(CurrentSongCacheStorageStatus::Admitted, std::memory_order_release);
        return CurrentSongCacheStorageStatus::Admitted;
    }

    CurrentSongCacheStorageStatus admit(std::size_t count, std::uint64_t maximumFrames) noexcept {
        if (count == 0U || count > kStemwiseMusicBusCount || maximumFrames == 0U ||
            maximumFrames > static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max()) / kBytesPerFrame ||
            maximumFrames > std::numeric_limits<std::uint64_t>::max() / kBytesPerFrame / count)
            return fail(CurrentSongCacheStorageStatus::LimitExceeded);
        const auto required = maximumFrames * kBytesPerFrame * count;
        const auto result = reserve(required);
        if (result == CurrentSongCacheStorageStatus::Admitted) {
            admittedLanes = count;
            frameBound = maximumFrames;
            admittedFrameUpperBound.store(frameBound, std::memory_order_release);
            admittedLaneCount.store(admittedLanes, std::memory_order_release);
        }
        return result;
    }

    bool permits(std::size_t lane, std::uint64_t end) const noexcept {
        return lane < kStemwiseMusicBusCount && end <= static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max()) / kBytesPerFrame &&
            (admittedLanes == 0U || (lane < admittedLanes && end <= frameBound));
    }

    bool append(std::size_t lane, std::uint64_t first, const float *samples, std::size_t frames) noexcept {
        if (!samples || frames == 0U || first > std::numeric_limits<std::uint64_t>::max() - frames ||
            !permits(lane, first + frames) || first != committedFrames[lane]) {
            fail(CurrentSongCacheStorageStatus::LimitExceeded); return false;
        }
        const auto end = first + frames;
        if (admittedLanes == 0U) {
            const auto required = fileBytes.load(std::memory_order_relaxed) + frames * kBytesPerFrame;
            if (reserve(required) != CurrentSongCacheStorageStatus::Admitted) return false;
        }
        // Recheck external storage pressure during growth; admitted bytes remain bounded accounting.
        if (bytesWritten >= nextSpaceCheck) {
            if (reserve(reservedBytes.load(std::memory_order_relaxed)) != CurrentSongCacheStorageStatus::Admitted)
                return false;
            nextSpaceCheck = bytesWritten + 1'024U * 1'024U;
        }
        try {
            if (!files[lane]) files[lane] = std::make_unique<TemporaryPcmFile>(directory);
        } catch (...) { fail(CurrentSongCacheStorageStatus::IoFailure); return false; }
        const auto oldSize = fileSizes[lane];
        const bool written = files[lane]->writeExact(first * kBytesPerFrame, samples, frames * kBytesPerFrame);
        const auto size = files[lane]->size();
        {
            const std::lock_guard lock(budgetMutex);
            totalFileBytes = totalFileBytes - oldSize + size;
            fileSizes[lane] = size;
        }
        const auto bytes = fileBytes.fetch_add(size - oldSize, std::memory_order_release) + size - oldSize;
        peakFileBytes.store(std::max(peakFileBytes.load(std::memory_order_relaxed), bytes), std::memory_order_relaxed);
        if (!written) { fail(CurrentSongCacheStorageStatus::IoFailure); return false; }
        committedFrames[lane] = end;
        bytesWritten += frames * kBytesPerFrame;
        return true;
    }

    bool read(std::size_t lane, std::uint64_t first, float *samples, std::size_t frames) const noexcept {
        if (lane >= kStemwiseMusicBusCount || !samples || frames == 0U || !files[lane] ||
            first > committedFrames[lane] || frames > committedFrames[lane] - first) return false;
        if (!files[lane]->readExact(first * kBytesPerFrame, samples, frames * kBytesPerFrame)) return false;
        readOperations.fetch_add(1U, std::memory_order_relaxed);
        readBytes.fetch_add(frames * kBytesPerFrame, std::memory_order_relaxed);
        lastReadOffset.store(first * kBytesPerFrame, std::memory_order_relaxed);
        lastReadBytes.store(frames * kBytesPerFrame, std::memory_order_release);
        return true;
    }

    void retireLanes(std::size_t first) noexcept {
        if (first >= kStemwiseMusicBusCount) return;
        std::array<std::unique_ptr<TemporaryPcmFile>, kStemwiseMusicBusCount> retired;
        {
            const std::lock_guard lock(budgetMutex);
            std::uint64_t removed{};
            for (auto lane = first; lane < files.size(); ++lane) {
                retired[lane] = std::move(files[lane]);
                removed += fileSizes[lane];
                fileSizes[lane] = 0U;
                committedFrames[lane] = 0U;
            }
            totalFileBytes -= removed;
            fileBytes.fetch_sub(removed, std::memory_order_release);
            const auto old = reservedBytes.load(std::memory_order_relaxed);
            const auto remaining = first == 0U ? 0U : admittedLanes != 0U
                ? std::min<std::size_t>(first, admittedLanes) * frameBound * kBytesPerFrame
                : fileBytes.load(std::memory_order_relaxed);
            totalReservedBytes = totalReservedBytes - old + remaining;
            reservedBytes.store(remaining, std::memory_order_release);
            admittedLanes = std::min(admittedLanes, first);
            if (first == 0U) frameBound = 0U;
            admittedLaneCount.store(admittedLanes, std::memory_order_release);
            admittedFrameUpperBound.store(frameBound, std::memory_order_release);
        }
#if defined(STEMWISE_CACHE_IO_TEST_HOOKS)
        if (first != 0U) if (auto hook = cache_test::beforeOptionalStorageRetirement.load()) hook();
#endif
    }

    CurrentSongCacheStorageStatus fail(CurrentSongCacheStorageStatus status) noexcept {
        storageStatus.store(status, std::memory_order_release); return status;
    }
    const std::string directory;
    std::array<std::unique_ptr<TemporaryPcmFile>, kStemwiseMusicBusCount> files{};
    std::array<std::uint64_t, kStemwiseMusicBusCount> fileSizes{}, committedFrames{};
    std::size_t admittedLanes{};
    std::uint64_t frameBound{}, bytesWritten{}, nextSpaceCheck{};
    std::atomic<std::uint64_t> admittedLaneCount{}, admittedFrameUpperBound{};
    std::atomic<CurrentSongCacheStorageStatus> lastAdmissionStatus{CurrentSongCacheStorageStatus::NotRequested};
    std::atomic<CurrentSongCacheStorageStatus> storageStatus{CurrentSongCacheStorageStatus::NotRequested};
    std::atomic<std::uint64_t> reservedBytes{}, fileBytes{}, peakFileBytes{};
    mutable std::atomic<std::uint64_t> readOperations{}, readBytes{}, lastReadOffset{}, lastReadBytes{};
};

CanonicalPcmStore::CanonicalPcmStore(std::string directory) : mImpl(std::make_unique<Impl>(std::move(directory))) {}
CanonicalPcmStore::~CanonicalPcmStore() = default;
CurrentSongCacheStorageStatus CanonicalPcmStore::admit(std::size_t lanes, std::uint64_t frames) noexcept { const auto result = mImpl->admit(lanes, frames); mImpl->lastAdmissionStatus.store(result, std::memory_order_release); return result; }
bool CanonicalPcmStore::validateExtent(std::size_t lane, std::uint64_t end) noexcept {
    if (mImpl->permits(lane, end)) return true;
    mImpl->fail(CurrentSongCacheStorageStatus::LimitExceeded);
    return false;
}
bool CanonicalPcmStore::append(std::size_t lane, std::uint64_t first, const float *samples, std::size_t frames) noexcept { return mImpl->append(lane, first, samples, frames); }
bool CanonicalPcmStore::read(std::size_t lane, std::uint64_t first, float *samples, std::size_t frames) const noexcept { return mImpl->read(lane, first, samples, frames); }
void CanonicalPcmStore::retireLanes(std::size_t first) noexcept { mImpl->retireLanes(first); }
CurrentSongCacheStorageStatus CanonicalPcmStore::status() const noexcept { return mImpl->storageStatus.load(std::memory_order_acquire); }
CanonicalPcmIoDiagnostics CanonicalPcmStore::diagnostics() const noexcept {
    return {mImpl->readOperations.load(), mImpl->readBytes.load(), mImpl->lastReadOffset.load(),
        mImpl->lastReadBytes.load(), mImpl->fileBytes.load(), mImpl->reservedBytes.load(), mImpl->peakFileBytes.load(),
        mImpl->admittedLaneCount.load(), mImpl->admittedFrameUpperBound.load(), mImpl->lastAdmissionStatus.load()};
}
} // namespace stemwise::audio::engine
