#include <cassert>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <pthread.h>
// ESBMC 8.5's atomic model lacks the compile-time lock-free trait. The runner
// first compiles this exact header with native g++, retaining these assertions.
#ifdef __ESBMC__
#define static_assert(...)
#endif
#include "OutputRingRoleCoordinator.h"
#ifdef __ESBMC__
#undef static_assert
#endif

using namespace stemwise::audio;
extern "C" unsigned char __VERIFIER_nondet_uchar();
extern "C" void __ESBMC_assume(bool);
#ifndef FORMAL_MODE
#define FORMAL_MODE 0
#endif

// Two complete swaps exercise both physical rings. Exhaust all 1..250 starting
// generations and 1..254 starting revisions; production fields remain 64-bit.
void check_protocol() {
    const std::uint64_t first = __VERIFIER_nondet_uchar();
    const std::uint64_t revision = __VERIFIER_nondet_uchar();
    constexpr unsigned mode = FORMAL_MODE;
    __ESBMC_assume(first > 0 && first <= 250);
    __ESBMC_assume(revision > 0 && revision <= 254);
    OutputRingRoleCoordinator roles(first);
    std::uint64_t applied = 0;
    for (unsigned round = 0; round < 2; ++round) {
        const auto owner = roles.activeGeneration();
        const auto outgoing = roles.activeRing();
        const auto next = mode == 2 ? owner : owner + 1;
        const auto rev = revision + round;
        const auto stage = mode == 0 ? roles.begin(next) : mode == 1
            ? roles.beginDrain(owner, next, rev) : roles.beginLoopCutover(owner, rev);
        assert(stage.accepted());
        assert(stage.stagingRing < 2 && stage.stagingRing != outgoing);
        const auto status = mode == 0 ? roles.commit(next, 1) : mode == 1
            ? roles.commitDrain(owner, next, rev, 1) : roles.commitLoopCutover(owner, rev, 1);
        assert(status == OutputHandoffStatus::Accepted);
        assert(!roles.begin(first + 4).accepted());
        assert(!roles.callbackSwap(applied, false, false).valid());
        if (mode == 2) assert(!roles.callbackLoopSwap(applied, owner, rev, false).valid());
        const auto swap = mode == 2 ? roles.callbackLoopSwap(applied, owner, rev, true)
                                    : roles.callbackSwap(applied, true, true);
        assert(swap.valid() && swap.ring == stage.stagingRing);
        // A claim is not yet permission to recycle the old callback ring.
        roles.acknowledge(swap);
        assert(roles.activeRing() == outgoing);
        assert(!roles.begin(first + 4).accepted());
        assert(roles.commitCallbackSwap(swap));
        assert(!roles.begin(first + 4).accepted());
        roles.acknowledge(swap);  // Caller has now stopped reading outgoing PCM.
        applied = swap.sequence;
        assert(roles.activeRing() == stage.stagingRing && roles.activeGeneration() == next);
        assert(!roles.commitAwaitingAcknowledgement());
        assert(!roles.commitCallbackSwap(swap));  // One adoption per publication.
        if (mode == 2) {
            assert(!roles.writableRingForGeneration(owner).valid());
            assert(!roles.releaseLoopCutoverWrites(owner, 0));
            assert(!roles.releaseLoopCutoverWrites(owner + 1, rev));
            assert(roles.releaseLoopCutoverWrites(owner, rev));
        } else {
            assert(!roles.writableRingForGeneration(owner).valid());
        }
        const auto writable = roles.writableRingForGeneration(next);
        assert(writable.valid() && writable.ring == stage.stagingRing);
    }
}

// Exactly one producer attempt overlaps one callback adoption. Operations inside
// the production methods may interleave; whole methods are NOT atomic blocks.
OutputRingRoleCoordinator concurrentRoles(1);
std::atomic<std::uint32_t> physicalCallbackRing{0};

void *producer(void *) {
    const auto stage = concurrentRoles.begin(3);
    if (stage.accepted()) {
        assert(concurrentRoles.activeGeneration() == 2);
        assert(stage.stagingRing == 0); // Ring 1 belongs to the published successor.
        assert(stage.stagingRing != physicalCallbackRing.load());
    }
    return nullptr;
}

void *callback(void *) {
    const auto swap = concurrentRoles.callbackSwap(0);
    assert(swap.valid());
    assert(concurrentRoles.commitCallbackSwap(swap));
    physicalCallbackRing.store(swap.ring); // Last read of old ring has ended.
    concurrentRoles.acknowledge(swap);
    return nullptr;
}

void check_concurrent_reuse() {
    assert(concurrentRoles.begin(2).accepted());
    assert(concurrentRoles.commit(2, 1) == OutputHandoffStatus::Accepted);
    pthread_t writer, reader;
    assert(pthread_create(&writer, nullptr, producer, nullptr) == 0);
    assert(pthread_create(&reader, nullptr, callback, nullptr) == 0);
    assert(pthread_join(writer, nullptr) == 0);
    assert(pthread_join(reader, nullptr) == 0);
    assert(physicalCallbackRing.load() == 1);
}

std::atomic<bool> fenceCompleted{false};
void *fence(void *) {
    concurrentRoles.armAutomaticDrainFence();
    fenceCompleted.store(true);
    return nullptr;
}

void *drainCallback(void *) {
    const auto swap = concurrentRoles.callbackSwap(0, true);
    if (swap.valid()) {
        const bool fencedBeforeCommit = fenceCompleted.load();
        const bool committed = concurrentRoles.commitCallbackSwap(swap);
        assert(!fencedBeforeCommit || !committed);
        if (committed) concurrentRoles.acknowledge(swap);
    }
    return nullptr;
}

void check_concurrent_fence() {
    assert(concurrentRoles.beginDrain(1, 2, 1).accepted());
    assert(concurrentRoles.commitDrain(1, 2, 1, 1) == OutputHandoffStatus::Accepted);
    pthread_t control, reader;
    assert(pthread_create(&control, nullptr, fence, nullptr) == 0);
    assert(pthread_create(&reader, nullptr, drainCallback, nullptr) == 0);
    assert(pthread_join(control, nullptr) == 0);
    assert(pthread_join(reader, nullptr) == 0);
    assert(fenceCompleted.load());
}

void *oldLoopProducer(void *) {
    const auto writable = concurrentRoles.writableRingForGeneration(1);
    assert(!writable.valid() || writable.ring == 0);
    return nullptr;
}

void *loopCallback(void *) {
    const auto swap = concurrentRoles.callbackLoopSwap(0, 1, 1, true);
    assert(swap.valid());
    assert(concurrentRoles.commitCallbackSwap(swap));
    physicalCallbackRing.store(swap.ring);
    concurrentRoles.acknowledge(swap);
    return nullptr;
}

void check_concurrent_loop_writes() {
    assert(concurrentRoles.beginLoopCutover(1, 1).accepted());
    assert(concurrentRoles.commitLoopCutover(1, 1, 1) == OutputHandoffStatus::Accepted);
    pthread_t writer, reader;
    assert(pthread_create(&writer, nullptr, oldLoopProducer, nullptr) == 0);
    assert(pthread_create(&reader, nullptr, loopCallback, nullptr) == 0);
    assert(pthread_join(writer, nullptr) == 0);
    assert(pthread_join(reader, nullptr) == 0);
    // No releaseLoopCutoverWrites: the producer still owns the old pipeline.
    assert(!concurrentRoles.writableRingForGeneration(1).valid());
}
