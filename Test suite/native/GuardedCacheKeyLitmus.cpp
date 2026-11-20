// Optional CDSChecker litmus; see STEMWISE_DOCUMENTATION.md for invocation.
// One writer publication and one reader attempt over the five cache-key fields.
#include <atomic>
#include <cstdint>
#include <threads.h>
#include "model-assert.h"
#include "GuardedAtomicSnapshot.h"

static std::atomic<std::uint64_t> guard;
static std::atomic<std::uint64_t> fields[5];

static void writer(void *) {
#ifdef OLD_ORDERING
    // Negative control: the old odd marker had no following release fence.
    guard.fetch_add(1U, std::memory_order_acq_rel);
#else
    stemwise::audio::beginGuardedPublication(guard);
#endif
    for (unsigned i = 0; i != 5; ++i)
        fields[i].store(200U + i, std::memory_order_relaxed);
    guard.fetch_add(1U, std::memory_order_release);
}

static void reader(void *) {
    const auto before = guard.load(std::memory_order_acquire);
    if (before & 1U) return;
    std::uint64_t result[5];
    for (unsigned i = 0; i != 5; ++i)
        result[i] = fields[i].load(std::memory_order_relaxed);
    std::atomic_thread_fence(std::memory_order_acquire);
    const auto after = guard.load(std::memory_order_acquire);
    if (before != after) return;
    for (unsigned i = 0; i != 5; ++i)
        MODEL_ASSERT(result[i] == (before == 0U ? 100U : 200U) + i);
}

int user_main(int, char **) {
    guard.store(0U, std::memory_order_relaxed);
    for (unsigned i = 0; i != 5; ++i)
        fields[i].store(100U + i, std::memory_order_relaxed);
    thrd_t w, r;
    thrd_create(&w, writer, nullptr);
    thrd_create(&r, reader, nullptr);
    thrd_join(w);
    thrd_join(r);
    return 0;
}
