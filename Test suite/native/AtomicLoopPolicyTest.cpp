#include "AllocationProbeAttributes.h"
#include "AtomicLoopPolicy.h"

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <new>
#include <thread>
#include <chrono>

namespace allocation_probe {

std::atomic<bool> enabled{false};
std::atomic<std::uint64_t> count{0U};

} // namespace allocation_probe

STEMWISE_ALLOCATION_HOOK void *operator new(std::size_t size) {
    if (allocation_probe::enabled.load(std::memory_order_relaxed)) {
        allocation_probe::count.fetch_add(1U, std::memory_order_relaxed);
    }
    if (void *allocation = std::malloc(size)) return allocation;
    throw std::bad_alloc();
}

STEMWISE_ALLOCATION_HOOK void *operator new[](std::size_t size) {
    return ::operator new(size);
}

STEMWISE_ALLOCATION_HOOK void operator delete(void *allocation) noexcept {
    std::free(allocation);
}

STEMWISE_ALLOCATION_HOOK void operator delete[](void *allocation) noexcept {
    std::free(allocation);
}

STEMWISE_ALLOCATION_HOOK void operator delete(void *allocation, std::size_t) noexcept {
    std::free(allocation);
}

STEMWISE_ALLOCATION_HOOK void operator delete[](void *allocation, std::size_t) noexcept {
    std::free(allocation);
}

namespace {

using stemwise::audio::engine::AtomicLoopAdoption;
using stemwise::audio::engine::AtomicLoopPolicy;
using stemwise::audio::engine::AtomicLoopPolicySnapshot;

[[noreturn]] void failRequirement(const char *expression, const char *file, int line) noexcept {
    allocation_probe::enabled.store(false, std::memory_order_release);
    std::fprintf(stderr, "StemWise atomic-loop requirement failed: %s (%s:%d)\n", expression, file, line);
    std::fflush(stderr);
    std::_Exit(EXIT_FAILURE);
}

#define REQUIRE(expression) \
    ((expression) ? static_cast<void>(0) : failRequirement(#expression, __FILE__, __LINE__))

[[nodiscard]] AtomicLoopPolicySnapshot enabled(
    std::uint64_t revision,
    std::uint64_t token,
    std::uint64_t a,
    std::uint64_t b,
    std::uint64_t acceptedAtMicros = 0U,
    std::uint64_t inputEventAtMicros = 0U) noexcept {
    return {revision, token, true, a, b, acceptedAtMicros, inputEventAtMicros};
}

void testExactOwnerLatestPublication() {
    AtomicLoopPolicy policy;
    REQUIRE(!policy.publish({}));
    REQUIRE(policy.adoptLatest(0U) == AtomicLoopAdoption::InvalidSelection);
    REQUIRE(policy.publish(enabled(1U, 10U, 100U, 200U, 1200U, 1000U)));
    REQUIRE(!policy.publish(enabled(1U, 10U, 110U, 210U)));
    REQUIRE(policy.publish(enabled(3U, 10U, 130U, 230U, 1500U, 1400U)));
    allocation_probe::enabled.store(true);
    REQUIRE(policy.adoptLatest(10U) == AtomicLoopAdoption::Adopted);
    REQUIRE(policy.adopted() == enabled(3U, 10U, 130U, 230U));
    REQUIRE(policy.adopted().acceptedAtMonotonicMicros == 1500U);
    REQUIRE(policy.adopted().inputEventAtMonotonicMicros == 1400U);
    REQUIRE(policy.adoptLatest(10U) == AtomicLoopAdoption::NoChange);
    REQUIRE(policy.publish(enabled(4U, 20U, 300U, 400U)));
    REQUIRE(policy.adoptLatest(10U) == AtomicLoopAdoption::SelectionMismatch);
    REQUIRE(policy.adopted().revision == 3U);
    REQUIRE(policy.adoptLatest(20U) == AtomicLoopAdoption::Adopted);
    REQUIRE(policy.adopted().selectionToken == 20U);
    REQUIRE(policy.publish({5U, 20U, false}));
    REQUIRE(policy.adoptLatest(20U) == AtomicLoopAdoption::Adopted);
    REQUIRE(!policy.adopted().enabled);
    REQUIRE(policy.adoptLatest(0U) == AtomicLoopAdoption::InvalidSelection);
    REQUIRE(policy.adopted().selectionToken == 0U);
    allocation_probe::enabled.store(false);
    REQUIRE(allocation_probe::count.load() == 0U);
}

void testConcurrentPublicationNeverTears() {
    AtomicLoopPolicy policy;
    std::atomic<bool> done{false};
    std::thread publisher([&] {
        for (std::uint64_t revision = 1U; revision <= 50000U; ++revision)
            REQUIRE(policy.publish(enabled(revision, 7U, revision * 11U, revision * 11U + 97U,
                revision * 13U, revision * 17U)));
        done.store(true, std::memory_order_release);
    });
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
    bool finished = false;
    while (std::chrono::steady_clock::now() < deadline) {
        static_cast<void>(policy.adoptLatest(7U));
        const auto snapshot = policy.adopted();
        if (snapshot.revision != 0U) {
            REQUIRE(snapshot.selectionToken == 7U);
            REQUIRE(snapshot.aSourceFrame == snapshot.revision * 11U);
            REQUIRE(snapshot.bSourceFrameExclusive == snapshot.aSourceFrame + 97U);
            REQUIRE(snapshot.acceptedAtMonotonicMicros == snapshot.revision * 13U);
            REQUIRE(snapshot.inputEventAtMonotonicMicros == snapshot.revision * 17U);
        }
        if (done.load(std::memory_order_acquire) && snapshot.revision == 50000U) {
            finished = true;
            break;
        }
    }
    publisher.join();
    REQUIRE(finished);
}
} // namespace

int main() {
    testExactOwnerLatestPublication();
    testConcurrentPublicationNeverTears();
    std::puts("AtomicLoopPolicy: exact-owner latest-only bounded publication PASS");
    return EXIT_SUCCESS;
}
