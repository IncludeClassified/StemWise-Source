#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>

namespace stemwise::audio::engine {

enum class CurrentSongCacheStorageStatus : std::uint8_t {
    NotRequested = 0U, Admitted = 1U, LimitExceeded = 2U, InsufficientSpace = 3U, IoFailure = 4U,
};

/** One authoritative budget for live canonical PCM stores, including handoff/retirement overlap. */
struct CanonicalPcmStorageBudget final {
    static constexpr std::uint64_t freeSpaceFloorBytes = 512ULL * 1'024ULL * 1'024ULL;
};

struct CanonicalPcmIoDiagnostics final {
    std::uint64_t readOperations{}, readBytes{}, lastReadByteOffset{}, lastReadBytes{};
    std::uint64_t fileBytes{}, reservedBytes{}, peakFileBytes{};
    std::uint64_t admittedLaneCount{}, admittedFrameUpperBound{};
    CurrentSongCacheStorageStatus lastAdmissionStatus{CurrentSongCacheStorageStatus::NotRequested};
    [[nodiscard]] bool operator==(const CanonicalPcmIoDiagnostics &) const noexcept = default;
};

/**
 * Serialized I/O owner of immutable, contiguous post-SRC stereo Float32 PCM. Admission reserves
 * accounting only: files grow as ordinary decoded PCM arrives, and are unlinked on open. Reads
 * address logical frame offsets directly; no codec/SRC work or prefix scan occurs here.
 */
class CanonicalPcmStore final {
public:
    explicit CanonicalPcmStore(std::string directory);
    ~CanonicalPcmStore();
    CanonicalPcmStore(const CanonicalPcmStore &) = delete;
    CanonicalPcmStore &operator=(const CanonicalPcmStore &) = delete;
    [[nodiscard]] CurrentSongCacheStorageStatus admit(std::size_t lanes, std::uint64_t maximumFrames) noexcept;
    [[nodiscard]] bool validateExtent(std::size_t lane, std::uint64_t endFrame) noexcept;
    [[nodiscard]] bool append(std::size_t lane, std::uint64_t firstFrame,
        const float *samples, std::size_t frames) noexcept;
    [[nodiscard]] bool read(std::size_t lane, std::uint64_t firstFrame,
        float *samples, std::size_t frames) const noexcept;
    void retireLanes(std::size_t firstLane) noexcept;
    [[nodiscard]] CurrentSongCacheStorageStatus status() const noexcept;
    [[nodiscard]] CanonicalPcmIoDiagnostics diagnostics() const noexcept;
private:
    class Impl;
    std::unique_ptr<Impl> mImpl;
};
}
