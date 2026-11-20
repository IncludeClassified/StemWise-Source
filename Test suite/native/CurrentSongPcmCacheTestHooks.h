#pragma once
#include <cstddef>
#include <cstdint>
#include <atomic>
namespace stemwise::audio::engine { struct CurrentSongPcmReadConsumer; }
namespace stemwise::audio::engine::cache_test {
// Native cache/runtime tests only; zero bytes injects an I/O error.
std::size_t beforeIo(bool writing, std::size_t requested) noexcept;
std::uint64_t availableStorageBytes() noexcept;
void beforePrepareLanes();
void beforeRetireCache() noexcept;
void beforeInheritCache() noexcept;
inline std::atomic<void (*)() noexcept> beforeOptionalStorageRetirement{nullptr};
inline std::atomic<void (*)() noexcept> afterRetainedBodyCopySlice{nullptr};
inline std::atomic<void (*)() noexcept> beforeReadIo{nullptr};
inline std::atomic<void (*)() noexcept> beforeCacheRebind{nullptr};
inline std::atomic<std::uint64_t> denyReadConsumerGeneration{0U}, readConsumerAdmissionDenials{0U};
inline std::atomic<bool (*)(const CurrentSongPcmReadConsumer &, std::size_t,
    std::uint64_t, std::size_t) noexcept> deferReadRequirement{nullptr};
inline std::atomic<bool (*)(const CurrentSongPcmReadConsumer *, std::size_t,
    std::uint64_t, std::size_t, std::size_t) noexcept> deferReadCopy{nullptr};
inline std::atomic<void (*)(const CurrentSongPcmReadConsumer *, bool) noexcept> afterReadCopy{nullptr};
}
