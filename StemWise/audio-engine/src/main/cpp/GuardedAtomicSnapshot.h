#pragma once

#include <atomic>
#include <cstdint>
#include <utility>

namespace stemwise::audio {

// Single-writer atomic-payload seqlock. If any relaxed payload read observes a later writer,
// this release fence synchronizes with the reader's acquire fence via that payload atomic.
// The odd guard then happens-before the final guard load, which cannot accept the old even
// guard (atomic write/read coherence). Otherwise the initial acquire observes the complete
// even publication. Every reader must fence after payload reads and bound its attempts.
inline void beginGuardedPublication(std::atomic<std::uint64_t> &guard) noexcept {
    guard.fetch_add(1U, std::memory_order_relaxed);
    std::atomic_thread_fence(std::memory_order_release);
}

// Diagnostic contention returns unavailable instead of delaying a lifecycle/control operation.
template <typename Value, typename TryRead>
[[nodiscard]] Value boundedSnapshotRead(TryRead &&tryRead) noexcept {
    Value result{};
    for (unsigned attempt = 0U; attempt != 8U; ++attempt) {
        if (tryRead(result)) return result;
    }
    return {};
}

// One writer (the callback, or its lifecycle-serialized stopped-stream replacement) owns every
// atomic payload field. Payload stores use release and payload loads acquire: observing any new
// field orders its preceding odd guard before the final check. Readers also acquire the initial
// complete publication, then order all payload reads before
// rechecking the guard. A preempted writer returns an invalid default after bounded attempts.
// This is not a multiwriter journal or a lifetime/reclamation protocol.
class GuardedAtomicSnapshot final {
public:
    class Publication final {
    public:
        explicit Publication(GuardedAtomicSnapshot &owner) noexcept : owner_(owner) {
            owner_.sequence_.fetch_add(1U, std::memory_order_acq_rel);
        }
        ~Publication() { owner_.sequence_.fetch_add(1U, std::memory_order_release); }
        Publication(const Publication &) = delete;
        Publication &operator=(const Publication &) = delete;
    private:
        GuardedAtomicSnapshot &owner_;
    };
    [[nodiscard]] Publication publish() noexcept { return Publication(*this); }
    template <typename Read>
    auto read(Read &&read) const noexcept -> decltype(read(std::uint64_t{})) {
        for (unsigned attempt = 0U; attempt != 8U; ++attempt) {
            const auto before = sequence_.load(std::memory_order_acquire);
            if (before & 1U) continue;
            auto result = read(before / 2U);
            std::atomic_thread_fence(std::memory_order_acquire);
            if (sequence_.load(std::memory_order_acquire) == before) return result;
        }
        return {};
    }
private:
    std::atomic<std::uint64_t> sequence_{0U};
};

} // namespace stemwise::audio
