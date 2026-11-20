#pragma once

#include "GuardedAtomicSnapshot.h"
#include <atomic>
#include <cstdint>

namespace stemwise::audio {

/** Render-worker rejection is separate from the control-to-callback demand mailbox. */
class NativeAudioOutputLoopDemandRetirement final {
public:
    void retire(std::uint64_t revision, std::uint64_t generation, std::uint64_t token) noexcept {
        auto publication = publication_.publish();
        revision_.store(revision, std::memory_order_release);
        generation_.store(generation, std::memory_order_release);
        token_.store(token, std::memory_order_release);
    }

    [[nodiscard]] bool matches(std::uint64_t revision, std::uint64_t generation,
        std::uint64_t token) const noexcept {
        return publication_.read([&](std::uint64_t) noexcept {
            return revision != 0U && revision_.load(std::memory_order_acquire) == revision &&
                generation_.load(std::memory_order_acquire) == generation &&
                token_.load(std::memory_order_acquire) == token;
        });
    }

private:
    GuardedAtomicSnapshot publication_{};
    std::atomic<std::uint64_t> revision_{0U}, generation_{0U}, token_{0U};
};

} // namespace stemwise::audio
