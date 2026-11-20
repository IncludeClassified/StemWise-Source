#include "StreamLifecycleCoordinator.h"
#include "NativeAudioOutputCallbackDiagnostics.h"
#include "GuardedAtomicSnapshot.h"
#include "PresentedPositionPublication.h"
#include "OutputRingRoleCoordinator.h"
#include "StemGainCallbackPublication.h"

#include <atomic>
#include <barrier>
#include <cassert>
#include <cstdint>
#include <thread>
#include <chrono>

using namespace stemwise::audio;

void sharedStemGainPublicationNeverMixesOwnersOrLanes() {
    StemGainCallbackPublication publication;
    assert(!publication.snapshot().valid());
    std::atomic<bool> finished{false}, stop{false};
    std::atomic<std::uint64_t> completed{0U};
    std::jthread writer([&] {
        for (std::uint64_t index = 1U; index <= 20'000U && !stop.load(); ++index) {
            NativeAudioStemGainCallbackEvidence evidence{
                .sequence = index,
                .generation = index + 10U,
                .selectionToken = index + 20U,
                .publishedSequence = index + 30U,
                .offeredSequence = index + 40U,
                .activeMask = static_cast<std::uint32_t>(index) &
                    ((1U << kNativeAudioStemGainLaneCount) - 1U),
                .callbackSequence = index + 50U,
                .firstTaggedFrame = static_cast<std::int64_t>(index * 2U),
                .lastTaggedFrame = static_cast<std::int64_t>(index * 2U + 1U),
                .taggedFrameCount = index + 70U,
                .nonZeroPcmFrameCount = index + 69U,
                .callbackObservedAtNs = index + 60U,
            };
            for (std::size_t lane = 0U; lane < evidence.gainBits.size(); ++lane) {
                evidence.gainBits[lane] = static_cast<std::uint32_t>(index * 17U + lane);
            }
            publication.publish(evidence);
            completed.store(index);
        }
        finished.store(true, std::memory_order_release);
    });
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
    do {
        const auto evidence = publication.snapshot();
        if (evidence.sequence != 0U) {
            const auto index = evidence.sequence;
            assert(evidence.valid() && evidence.generation == index + 10U &&
                evidence.selectionToken == index + 20U && evidence.publishedSequence == index + 30U &&
                evidence.offeredSequence == index + 40U && evidence.callbackSequence == index + 50U &&
                evidence.firstTaggedFrame == static_cast<std::int64_t>(index * 2U) &&
                evidence.lastTaggedFrame == static_cast<std::int64_t>(index * 2U + 1U) &&
                evidence.taggedFrameCount == index + 70U && evidence.nonZeroPcmFrameCount == index + 69U &&
                evidence.callbackObservedAtNs == index + 60U);
            assert(evidence.activeMask == (static_cast<std::uint32_t>(index) &
                ((1U << kNativeAudioStemGainLaneCount) - 1U)));
            for (std::size_t lane = 0U; lane < evidence.gainBits.size(); ++lane) {
                assert(evidence.gainBits[lane] == index * 17U + lane);
            }
        }
        if (std::chrono::steady_clock::now() >= deadline) stop.store(true);
    } while (!finished.load(std::memory_order_acquire) && !stop.load());
    writer.join();
    assert(completed.load() == 20'000U && publication.snapshot().sequence == 20'000U);
}

void lifecycleExcludesStartUntilStoppedResetFinishes() {
    StreamLifecycleCoordinator lifecycle;
    std::barrier rendezvous(2);
    std::atomic<bool> contenderEntered{false};
    std::atomic<bool> resetFinished{false};
    std::jthread contender;
    {
        auto transaction = lifecycle.acquire();
        contender = std::jthread([&] {
            rendezvous.arrive_and_wait();
            auto start = lifecycle.acquire();
            assert(resetFinished.load());
            contenderEntered.store(true);
        });
        rendezvous.arrive_and_wait();
        assert(!contenderEntered.load());
        // This is the stopped-reset/adoption transaction, under the same production token.
        resetFinished.store(true);
    }
    contender.join();
    auto transaction = lifecycle.acquire();
    assert(contenderEntered.load());
}

void adoptionAndOwnerResetAreOnePublication() {
    GuardedAtomicSnapshot snapshot;
    std::atomic<std::uint64_t> generation{1}, token{11}, revision{7}, a{100}, b{200};
    struct Tuple { std::uint64_t generation{}, token{}, revision{}, a{}, b{}; };
    const auto read = [&] {
        return snapshot.read([&](std::uint64_t) noexcept {
            return Tuple{generation.load(std::memory_order_acquire), token.load(std::memory_order_acquire),
                revision.load(std::memory_order_acquire), a.load(std::memory_order_acquire), b.load(std::memory_order_acquire)};
        });
    };
    std::barrier rendezvous(2);
    std::jthread writer([&] {
        auto publication = snapshot.publish();
        revision.store(8, std::memory_order_release);
        rendezvous.arrive_and_wait();
        rendezvous.arrive_and_wait();
        b.store(400, std::memory_order_release);
    });
    rendezvous.arrive_and_wait();
    assert(read().generation == 0); // Bounded invalid result while writer is preempted.
    rendezvous.arrive_and_wait();
    writer.join();
    const auto adopted = read();
    assert(adopted.generation == 1 && adopted.token == 11 && adopted.revision == 8 && adopted.a == 100 && adopted.b == 400);
    {
        auto publication = snapshot.publish();
        generation.store(0, std::memory_order_release);
        token.store(0, std::memory_order_release);
        revision.store(0, std::memory_order_release);
        a.store(0, std::memory_order_release);
        b.store(0, std::memory_order_release);
        assert(read().generation == 0);
    }
    assert(read().generation == 0 && read().revision == 0);
}

void productionPositionAndAdoptionNeverCrossOwners() {
    PresentedPositionPublication publication;
    const NativeAudioPresentedPosition first{
        .generation = 1U, .selectionToken = 11U, .fingerprintSequence = 1U,
        .lastTimelineFrame = 150, .callbackSequence = 1U, .pcmFrames = 64U, .lastNextTimelineFrame = 151};
    auto second = first;
    second.generation = 2U; second.selectionToken = 22U;
    second.lastTimelineFrame = 302; second.lastNextTimelineFrame = 303;
    std::atomic<bool> finished{false};
    std::jthread writer([&] {
        for (unsigned iteration = 0U; iteration != 10'000U; ++iteration) {
            publication.reset();
            publication.position(first);
            publication.adopt({1U, 11U, 7U, true, 100U, 200U});
            publication.position(second);
            publication.adopt({2U, 22U, 8U, true, 300U, 400U});
        }
        finished.store(true, std::memory_order_release);
    });
    const auto verify = [](const auto &value) {
        if (!value.valid() || value.loopPolicyAdoptedRevision == 0U) return;
        const bool firstTuple = value.generation == 1U && value.selectionToken == 11U &&
            value.lastNextTimelineFrame == 151 && value.loopPolicyAdoptedRevision == 7U && value.loopPolicyAdoptedAFrame == 100U && value.loopPolicyAdoptedBFrameExclusive == 200U;
        const bool secondTuple = value.generation == 2U && value.selectionToken == 22U &&
            value.lastNextTimelineFrame == 303 && value.loopPolicyAdoptedRevision == 8U && value.loopPolicyAdoptedAFrame == 300U && value.loopPolicyAdoptedBFrameExclusive == 400U;
        assert(firstTuple || secondTuple);
    };
    while (!finished.load(std::memory_order_acquire)) verify(publication.read());
    writer.join();
    const auto final = publication.read();
    assert(final.valid() && final.loopPolicyAdoptedRevision == 8U);
    verify(final);
}

void rawAtomicPayloadProtocolRejectsPausedAndMixedPublications() {
    std::atomic<std::uint64_t> guard{0}, token{1}, revision{1}, endpoint{100};
    struct Tuple { std::uint64_t token{}, revision{}, endpoint{}; };
    const auto read = [&] {
        return boundedSnapshotRead<Tuple>([&](Tuple &result) noexcept {
            const auto before = guard.load(std::memory_order_acquire);
            if (before & 1U) return false;
            const Tuple candidate{token.load(std::memory_order_relaxed),
                revision.load(std::memory_order_relaxed), endpoint.load(std::memory_order_relaxed)};
            std::atomic_thread_fence(std::memory_order_acquire);
            if (guard.load(std::memory_order_acquire) != before) return false;
            result = candidate;
            return true;
        });
    };
    std::barrier rendezvous(2);
    std::atomic<bool> finished{false};
    std::jthread writer([&] {
        beginGuardedPublication(guard);
        token.store(2,std::memory_order_relaxed);
        rendezvous.arrive_and_wait();
        rendezvous.arrive_and_wait();
        revision.store(2,std::memory_order_relaxed);
        endpoint.store(200,std::memory_order_relaxed);
        guard.fetch_add(1,std::memory_order_release);
        for (std::uint64_t next=3;next<20'000;++next) {
            beginGuardedPublication(guard);
            token.store(next,std::memory_order_relaxed);
            revision.store(next,std::memory_order_relaxed);
            endpoint.store(next*100,std::memory_order_relaxed);
            guard.fetch_add(1,std::memory_order_release);
        }
        finished.store(true,std::memory_order_release);
    });
    rendezvous.arrive_and_wait();
    assert(read().token==0); // Reader must finish while the sole writer is paused mid-tuple.
    rendezvous.arrive_and_wait();
    do {
        const auto value=read();
        assert(value.token==0 || (value.token==value.revision && value.endpoint==value.token*100));
    } while(!finished.load(std::memory_order_acquire));
    writer.join();
    assert(read().token==19'999);
}

void callbackFailurePublicationNeverCrossesOwnersOrCounters() {
    NativeAudioCallbackMixPublication publication;
    std::atomic<bool> finished{false};
    std::jthread callback([&] {
        for (std::uint64_t index = 1U; index <= 20'000U; ++index) {
            publication.publish({.callbackSequence = index, .generation = index + 10U,
                .selectionToken = index + 20U, .controlRevision = index + 30U,
                .missingControlFrames = index * 2U, .staleControlFrames = index * 3U,
                .mixerRejectedFrames = index * 4U, .invalidTagFrames = index * 5U,
                .advanceFailures = index * 6U, .safeOriginalFallbackFrames = index * 7U,
                .mixerNonFiniteSamplesScrubbed = index * 8U,
                .mixerLimiterActiveFrames = index * 9U, .mixerLimiterSafetyClamps = index * 10U,
                .finalNonFiniteSamplesScrubbed = index * 11U,
                .finalSafetyClamps = index * 13U});
        }
        finished.store(true, std::memory_order_release);
    });
    do {
        const auto value = publication.read();
        if (value.sequence == 0U) continue; // Bounded read may report contention, never a mixed tuple.
        const auto index = value.callbackSequence;
        assert(value.generation == index + 10U && value.selectionToken == index + 20U &&
            value.controlRevision == index + 30U && value.missingControlFrames == index * 2U &&
            value.staleControlFrames == index * 3U && value.mixerRejectedFrames == index * 4U &&
            value.invalidTagFrames == index * 5U && value.advanceFailures == index * 6U &&
            value.safeOriginalFallbackFrames == index * 7U &&
            value.mixerNonFiniteSamplesScrubbed == index * 8U &&
            value.mixerLimiterActiveFrames == index * 9U && value.mixerLimiterSafetyClamps == index * 10U &&
            value.finalNonFiniteSamplesScrubbed == index * 11U &&
            value.finalSafetyClamps == index * 13U);
    } while (!finished.load(std::memory_order_acquire));
    callback.join();
    assert(publication.read().callbackSequence == 20'000U);
}

int main() {
    sharedStemGainPublicationNeverMixesOwnersOrLanes();
    callbackFailurePublicationNeverCrossesOwnersOrCounters();
    lifecycleExcludesStartUntilStoppedResetFinishes();
    adoptionAndOwnerResetAreOnePublication();
    productionPositionAndAdoptionNeverCrossOwners();
    rawAtomicPayloadProtocolRejectsPausedAndMixedPublications();
    OutputRingRoleCoordinator roles(1U);
    std::atomic<bool> finished{false};
    std::jthread lifecycle([&] {
        for (std::uint64_t generation=2;generation<20'000;++generation) {
            assert(roles.forceResetAfterCallbackStopped(generation,
                static_cast<std::uint32_t>(generation & 1U)) != 0U);
        }
        finished.store(true,std::memory_order_release);
    });
    do {
        const auto generation=roles.activeGeneration();
        const auto target=roles.ringForGeneration(generation);
        assert(!target.valid() || (target.generation==generation &&
            (generation==1U ? target.ring==0U : target.ring==(generation & 1U))));
    } while(!finished.load(std::memory_order_acquire));
    lifecycle.join();
}
