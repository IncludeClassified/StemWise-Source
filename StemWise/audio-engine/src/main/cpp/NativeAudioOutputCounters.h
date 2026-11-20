#pragma once
#include <atomic>
#include <chrono>
#include <cstdint>
#include <limits>

namespace stemwise::audio {
// Actual native output-owner lifetimes, shared across every JNI wrapper in this process.
inline std::atomic<std::uint64_t> gOutputConstructionCount{0};
inline std::atomic<std::uint64_t> gOutputLiveInstanceCount{0};
// Fault evidence is process-cumulative so route reopen or output-owner replacement cannot erase
// a failure that occurred earlier in one release-qualification process.
inline std::atomic<std::uint64_t> gOutputInvalidOwnershipTagFrames{0};
inline std::atomic<std::uint64_t> gOutputUnderrunCallbackCount{0};
inline std::atomic<std::uint64_t> gOutputUnderrunFrames{0};
// Exact subset of process underruns produced by the sole callback that had already observed the
// manual gate open when a later arm linearized. These are selection-boundary callbacks, not
// evidence that the newly selected owner's producer ran dry.
inline std::atomic<std::uint64_t> gOutputManualArmBoundaryUnderrunCallbackCount{0};
inline std::atomic<std::uint64_t> gOutputManualArmBoundaryUnderrunFrames{0};
inline std::atomic<std::uint64_t> gOutputXRunCount{0};
inline std::atomic<std::uint64_t> gOutputIncompleteStreamObservationCount{0};
inline std::atomic<std::uint64_t> gOutputCallbackDeadlineMissCount{0};
inline std::atomic<std::uint64_t> gOutputLoopBoundaryMissCount{0};

inline void saturatingAdd(
    std::atomic<std::uint64_t> &counter,
    std::uint64_t amount) noexcept {
    std::uint64_t previous = counter.load(std::memory_order_relaxed);
    while (true) {
        const std::uint64_t maximum = std::numeric_limits<std::uint64_t>::max();
        const std::uint64_t updated = amount > maximum - previous
            ? maximum
            : previous + amount;
        if (counter.compare_exchange_weak(
                previous,
                updated,
                std::memory_order_relaxed,
                std::memory_order_relaxed)) {
            return;
        }
    }
}

inline void saturatingIncrement(std::atomic<std::uint64_t> &counter) noexcept {
    saturatingAdd(counter, 1U);
}

inline std::int64_t saturatedCounter(std::uint64_t value) noexcept {
    const auto maximum = static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max());
    return value > maximum ? std::numeric_limits<std::int64_t>::max() : static_cast<std::int64_t>(value);
}

inline std::int64_t monotonicMicros() noexcept {
    return std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}
inline std::uint64_t monotonicNanos() noexcept {
    return static_cast<std::uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count());
}

template <typename T>
void updateMaximum(std::atomic<T> &target, T candidate) noexcept {
    T previous = target.load(std::memory_order_relaxed);
    while (candidate > previous &&
           !target.compare_exchange_weak(
               previous,
               candidate,
               std::memory_order_relaxed,
               std::memory_order_relaxed)) {
    }
}

} // namespace stemwise::audio
