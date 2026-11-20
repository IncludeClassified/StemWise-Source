#pragma once

#include <mutex>

namespace stemwise::audio {

// Control/worker threads own a transaction from stream-state inspection through stopped-ring
// mutation. The physical callback never acquires this coordinator.
class StreamLifecycleCoordinator final {
public:
    class Transaction final {
    public:
        explicit Transaction(StreamLifecycleCoordinator &owner) : lock_(owner.mutex_) {}
    private:
        std::unique_lock<std::mutex> lock_;
    };
    [[nodiscard]] Transaction acquire() { return Transaction(*this); }
private:
    std::mutex mutex_;
};

} // namespace stemwise::audio
