#include "AllocationProbeAttributes.h"
#include "CurrentSongPcmCache.h"
#include "CurrentSongPcmCacheTestHooks.h"
#include <chrono>
#include <thread>
#include <limits>

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstring>
#include <cstdlib>
#include <filesystem>
#include <iostream>
#include <new>
#include <optional>
#include <string>
#include <vector>

namespace stemwise::audio::engine::cache_test {
std::atomic<std::size_t> limit{std::numeric_limits<std::size_t>::max()};
std::atomic<int> callsBeforeFailure{-1};
std::atomic<int> blockedOperation{-1}; // 0 = read, 1 = write
std::atomic<bool> entered{false}, release{false};
std::atomic<std::uint64_t> availableBytes{64ULL * 1'024ULL * 1'024ULL * 1'024ULL};
std::uint64_t availableStorageBytes() noexcept { return availableBytes.load(); }
std::size_t beforeIo(bool writing, std::size_t requested) noexcept {
    if (blockedOperation.load() == (writing ? 1 : 0)) {
        entered.store(true);
        while (!release.load()) std::this_thread::yield();
    }
    if (callsBeforeFailure.load() >= 0 && callsBeforeFailure.fetch_sub(1) == 0) return 0U;
    return std::min(requested, limit.load());
}
void reset() {
    availableBytes.store(64ULL * 1'024ULL * 1'024ULL * 1'024ULL);
    limit.store(std::numeric_limits<std::size_t>::max());
    callsBeforeFailure.store(-1); blockedOperation.store(-1);
    entered.store(false); release.store(false);
}


}

namespace {

std::atomic<bool> gTrackAllocations{false};
std::atomic<std::uint64_t> gAllocationCount{0U};
std::atomic<bool> gFailNextAllocation{false};
std::atomic<int> gFailAfterAllocations{-1};

void require(bool condition, const char *message) {
    if (!condition) {
        std::cerr << "CurrentSongPcmCache test failure: " << message << '\n';
        std::exit(1);
    }
}

std::vector<float> pcm(std::size_t lane, std::uint64_t first, std::size_t frames) {
    std::vector<float> result(frames * 2U);
    for (std::size_t frame = 0U; frame < frames; ++frame) {
        const float value = static_cast<float>(lane + 1U) * 0.02F +
            static_cast<float>(static_cast<std::int64_t>((first + frame) % 997U) - 498) * 0.000001F;
        result[frame * 2U] = value;
        result[frame * 2U + 1U] = -value;
    }
    return result;
}

std::vector<float> exactSigned16Pcm(std::size_t frames) {
    constexpr std::array<std::int16_t, 8U> kValues{
        -32'768, -24'576, -1, 0, 1, 8'192, 24'576, 32'767};
    std::vector<float> result(frames * 2U);
    for (std::size_t sample = 0U; sample < result.size(); ++sample) {
        result[sample] = static_cast<float>(kValues[sample % kValues.size()]) / 32'768.0F;
    }
    return result;
}

void requirePcm(
    const std::vector<float> &actual,
    std::size_t lane,
    std::uint64_t first,
    std::size_t frames) {
    const auto expected = pcm(lane, first, frames);
    require(actual == expected, "copied PCM lost exact lane/frame identity");
}

} // namespace

STEMWISE_ALLOCATION_HOOK void *operator new(std::size_t size) {
    if (gFailNextAllocation.exchange(false, std::memory_order_relaxed)) {
        throw std::bad_alloc();
    }
    if (gFailAfterAllocations.load(std::memory_order_relaxed) >= 0 &&
        gFailAfterAllocations.fetch_sub(1, std::memory_order_relaxed) == 0) {
        throw std::bad_alloc();
    }
    if (gTrackAllocations.load(std::memory_order_relaxed)) {
        gAllocationCount.fetch_add(1U, std::memory_order_relaxed);
    }
    if (void *value = std::malloc(size)) return value;
    throw std::bad_alloc();
}

STEMWISE_ALLOCATION_HOOK void *operator new[](std::size_t size) { return ::operator new(size); }
STEMWISE_ALLOCATION_HOOK void operator delete(void *value) noexcept { std::free(value); }
STEMWISE_ALLOCATION_HOOK void operator delete(void *value, std::size_t) noexcept { std::free(value); }
STEMWISE_ALLOCATION_HOOK void operator delete[](void *value) noexcept { std::free(value); }
STEMWISE_ALLOCATION_HOOK void operator delete[](void *value, std::size_t) noexcept { std::free(value); }

void storageAdmissionIsProportionalAndFailsWithoutLosingOriginal() {
    using namespace stemwise::audio::engine;
    using namespace stemwise::audio::engine::cache_test;
    reset();
    CurrentSongPcmCache cache;
    constexpr std::uint64_t frames = 300ULL * 48'000ULL;
    constexpr std::uint64_t fullBytes = frames * kCurrentSongCacheLaneCount * 8ULL;
    availableBytes.store(fullBytes + CurrentSongPcmCache::kFreeStorageReserveBytes);
    require(cache.admitStorage(kCurrentSongCacheLaneCount, frames) == CurrentSongCacheStorageStatus::Admitted,
        "admitted five-minute maximum-part package exceeds actual canonical budget");
    availableBytes.store(fullBytes + CurrentSongPcmCache::kFreeStorageReserveBytes - 1U);
    require(cache.admitStorage(kCurrentSongCacheLaneCount, frames) == CurrentSongCacheStorageStatus::InsufficientSpace,
        "storage reserve was consumed by admission");
    require(cache.snapshot().storedBytes == 0U && !cache.snapshot().begun,
        "failed admission created PCM or changed cache ownership");
    require(cache.admitStorage(1U, frames) == CurrentSongCacheStorageStatus::Admitted,
        "Original-only admission paid for unused maximum lanes");
    require(cache.admitStorage(kCurrentSongCacheLaneCount, std::numeric_limits<std::uint64_t>::max()) ==
        CurrentSongCacheStorageStatus::LimitExceeded, "storage arithmetic overflow bypassed the bound");
    require(cache.admitStorage(0U, frames) == CurrentSongCacheStorageStatus::LimitExceeded,
        "zero lanes bypassed admission");
    const CurrentSongCacheKey key{791U, 792U, 793U, 794U, 0U};
    const auto original = pcm(0U, 0U, 4096U);
    availableBytes.store(64ULL * 1'024ULL * 1'024ULL * 1'024ULL);
    require(cache.admitStorage(1U, 4096U) == CurrentSongCacheStorageStatus::Admitted &&
        cache.begin(key) == CurrentSongCacheBeginStatus::Accepted &&
        cache.offer(key, 0U, 0U, original.data(), 4096U).status == CurrentSongCacheOfferStatus::Accepted &&
        cache.sealLane(key, 0U, 4096U) == CurrentSongCacheSealStatus::Accepted,
        "storage Original setup");
    availableBytes.store(CurrentSongPcmCache::kFreeStorageReserveBytes);
    const auto before = cache.snapshot();
    require(cache.admitStorage(kCurrentSongCacheLaneCount, 4096U) == CurrentSongCacheStorageStatus::InsufficientSpace &&
        cache.complete(1U) && cache.snapshot().storedBytes == before.storedBytes,
        "late package admission destroyed the exact Original");
    require(cache.cancel(key) && cache.snapshot().storedBytes == 0U &&
        cache.availableFrames(0U) == 0U, "retired cache retained temporary PCM storage");
    reset();
}

void storageBudgetCountsOverlappingOwnersAndRejectsUndeclaredExtents() {
    using namespace stemwise::audio::engine;
    using namespace stemwise::audio::engine::cache_test;
    reset();
    constexpr auto twoGiBFrames = (2ULL * 1'024ULL * 1'024ULL * 1'024ULL) / 8ULL;
    CanonicalPcmStore departing("");
    CanonicalPcmStore successor("");
    require(departing.admit(1U, twoGiBFrames) == CurrentSongCacheStorageStatus::Admitted &&
        departing.diagnostics().fileBytes == 0U,
        "reservation eagerly allocated the declared recording");
    availableBytes.store(3ULL * 1'024ULL * 1'024ULL * 1'024ULL + CanonicalPcmStorageBudget::freeSpaceFloorBytes);
    require(successor.admit(1U, twoGiBFrames) == CurrentSongCacheStorageStatus::InsufficientSpace,
        "overlapping owners reserved the same available storage twice");
    departing.retireLanes(0U);
    require(successor.admit(1U, twoGiBFrames) == CurrentSongCacheStorageStatus::Admitted,
        "exact departing store retirement did not release its reservation");
    successor.retireLanes(0U);
    availableBytes.store(64ULL * 1'024ULL * 1'024ULL * 1'024ULL);
    constexpr auto twentyMinuteFrames = 20ULL * 60ULL * 48'000ULL;
    require(successor.admit(10U, twentyMinuteFrames) == CurrentSongCacheStorageStatus::Admitted &&
        successor.diagnostics().reservedBytes == twentyMinuteFrames * 10U * 8U &&
        successor.diagnostics().fileBytes == 0U,
        "feasible twenty-minute ten-lane source hit a fixed PCM size ceiling");
    successor.retireLanes(0U);

    CurrentSongPcmCache cache;
    const CurrentSongCacheKey key{801U, 802U, 803U, 804U, 0U};
    const auto original = pcm(0U, 0U, 4096U);
    require(cache.admitStorage(1U, 2048U) == CurrentSongCacheStorageStatus::Admitted &&
        cache.begin(key) == CurrentSongCacheBeginStatus::Accepted &&
        cache.offer(key, 0U, 0U, original.data(), 2048U).framesStored == 2048U,
        "extent rejection prefix setup");
    require(cache.offer(key, 0U, 2048U, original.data() + 4096U, 2048U).status ==
            CurrentSongCacheOfferStatus::AllocationFailed &&
        cache.snapshot().storageStatus == CurrentSongCacheStorageStatus::LimitExceeded &&
        cache.availableFrames(0U) == 2048U,
        "undeclared extent did not preserve the committed prefix and typed failure");
    std::vector<float> copied(4096U);
    require(cache.copyAvailableLane(0U, 0U, copied.data(), 2048U, 2048U) &&
        std::equal(copied.begin(), copied.end(), original.begin()),
        "extent admission failure changed committed PCM");
}

void storageFaultsPreservePublishedPcm() {
    using namespace stemwise::audio::engine;
    using namespace stemwise::audio::engine::cache_test;
    const CurrentSongCacheKey key{91U, 92U, 93U, 94U, 0U};
    const auto samples = pcm(0, 0, 8192);
    std::vector<float> copied(4096);
    for (int operation : {0, 1}) {
        reset();
        CurrentSongPcmCache cache;
        require(cache.begin(key) == CurrentSongCacheBeginStatus::Accepted, "fault begin");
        require(cache.offer(key, 0, 0, samples.data(), 2048).framesStored == 2048, "fault prefix");
        blockedOperation.store(operation);
        std::atomic<bool> ok{false};
        std::thread worker([&] {
            ok.store(operation == 1
                ? cache.offer(key, 0, 2048, samples.data() + 4096, 2048).framesStored == 2048
                : cache.copyAvailableLane(0, 0, copied.data(), 2048, 2048));
        });
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(3);
        while (!entered.load() && std::chrono::steady_clock::now() < deadline) std::this_thread::yield();
        const bool observed = entered.load();
        const auto availableWhileBlocked = cache.availableFrames(0);
        release.store(true); worker.join();
        require(observed && ok.load(), "blocked storage did not resume");
        require(availableWhileBlocked == 2048, "unfinished write was exposed");
        if (operation == 0) require(std::equal(copied.begin(), copied.end(), samples.begin()), "delayed read PCM");
    }
    reset();
    CurrentSongPcmCache shortIo;
    require(shortIo.begin(key) == CurrentSongCacheBeginStatus::Accepted, "short begin");
    limit.store(37); // Force repeated short system reads/writes, including unaligned float bytes.
    require(shortIo.offer(key, 0, 0, samples.data(), 8192).framesStored == 8192, "short write retry");
    require(shortIo.sealLane(key, 0, 8192) == CurrentSongCacheSealStatus::Accepted, "short seal");
    require(shortIo.copyLane(0, 0, copied.data(), 2048, 2048), "short read retry");
    require(std::equal(copied.begin(), copied.end(), samples.begin()), "short I/O changed PCM");
    callsBeforeFailure.store(1); // One partial read succeeds, then the read fails.
    require(!shortIo.copyLane(0, 6144, copied.data(), 2048, 2048), "partial read failure accepted");
    require(shortIo.snapshot().allocationFailed, "read error missing from health");
    reset();
    CurrentSongPcmCache failedWrite;
    require(failedWrite.begin(key) == CurrentSongCacheBeginStatus::Accepted, "write failure begin");
    require(failedWrite.offer(key, 0, 0, samples.data(), 2048).framesStored == 2048, "write prefix");
    limit.store(37); callsBeforeFailure.store(1);
    const auto failed = failedWrite.offer(key, 0, 2048, samples.data() + 4096, 2048);
    require(failed.status == CurrentSongCacheOfferStatus::AllocationFailed, "partial write error accepted");
    require(failed.nextExpectedFrame == failedWrite.availableFrames(0),
        "failed offer did not return the committed fallback boundary");
    reset();
    require(failedWrite.availableFrames(0) == 2048 && !failedWrite.complete(1), "failed chunk published");
    require(failedWrite.copyAvailableLane(0, 0, copied.data(), 2048, 2048), "failure lost valid prefix");
    require(std::equal(copied.begin(), copied.end(), samples.begin()), "failure corrupted prefix");
    const auto replay = failedWrite.unpublishedOriginal(key);
    require(replay.firstFrame == 2048U && replay.frameCount == 2048U && replay.samples != nullptr,
        "failed write lost its complete accepted but unpublished chunk");
    require(std::equal(replay.samples, replay.samples + replay.frameCount * 2U, samples.data() + 4096U),
        "failed write replay changed PCM bits");
    for (bool sealFailure : {false, true}) {
        reset();
        CurrentSongPcmCache partial;
        require(partial.begin(key) == CurrentSongCacheBeginStatus::Accepted, "partial recovery begin");
        require(partial.offer(key, 0U, 0U, samples.data(), 513U).inputFramesConsumed == 513U,
            "partial recovery accepted prefix");
        callsBeforeFailure.store(0);
        if (sealFailure) {
            require(partial.sealLane(key, 0U, 513U) == CurrentSongCacheSealStatus::AllocationFailed,
                "partial final seal fault did not occur");
        } else {
            const auto rejected = partial.offer(key, 0U, 513U, samples.data() + 1026U, 1536U);
            require(rejected.status == CurrentSongCacheOfferStatus::AllocationFailed &&
                rejected.inputFramesConsumed == 1535U && rejected.nextExpectedFrame == 0U,
                "failed offer lost the accepted filling prefix or pending suffix boundary");
        }
        const auto retained = partial.unpublishedOriginal(key);
        require(retained.firstFrame == 0U && retained.frameCount == (sealFailure ? 513U : 2048U),
            "partial final chunk replay interval changed");
        require(std::equal(retained.samples, retained.samples + retained.frameCount * 2U, samples.data()),
            "partial final chunk replay PCM changed");
        require(partial.availableFrames(0U) == 0U && !partial.copyAvailableLane(0U, 0U, copied.data(), 2048U, 1U),
            "uncommitted failed PCM escaped the private replay view");
    }
    reset();
    require(failedWrite.cancel(key) && !failedWrite.copyAvailableLane(0, 0, copied.data(), 2048, 2048),
        "cancelled failed cache remained readable");
    require(failedWrite.unpublishedOriginal(key).frameCount == 0U, "cancelled replay remained readable");
}

void deletingPartsRetainsOriginalAndReplacementCannotReuseRetiredPcm() {
    using namespace stemwise::audio::engine;
    CurrentSongPcmCache cache;
    const CurrentSongCacheKey first{50U, 1U, 60U, 70U, 80U};
    const CurrentSongCacheKey originalOnly{50U, 1U, 60U, 70U, 0U};
    const CurrentSongCacheKey replacement{50U, 1U, 60U, 70U, 90U};
    constexpr std::size_t frames = 1'024U;
    const auto original = pcm(0U, 0U, frames);
    const auto oldPart = pcm(1U, 0U, frames);
    const auto newPart = pcm(17U, 0U, frames);
    require(cache.begin(first) == CurrentSongCacheBeginStatus::Accepted, "retirement cache begin");
    require(cache.offer(first, 0U, 0U, original.data(), frames).framesStored == frames,
        "retirement Original stored");
    require(cache.sealLane(first, 0U, frames) == CurrentSongCacheSealStatus::Accepted,
        "retirement Original sealed");
    require(cache.offer(first, 1U, 0U, oldPart.data(), frames).framesStored == frames,
        "retirement old part stored");
    require(cache.sealLane(first, 1U, frames) == CurrentSongCacheSealStatus::Accepted,
        "retirement old part sealed");
    std::vector<float> copied(frames * 2U);
    require(cache.copyLane(1U, 0U, copied.data(), frames, frames) && copied == oldPart,
        "retirement old part read window established");
    auto wrongOwner = originalOnly;
    ++wrongOwner.generation;
    require(!cache.retireStemPackageIdentity(first, wrongOwner) && cache.snapshot().key == first,
        "retirement accepted wrong owner or mutated cache on rejection");
    require(cache.retireStemPackageIdentity(first, originalOnly), "part retirement rejected");
    const auto retired = cache.snapshot();
    require(retired.key == originalOnly && retired.completeLaneMask == 1U &&
        retired.nextExpectedFrames[1U] == 0U, "part retirement retained obsolete completion state");
    require(cache.copyLane(0U, 0U, copied.data(), frames, frames) && copied == original,
        "part retirement changed Original samples");
    require(!cache.copyLane(1U, 0U, copied.data(), frames, frames),
        "part retirement leaked retained old part read window");
    require(cache.offer(first, 1U, 0U, oldPart.data(), frames).status == CurrentSongCacheOfferStatus::StaleKey,
        "part retirement accepted old package writer");
    require(cache.adoptStemPackageIdentity(originalOnly, replacement), "replacement package adoption rejected");
    require(!cache.complete(3U) && cache.availableFrames(1U) == 0U,
        "replacement package inherited old part readiness");
    require(cache.offer(replacement, 1U, 0U, newPart.data(), frames).framesStored == frames,
        "replacement package did not restart part at frame zero");
    require(cache.sealLane(replacement, 1U, frames) == CurrentSongCacheSealStatus::Accepted,
        "replacement package could not seal new part");
    require(cache.copyLane(1U, 0U, copied.data(), frames, frames) && copied == newPart,
        "replacement package reused retired PCM");
}

void requestedRangeIsReadyBeforeTheLongOriginalCompletes() {
    using namespace stemwise::audio::engine;
    CurrentSongPcmCache cache;
    const CurrentSongCacheKey key{71U, 83U, 91U, 101U, 103U};
    constexpr std::size_t prefix = 16'384U;
    require(cache.begin(key) == CurrentSongCacheBeginStatus::Accepted &&
                cache.admitStorage(3U, 600U * 48'000U) == CurrentSongCacheStorageStatus::Admitted,
            "ten-minute cache is admitted without allocating the declared recording");
    const auto original = pcm(0U, 0U, prefix);
    const auto stem = pcm(1U, 0U, prefix);
    require(cache.offer(key, 0U, 0U, original.data(), prefix).inputFramesConsumed == prefix,
            "long Original publishes only its current prefix");
    require(cache.offer(key, 1U, 0U, stem.data(), prefix).inputFramesConsumed == prefix,
            "requested stem range does not wait for unrelated Original EOF");
    require(cache.covers(3U, 0U, 4'800U) && !cache.complete(3U),
            "100ms range readiness is distinct from complete recording readiness");
    require(!cache.covers(3U, prefix - 64U, 128U), "unpublished suffix is never invented");
    std::vector<float> actual(4'800U * 2U);
    require(cache.copyLane(1U, 0U, actual.data(), 4'800U, 4'800U), "range reads current published stem");
    requirePcm(actual, 1U, 0U, 4'800U);
    require(cache.snapshot().storedBytes == prefix * 2U * 8U,
            "cold loop storage is proportional to published ranges, not ten-minute declaration");
    const auto shortStem = pcm(2U, 0U, 1'000U);
    require(cache.offer(key, 2U, 0U, shortStem.data(), 1'000U).inputFramesConsumed == 1'000U,
            "short stem's actual decoded prefix admitted");
    // The real short-stem end, not a missing decoder, permits a silent suffix.
    static_cast<void>(cache.sealLane(key, 2U, 1'000U));
    require(cache.covers(7U, 0U, 4'800U) && !cache.complete(7U),
            "known short-stem silence covers the requested range before Original EOF");
    require(cache.copyLane(2U, 0U, actual.data(), 4'800U, 4'800U), "short range can be read");
    const auto expectedShort = pcm(2U, 0U, 1'000U);
    require(std::equal(expectedShort.begin(), expectedShort.end(), actual.begin()) &&
                std::all_of(actual.begin() + 2'000U, actual.end(), [](float value) { return value == 0.0F; }),
            "short stem preserves its exact prefix and zero-pads only after observed EOS");
}

void committedCanonicalPcmRemainsRandomlyReadableAfterForwardPlayback() {
    using namespace stemwise::audio::engine;
    CurrentSongPcmCache cache;
    const CurrentSongCacheKey owner{951U, 952U, 953U, 954U, 0U};
    constexpr std::uint64_t frames = 12U * 48'000U;
    require(cache.admitStorage(1U, frames) == CurrentSongCacheStorageStatus::Admitted &&
        cache.begin(owner) == CurrentSongCacheBeginStatus::Accepted, "canonical read admission");
    for (std::uint64_t first = 0U; first < frames; first += 2'048U) {
        const auto count = static_cast<std::size_t>(std::min<std::uint64_t>(2'048U, frames - first));
        const auto samples = pcm(0U, first, count);
        require(cache.offer(owner, 0U, first, samples.data(), count).status ==
            CurrentSongCacheOfferStatus::Accepted, "canonical sequential publication");
    }
    require(cache.sealLane(owner, 0U, frames) == CurrentSongCacheSealStatus::Accepted,
        "canonical source sealed");
    cache.enableAsyncReads([](void *) noexcept {}, nullptr);
    std::array<float, 512U> actual{};
    for (const auto first : std::array<std::uint64_t, 3U>{480'000U, 0U, frames - 256U}) {
        require(!cache.copyLane(0U, first, actual.data(), 256U, 256U), "canonical cold read uses I/O owner");
        require(cache.serviceAsyncReads(), "canonical read must publish without reopening the source decoder");
        require(cache.copyLane(0U, first, actual.data(), 256U, 256U),
            "committed canonical PCM required discarded-prefix replay");
        requirePcm(std::vector<float>(actual.begin(), actual.end()), 0U, first, 256U);
    }
}

void optionalReadFailureRetainsOriginal() {
    using namespace stemwise::audio::engine;
    for (const int readKind : {0, 1, 2}) {
        const bool asynchronous = readKind != 0;
        cache_test::reset();
        CurrentSongPcmCache cache;
        const CurrentSongCacheKey key{701U, 702U, 703U, 704U, 705U};
        const auto original = pcm(0U, 0U, 16384U);
        const auto stem = pcm(1U, 0U, 16384U);
        require(cache.begin(key) == CurrentSongCacheBeginStatus::Accepted, "optional fault begin");
        for (std::size_t lane : {0U, 1U}) {
            require(cache.offer(key, lane, 0U, lane == 0U ? original.data() : stem.data(), 16384U).framesStored == 16384U,
                "optional fault publishes actual PCM");
            require(cache.sealLane(key, lane, 16384U) == CurrentSongCacheSealStatus::Accepted, "optional fault seal");
        }
        if (asynchronous) cache.enableAsyncReads([](void *) noexcept {}, nullptr);
        std::array<float, 128U> output{};
        if (readKind == 2) {
            require(!cache.copyLane(1U, 0U, output.data(), 64U, 64U), "stem head requests its window");
            require(cache.serviceAsyncReads(), "stem head warms");
            require(cache.copyLane(1U, 0U, output.data(), 64U, 64U), "resident stem schedules read ahead");
        }
        cache_test::callsBeforeFailure.store(0);
        if (readKind != 2) require(!cache.copyLane(1U, 0U, output.data(), 64U, 64U), "optional fault rejects unavailable PCM");
        if (asynchronous) require(cache.serviceAsyncReads(), "optional failure is observable progress");
        cache_test::reset();
        require(cache.snapshot().readFailureLaneMask == 2U && !cache.complete(2U), "failure reports only actual optional lane");
        require(cache.complete(1U) && cache.availableFrames(0U) == 16384U,
            "optional read failure must retain complete Original authority");
        if (asynchronous) {
            require(!cache.copyLane(0U, 0U, output.data(), 64U, 64U), "Original requests its own window");
            cache.serviceAsyncReads();
        }
        require(cache.copyLane(0U, 0U, output.data(), 64U, 64U) &&
            std::equal(output.begin(), output.end(), original.begin()), "optional failure changed Original samples");
        auto originalOnly = key; originalOnly.stemPackageHash = 0U;
        require(cache.retireStemPackageIdentity(key, originalOnly), "optional failure cannot prevent stem-only retirement");
        require(cache.snapshot().key == originalOnly && cache.complete(1U), "retirement must retain Original identity");
    }
}

void requiredPreparationPrecedesOptionalActiveBusWarming() {
    using namespace stemwise::audio::engine;
    CurrentSongPcmCache cache;
    const CurrentSongCacheKey key{70U, 80U, 90U, 100U, 110U};
    require(cache.begin(key) == CurrentSongCacheBeginStatus::Accepted, "priority fixture begins");
    for (std::size_t lane = 0U; lane < 2U; ++lane) {
        const auto frames = lane == 0U ? 8192U : 6000U;
        const auto input = pcm(lane, 0U, frames);
        require(cache.offer(key, lane, 0U, input.data(), frames).status == CurrentSongCacheOfferStatus::Accepted,
            "priority PCM publishes immutable chunks");
        if (lane != 0U)
            require(cache.sealLane(key, lane, frames) == CurrentSongCacheSealStatus::OriginalPending,
                "observed short-stem EOF admits silence inside the streaming Original prefix");
    }
    cache.enableAsyncReads([](void*) noexcept {}, nullptr);
    require(cache.readWindowRangeReady(2U, 7000U, 64U) && cache.snapshot().io.readOperations == 0U,
        "committed implicit silence is ready without a nonexistent file window");
    const auto active = std::make_shared<CurrentSongPcmReadConsumer>();
    const auto preparation = std::make_shared<CurrentSongPcmReadConsumer>();
    require(cache.admitReadConsumer(active, key.selectionToken, key.generation) &&
        cache.admitReadConsumer(preparation, key.selectionToken, key.generation), "both priority readers admit");
    preparation->preparation.store(true);
    std::array<float, 128U> actual{};
    require(!cache.copyAvailableLane(1U, 4096U, actual.data(), 64U, 64U, false, active.get()),
        "optional active bus starts as a true cold miss");
    cache.requireReadRange(*preparation, 0U, 1U, 0U, 64U);
    require(cache.serviceAsyncReads() && cache.copyLane(0U, 0U, actual.data(), 64U, 64U,
        true, preparation.get()), "required preparation PCM precedes optional active bus speculation");
    const auto expected = pcm(0U, 0U, 64U);
    require(std::equal(actual.begin(), actual.end(), expected.begin()), "required priority read changed PCM");
    require(!cache.copyAvailableLane(1U, 4096U, actual.data(), 64U, 64U, false, active.get()),
        "the first I/O unit serves required PCM exclusively");
    bool optionalResident = false;
    // The successful required copy also queued one Original read-ahead; both speculative
    // streams may run in either order after the required unit.
    for (unsigned unit = 0U; unit < 2U && !optionalResident; ++unit) {
        cache.serviceAsyncReads();
        optionalResident = cache.copyAvailableLane(1U, 4096U, actual.data(), 64U, 64U, false, active.get());
    }
    require(optionalResident, "optional prefetch remains enabled after required work completes");
}

void admittedReadersRetainAdvancingCrossWindowWorkingSets() {
    using namespace stemwise::audio::engine;
    using namespace stemwise::audio::engine::cache_test;
    reset();
    CurrentSongPcmCache cache;
    const CurrentSongCacheKey key{80U, 90U, 100U, 110U, 120U};
    constexpr std::size_t frames = 131'072U;
    require(cache.begin(key) == CurrentSongCacheBeginStatus::Accepted, "reader fixture begins");
    for (std::size_t lane = 0U; lane < kCurrentSongCacheLaneCount; ++lane) {
        const auto input = pcm(lane, 0U, frames);
        require(cache.offer(key, lane, 0U, input.data(), frames).status == CurrentSongCacheOfferStatus::Accepted &&
            cache.sealLane(key, lane, frames) == CurrentSongCacheSealStatus::Accepted,
            "maximum admitted package has exact immutable PCM");
    }
    cache.enableAsyncReads([](void*) noexcept {}, nullptr);
    std::array<std::shared_ptr<CurrentSongPcmReadConsumer>, 4U> readers;
    for (auto &reader : readers) reader = std::make_shared<CurrentSongPcmReadConsumer>();
    gAllocationCount.store(0U); gTrackAllocations.store(true);
    const bool admitted = cache.admitReadConsumer(readers[0U], key.selectionToken, key.generation) &&
        cache.admitReadConsumer(readers[1U], key.selectionToken, key.generation) &&
        cache.admitReadConsumer(readers[2U], key.selectionToken, key.generation);
    const bool overflow = cache.admitReadConsumer(readers[3U], key.selectionToken, key.generation);
    const bool wrongOwner = cache.admitReadConsumer(readers[0U], key.selectionToken + 1U, key.generation);
    gTrackAllocations.store(false);
    require(admitted && !overflow && !wrongOwner && gAllocationCount.load() == 0U,
        "three physical pipeline owners admit without allocation; fourth/wrong owner reject");
    readers[1U]->preparation.store(true); readers[2U]->preparation.store(true);
    std::array<float, 128U> output{};
    constexpr auto laneMask = (1U << kCurrentSongCacheLaneCount) - 1U;
    for (const auto advance : {0U, 4096U, 8192U}) {
        for (std::size_t reader = 0U; reader < 3U; ++reader)
            for (std::size_t domain = 0U; domain < CurrentSongPcmReadConsumer::kDomains; ++domain)
                cache.requireReadRange(*readers[reader], domain, laneMask,
                    reader * 32768U + domain * 8192U + 4080U + advance, 64U);
        require(cache.admitReadConsumer(readers[0U], key.selectionToken, key.generation),
            "same exact active reader may rebind without withdrawing its required ranges");
        for (unsigned service = 0U; service < 500U && cache.serviceAsyncReads(); ++service) {}
        for (std::size_t reader = 0U; reader < 3U; ++reader)
            for (std::size_t domain = 0U; domain < CurrentSongPcmReadConsumer::kDomains; ++domain)
                for (std::size_t lane = 0U; lane < kCurrentSongCacheLaneCount; ++lane) {
                    const auto first = reader * 32768U + domain * 8192U + 4080U + advance;
                    require(cache.copyLane(lane, first, output.data(), 64U, 64U, true,
                        readers[reader].get(), domain), "every admitted next unread slice remains resident");
                    const auto expected = pcm(lane, first, 64U);
                    require(std::equal(output.begin(), output.end(), expected.begin()),
                        "cross-window reader receives exact lane/cursor PCM");
                    cache.serviceAsyncReads(); // Actual successful copies request speculation.
                }
    }
    // A nonblocking publication may be deferred while its pipeline's output queue is full.
    // Keep its last accepted working set pinned; it must not reserve unrelated/empty slots.
    readers[0U]->pendingDomains.fetch_or(1U);
    cache.requireReadRange(*readers[1U], 0U, 1U, 120'000U, 64U);
    require(cache.serviceAsyncReads() && cache.copyLane(0U, 120'000U, output.data(), 64U, 64U,
        true, readers[1U].get()), "one deferred reader cannot block another reader's cold required PCM");
    require(cache.readWindowRangeReady(1U, 12'272U, 64U),
        "a deferred publication preserves its prior exact published pin");
    readers[0U]->pendingDomains.fetch_and(~1U);
    require(cache.copyLane(0U, 12'272U, output.data(), 64U, 64U, true, readers[0U].get()),
        "the prior owner resumes directly from its retained working set");
    readers[1U]->active.store(false);
    cache.serviceAsyncReads();
    require(cache.admitReadConsumer(readers[3U], key.selectionToken, key.generation),
        "exact consumer retirement releases admission to its replacement");
    cache.requireReadRange(*readers[3U], 0U, 2U, 130'000U, 64U);
    callsBeforeFailure.store(0);
    cache.serviceAsyncReads(); reset();
    require((cache.snapshot().readFailureLaneMask & 2U) != 0U &&
        !cache.copyLane(1U, 130'000U, output.data(), 64U, 64U, true, readers[3U].get()),
        "failed canonical reads never manufacture a required window");
    require(cache.complete(1U), "optional required-read failure preserves Original authority");
    require(cache.cancel(key) && !cache.copyLane(0U, 0U, output.data(), 64U, 64U, true, readers[0U].get()),
        "cancelled cache rejects previously admitted consumers");
}

void residentOriginalSurvivesHeldOptionalStorageRetirement() {
    using namespace stemwise::audio::engine;
    using namespace stemwise::audio::engine::cache_test;
    reset();
    static std::atomic<bool> retirementHoldExpired{false};
    retirementHoldExpired.store(false);
    CurrentSongPcmCache cache;
    const CurrentSongCacheKey key{8U, 9U, 10U, 11U, 12U};
    const auto original = pcm(0U, 0U, 4096U), part = pcm(1U, 0U, 4096U);
    require(cache.begin(key) == CurrentSongCacheBeginStatus::Accepted &&
        cache.offer(key, 0U, 0U, original.data(), 4096U).status == CurrentSongCacheOfferStatus::Accepted &&
        cache.sealLane(key, 0U, 4096U) == CurrentSongCacheSealStatus::Accepted &&
        cache.offer(key, 1U, 0U, part.data(), 4096U).status == CurrentSongCacheOfferStatus::Accepted &&
        cache.sealLane(key, 1U, 4096U) == CurrentSongCacheSealStatus::Accepted, "retirement fixture sealed");
    cache.enableAsyncReads([](void*) noexcept {}, nullptr);
    std::array<float, 128U> output{};
    static_cast<void>(cache.copyLane(0U, 0U, output.data(), 64U, 64U));
    cache.serviceAsyncReads();
    require(cache.copyLane(0U, 0U, output.data(), 64U, 64U), "Original resident before optional retirement");
    beforeOptionalStorageRetirement.store(+[]() noexcept {
        entered.store(true, std::memory_order_release);
        const auto end = std::chrono::steady_clock::now() + std::chrono::seconds(3);
        while (!release.load(std::memory_order_acquire) && std::chrono::steady_clock::now() < end)
            std::this_thread::yield();
        retirementHoldExpired.store(!release.load(std::memory_order_acquire));
    });
    auto next = key; next.stemPackageHash = 0U;
    bool retired = false;
    std::thread worker([&] { retired = cache.retireStemPackageIdentity(key, next); });
    const auto end = std::chrono::steady_clock::now() + std::chrono::seconds(2);
    while (!entered.load(std::memory_order_acquire) && std::chrono::steady_clock::now() < end)
        std::this_thread::yield();
    const bool held = entered.load(std::memory_order_acquire);
    const bool resident = cache.readWindowRangeReady(1U, 0U, 64U);
    const bool readable = cache.copyLane(0U, 0U, output.data(), 64U, 64U);
    const bool rejectedPart = !cache.copyLane(1U, 0U, output.data(), 64U, 64U);
    release.store(true, std::memory_order_release); worker.join();
    beforeOptionalStorageRetirement.store(nullptr);
    require(held && !retirementHoldExpired.load() && retired && rejectedPart,
        "optional retirement must detach stale part authority during the bounded hold");
    require(resident && readable, "held optional cleanup must preserve resident Original availability");
    require(std::equal(output.begin(), output.end(), original.begin()), "retirement changed resident Original PCM");
    require(cache.snapshot().key == next && cache.complete(1U), "retirement changed exact Original ownership/extent");
}

void wrongContentRebindCannotExposeThePreviousSource() {
    using namespace stemwise::audio::engine;
    constexpr CurrentSongCacheKey first{11U, 21U, 31U, 41U, 51U};
    constexpr CurrentSongCacheKey replacement{11U, 22U, 32U, 42U, 51U};
    CurrentSongPcmCache cache;
    const auto oldPcm = pcm(0U, 0U, 256U);
    const auto replacementPcm = pcm(1U, 0U, 256U);
    require(cache.begin(first) == CurrentSongCacheBeginStatus::Accepted &&
        cache.offer(first, 0U, 0U, oldPcm.data(), 256U).framesStored == 256U &&
        cache.sealLane(first, 0U, 256U) == CurrentSongCacheSealStatus::Accepted,
        "wrong-identity fixture commits an independently authored Original");
    const bool admitted = cache.rebindCompleteOwnership(first, replacement, 1U);
    std::vector<float> actual(512U);
    require(cache.copyLane(0U, 0U, actual.data(), 256U, 256U), "retained Original remains readable");
    if (admitted) require(actual == replacementPcm, "AE-07 wrong source identity exposed previous source PCM");
    require(!admitted && cache.snapshot().key == first && actual == oldPcm,
        "AE-07 rejected identity change preserves exact source ownership and PCM");
}

int main(int argc, char **argv) {
    using namespace stemwise::audio::engine;
    if (argc == 2 && std::string(argv[1]) == "--configured-directory") {
        CanonicalPcmStore store("stemwise-absent-cache-directory/child");
        require(store.admit(1U, 256U) == CurrentSongCacheStorageStatus::Admitted,
            "test admission boundary supplies capacity independently of filesystem creation");
        const auto samples = pcm(0U, 0U, 256U);
        require(!store.append(0U, 0U, samples.data(), 256U) &&
            store.status() == CurrentSongCacheStorageStatus::IoFailure,
            "cache must fail at its configured volume rather than silently write to the default directory");
        require(store.diagnostics().fileBytes == 0U, "failed configured creation must not publish PCM");
        const auto missing = std::filesystem::absolute("stemwise-absent-cache-directory/child").string();
        constexpr std::array names{"TMP", "TEMP", "TMPDIR", "TEMPDIR"};
        std::array<std::optional<std::string>, names.size()> previous{};
        const auto setEnvironment = [](const char *name, const char *value) {
#if defined(_WIN32)
            return _putenv_s(name, value == nullptr ? "" : value) == 0;
#else
            return (value == nullptr ? unsetenv(name) : setenv(name, value, 1)) == 0;
#endif
        };
        for (std::size_t index = 0U; index < names.size(); ++index) {
#if defined(_WIN32)
            char *value = nullptr;
            std::size_t length = 0U;
            require(_dupenv_s(&value, &length, names[index]) == 0, "temporary-directory fixture must read the environment");
            if (value != nullptr) previous[index] = value;
            std::free(value);
#else
            if (const auto *value = std::getenv(names[index])) previous[index] = value;
#endif
            require(setEnvironment(names[index], missing.c_str()), "unavailable temporary-directory fixture must install");
        }
        std::error_code resolutionError;
        static_cast<void>(std::filesystem::temp_directory_path(resolutionError));
        require(static_cast<bool>(resolutionError), "fixture must make platform temporary-directory resolution fail");
        CanonicalPcmStore unavailableDefault("");
        const auto unavailableStatus = unavailableDefault.admit(1U, 256U);
        const auto unavailableFiles = unavailableDefault.diagnostics().fileBytes;
        for (std::size_t index = 0U; index < names.size(); ++index)
            require(setEnvironment(names[index], previous[index] ? previous[index]->c_str() : nullptr),
                "temporary-directory fixture must restore the environment");
        require(unavailableStatus == CurrentSongCacheStorageStatus::InsufficientSpace && unavailableFiles == 0U,
            "unavailable default temporary directory must reject optional cache admission without throwing or using a relative path");
        return 0;
    }
    wrongContentRebindCannotExposeThePreviousSource();
    if (argc == 2 && std::string(argv[1]) == "--reader-priority") {
        requiredPreparationPrecedesOptionalActiveBusWarming();
        return 0;
    }
    if (argc == 2 && std::string(argv[1]) == "--reader-working-sets") {
        admittedReadersRetainAdvancingCrossWindowWorkingSets();
        return 0;
    }
    if (argc == 2 && std::string(argv[1]) == "--optional-retirement") {
        residentOriginalSurvivesHeldOptionalStorageRetirement();
        return 0;
    }
    if (argc == 2 && std::string(argv[1]) == "--canonical-random-access") {
        committedCanonicalPcmRemainsRandomlyReadableAfterForwardPlayback();
        std::cout << "PASS canonical random access\n";
        return 0;
    }

    requiredPreparationPrecedesOptionalActiveBusWarming();
    admittedReadersRetainAdvancingCrossWindowWorkingSets();
    residentOriginalSurvivesHeldOptionalStorageRetirement();
    optionalReadFailureRetainsOriginal();
    storageAdmissionIsProportionalAndFailsWithoutLosingOriginal();
    deletingPartsRetainsOriginalAndReplacementCannotReuseRetiredPcm();
    storageFaultsPreservePublishedPcm();
    requestedRangeIsReadyBeforeTheLongOriginalCompletes();
    storageBudgetCountsOverlappingOwnersAndRejectsUndeclaredExtents();
    committedCanonicalPcmRemainsRandomlyReadableAfterForwardPlayback();
    {
        CurrentSongPcmCache cache;
        const CurrentSongCacheKey key{1U, 2U, 3U, 4U, 0U};
        require(cache.begin(key) == CurrentSongCacheBeginStatus::Accepted, "read-ahead begin");
        const auto original = pcm(0U, 0U, 48'007U);
        require(cache.offer(key, 0U, 0U, original.data(), 48'007U).status ==
            CurrentSongCacheOfferStatus::Accepted, "read-ahead offer");
        require(cache.sealLane(key, 0U, 48'007U) == CurrentSongCacheSealStatus::Accepted,
            "read-ahead seal");
        std::array<float, 960U> block{};
        gTrackAllocations.store(true);
        const auto allocations = gAllocationCount.load();
        for (std::uint64_t start = 0U; start < 48'007U; start += 480U) {
            const auto count = static_cast<std::size_t>(std::min<std::uint64_t>(480U, 48'007U - start));
            require(cache.copyLane(0U, start, block.data(), 480U, count), "read-ahead copy");
            require(std::memcmp(block.data(), original.data() + start * 2U, count * 8U) == 0,
                "read-ahead changed PCM");
        }
        require(gAllocationCount.load() == allocations, "read-ahead allocated during rendering");
        gTrackAllocations.store(false);
        require(cache.snapshot().diskReadOperations == 12U, "sequential reads were not batched");
        require(cache.copyLane(0U, 97U, block.data(), 480U, 480U), "random loop head");
        const auto reads = cache.snapshot().diskReadOperations;
        require(cache.copyLane(0U, 47'100U, block.data(), 480U, 480U), "retained body");
        require(cache.copyLane(0U, 97U, block.data(), 480U, 480U), "retained loop head");
        require(cache.snapshot().diskReadOperations == reads, "two retained windows thrashed");
        auto next = key;
        next.generation++;
        require(cache.rebindCompleteOwnership(key, next, 1U), "read-ahead owner rebind");
        require(cache.copyLane(0U, 97U, block.data(), 480U, 480U) &&
            std::memcmp(block.data(), original.data() + 97U * 2U, block.size() * sizeof(float)) == 0,
            "rebound cache lost exact samples");
        require(cache.cancel(next) && !cache.copyLane(0U, 97U, block.data(), 480U, 480U),
            "cancelled cache leaked retained read window");
    }

    {
        CurrentSongPcmCache cache;
        const CurrentSongCacheKey originalOnly{7U, 8U, 9U, 10U, 0U};
        const CurrentSongCacheKey withStems{7U, 8U, 9U, 10U, 11U};
        require(cache.begin(originalOnly) == CurrentSongCacheBeginStatus::Accepted,
            "begin Original-only identity adoption cache");
        const auto original = pcm(0U, 0U, 1'000U);
        require(cache.offer(originalOnly, 0U, 0U, original.data(), 1'000U).status ==
                CurrentSongCacheOfferStatus::Accepted,
            "offer Original before stem identity adoption");
        require(cache.adoptStemPackageIdentity(originalOnly, withStems),
            "first stem package did not adopt the in-progress cache");
        require(cache.snapshot().key == withStems,
            "adopted stem identity was not published");
        require(cache.offer(originalOnly, 1U, 0U, original.data(), 1'000U).status ==
                CurrentSongCacheOfferStatus::StaleKey,
            "retired Original-only key remained writable");
        require(!cache.adoptStemPackageIdentity(withStems, originalOnly),
            "stem identity was allowed to regress to Original-only");
    }

    const CurrentSongCacheKey key{11U, 21U, 31U, 41U, 51U};
    CurrentSongPcmCache cache;
    require(cache.begin(key) == CurrentSongCacheBeginStatus::Accepted, "begin");
    require(cache.begin(key) == CurrentSongCacheBeginStatus::AlreadyBegun, "single generation");

    const auto originalA = pcm(0U, 0U, 2'000U);
    const auto originalB = pcm(0U, 2'000U, 1'000U);
    std::array<float, 256U * 2U> block{};
    require(cache.offer(key, 0U, 0U, originalA.data(), 2'000U).framesStored == 2'000U,
        "first Original offer");
    require(cache.offer(key, 0U, 2'000U, originalB.data(), 1'000U).framesStored == 1'000U,
        "cross-chunk Original offer");
    require(cache.availableFrames(0U) == CurrentSongPcmCache::kChunkFrames,
        "complete Original startup chunk was not release-published");
    std::array<float, CurrentSongPcmCache::kChunkFrames * 2U> startup{};
    require(cache.copyAvailableLane(
                0U,
                0U,
                startup.data(),
                CurrentSongPcmCache::kChunkFrames,
                CurrentSongPcmCache::kChunkFrames),
        "published Original startup chunk was not readable");
    requirePcm(
        std::vector<float>(startup.begin(), startup.end()),
        0U,
        0U,
        CurrentSongPcmCache::kChunkFrames);
    require(!cache.copyAvailableLane(0U, 2'048U, block.data(), 256U, 256U),
        "partial startup chunk became readable");
    require(!cache.copyLane(0U, 1'900U, block.data(), 256U, 256U),
        "partial Original became readable");
    require(cache.sealLane(key, 0U, 3'000U) == CurrentSongCacheSealStatus::Accepted,
        "seal Original");
    require(cache.complete(CurrentSongPcmCache::kOriginalLaneMask), "Original readiness");
    require(cache.copyLane(0U, 1'900U, block.data(), 256U, 256U),
        "cross-chunk Original read");
    requirePcm(std::vector<float>(block.begin(), block.end()), 0U, 1'900U, 256U);
    require(!cache.complete(CurrentSongPcmCache::kAllLaneMask),
        "Stems became ready with no stems");
    const CurrentSongCacheKey earlySeekKey{11U, 22U, 31U, 41U, 51U};
    require(!cache.rebindCompleteOwnership(
                key,
                earlySeekKey,
                CurrentSongPcmCache::kAllLaneMask),
        "incomplete cache rebound across an early seek");
    require(cache.snapshot().key == key, "failed early-seek rebind changed cache owner");
    require(cache.rebindCompleteOwnership(
                key,
                earlySeekKey,
                CurrentSongPcmCache::kOriginalLaneMask),
        "complete Original cache did not rebind across an Original-only seek");
    require(cache.rebindCompleteOwnership(
                earlySeekKey,
                key,
                CurrentSongPcmCache::kOriginalLaneMask),
        "Original-only seek cache did not rebind back to its test owner");

    for (std::size_t lane = 1U; lane < kCurrentSongCacheLaneCount; ++lane) {
        const std::size_t offeredFrames = lane == 1U ? 2'500U : 3'250U;
        const auto lanePcm = pcm(lane, 0U, offeredFrames);
        require(cache.offer(key, lane, 100U, lanePcm.data(), 100U).status ==
                CurrentSongCacheOfferStatus::GapRejected,
            "nonzero stem start must not skip its cache prefix");
        require(cache.sealLane(key, lane, offeredFrames) ==
                CurrentSongCacheSealStatus::ConflictingEnd,
            "missing decoded stem PCM must not be sealed as silence");
        const auto offered = cache.offer(key, lane, 0U, lanePcm.data(), offeredFrames);
        require(offered.status == CurrentSongCacheOfferStatus::Accepted, "stem offer");
        require(offered.framesStored == std::min<std::size_t>(offeredFrames, 3'000U),
            "stem truncate");
        require(cache.sealLane(key, lane, offeredFrames) == CurrentSongCacheSealStatus::Accepted,
            "stem seal/pad");
    }
    require(cache.complete(CurrentSongPcmCache::kAllLaneMask), "all-lane readiness");
    const auto beforeSeek = cache.snapshot();
    const CurrentSongCacheKey sealedSeekKey{11U, 23U, 31U, 41U, 51U};
    require(cache.rebindCompleteOwnership(
                key,
                sealedSeekKey,
                CurrentSongPcmCache::kAllLaneMask),
        "sealed same-content cache did not rebind across seek");
    const auto afterSeek = cache.snapshot();
    require(afterSeek.key == sealedSeekKey, "sealed seek did not publish exact new owner");
    std::vector<float> reboundOriginal(3'000U * 2U);
    require(cache.copyLane(0U, 0U, reboundOriginal.data(), 3'000U, 3'000U) &&
            reboundOriginal == pcm(0U, 0U, 3'000U), "sealed seek changed Original bytes");
    require(afterSeek.storedBytes == beforeSeek.storedBytes,
        "sealed seek copied or changed PCM storage");

    std::array<float, 600U * 2U> vocalTail{};
    require(cache.copyLane(1U, 2'400U, vocalTail.data(), 600U, 600U), "padded vocal tail");
    const auto expectedVocal = pcm(1U, 2'400U, 100U);
    require(std::equal(expectedVocal.begin(), expectedVocal.end(), vocalTail.begin()),
        "short stem PCM changed");
    require(std::all_of(
                vocalTail.begin() + static_cast<std::ptrdiff_t>(expectedVocal.size()),
                vocalTail.end(),
                [](float value) { return value == 0.0F; }),
        "short stem was not zero-padded");

    gAllocationCount.store(0U, std::memory_order_relaxed);
    gTrackAllocations.store(true, std::memory_order_relaxed);
    for (std::size_t iteration = 0U; iteration < 10'000U; ++iteration) {
        require(cache.copyLane(iteration % kCurrentSongCacheLaneCount, 1'900U,
                    block.data(), 256U, 256U),
            "steady cache read");
    }
    gTrackAllocations.store(false, std::memory_order_relaxed);
    require(gAllocationCount.load(std::memory_order_relaxed) == 0U,
        "render reads allocated");

    CurrentSongCacheKey compactKey{12U, 22U, 32U, 42U, 52U};
    CurrentSongPcmCache compact;
    require(compact.begin(compactKey) == CurrentSongCacheBeginStatus::Accepted,
        "compact begin");
    constexpr std::size_t kCompactFrames = CurrentSongPcmCache::kChunkFrames * 2U;
    auto exact = exactSigned16Pcm(kCompactFrames);
    exact[CurrentSongPcmCache::kChunkFrames * 2U] = -0.0F;
    exact[CurrentSongPcmCache::kChunkFrames * 2U + 1U] =
        std::nextafter(0.25F, 1.0F);
    const auto compactOffer = compact.offer(
        compactKey,
        0U,
        0U,
        exact.data(),
        kCompactFrames);
    require(compactOffer.status == CurrentSongCacheOfferStatus::Accepted,
        "compact offer");
    require(compact.sealLane(compactKey, 0U, kCompactFrames) ==
            CurrentSongCacheSealStatus::Accepted,
        "compact seal");
    const CurrentSongCacheSnapshot compactSnapshot = compact.snapshot();
    require(compactSnapshot.storedBytes == exact.size() * sizeof(float),
        "file-backed storage byte accounting");
    std::vector<float> exactCopy(exact.size());
    require(compact.copyLane(
                0U,
                0U,
                exactCopy.data(),
                kCompactFrames,
                kCompactFrames),
        "file-backed cache read");
    require(std::memcmp(
                exact.data(),
                exactCopy.data(),
                exact.size() * sizeof(float)) == 0,
        "file-backed cache did not reproduce float bits exactly");
    gAllocationCount.store(0U, std::memory_order_relaxed);
    gTrackAllocations.store(true, std::memory_order_relaxed);
    for (std::size_t iteration = 0U; iteration < 10'000U; ++iteration) {
        require(compact.copyLane(
                    0U,
                    iteration % CurrentSongPcmCache::kChunkFrames,
                    block.data(),
                    256U,
                    256U),
            "steady file-backed cache read");
    }
    gTrackAllocations.store(false, std::memory_order_relaxed);
    require(gAllocationCount.load(std::memory_order_relaxed) == 0U,
        "file-backed render reads allocated");

    CurrentSongCacheKey allocationKey{13U, 23U, 33U, 43U, 53U};
    CurrentSongPcmCache allocationFailure;
    require(allocationFailure.begin(allocationKey) == CurrentSongCacheBeginStatus::Accepted,
        "allocation failure begin");
    const auto oneChunk = exactSigned16Pcm(CurrentSongPcmCache::kChunkFrames);
    gFailNextAllocation.store(true, std::memory_order_relaxed);
    require(allocationFailure.offer(
                allocationKey,
                0U,
                0U,
                oneChunk.data(),
                CurrentSongPcmCache::kChunkFrames).status ==
            CurrentSongCacheOfferStatus::AllocationFailed,
        "allocation failure escaped the cache");
    require(allocationFailure.snapshot().allocationFailed,
        "allocation failure did not seal the cache builder");
    require(!allocationFailure.snapshot().cancelled,
        "allocation failure destroyed the cache read authority");
    require(allocationFailure.offer(
                allocationKey,
                0U,
                0U,
                oneChunk.data(),
                CurrentSongPcmCache::kChunkFrames).status ==
            CurrentSongCacheOfferStatus::AllocationFailed,
        "allocation-failed cache accepted more PCM");

#if !defined(_WIN32)
    // POSIX temporary-file setup also allocates its pathname and mutable mkstemp buffer.
    // Failing only make_unique (the first allocation) misses noexcept constructor crashes.
    for (int successfulAllocations = 1; successfulAllocations <= 3; ++successfulAllocations) {
        CurrentSongPcmCache pathFailure;
        require(pathFailure.begin(allocationKey) == CurrentSongCacheBeginStatus::Accepted,
            "pathname allocation failure begin");
        gFailAfterAllocations.store(successfulAllocations, std::memory_order_relaxed);
        const auto result = pathFailure.offer(allocationKey, 0U, 0U, oneChunk.data(),
            CurrentSongPcmCache::kChunkFrames);
        gFailAfterAllocations.store(-1, std::memory_order_relaxed);
        require(result.status == CurrentSongCacheOfferStatus::AllocationFailed,
            "temporary-file pathname allocation escaped recovery");
        require(pathFailure.snapshot().allocationFailed && !pathFailure.snapshot().cancelled,
            "pathname failure lost the cache recovery authority");
    }
#endif

    // A short decoded stem yields between padding chunks and retains exact zero-padded bytes.
    CurrentSongPcmCache incremental;
    constexpr std::size_t paddedFrames = 3U * CurrentSongPcmCache::kChunkFrames + 73U;
    constexpr std::size_t shortFrames = 31U;
    auto padded = pcm(1U, 0U, shortFrames);
    padded.resize(paddedFrames * 2U, 0.0F);
    require(incremental.begin(key) == CurrentSongCacheBeginStatus::Accepted, "padding begin");
    require(incremental.offer(key, 0U, 0U, padded.data(), paddedFrames).status ==
            CurrentSongCacheOfferStatus::Accepted &&
            incremental.sealLane(key, 0U, paddedFrames) == CurrentSongCacheSealStatus::Accepted,
        "padding Original seal");
    require(incremental.offer(key, 1U, 0U, padded.data(), shortFrames).status ==
            CurrentSongCacheOfferStatus::Accepted &&
            incremental.offer(key, 2U, 0U, padded.data(), paddedFrames).status ==
            CurrentSongCacheOfferStatus::Accepted &&
            incremental.sealLane(key, 2U, paddedFrames) == CurrentSongCacheSealStatus::Accepted,
        "padding reference lanes");
    std::size_t paddingCalls = 0U;
    while (!incremental.complete(1U << 1U)) {
        const auto before = incremental.snapshot();
        const auto status = incremental.sealLane(key, 1U, shortFrames);
        const auto after = incremental.snapshot();
        ++paddingCalls;
        require(after.nextExpectedFrames[1U] > before.nextExpectedFrames[1U] &&
                after.nextExpectedFrames[1U] - before.nextExpectedFrames[1U] <=
                    CurrentSongPcmCache::kChunkFrames,
            "one seal service padded more than one chunk or made no progress");
        if (after.nextExpectedFrames[1U] < paddedFrames) {
            require(status == CurrentSongCacheSealStatus::PaddingPending &&
                    !incremental.complete(1U << 1U),
                "partial padding published a sealed lane");
            require(incremental.sealLane(key, 1U, shortFrames + 1U) ==
                    CurrentSongCacheSealStatus::ConflictingEnd &&
                    incremental.snapshot().nextExpectedFrames == after.nextExpectedFrames,
                "padding retry changed its exact decoded end");
        } else {
            require(status == CurrentSongCacheSealStatus::Accepted, "final padding seal");
        }
        require(paddingCalls <= 4U, "padding failed to terminate");
    }
    require(paddingCalls == 4U, "incremental padding service count changed");
    std::vector<float> paddedRead(padded.size());
    require(incremental.copyLane(1U, 0U, paddedRead.data(), paddedFrames, paddedFrames) &&
            std::memcmp(paddedRead.data(), padded.data(), padded.size() * sizeof(float)) == 0,
        "incremental padding PCM changed");

    CurrentSongPcmCache stale;
    require(stale.begin(key) == CurrentSongCacheBeginStatus::Accepted, "stale begin");
    auto newerKey = key;
    newerKey.generation += 1U;
    const auto tiny = pcm(0U, 0U, 16U);
    require(stale.offer(newerKey, 0U, 0U, tiny.data(), 16U).status ==
            CurrentSongCacheOfferStatus::StaleKey,
        "stale generation wrote PCM");
    require(stale.cancel(key), "cancel current generation");
    require(stale.offer(key, 0U, 0U, tiny.data(), 16U).status ==
            CurrentSongCacheOfferStatus::Cancelled,
        "cancelled generation accepted PCM");

    // Two render consumers may read the same Original while a clean fallback is staged.
    // A cached hit's speculative next window must not replace the other's missing window.
    for (const std::uint64_t activeFirst : {0U, 4'096U}) {
        CurrentSongPcmCache growingWindows;
        const auto growingPcm = pcm(0U, 0U, 16'384U);
        const auto initialFrames = static_cast<std::size_t>(activeFirst + 2'048U);
        require(growingWindows.begin(key) == CurrentSongCacheBeginStatus::Accepted &&
                growingWindows.offer(key, 0U, 0U, growingPcm.data(), initialFrames).status ==
                    CurrentSongCacheOfferStatus::Accepted,
            "growing read windows publish a short final window first");
        growingWindows.enableAsyncReads([](void *) noexcept {}, nullptr);
        std::array<float, 64U * 2U> growingOutput{};
        require(!growingWindows.copyLane(0U, 0U, growingOutput.data(), 64U, 64U),
            "growing read windows request initial publication");
        growingWindows.serviceAsyncReads();
        if (activeFirst != 0U) {
            require(growingWindows.copyLane(0U, 0U, growingOutput.data(), 64U, 64U),
                "growing read windows warm the next window in the opposite slot");
            growingWindows.serviceAsyncReads();
        }
        require(growingWindows.offer(key, 0U, initialFrames,
                    growingPcm.data() + initialFrames * 2U, 16'384U - initialFrames).status ==
                    CurrentSongCacheOfferStatus::Accepted &&
                growingWindows.sealLane(key, 0U, 16'384U) == CurrentSongCacheSealStatus::Accepted &&
                growingWindows.copyLane(0U, activeFirst, growingOutput.data(), 64U, 64U),
            "growing read windows retain the initial reader while the source publishes");
        growingWindows.serviceAsyncReads(); // The active reader's next canonical window.
        require(!growingWindows.copyLane(0U, activeFirst + 2'048U, growingOutput.data(), 64U, 64U),
            "continuation requests the second half beyond the initial short window");
        growingWindows.serviceAsyncReads();
        require(growingWindows.copyLane(0U, activeFirst, growingOutput.data(), 64U, 64U),
            "active reader remains available beside the continuation");
        growingWindows.serviceAsyncReads();
        require(growingWindows.copyLane(0U, activeFirst + 2'048U, growingOutput.data(), 64U, 64U) &&
                std::memcmp(growingOutput.data(), growingPcm.data() + (activeFirst + 2'048U) * 2U,
                    growingOutput.size() * sizeof(float)) == 0,
            "active read-ahead preserves the exact overlapping continuation window");
    }

    CurrentSongPcmCache asynchronous;
    const auto asynchronousPcm = pcm(0U, 0U, 16'384U);
    require(asynchronous.begin(key) == CurrentSongCacheBeginStatus::Accepted &&
        asynchronous.offer(key, 0U, 0U, asynchronousPcm.data(), 16'384U).status ==
            CurrentSongCacheOfferStatus::Accepted &&
        asynchronous.sealLane(key, 0U, 16'384U) == CurrentSongCacheSealStatus::Accepted,
        "asynchronous shared Original fixture sealed");
    asynchronous.enableAsyncReads([](void *) noexcept {}, nullptr);
    std::array<float, 64U * 2U> asynchronousOutput{};
    require(!asynchronous.copyLane(0U, 0U, asynchronousOutput.data(), 64U, 64U),
        "cold asynchronous Original requests its first window");
    asynchronous.serviceAsyncReads();
    require(asynchronous.copyLane(0U, 0U, asynchronousOutput.data(), 64U, 64U),
        "first asynchronous Original window becomes readable");
    asynchronous.serviceAsyncReads();
    require(asynchronous.copyLane(0U, 4'096U, asynchronousOutput.data(), 64U, 64U),
        "second asynchronous Original window becomes readable");
    asynchronous.serviceAsyncReads();
    require(!asynchronous.copyLane(0U, 0U, asynchronousOutput.data(), 64U, 64U),
        "fallback consumer requests its evicted Original window");
    require(asynchronous.copyLane(0U, 4'096U, asynchronousOutput.data(), 64U, 64U),
        "audible consumer still reads its resident Original window");
    asynchronous.serviceAsyncReads();
    require(asynchronous.copyLane(0U, 0U, asynchronousOutput.data(), 64U, 64U),
        "speculative read ahead cannot erase the fallback consumer's pending demand");
    require(std::memcmp(asynchronousOutput.data(), asynchronousPcm.data(),
            asynchronousOutput.size() * sizeof(float)) == 0,
        "asynchronous demand returns exact published Original bytes");

    CurrentSongPcmCache rebound;
    require(rebound.begin(key) == CurrentSongCacheBeginStatus::Accepted &&
        rebound.offer(key, 0U, 0U, tiny.data(), 16U).status == CurrentSongCacheOfferStatus::Accepted &&
        rebound.sealLane(key, 0U, 16U) == CurrentSongCacheSealStatus::Accepted, "rebind snapshot fixture sealed");
    auto secondKey = key;
    secondKey.selectionToken += 700U;
    secondKey.generation += 900U;
    std::atomic<bool> finished{false}, stop{false}, writerFailed{false};
    std::thread rebinder([&] {
        auto current = key;
        for (unsigned iteration = 0U; iteration < 20'000U && !stop.load(); ++iteration) {
            const auto next = current == key ? secondKey : key;
            if (!rebound.rebindCompleteOwnership(current, next, 1U)) { writerFailed.store(true); break; }
            current = next;
        }
        finished.store(true, std::memory_order_release);
    });
    bool coherent = true;
    const auto snapshotDeadline = std::chrono::steady_clock::now() + std::chrono::seconds(2);
    while (!finished.load(std::memory_order_acquire) && std::chrono::steady_clock::now() < snapshotDeadline) {
        const auto observed = rebound.snapshot().key;
        coherent = coherent && (!observed.valid() || observed == key || observed == secondKey);
    }
    stop.store(true);
    rebinder.join();
    require(finished.load() && !writerFailed.load() && coherent,
        "const render snapshot invented a torn ownership tuple during I/O-owner rebind");

    std::cout << "CurrentSongPcmCache tests passed: immutable Original-first publication, "
                 "all-six-stem qualification, exact truncation/padding, stale cancellation, "
                 "bit-exact file-backed storage, byte accounting, frame identity, and "
                 "zero-allocation reads with retained-prefix allocation failure.\n";
    return 0;
}
