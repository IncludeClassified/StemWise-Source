#include "LoopHeadCache.h"
#include "CurrentSongPcmCache.h"
#include <mutex>

#include <algorithm>
#include <bit>
#include <cmath>
#include <cstring>
#include <limits>
#include <new>
#include <utility>

namespace stemwise::audio::engine {
namespace {

constexpr std::uint64_t kFnvOffset = 14'695'981'039'346'656'037ULL;
constexpr std::uint64_t kFnvPrime = 1'099'511'628'211ULL;

void saturatingIncrement(std::atomic<std::uint64_t> &value) noexcept {
    std::uint64_t current = value.load(std::memory_order_relaxed);
    while (current != std::numeric_limits<std::uint64_t>::max() &&
           !value.compare_exchange_weak(
               current,
               current + 1U,
               std::memory_order_relaxed,
               std::memory_order_relaxed)) {}
}

void saturatingAdd(std::atomic<std::uint64_t> &value, std::uint64_t amount) noexcept {
    std::uint64_t current = value.load(std::memory_order_relaxed);
    while (true) {
        const std::uint64_t next = amount > std::numeric_limits<std::uint64_t>::max() - current
            ? std::numeric_limits<std::uint64_t>::max()
            : current + amount;
        if (value.compare_exchange_weak(
                current,
                next,
                std::memory_order_relaxed,
                std::memory_order_relaxed)) {
            return;
        }
    }
}

[[nodiscard]] constexpr std::uint32_t laneBit(std::size_t lane) noexcept {
    return 1U << static_cast<std::uint32_t>(lane);
}

[[nodiscard]] std::uint64_t hashWord(std::uint64_t hash, std::uint64_t word) noexcept {
    for (std::size_t byte = 0; byte < sizeof(word); ++byte) {
        hash ^= (word >> (byte * 8U)) & 0xFFU;
        hash *= kFnvPrime;
    }
    return hash;
}

[[nodiscard]] std::uint64_t hashFloat(std::uint64_t hash, float sample) noexcept {
    return hashWord(hash, std::bit_cast<std::uint32_t>(sample));
}

[[nodiscard]] std::uint64_t hashKey(std::uint64_t hash, const LoopHeadContentKey &key) noexcept {
    hash = hashWord(hash, key.songIdentityHash);
    hash = hashWord(hash, key.originalContentHash);
    hash = hashWord(hash, key.stemPackageHash);
    hash = hashWord(hash, key.contentRevision);
    hash = hashWord(hash, key.loopRevision);
    hash = hashWord(hash, key.startTimelineFrame);
    return hashWord(hash, key.endTimelineFrameExclusive);
}

[[nodiscard]] std::uint32_t headFrameCount(const LoopHeadContentKey &key) noexcept {
    const std::uint64_t length = key.endTimelineFrameExclusive - key.startTimelineFrame;
    return static_cast<std::uint32_t>(std::min<std::uint64_t>(
        length,
        LoopHeadCache::kCapacityFrames));
}

[[nodiscard]] std::uint64_t headEndFrame(const LoopHeadCacheSnapshot &snapshot) noexcept {
    return snapshot.key.startTimelineFrame + snapshot.frameCount;
}

}  // namespace

class LoopHeadCache::Impl final {
public:
    struct Slot final {
        LoopHeadCacheSnapshot snapshot{};
        std::array<std::uint32_t, kLoopHeadLaneCount> writtenFrames{};
        std::array<std::uint64_t, kLoopHeadLaneCount> runningChecksums{};
        std::array<std::uint64_t, kLoopHeadLaneCount> paddingDecodedEnds{};
        std::uint32_t paddingLaneMask{0U};
        std::unique_ptr<CurrentSongPcmCache> window;
        CurrentSongCacheKey windowKey{};
        std::atomic<bool> windowPublished{false};
    };

    explicit Impl(std::string directory) : cacheDirectory(std::move(directory)) {}

    [[nodiscard]] float *laneData(std::uint32_t slot, std::size_t lane) noexcept {
        return pcm[slot][lane].get();
    }

    [[nodiscard]] const float *laneData(
        std::uint32_t slot,
        std::size_t lane) const noexcept {
        return pcm[slot][lane].get();
    }

    [[nodiscard]] std::uint32_t selectStagingSlot() const noexcept {
        // This participates in the cross-atomic reader handshake below. Sequential consistency is
        // intentional: acquire/release on activeSlot and readers independently permits the ARM
        // store-buffering outcome where each side misses the other's publication.
        const std::uint32_t active = activeSlot.load(std::memory_order_seq_cst);
        if (active == kNoSlot) return nextEmptySlot;
        return active == 0U ? 1U : 0U;
    }

    void resetSlotForStage(
        std::uint32_t index,
        const LoopHeadContentKey &key,
        std::uint32_t requiredMask,
        LoopHeadBoundaryMode boundaryMode) noexcept {
        Slot &slot = slots[index];
        slot.snapshot = {
            .valid = true,
            .slotIndex = index,
            .key = key,
            .requiredLaneMask = requiredMask,
            .completedLaneMask = 0U,
            .frameCount = boundaryMode == LoopHeadBoundaryMode::RetainedWindow
                ? static_cast<std::uint32_t>(key.endTimelineFrameExclusive - key.startTimelineFrame)
                : headFrameCount(key),
            .cacheOnly = false,
            .boundaryMode = boundaryMode,
            .originalEndResolved = false,
            .resolvedOriginalEndTimelineFrameExclusive = 0U,
            .laneChecksums = {},
            .combinedChecksum = 0U,
        };
        slot.writtenFrames.fill(0U);
        slot.paddingLaneMask = 0U;
        slot.runningChecksums.fill(kFnvOffset);
    }

    void completeLane(Slot &slot, std::size_t lane) noexcept {
        const std::uint32_t bit = laneBit(lane);
        if ((slot.snapshot.completedLaneMask & bit) != 0U) return;
        slot.snapshot.completedLaneMask |= bit;
        slot.snapshot.laneChecksums[lane] = hashWord(
            slot.runningChecksums[lane],
            slot.snapshot.frameCount);
        saturatingIncrement(lanesCompleted);
    }

    [[nodiscard]] LoopHeadCacheSnapshot staging() const noexcept {
        const std::uint32_t index = stagingSlot;
        return index < kSlotCount ? slots[index].snapshot : LoopHeadCacheSnapshot{};
    }

    [[nodiscard]] LoopHeadCacheDiagnostics diagnosticSnapshot() const noexcept {
        return {
            .beginCalls = beginCalls.load(std::memory_order_relaxed),
            .beginAccepted = beginAccepted.load(std::memory_order_relaxed),
            .staleBegins = staleBegins.load(std::memory_order_relaxed),
            .readerDeferrals = readerDeferrals.load(std::memory_order_relaxed),
            .writeCalls = writeCalls.load(std::memory_order_relaxed),
            .writtenFrames = writtenFrames.load(std::memory_order_relaxed),
            .overlappedFramesDiscarded =
                overlappedFramesDiscarded.load(std::memory_order_relaxed),
            .gapRejections = gapRejections.load(std::memory_order_relaxed),
            .nonFiniteRejections = nonFiniteRejections.load(std::memory_order_relaxed),
            .silenceFramesWritten = silenceFramesWritten.load(std::memory_order_relaxed),
            .lanesCompleted = lanesCompleted.load(std::memory_order_relaxed),
            .commits = commits.load(std::memory_order_relaxed),
            .rejectedCommits = rejectedCommits.load(std::memory_order_relaxed),
            .aborts = aborts.load(std::memory_order_relaxed),
            .activeViewAcquisitions =
                activeViewAcquisitions.load(std::memory_order_relaxed),
            .activeViewMisses = activeViewMisses.load(std::memory_order_relaxed),
            .activeReaders = {
                readers[0].load(std::memory_order_seq_cst),
                readers[1].load(std::memory_order_seq_cst),
            },
        };
    }

    std::array<std::array<std::unique_ptr<float[]>, kLoopHeadLaneCount>, kSlotCount> pcm{};
    std::string cacheDirectory;
    AsyncReadWake readWake{};
    void *readContext{};
    mutable std::mutex windowLifetimeMutex;
    std::array<Slot, kSlotCount> slots{};
    mutable std::array<std::atomic<std::uint32_t>, kSlotCount> readers{};
    std::atomic<std::uint32_t> activeSlot{kNoSlot};
    std::uint32_t stagingSlot{kNoSlot};
    std::uint32_t nextEmptySlot{0U};
    std::uint64_t latestRevision{0U};

    std::atomic<std::uint64_t> beginCalls{0};
    std::atomic<std::uint64_t> beginAccepted{0};
    std::atomic<std::uint64_t> staleBegins{0};
    std::atomic<std::uint64_t> readerDeferrals{0};
    std::atomic<std::uint64_t> writeCalls{0};
    std::atomic<std::uint64_t> writtenFrames{0};
    std::atomic<std::uint64_t> overlappedFramesDiscarded{0};
    std::atomic<std::uint64_t> gapRejections{0};
    std::atomic<std::uint64_t> nonFiniteRejections{0};
    std::atomic<std::uint64_t> silenceFramesWritten{0};
    std::atomic<std::uint64_t> lanesCompleted{0};
    std::atomic<std::uint64_t> commits{0};
    std::atomic<std::uint64_t> rejectedCommits{0};
    std::atomic<std::uint64_t> aborts{0};
    mutable std::atomic<std::uint64_t> activeViewAcquisitions{0};
    mutable std::atomic<std::uint64_t> activeViewMisses{0};
};

LoopHeadCache::ReadView::ReadView(
    const LoopHeadCache *owner,
    std::uint32_t slot,
    LoopHeadCacheSnapshot snapshot) noexcept
    : mOwner(owner), mSlot(slot), mSnapshot(std::move(snapshot)) {}

LoopHeadCache::ReadView::~ReadView() {
    release();
}

LoopHeadCache::ReadView::ReadView(ReadView &&other) noexcept
    : mOwner(std::exchange(other.mOwner, nullptr)),
      mSlot(std::exchange(other.mSlot, kNoSlot)),
      mSnapshot(other.mSnapshot) {}

LoopHeadCache::ReadView &LoopHeadCache::ReadView::operator=(ReadView &&other) noexcept {
    if (this == &other) return *this;
    release();
    mOwner = std::exchange(other.mOwner, nullptr);
    mSlot = std::exchange(other.mSlot, kNoSlot);
    mSnapshot = other.mSnapshot;
    return *this;
}

bool LoopHeadCache::ReadView::valid() const noexcept {
    return mOwner != nullptr && mSlot < kSlotCount && mSnapshot.valid;
}

const LoopHeadCacheSnapshot &LoopHeadCache::ReadView::snapshot() const noexcept {
    return mSnapshot;
}

LoopHeadCache::ReadView LoopHeadCache::ReadView::retain() const noexcept {
    if (!valid()) return {};
    // This live lease already excludes slot reuse while the additional reader is acquired.
    mOwner->mImpl->readers[mSlot].fetch_add(1U, std::memory_order_seq_cst);
    return {mOwner, mSlot, mSnapshot};
}

std::uint32_t LoopHeadCache::ReadView::readFailureLaneMask() const noexcept {
    if (!valid()) return 0U;
    const auto &window = mOwner->mImpl->slots[mSlot].window;
    return window ? window->snapshot().readFailureLaneMask : 0U;
}

bool LoopHeadCache::ReadView::copyLane(
    std::size_t laneIndex,
    std::uint64_t firstTimelineFrame,
    float *output,
    std::size_t outputCapacityFrames,
    std::size_t requestedFrames,
    LoopHeadCopyStatus *status) const noexcept {
    if (status) *status = LoopHeadCopyStatus::Unavailable;
    if (!valid() || laneIndex >= kLoopHeadLaneCount || output == nullptr ||
        requestedFrames == 0U || requestedFrames > outputCapacityFrames ||
        (mSnapshot.completedLaneMask & laneBit(laneIndex)) == 0U ||
        firstTimelineFrame < mSnapshot.key.startTimelineFrame) {
        return false;
    }
    if ((readFailureLaneMask() & (laneBit(laneIndex) | 1U)) != 0U) {
        if (status) *status = LoopHeadCopyStatus::ReadFailure;
        return false;
    }
    const std::uint64_t offset =
        firstTimelineFrame - mSnapshot.key.startTimelineFrame;
    if (offset > mSnapshot.frameCount ||
        requestedFrames > static_cast<std::uint64_t>(mSnapshot.frameCount) - offset) {
        return false;
    }
    if (const auto &window = mOwner->mImpl->slots[mSlot].window;
        window && (offset >= kCapacityFrames || requestedFrames > kCapacityFrames - offset)) {
        const bool copied = window->copyAvailableLane(laneIndex, offset, output, outputCapacityFrames, requestedFrames);
        if (status) {
            *status = LoopHeadCopyStatus::Complete;
            if (!copied) {
                const auto state = window->snapshot();
                *status = mOwner->mImpl->readWake && !state.cancelled && !state.allocationFailed &&
                        (state.readFailureLaneMask & (laneBit(laneIndex) | 1U)) == 0U
                    ? LoopHeadCopyStatus::Pending : LoopHeadCopyStatus::ReadFailure;
            }
        }
        return copied;
    }
    const float *source = mOwner->mImpl->laneData(mSlot, laneIndex) +
        static_cast<std::size_t>(offset) * kChannelCount;
    std::memcpy(
        output,
        source,
        requestedFrames * kChannelCount * sizeof(float));
    if (status) *status = LoopHeadCopyStatus::Complete;
    return true;
}

void LoopHeadCache::ReadView::release() noexcept {
    if (mOwner == nullptr || mSlot >= kSlotCount) return;
    mOwner->mImpl->readers[mSlot].fetch_sub(1U, std::memory_order_seq_cst);
    mOwner = nullptr;
    mSlot = kNoSlot;
    mSnapshot = {};
}

LoopHeadCache::LoopHeadCache(std::string directory) : mImpl(std::make_unique<Impl>(std::move(directory))) {}
LoopHeadCache::~LoopHeadCache() = default;

void LoopHeadCache::enableAsyncReads(AsyncReadWake wake, void *context) noexcept {
    mImpl->readWake = wake;
    mImpl->readContext = context;
}

bool LoopHeadCache::serviceAsyncReads() const noexcept {
    const std::lock_guard lifetime(mImpl->windowLifetimeMutex);
    bool progressed = false;
    for (const auto &slot : mImpl->slots) {
        if (slot.windowPublished.load(std::memory_order_acquire) && slot.window)
            progressed = slot.window->serviceAsyncReads() || progressed;
    }
    return progressed;
}

LoopHeadBeginStatus LoopHeadCache::beginStaging(
    const LoopHeadContentKey &key,
    std::uint32_t requiredLaneMask,
    LoopHeadBoundaryMode boundaryMode) noexcept {
    saturatingIncrement(mImpl->beginCalls);
    if (!key.valid()) return LoopHeadBeginStatus::InvalidKey;
    const auto requestedFrames = key.endTimelineFrameExclusive - key.startTimelineFrame;
    if ((boundaryMode != LoopHeadBoundaryMode::OriginalEos && boundaryMode != LoopHeadBoundaryMode::RetainedWindow) ||
        (boundaryMode == LoopHeadBoundaryMode::OriginalEos && requestedFrames != kCapacityFrames) ||
        requestedFrames > std::numeric_limits<std::uint32_t>::max()) {
        return LoopHeadBeginStatus::InvalidKey;
    }
    if (requiredLaneMask == 0U ||
        (requiredLaneMask & kLoopHeadAllLanesMask) != requiredLaneMask ||
        (requiredLaneMask & 1U) == 0U) {
        return LoopHeadBeginStatus::InvalidLaneMask;
    }
    if ((requiredLaneMask & ~1U) != 0U && key.stemPackageHash == 0U) {
        return LoopHeadBeginStatus::InvalidKey;
    }
    if (key.loopRevision <= mImpl->latestRevision) {
        saturatingIncrement(mImpl->staleBegins);
        return LoopHeadBeginStatus::StaleRevision;
    }
    const std::uint32_t target = mImpl->selectStagingSlot();
    if (mImpl->readers[target].load(std::memory_order_seq_cst) != 0U) {
        saturatingIncrement(mImpl->readerDeferrals);
        return LoopHeadBeginStatus::ReadersActive;
    }
    const std::lock_guard lifetime(mImpl->windowLifetimeMutex);
    auto &slot = mImpl->slots[target];
    slot.windowPublished.store(false, std::memory_order_release);
    slot.window.reset();
    try {
        if (boundaryMode == LoopHeadBoundaryMode::RetainedWindow) {
            auto window = std::make_unique<CurrentSongPcmCache>(mImpl->cacheDirectory);
            slot.windowKey = {key.loopRevision, key.loopRevision,
                key.songIdentityHash, key.originalContentHash, key.stemPackageHash};
            const auto capacity = window->admitStorage(std::bit_width(requiredLaneMask), requestedFrames);
            if (capacity == CurrentSongCacheStorageStatus::InsufficientSpace)
                return LoopHeadBeginStatus::InsufficientSpace;
            if (capacity != CurrentSongCacheStorageStatus::Admitted ||
                window->begin(slot.windowKey) != CurrentSongCacheBeginStatus::Accepted) {
                return LoopHeadBeginStatus::AllocationFailed;
            }
            if (mImpl->readWake) window->enableAsyncReads(mImpl->readWake, mImpl->readContext);
            slot.window = std::move(window);
        }
        for (std::size_t lane = 0U; lane < kLoopHeadLaneCount; ++lane) {
            if ((requiredLaneMask & laneBit(lane)) == 0U) continue;
            if (!mImpl->pcm[target][lane]) {
                mImpl->pcm[target][lane] = std::make_unique<float[]>(
                    static_cast<std::size_t>(kCapacityFrames) * kChannelCount);
            }
        }
    } catch (...) { return LoopHeadBeginStatus::AllocationFailed; }
    for (std::size_t lane = 0U; lane < kLoopHeadLaneCount; ++lane) {
        if ((requiredLaneMask & laneBit(lane)) == 0U) mImpl->pcm[target][lane].reset();
    }
    mImpl->latestRevision = key.loopRevision;
    mImpl->stagingSlot = target;
    mImpl->resetSlotForStage(target, key, requiredLaneMask, boundaryMode);
    saturatingIncrement(mImpl->beginAccepted);
    return LoopHeadBeginStatus::Accepted;
}

LoopHeadWriteResult LoopHeadCache::writeStaging(
    std::uint64_t loopRevision,
    std::size_t laneIndex,
    std::uint64_t firstTimelineFrame,
    const float *input,
    std::size_t frameCount) noexcept {
    saturatingIncrement(mImpl->writeCalls);
    const std::uint32_t slotIndex = mImpl->stagingSlot;
    if (slotIndex >= kSlotCount) return {LoopHeadWriteStatus::NoStagingSlot};
    Impl::Slot &slot = mImpl->slots[slotIndex];
    if (loopRevision != slot.snapshot.key.loopRevision) {
        return {LoopHeadWriteStatus::StaleRevision};
    }
    if (laneIndex >= kLoopHeadLaneCount) return {LoopHeadWriteStatus::InvalidLane};
    if ((slot.snapshot.requiredLaneMask & laneBit(laneIndex)) == 0U) {
        return {LoopHeadWriteStatus::LaneNotRequested};
    }
    const std::uint64_t expected = slot.snapshot.key.startTimelineFrame + slot.writtenFrames[laneIndex];
    if ((slot.snapshot.completedLaneMask & laneBit(laneIndex)) != 0U) {
        return {LoopHeadWriteStatus::LaneComplete, 0U, 0U, expected};
    }
    if ((slot.paddingLaneMask & laneBit(laneIndex)) != 0U || input == nullptr || frameCount == 0U ||
        firstTimelineFrame >
            std::numeric_limits<std::uint64_t>::max() - frameCount) {
        return {LoopHeadWriteStatus::InvalidArgument, 0U, 0U, expected};
    }
    const std::uint64_t inputEnd = firstTimelineFrame + frameCount;
    if (firstTimelineFrame > expected) {
        saturatingIncrement(mImpl->gapRejections);
        return {LoopHeadWriteStatus::GapRejected, 0U, 0U, expected};
    }
    const std::size_t overlap = inputEnd <= expected
        ? frameCount
        : static_cast<std::size_t>(expected - firstTimelineFrame);
    if (overlap != 0U) {
        saturatingAdd(mImpl->overlappedFramesDiscarded, overlap);
    }
    if (inputEnd <= expected) {
        return {LoopHeadWriteStatus::Accepted, frameCount, 0U, expected};
    }

    const std::uint64_t end = headEndFrame(slot.snapshot);
    const std::uint64_t available = end - expected;
    const std::size_t writeCount = static_cast<std::size_t>(std::min<std::uint64_t>(
        inputEnd - expected,
        available));
    const float *writeInput = input + overlap * kChannelCount;
    for (std::size_t sample = 0; sample < writeCount * kChannelCount; ++sample) {
        if (!std::isfinite(writeInput[sample])) {
            saturatingIncrement(mImpl->nonFiniteRejections);
            return {LoopHeadWriteStatus::NonFinitePcm, 0U, 0U, expected};
        }
    }

    if (slot.window) {
        const auto stored = slot.window->offer(slot.windowKey, laneIndex,
            slot.writtenFrames[laneIndex], writeInput, writeCount);
        if (stored.status != CurrentSongCacheOfferStatus::Accepted || stored.inputFramesConsumed != writeCount)
            return {LoopHeadWriteStatus::InvalidArgument, 0U, 0U, expected};
    }
    const auto resident = slot.writtenFrames[laneIndex] < kCapacityFrames
        ? std::min<std::size_t>(writeCount, kCapacityFrames - slot.writtenFrames[laneIndex]) : 0U;
    if (resident != 0U) std::memcpy(mImpl->laneData(slotIndex, laneIndex) +
        static_cast<std::size_t>(slot.writtenFrames[laneIndex]) * kChannelCount,
        writeInput, resident * kChannelCount * sizeof(float));
    std::uint64_t checksum = slot.runningChecksums[laneIndex];
    for (std::size_t sample = 0; sample < writeCount * kChannelCount; ++sample) {
        checksum = hashFloat(checksum, writeInput[sample]);
    }
    slot.runningChecksums[laneIndex] = checksum;
    slot.writtenFrames[laneIndex] += static_cast<std::uint32_t>(writeCount);
    saturatingAdd(mImpl->writtenFrames, writeCount);

    const std::size_t consumed = overlap + writeCount;
    const std::uint64_t next = expected + writeCount;
    if (slot.writtenFrames[laneIndex] == slot.snapshot.frameCount) {
        if (slot.window && slot.window->sealLane(slot.windowKey, laneIndex,
                slot.writtenFrames[laneIndex]) != CurrentSongCacheSealStatus::Accepted)
            return {LoopHeadWriteStatus::InvalidArgument, consumed, writeCount, next};
        mImpl->completeLane(slot, laneIndex);
        return {LoopHeadWriteStatus::LaneComplete, consumed, writeCount, next};
    }
    return {LoopHeadWriteStatus::Accepted, consumed, writeCount, next};
}

LoopHeadSealStatus LoopHeadCache::sealStagingLaneWithSilence(
    std::uint64_t loopRevision,
    std::size_t laneIndex,
    std::uint64_t actualEnd) noexcept {
    const std::uint32_t slotIndex = mImpl->stagingSlot;
    if (slotIndex >= kSlotCount) return LoopHeadSealStatus::NoStagingSlot;
    Impl::Slot &slot = mImpl->slots[slotIndex];
    if (loopRevision != slot.snapshot.key.loopRevision) {
        return LoopHeadSealStatus::StaleRevision;
    }
    if (laneIndex >= kLoopHeadLaneCount) return LoopHeadSealStatus::InvalidLane;
    if ((slot.snapshot.requiredLaneMask & laneBit(laneIndex)) == 0U) {
        return LoopHeadSealStatus::LaneNotRequested;
    }
    if ((slot.snapshot.completedLaneMask & laneBit(laneIndex)) != 0U) {
        return LoopHeadSealStatus::AlreadyComplete;
    }
    const std::uint64_t expected = (slot.paddingLaneMask & laneBit(laneIndex)) != 0U
        ? slot.paddingDecodedEnds[laneIndex]
        : slot.snapshot.key.startTimelineFrame + slot.writtenFrames[laneIndex];
    if (actualEnd != expected || actualEnd > headEndFrame(slot.snapshot)) {
        return LoopHeadSealStatus::InvalidBoundary;
    }
    if (slot.window) {
        const auto sealed = slot.window->sealLane(slot.windowKey, laneIndex,
            actualEnd - slot.snapshot.key.startTimelineFrame);
        if (sealed != CurrentSongCacheSealStatus::Accepted && sealed != CurrentSongCacheSealStatus::PaddingPending)
            return LoopHeadSealStatus::InvalidBoundary;
        slot.paddingLaneMask |= laneBit(laneIndex);
        slot.paddingDecodedEnds[laneIndex] = actualEnd;
    }
    if (laneIndex == 0U) {
        if (actualEnd <= slot.snapshot.key.startTimelineFrame) {
            return LoopHeadSealStatus::InvalidBoundary;
        }
        slot.snapshot.frameCount = static_cast<std::uint32_t>(
            actualEnd - slot.snapshot.key.startTimelineFrame);
        slot.snapshot.cacheOnly = true;
        slot.snapshot.originalEndResolved = true;
        slot.snapshot.resolvedOriginalEndTimelineFrameExclusive = actualEnd;
        mImpl->completeLane(slot, laneIndex);
        return LoopHeadSealStatus::Accepted;
    }
    const std::uint32_t remaining = slot.window
        ? std::min(CurrentSongPcmCache::kChunkFrames, slot.snapshot.frameCount - slot.writtenFrames[laneIndex])
        : slot.snapshot.frameCount - slot.writtenFrames[laneIndex];
    const auto resident = slot.writtenFrames[laneIndex] < kCapacityFrames
        ? std::min<std::size_t>(remaining, kCapacityFrames - slot.writtenFrames[laneIndex]) : 0U;
    if (resident != 0U) std::fill_n(mImpl->laneData(slotIndex, laneIndex) +
        static_cast<std::size_t>(slot.writtenFrames[laneIndex]) * kChannelCount,
        resident * kChannelCount, 0.0F);
    std::uint64_t checksum = slot.runningChecksums[laneIndex];
    for (std::size_t sample = 0;
         sample < static_cast<std::size_t>(remaining) * kChannelCount;
         ++sample) {
        checksum = hashFloat(checksum, 0.0F);
    }
    slot.runningChecksums[laneIndex] = checksum;
    slot.writtenFrames[laneIndex] += remaining;
    saturatingAdd(mImpl->writtenFrames, remaining);
    saturatingAdd(mImpl->silenceFramesWritten, remaining);
    if (slot.writtenFrames[laneIndex] != slot.snapshot.frameCount) return LoopHeadSealStatus::PaddingPending;
    mImpl->completeLane(slot, laneIndex);
    return LoopHeadSealStatus::Accepted;
}

LoopHeadCommitStatus LoopHeadCache::commitStaging(std::uint64_t loopRevision) noexcept {
    const std::uint32_t slotIndex = mImpl->stagingSlot;
    if (slotIndex >= kSlotCount) {
        saturatingIncrement(mImpl->rejectedCommits);
        return LoopHeadCommitStatus::NoStagingSlot;
    }
    Impl::Slot &slot = mImpl->slots[slotIndex];
    if (loopRevision != slot.snapshot.key.loopRevision) {
        saturatingIncrement(mImpl->rejectedCommits);
        return LoopHeadCommitStatus::StaleRevision;
    }
    if ((slot.snapshot.completedLaneMask & slot.snapshot.requiredLaneMask) !=
        slot.snapshot.requiredLaneMask) {
        saturatingIncrement(mImpl->rejectedCommits);
        return LoopHeadCommitStatus::MissingRequiredLanes;
    }
    std::uint64_t checksum = hashKey(kFnvOffset, slot.snapshot.key);
    checksum = hashWord(checksum, slot.snapshot.requiredLaneMask);
    checksum = hashWord(checksum, slot.snapshot.completedLaneMask);
    checksum = hashWord(checksum, slot.snapshot.frameCount);
    checksum = hashWord(checksum, static_cast<std::uint64_t>(slot.snapshot.boundaryMode));
    checksum = hashWord(checksum, slot.snapshot.originalEndResolved ? 1U : 0U);
    checksum = hashWord(
        checksum,
        slot.snapshot.resolvedOriginalEndTimelineFrameExclusive);
    for (std::size_t lane = 0; lane < kLoopHeadLaneCount; ++lane) {
        if ((slot.snapshot.requiredLaneMask & laneBit(lane)) == 0U) continue;
        checksum = hashWord(checksum, lane);
        checksum = hashWord(checksum, slot.snapshot.laneChecksums[lane]);
    }
    if (checksum == 0U) checksum = 1U;
    slot.snapshot.combinedChecksum = checksum;
    slot.windowPublished.store(slot.window != nullptr, std::memory_order_release);
    // activeSlot and readers form one two-location exclusion protocol. Keeping the publication and
    // every reader handshake operation in the single sequentially-consistent order guarantees:
    // either beginStaging observes the reader, or that reader observes this publication change and
    // refuses the retired slot before touching its snapshot/PCM. We must not weaken these orders
    // independently on ARM.
    mImpl->activeSlot.store(slotIndex, std::memory_order_seq_cst);
    mImpl->stagingSlot = kNoSlot;
    mImpl->nextEmptySlot = slotIndex == 0U ? 1U : 0U;
    saturatingIncrement(mImpl->commits);
    return LoopHeadCommitStatus::Accepted;
}

LoopHeadAbortStatus LoopHeadCache::abortStaging(std::uint64_t loopRevision) noexcept {
    const std::uint32_t slotIndex = mImpl->stagingSlot;
    if (slotIndex >= kSlotCount) return LoopHeadAbortStatus::NoStagingSlot;
    if (mImpl->slots[slotIndex].snapshot.key.loopRevision != loopRevision) {
        return LoopHeadAbortStatus::StaleRevision;
    }
    mImpl->slots[slotIndex].snapshot.valid = false;
    {
        const std::lock_guard lifetime(mImpl->windowLifetimeMutex);
        mImpl->slots[slotIndex].windowPublished.store(false, std::memory_order_release);
        mImpl->slots[slotIndex].window.reset();
    }
    mImpl->stagingSlot = kNoSlot;
    saturatingIncrement(mImpl->aborts);
    return LoopHeadAbortStatus::Accepted;
}

LoopHeadCache::ReadView LoopHeadCache::acquireActive(
    const LoopHeadContentKey &expectedKey) const noexcept {
    ReadView result = acquireActive();
    if (!result.valid()) return {};
    if (result.snapshot().key != expectedKey) {
        saturatingIncrement(mImpl->activeViewMisses);
        return {};
    }
    return result;
}

LoopHeadCache::ReadView LoopHeadCache::acquireActive() const noexcept {
    for (std::size_t attempt = 0; attempt < 3U; ++attempt) {
        const std::uint32_t slot = mImpl->activeSlot.load(std::memory_order_seq_cst);
        if (slot >= kSlotCount) {
            saturatingIncrement(mImpl->activeViewMisses);
            return {};
        }
        mImpl->readers[slot].fetch_add(1U, std::memory_order_seq_cst);
        if (mImpl->activeSlot.load(std::memory_order_seq_cst) != slot) {
            mImpl->readers[slot].fetch_sub(1U, std::memory_order_seq_cst);
            continue;
        }
        const LoopHeadCacheSnapshot snapshot = mImpl->slots[slot].snapshot;
        if (!snapshot.valid || snapshot.combinedChecksum == 0U) {
            mImpl->readers[slot].fetch_sub(1U, std::memory_order_seq_cst);
            saturatingIncrement(mImpl->activeViewMisses);
            return {};
        }
        saturatingIncrement(mImpl->activeViewAcquisitions);
        return ReadView(this, slot, snapshot);
    }
    saturatingIncrement(mImpl->activeViewMisses);
    return {};
}

LoopHeadCacheSnapshot LoopHeadCache::stagingSnapshot() const noexcept {
    return mImpl->staging();
}

LoopHeadCacheDiagnostics LoopHeadCache::diagnostics() const noexcept {
    return mImpl->diagnosticSnapshot();
}

}  // namespace stemwise::audio::engine
