#include "AllocationProbeAttributes.h"
#include "LateStemMixControlMailbox.h"

#include <atomic>
#include <bit>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <new>
#include <stdexcept>
#include <thread>
#include <type_traits>

namespace allocation_probe {
bool enabled = false;
std::uint64_t count = 0U;
}  // namespace allocation_probe

STEMWISE_ALLOCATION_HOOK void *operator new(std::size_t size) {
    if (allocation_probe::enabled) ++allocation_probe::count;
    if (void *memory = std::malloc(size)) return memory;
    throw std::bad_alloc();
}

STEMWISE_ALLOCATION_HOOK void *operator new[](std::size_t size) {
    return ::operator new(size);
}

STEMWISE_ALLOCATION_HOOK void operator delete(void *memory) noexcept {
    std::free(memory);
}

STEMWISE_ALLOCATION_HOOK void operator delete[](void *memory) noexcept {
    ::operator delete(memory);
}

STEMWISE_ALLOCATION_HOOK void operator delete(void *memory, std::size_t) noexcept {
    std::free(memory);
}

STEMWISE_ALLOCATION_HOOK void operator delete[](void *memory, std::size_t) noexcept {
    ::operator delete(memory);
}

namespace {

using stemwise::audio::LateStemMixControlMailbox;
using stemwise::audio::LateStemMixControlSnapshot;
using stemwise::audio::LateStemSourceMode;
using stemwise::audio::kLateStemMixOutputRoleCount;
using stemwise::audio::kStemwiseStemBusCount;

static_assert(kLateStemMixOutputRoleCount == 2U);
static_assert(LateStemMixControlMailbox::kRoleCount == 2U);
static_assert(std::is_nothrow_default_constructible_v<LateStemMixControlMailbox>);
static_assert(noexcept(std::declval<LateStemMixControlMailbox &>().resetRole(0U, 1U, 1U)));
static_assert(noexcept(std::declval<LateStemMixControlMailbox &>().publish(
    0U,
    std::declval<const LateStemMixControlSnapshot &>())));
static_assert(noexcept(std::declval<const LateStemMixControlMailbox &>().tryRead(
    0U,
    1U,
    1U,
    std::declval<LateStemMixControlSnapshot &>())));

void require(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}

LateStemMixControlSnapshot encodedSnapshot(
    std::uint64_t generation,
    std::uint64_t selectionToken,
    std::uint64_t sequence) noexcept {
    LateStemMixControlSnapshot snapshot;
    snapshot.publicationSequence = sequence;
    snapshot.generation = generation;
    snapshot.selectionToken = selectionToken;
    snapshot.canonicalControlRevision = 10'000U + sequence;
    snapshot.sourceIntentRevision = 20'000U + sequence;
    snapshot.stemIntentRevision = 30'000U + sequence;
    snapshot.sourceMode = (sequence & 1U) == 0U
        ? LateStemSourceMode::Original
        : LateStemSourceMode::Stems;
    for (std::size_t lane = 0U; lane < kStemwiseStemBusCount; ++lane) {
        const std::uint64_t code = (sequence + lane * 3U) % 17U;
        snapshot.effectiveStemGains[lane] = static_cast<float>(code) / 8.0F;
    }
    snapshot.musicGain = static_cast<float>((sequence * 3U) % 17U) / 8.0F;
    snapshot.mixHeadroom = static_cast<float>((sequence * 5U) % 9U) / 8.0F;
    snapshot.sourceRampFrames = static_cast<std::uint32_t>(1U + sequence % 2'048U);
    snapshot.laneRampFrames = static_cast<std::uint32_t>(1U + sequence % 4'096U);
    snapshot.masterRampFrames = static_cast<std::uint32_t>(1U + sequence % 8'192U);
    return snapshot;
}

bool sameFloatBits(float left, float right) noexcept {
    return std::bit_cast<std::uint32_t>(left) == std::bit_cast<std::uint32_t>(right);
}

bool sameSnapshot(
    const LateStemMixControlSnapshot &left,
    const LateStemMixControlSnapshot &right) noexcept {
    if (left.publicationSequence != right.publicationSequence ||
        left.generation != right.generation ||
        left.selectionToken != right.selectionToken ||
        left.canonicalControlRevision != right.canonicalControlRevision ||
        left.sourceIntentRevision != right.sourceIntentRevision ||
        left.stemIntentRevision != right.stemIntentRevision ||
        left.sourceMode != right.sourceMode ||
        !sameFloatBits(left.musicGain, right.musicGain) ||
        !sameFloatBits(left.mixHeadroom, right.mixHeadroom) ||
        left.sourceRampFrames != right.sourceRampFrames ||
        left.laneRampFrames != right.laneRampFrames ||
        left.masterRampFrames != right.masterRampFrames) {
        return false;
    }
    for (std::size_t lane = 0U; lane < kStemwiseStemBusCount; ++lane) {
        if (!sameFloatBits(left.effectiveStemGains[lane], right.effectiveStemGains[lane])) {
            return false;
        }
    }
    return true;
}

void twoRolesAreIndependentAndOwnerExact() {
    LateStemMixControlMailbox mailbox;
    require(mailbox.resetRole(0U, 10U, 101U), "bind first output role");
    require(mailbox.resetRole(1U, 20U, 202U), "bind second output role");

    const auto first = encodedSnapshot(10U, 101U, 1U);
    const auto second = encodedSnapshot(20U, 202U, 1U);
    require(mailbox.publish(0U, first), "publish first output role");
    require(mailbox.publish(1U, second), "publish second output role");

    LateStemMixControlSnapshot observed;
    require(mailbox.tryRead(0U, 10U, 101U, observed), "read first output role");
    require(sameSnapshot(observed, first), "first output role payload changed");
    require(mailbox.tryRead(1U, 20U, 202U, observed), "read second output role");
    require(sameSnapshot(observed, second), "second output role payload changed");

    const auto sentinel = encodedSnapshot(99U, 999U, 99U);
    observed = sentinel;
    require(!mailbox.tryRead(0U, 10U, 202U, observed), "wrong token read succeeded");
    require(sameSnapshot(observed, sentinel), "failed read changed output snapshot");
    require(!mailbox.tryRead(2U, 10U, 101U, observed), "invalid role read succeeded");
    require(sameSnapshot(observed, sentinel), "invalid-role read changed output snapshot");
    require(!mailbox.resetRole(2U, 30U, 303U), "invalid role reset succeeded");
}

void publicationAndRevisionHighWaterMarksNeverRegress() {
    LateStemMixControlMailbox mailbox;
    require(mailbox.resetRole(0U, 7U, 77U), "bind monotonic role");

    auto endpoint = encodedSnapshot(7U, 77U, 100U);
    endpoint.canonicalControlRevision = 50U;
    endpoint.sourceIntentRevision = 60U;
    endpoint.stemIntentRevision = 70U;
    require(mailbox.publish(0U, endpoint), "publish monotonic baseline");

    auto rejected = endpoint;
    rejected.musicGain = 0.25F;
    require(!mailbox.publish(0U, rejected), "equal publication sequence succeeded");
    rejected.publicationSequence = 99U;
    require(!mailbox.publish(0U, rejected), "older publication sequence succeeded");

    rejected = endpoint;
    rejected.publicationSequence = 101U;
    rejected.canonicalControlRevision = 49U;
    require(!mailbox.publish(0U, rejected), "canonical revision regressed");
    rejected = endpoint;
    rejected.publicationSequence = 101U;
    rejected.sourceIntentRevision = 59U;
    require(!mailbox.publish(0U, rejected), "source revision regressed");
    rejected = endpoint;
    rejected.publicationSequence = 101U;
    rejected.stemIntentRevision = 69U;
    require(!mailbox.publish(0U, rejected), "stem revision regressed");

    auto gainOnly = endpoint;
    gainOnly.publicationSequence = 101U;
    gainOnly.effectiveStemGains[3U] = 0.375F;
    gainOnly.musicGain = 0.5F;
    require(mailbox.publish(0U, gainOnly), "gain-only publication was rejected");

    rejected = gainOnly;
    rejected.publicationSequence = 102U;
    rejected.sourceMode = static_cast<LateStemSourceMode>(0xFFU);
    require(!mailbox.publish(0U, rejected), "invalid source mode succeeded");
    rejected = gainOnly;
    rejected.publicationSequence = 102U;
    rejected.selectionToken = 78U;
    rejected.effectiveStemGains[3U] = 0.875F;
    rejected.musicGain = 0.25F;
    const bool wrongOwnerAccepted = mailbox.publish(0U, rejected);
    LateStemMixControlSnapshot observed;
    require(mailbox.tryRead(0U, 7U, 77U, observed), "read gain-only endpoint");
    require(sameSnapshot(observed, gainOnly), "AE-01 stale control changed independent current endpoint");
    require(!wrongOwnerAccepted, "wrong owner publication succeeded");
}

void newerOwnerMayRestartOwnerScopedRevisions() {
    LateStemMixControlMailbox mailbox;
    require(mailbox.resetRole(0U, 40U, 400U), "bind high-revision owner");

    auto outgoing = encodedSnapshot(40U, 400U, 100U);
    outgoing.canonicalControlRevision = 900U;
    outgoing.sourceIntentRevision = 800U;
    outgoing.stemIntentRevision = 700U;
    require(mailbox.publish(0U, outgoing), "publish high-revision owner");

    require(mailbox.resetRole(0U, 41U, 401U), "bind newer low-revision owner");
    auto incoming = encodedSnapshot(41U, 401U, 101U);
    incoming.canonicalControlRevision = 3U;
    incoming.sourceIntentRevision = 1U;
    incoming.stemIntentRevision = 1U;
    require(mailbox.publish(0U, incoming),
            "new owner-scoped revisions were incorrectly compared with retired owner");

    LateStemMixControlSnapshot observed;
    require(mailbox.tryRead(0U, 41U, 401U, observed), "read newer low-revision owner");
    require(sameSnapshot(observed, incoming), "newer owner payload changed");

    auto stalePublication = outgoing;
    stalePublication.publicationSequence = 102U;
    require(!mailbox.publish(0U, stalePublication), "retired owner republished after rebind");
}

void resetsRejectAbaAndPreserveOtherRole() {
    LateStemMixControlMailbox mailbox;
    require(mailbox.resetRole(0U, 10U, 111U), "bind reusable role");
    require(mailbox.resetRole(1U, 90U, 909U), "bind stable role");

    auto current = encodedSnapshot(10U, 111U, 10U);
    const auto stable = encodedSnapshot(90U, 909U, 1U);
    require(mailbox.publish(0U, current), "publish reusable role");
    require(mailbox.publish(1U, stable), "publish stable role");

    require(mailbox.resetRole(0U, 10U, 111U), "exact-owner reset failed");
    LateStemMixControlSnapshot observed = encodedSnapshot(99U, 999U, 99U);
    const auto sentinel = observed;
    require(!mailbox.tryRead(0U, 10U, 111U, observed), "reset role remained readable");
    require(sameSnapshot(observed, sentinel), "reset read changed output snapshot");
    require(!mailbox.publish(0U, current), "reset admitted old publication sequence");

    current.publicationSequence = 11U;
    require(mailbox.publish(0U, current), "new same-owner publication failed");
    require(mailbox.resetRole(0U, 11U, 222U), "newer owner reset failed");
    auto staleOwner = encodedSnapshot(10U, 111U, 12U);
    require(!mailbox.publish(0U, staleOwner), "retired owner publication succeeded");
    require(!mailbox.tryRead(0U, 10U, 111U, observed), "retired owner read succeeded");

    current = encodedSnapshot(11U, 222U, 12U);
    require(mailbox.publish(0U, current), "new owner publication failed");
    require(!mailbox.resetRole(0U, 10U, 111U), "older owner rebound role");
    require(!mailbox.resetRole(0U, 11U, 333U), "same generation changed token");
    require(mailbox.tryRead(0U, 11U, 222U, observed), "failed reset invalidated role");
    require(sameSnapshot(observed, current), "failed reset changed role payload");

    std::uint64_t previousGeneration = 11U;
    std::uint64_t previousToken = 222U;
    std::uint64_t sequence = 12U;
    for (std::uint64_t generation = 12U; generation < 4'012U; ++generation) {
        const std::uint64_t token = 300U + generation % 7U;
        require(mailbox.resetRole(0U, generation, token), "generation churn reset failed");
        auto aba = encodedSnapshot(previousGeneration, previousToken, ++sequence);
        require(!mailbox.publish(0U, aba), "ABA owner publication succeeded");
        auto next = encodedSnapshot(generation, token, sequence);
        require(mailbox.publish(0U, next), "generation churn publication failed");
        require(!mailbox.tryRead(0U, previousGeneration, previousToken, observed),
                "ABA owner read succeeded");
        if (generation >= 19U) {
            const auto repeatedTokenAba = encodedSnapshot(generation - 7U, token, sequence + 1U);
            require(!mailbox.publish(0U, repeatedTokenAba),
                    "repeated selection token admitted an older generation");
            require(!mailbox.tryRead(0U, generation - 7U, token, observed),
                    "repeated selection token exposed an older generation");
        }
        require(mailbox.tryRead(0U, generation, token, observed),
                "generation churn read failed");
        require(sameSnapshot(observed, next), "generation churn payload changed");
        previousGeneration = generation;
        previousToken = token;
    }

    require(mailbox.tryRead(1U, 90U, 909U, observed), "role reuse disturbed other role");
    require(sameSnapshot(observed, stable), "role reuse changed other role payload");
}

void inactiveRoleCanReturnFromPreloadedSuccessorToActiveOwner() {
    LateStemMixControlMailbox mailbox;
    require(mailbox.resetRole(1U, 20U, 202U), "bind preloaded successor role");
    require(mailbox.publish(1U, encodedSnapshot(20U, 202U, 50U)),
        "publish preloaded successor control");

    require(mailbox.resetRole(1U, 10U, 101U, true),
        "coordinator-proven inactive role returns to active owner");
    auto returned = encodedSnapshot(10U, 101U, 51U);
    returned.canonicalControlRevision = 1U;
    returned.sourceIntentRevision = 1U;
    returned.stemIntentRevision = 1U;
    require(mailbox.publish(1U, returned),
        "returned owner restarts owner-scoped revisions");
    LateStemMixControlSnapshot observed{};
    require(mailbox.tryRead(1U, 10U, 101U, observed) && sameSnapshot(observed, returned),
        "callback reads only the returned active owner");
}

void concurrentPublicationNeverExposesTornSnapshots() {
    constexpr std::uint64_t kPublicationCount = 100'000U;
    LateStemMixControlMailbox mailbox;
    require(mailbox.resetRole(0U, 100U, 1'001U), "bind stress role zero");
    require(mailbox.resetRole(1U, 200U, 2'002U), "bind stress role one");

    std::atomic<bool> readerReady{false};
    std::atomic<bool> start{false};
    std::atomic<bool> done{false};
    std::atomic<bool> failed{false};
    std::atomic<std::uint64_t> roleZeroReads{0U};
    std::atomic<std::uint64_t> roleOneReads{0U};

    std::jthread reader([&](std::stop_token stopToken) noexcept {
        readerReady.store(true, std::memory_order_release);
        while (!start.load(std::memory_order_acquire) && !stopToken.stop_requested()) {
            std::this_thread::yield();
        }
        do {
            LateStemMixControlSnapshot observed;
            if (mailbox.tryRead(0U, 100U, 1'001U, observed)) {
                if (!sameSnapshot(observed, encodedSnapshot(100U, 1'001U,
                                                            observed.publicationSequence))) {
                    failed.store(true, std::memory_order_relaxed);
                }
                roleZeroReads.fetch_add(1U, std::memory_order_relaxed);
            }
            if (mailbox.tryRead(1U, 200U, 2'002U, observed)) {
                if (!sameSnapshot(observed, encodedSnapshot(200U, 2'002U,
                                                            observed.publicationSequence))) {
                    failed.store(true, std::memory_order_relaxed);
                }
                roleOneReads.fetch_add(1U, std::memory_order_relaxed);
            }
        } while (!done.load(std::memory_order_acquire) && !stopToken.stop_requested());
    });

    const auto readyDeadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
    while (!readerReady.load(std::memory_order_acquire) &&
           std::chrono::steady_clock::now() < readyDeadline) {
        std::this_thread::yield();
    }
    const bool readerStarted = readerReady.load(std::memory_order_acquire);
    std::jthread writer([&](std::stop_token stopToken) noexcept {
        start.store(true, std::memory_order_release);
        for (std::uint64_t sequence = 1U;
             sequence <= kPublicationCount && !stopToken.stop_requested();
             ++sequence) {
            if (!mailbox.publish(0U, encodedSnapshot(100U, 1'001U, sequence)) ||
                !mailbox.publish(1U, encodedSnapshot(200U, 2'002U, sequence))) {
                failed.store(true, std::memory_order_relaxed);
            }
            if ((sequence & 0x3FU) == 0U) std::this_thread::yield();
        }
        done.store(true, std::memory_order_release);
    });

    const auto completionDeadline = std::chrono::steady_clock::now() + std::chrono::seconds(10);
    while (!done.load(std::memory_order_acquire) &&
           std::chrono::steady_clock::now() < completionDeadline) {
        std::this_thread::yield();
    }
    const bool writerCompleted = done.load(std::memory_order_acquire);
    if (!writerCompleted) writer.request_stop();
    writer.join();
    done.store(true, std::memory_order_release);
    reader.request_stop();
    reader.join();
    require(readerStarted, "concurrent reader reached its start barrier");
    require(writerCompleted, "concurrent writer completed before watchdog");
    require(!failed.load(std::memory_order_relaxed), "concurrent read observed torn payload");
    require(roleZeroReads.load(std::memory_order_relaxed) != 0U, "stress read no role-zero data");
    require(roleOneReads.load(std::memory_order_relaxed) != 0U, "stress read no role-one data");

    LateStemMixControlSnapshot observed;
    require(mailbox.tryRead(0U, 100U, 1'001U, observed), "final role-zero read failed");
    require(sameSnapshot(observed, encodedSnapshot(100U, 1'001U, kPublicationCount)),
            "final role-zero endpoint changed");
    require(mailbox.tryRead(1U, 200U, 2'002U, observed), "final role-one read failed");
    require(sameSnapshot(observed, encodedSnapshot(200U, 2'002U, kPublicationCount)),
            "final role-one endpoint changed");
}

void callbackOperationsAllocateNothing() {
    LateStemMixControlMailbox mailbox;
    LateStemMixControlSnapshot endpoint = encodedSnapshot(1U, 11U, 1U);
    LateStemMixControlSnapshot observed;
    bool succeeded = true;
    const std::uint64_t before = allocation_probe::count;

    allocation_probe::enabled = true;
    succeeded = mailbox.resetRole(0U, 1U, 11U) && succeeded;
    for (std::uint64_t sequence = 1U; sequence <= 10'000U; ++sequence) {
        endpoint = encodedSnapshot(1U, 11U, sequence);
        succeeded = mailbox.publish(0U, endpoint) && succeeded;
        succeeded = mailbox.tryRead(0U, 1U, 11U, observed) && succeeded;
    }
    allocation_probe::enabled = false;

    require(succeeded, "allocation probe operation failed");
    require(allocation_probe::count == before, "mailbox operation allocated memory");
    require(sameSnapshot(observed, endpoint), "allocation probe lost final endpoint");
}

}  // namespace

int main() {
    try {
        twoRolesAreIndependentAndOwnerExact();
        publicationAndRevisionHighWaterMarksNeverRegress();
        newerOwnerMayRestartOwnerScopedRevisions();
        resetsRejectAbaAndPreserveOtherRole();
        inactiveRoleCanReturnFromPreloadedSuccessorToActiveOwner();
        concurrentPublicationNeverExposesTornSnapshots();
        callbackOperationsAllocateNothing();
        std::cout << "Late stem mix control mailbox tests passed (roles="
                  << kLateStemMixOutputRoleCount
                  << ", concurrent publications=200000, allocations="
                  << allocation_probe::count << ")\n";
        return 0;
    } catch (const std::exception &error) {
        allocation_probe::enabled = false;
        std::cerr << "Late stem mix control mailbox test failure: " << error.what() << '\n';
        return 1;
    }
}
