#include "NativePlaybackResources.h"
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <limits>
#include <mutex>
#include <thread>
#if defined(STEMWISE_RESOURCE_TEST_HOOKS)
#include "CurrentSongPcmCacheTestHooks.h"
#endif

namespace stemwise::audio::engine {
namespace {
std::uint64_t micros() noexcept {
    return static_cast<std::uint64_t>(std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count());
}
void maximum(std::atomic<std::uint64_t> &value, std::uint64_t candidate) noexcept {
    auto prior = value.load(std::memory_order_relaxed);
    while (prior < candidate && !value.compare_exchange_weak(prior, candidate, std::memory_order_relaxed)) {}
}
enum class Operation { Create, Admit, Offer, Seal, Rebind, AdoptStems, RetireStems, Inherit };
enum class State { Idle, Pending, Running, Complete, RetireResult };
struct Request final {
    Operation operation{};
    CurrentSongCacheKey key{}, next{};
    std::size_t lane{}, count{};
    std::uint64_t frame{}, revision{};
    std::uint32_t mask{};
    bool streamingOriginal{};
    bool operator==(const Request &) const = default;
};
struct ResourceOperationMetrics final {
    std::atomic<std::uint64_t> operations{}, lastDuration{}, maximumDuration{}, maximumQueueAge{};
    void record(std::uint64_t queuedAt, std::uint64_t started) noexcept {
        if (queuedAt != 0U) maximum(maximumQueueAge, started - queuedAt);
        const auto duration = micros() - started;
        lastDuration.store(duration, std::memory_order_relaxed);
        maximum(maximumDuration, duration);
        operations.fetch_add(1U, std::memory_order_relaxed);
    }
};

enum class PreparationKind { Pipeline, Lanes };
struct PreparationRequest final {
    PreparationKind kind{};
    std::size_t target{}, count{};
    std::uint64_t revision{};
    bool operator==(const PreparationRequest &) const = default;
};

/** Single-flight DSP construction/destruction never occupies the canonical I/O owner. */
class DspPreparationOwner final {
public:
    DspPreparationOwner(SongRenderPipelineConfiguration configured, NativePlaybackResources::Wake notify,
        void *context, ResourceOperationMetrics &timing)
        : configuration(configured), wake(notify), context(context), metrics(timing), worker([this] { run(); }) {}
    ~DspPreparationOwner() { stop(); }
    void signal() noexcept { epoch.fetch_add(1U, std::memory_order_release); epoch.notify_one(); }
    void requestStop() noexcept { stopping.store(true, std::memory_order_release); signal(); }
    void stop() noexcept { requestStop(); if (worker.joinable()) worker.join(); }
    void retire() noexcept {
        retiring = true;
        retirement.store(true, std::memory_order_release);
        signal();
    }
    bool retirementPending() noexcept {
        if (!retiring) return false;
        if (retirement.load(std::memory_order_acquire) || state.load(std::memory_order_acquire) != State::Idle) return true;
        retiring = false;
        return false;
    }
    bool ready(const PreparationRequest &next) noexcept {
        if (retirementPending()) return false;
        const auto current = state.load(std::memory_order_acquire);
        if (current == State::Complete) {
            if (superseded) { consumed(); return false; }
            return request == next;
        }
        if (current != State::Idle) return false;
        request = next;
        superseded = false;
        queuedAt = micros();
        state.store(State::Pending, std::memory_order_release);
        signal();
        return false;
    }
    void consumed() noexcept { state.store(State::RetireResult, std::memory_order_release); signal(); }
    void discardSuperseded(std::size_t target, std::uint64_t revision) noexcept {
        if (retirementPending()) return;
        const auto current = state.load(std::memory_order_acquire);
        if (current == State::Idle || current == State::RetireResult ||
            request.target != target || request.revision == revision) return;
        superseded = true;
        if (current == State::Complete) consumed();
    }
    [[nodiscard]] bool idle() const noexcept {
        return state.load(std::memory_order_acquire) == State::Idle && !retirement.load(std::memory_order_acquire);
    }

    std::atomic<State> state{State::Idle};
    PreparationRequest request{}, failedRequest{};
    bool success{}, superseded{}, hasFailed{};
    std::unique_ptr<SongRenderPreparedLanes> lanes;
    std::unique_ptr<SongRenderPipeline> pipeline;
private:
    void run() noexcept {
        for (;;) {
            const auto observed = epoch.load(std::memory_order_acquire);
            if (stopping.load(std::memory_order_acquire)) break;
            const auto current = state.load(std::memory_order_acquire);
            const bool retireRequested = retirement.load(std::memory_order_acquire);
            if (retireRequested || current == State::RetireResult) {
                lanes.reset();
                pipeline.reset();
                if (retireRequested) retirement.store(false, std::memory_order_release);
                state.store(State::Idle, std::memory_order_release);
                wake(context);
                continue;
            }
            if (current == State::Pending) {
                state.store(State::Running, std::memory_order_release);
                const auto started = micros();
                success = false;
                try {
#if defined(STEMWISE_RESOURCE_TEST_HOOKS)
                    cache_test::beforePrepareLanes();
#endif
                    if (!retirement.load(std::memory_order_acquire) && !stopping.load(std::memory_order_acquire)) {
                        if (request.kind == PreparationKind::Pipeline) {
                            auto configured = configuration;
                            configured.stemLaneCount = static_cast<std::uint32_t>(request.count);
                            pipeline = std::make_unique<SongRenderPipeline>(configured);
                        } else {
                            lanes = SongRenderPipeline::prepareStemLaneCount(configuration, request.count);
                        }
                        success = true;
                    }
                } catch (...) { success = false; }
                metrics.record(queuedAt, started);
                if (retirement.load(std::memory_order_acquire) || stopping.load(std::memory_order_acquire)) continue;
                state.store(State::Complete, std::memory_order_release);
                wake(context);
            }
            epoch.wait(observed, std::memory_order_acquire);
        }
        lanes.reset();
        pipeline.reset();
    }
    const SongRenderPipelineConfiguration configuration;
    NativePlaybackResources::Wake wake;
    void *context;
    ResourceOperationMetrics &metrics;
    bool retiring{};
    std::uint64_t queuedAt{};
    std::atomic<bool> retirement{}, stopping{};
    std::atomic<std::uint64_t> epoch{};
    std::thread worker;
};

struct SourceWindowRead final {
    CurrentSongCacheKey key{};
    std::uint64_t ticket{}, first{};
    std::size_t lane{}, frames{}, copied{};
    std::vector<float> samples;
    std::atomic<bool> complete{false};
    bool success{true}, stale{};
};
struct AuditRead final {
    CurrentSongCacheKey key;
    std::size_t lane{}, frames{};
    std::uint64_t first{};
    std::array<float, 4'096U * 2U> samples{};
    std::mutex mutex;
    std::condition_variable condition;
    bool complete{}, success{};
};
}

class NativePlaybackResources::Impl final {
public:
    Impl(SongRenderPipelineConfiguration configured, std::string directory, Wake notify, void *owner)
        : configuration(configured), cacheDirectory(std::move(directory)), wake(notify), context(owner),
          preparation(configured, notify, owner, metrics), worker([this] { run(); }) {}
    ~Impl() {
        stop();
    }
    void signal() noexcept {
        workEpoch.fetch_add(1U, std::memory_order_release);
        workEpoch.notify_one();
    }
    void stop() noexcept {
        stopPcmCacheProgressWaiters();
        preparation.requestStop();
        stopping.store(true, std::memory_order_release);
        signal();
        if (worker.joinable()) worker.join();
        preparation.stop();
    }

    static void requestRead(void *owner) noexcept {
        auto &self = *static_cast<Impl *>(owner);
        std::uint64_t noRequest = 0U;
        self.readQueuedAt.compare_exchange_strong(noRequest, micros(), std::memory_order_relaxed);
        self.readsPending.store(true, std::memory_order_release);
        self.signal();
    }

    bool retirementPending() noexcept {
        if (!retiring) return false;
        if (retirement.load(std::memory_order_acquire) ||
            state.load(std::memory_order_acquire) != State::Idle) return true;
        retiring = false;
        return false;
    }
    bool ready(const Request &next, const float *source = nullptr) noexcept {
        if (retirementPending()) return false;
        const auto current = state.load(std::memory_order_acquire);
        if (current == State::Complete) {
            // Cache prefixes are immutable and offer/seal are idempotent. A decoder epoch may
            // retire before polling its result; admit that durable prefix rather than pinning
            // the sole work slot to a caller which no longer exists. A retry reads the exact
            // receipt below (or is deduplicated by the cache's contiguous-prefix contract).
            if ((request.operation == Operation::Offer || request.operation == Operation::Seal) && request != next) {
                consumed();
                return false;
            }
            return request == next;
        }
        if (current != State::Idle) return false;
        request = next;
        if (source) std::copy_n(source, next.count * 2U, samples.data());
        queuedAt = micros();
        state.store(State::Pending, std::memory_order_release);
        signal();
        return false;
    }
    void consumed() noexcept {
        rebindOrigin = {};
        // Retiring parts shrinks the store reservation; a later identical package must readmit it.
        if (request.operation == Operation::RetireStems) hasAdmittedRequest = false;
        if (request.operation == Operation::Create || request.operation == Operation::Rebind ||
            request.operation == Operation::AdoptStems || request.operation == Operation::RetireStems) {
            hasLastOffer = false;
            hasLastSeal = false;
        }
        if (request.operation == Operation::Offer) {
            // Original publication can advance both a waiting and a partially accepted stem offer.
            hasLastOffer = offerResult.status != CurrentSongCacheOfferStatus::Pending &&
                offerResult.status != CurrentSongCacheOfferStatus::OriginalRangePending &&
                (offerResult.status != CurrentSongCacheOfferStatus::Accepted ||
                    offerResult.inputFramesConsumed == request.count);
            if (hasLastOffer) {
                lastOfferRequest = request;
                lastOfferResult = offerResult;
            }
        }
        if (request.operation == Operation::Seal && sealResult != CurrentSongCacheSealStatus::Pending) {
            lastSealRequest = request;
            lastSealResult = sealResult;
            hasLastSeal = true;
        }
        lastCache = resultCache;
        state.store(State::Idle, std::memory_order_release);
        signal();
    }
    void publishCache() noexcept {
        auto next = cache ? cache->snapshot() : CurrentSongCacheSnapshot{};
        if (!cache) next.storageStatus = storage;
        bool changed{};
        {
            const std::lock_guard lock(snapshotMutex);
            changed = publishedCache.key != next.key || publishedCache.publishedFrames != next.publishedFrames ||
                publishedCache.completeLaneMask != next.completeLaneMask || publishedCache.cancelled != next.cancelled ||
                publishedCache.readFailureLaneMask != next.readFailureLaneMask || publishedCache.allocationFailed != next.allocationFailed ||
                publishedCache.storageStatus != next.storageStatus ||
                publishedCache.io.admittedLaneCount != next.io.admittedLaneCount ||
                publishedCache.io.admittedFrameUpperBound != next.io.admittedFrameUpperBound ||
                publishedCache.io.reservedBytes != next.io.reservedBytes ||
                publishedCache.io.lastAdmissionStatus != next.io.lastAdmissionStatus;
            // Read/byte/latency counters remain observable in diagnostics. They do not change
            // logical coverage or admission and must not schedule full control reconciliation.
            publishedCache = next;
        }
        if (changed) { cacheProgress.fetch_add(1U, std::memory_order_release); cacheProgress.notify_all(); }
    }
    void stopPcmCacheProgressWaiters() noexcept {
        cacheWaitersStopped.store(true, std::memory_order_release);
        cacheProgress.fetch_add(1U, std::memory_order_release);
        cacheProgress.notify_all();
    }
    std::uint64_t waitPcmCacheProgress(std::uint64_t after) noexcept {
        for (;;) {
            const auto value = cacheProgress.load(std::memory_order_acquire);
            if (cacheWaitersStopped.load(std::memory_order_acquire)) return 0U;
            if (value > after) return value;
            cacheProgress.wait(value, std::memory_order_acquire);
        }
    }

    CurrentSongCacheSnapshot snapshot() const noexcept {
        if (state.load(std::memory_order_acquire) != State::Idle) return lastCache;
        std::unique_lock lock(snapshotMutex, std::try_to_lock);
        if (lock.owns_lock()) lastCache = publishedCache;
        return lastCache;
    }
    void run() noexcept {
        for (;;) {
            const auto epoch = workEpoch.load(std::memory_order_acquire);
            const auto current = state.load(std::memory_order_acquire);
            if (stopping.load(std::memory_order_acquire)) break;
            if (retirement.load(std::memory_order_acquire)) {
#if defined(STEMWISE_RESOURCE_TEST_HOOKS)
                cache_test::beforeRetireCache();
#endif
                { std::lock_guard lock(cacheLifetimeMutex); cache.reset(); }
                storage = CurrentSongCacheStorageStatus::NotRequested;
                resultCache = cache ? cache->snapshot() : CurrentSongCacheSnapshot{};
                if (!cache) resultCache.storageStatus = storage;
                publishCache();
                readsPending.store(false, std::memory_order_release);
                state.store(State::Idle, std::memory_order_release);
                retirement.store(false, std::memory_order_release);
                wake(context);
                continue;
            }
            if (current == State::Pending) {
                state.store(State::Running, std::memory_order_release);
                const auto started = micros();
                success = false;
                offerResult = {.status=CurrentSongCacheOfferStatus::NotBegun};
                sealResult = CurrentSongCacheSealStatus::NotBegun;
                try {
                    switch (request.operation) {
                    case Operation::Inherit:
                        {
#if defined(STEMWISE_RESOURCE_TEST_HOOKS)
                            cache_test::beforeInheritCache();
#endif
                            std::lock_guard lock(cacheLifetimeMutex);
                            cache = std::move(inheritedCache);
                            storage = inheritedStorage;
                            if (cache) cache->enableAsyncReads(requestRead, this);
                            success = true;
                        }
                        break;
                    case Operation::Create:
                        {
                            auto created = std::make_unique<CurrentSongPcmCache>(cacheDirectory);
                            storage = created->admitStorage(request.count, request.frame);
                            success = storage == CurrentSongCacheStorageStatus::Admitted &&
                                created->begin(request.key) == CurrentSongCacheBeginStatus::Accepted;
                            if (success) created->enableAsyncReads(requestRead, this);
                            std::lock_guard lock(cacheLifetimeMutex);
                            cache = success ? std::move(created) : nullptr;
                        }
                        break;
                    case Operation::Admit:
                        if (cache) {
                            storage = cache->admitStorage(request.count, request.frame);
                            success = storage == CurrentSongCacheStorageStatus::Admitted;
                        }
                        break;
                    case Operation::Offer:
                        if (cache) offerResult = cache->offer(request.key, request.lane, request.frame, samples.data(), request.count);
                        break;
                    case Operation::Seal:
                        if (cache) sealResult = cache->sealLane(request.key, request.lane, request.frame);
                        break;
                    case Operation::Rebind:
#if defined(STEMWISE_RESOURCE_TEST_HOOKS)
                        if (auto hook = cache_test::beforeCacheRebind.load()) hook();
#endif
                        success = cache && (request.streamingOriginal && request.mask == CurrentSongPcmCache::kOriginalLaneMask
                            ? cache->rebindStreamingOriginalOwnership(request.key, request.next)
                            : cache->rebindCompleteOwnership(request.key, request.next, request.mask));
                        break;
                    case Operation::AdoptStems:
                        success = cache && cache->adoptStemPackageIdentity(request.key, request.next);
                        break;
                    case Operation::RetireStems:
                        success = cache && cache->retireStemPackageIdentity(request.key, request.next);
                        break;
                    }
                } catch (...) {
                    success = false;
                    storage = CurrentSongCacheStorageStatus::IoFailure;
                }
                resultCache = cache ? cache->snapshot() : CurrentSongCacheSnapshot{};
                if (!cache) resultCache.storageStatus = storage;
                publishCache();
                metrics.record(queuedAt, started);
                state.store(State::Complete, std::memory_order_release);
                state.notify_all();
                wake(context);
            }
            // Commands and direct reads share the I/O owner without starving either class.
            // Preserve its exact acknowledgement, then service each I/O class before another command.
            if (readsPending.exchange(false, std::memory_order_acq_rel)) {
                const auto started = micros();
                const auto queued = readQueuedAt.exchange(0U, std::memory_order_relaxed);
                bool progressed = cache && cache->serviceAsyncReads();
                if (auto *windows = loopWindows.load(std::memory_order_acquire))
                    progressed = windows->serviceAsyncReads() || progressed;
                publishCache();
                metrics.record(queued, started);
                // Publication or reader reclamation can unblock render; an unchanged pending read cannot.
                if (progressed) wake(context);
            }
            std::shared_ptr<AuditRead> read;
            { std::lock_guard lock(auditReadMutex); read = std::exchange(auditRead, {}); }
            if (read) {
                const bool readSucceeded = cache && cache->readForAudit(read->key, read->lane, read->first, read->samples.data(), read->frames);
                publishCache();
                { std::lock_guard lock(read->mutex); read->success = readSucceeded; read->complete = true; }
                read->condition.notify_one();
                continue;
            }
            std::shared_ptr<SourceWindowRead> window;
            { std::lock_guard lock(windowReadMutex); window = windowRead; }
            if (window && !window->complete.load(std::memory_order_acquire)) {
                auto &windowReadRequest = *window;
                windowReadRequest.stale = cache && cache->snapshot().key != windowReadRequest.key;
                const auto first = windowReadRequest.first + windowReadRequest.copied;
                const auto available = cache && cache->snapshot().key == windowReadRequest.key ?
                    cache->availableFrames(windowReadRequest.lane) : 0U;
                const auto frames = static_cast<std::size_t>(std::min<std::uint64_t>({4'096U,
                    windowReadRequest.frames - windowReadRequest.copied, available > first ? available - first : 0U}));
                windowReadRequest.success = frames == 0U || cache->readForAudit(windowReadRequest.key, windowReadRequest.lane, first,
                    windowReadRequest.samples.data() + windowReadRequest.copied * 2U, frames);
                if (windowReadRequest.success) windowReadRequest.copied += frames;
                const bool complete = !windowReadRequest.success || frames == 0U || windowReadRequest.copied == windowReadRequest.frames;
                if (complete) publishCache();
                windowReadRequest.complete.store(complete, std::memory_order_release);
                if (!complete) continue;
            }
            workEpoch.wait(epoch, std::memory_order_acquire);
        }
        if (!keepCacheOnStop) { std::lock_guard lock(cacheLifetimeMutex); cache.reset(); }
    }

    const SongRenderPipelineConfiguration configuration;
    std::mutex auditReadMutex;
    std::mutex windowReadMutex;
    std::shared_ptr<SourceWindowRead> windowRead;
    std::uint64_t nextWindowTicket{};
    std::mutex cacheLifetimeMutex;
    std::shared_ptr<AuditRead> auditRead;
    const std::string cacheDirectory;
    Wake wake;
    void *context;
    std::atomic<State> state{State::Idle};
    std::atomic<bool> retirement{false}, readsPending{false}, stopping{false};
    bool retiring{false};
    bool keepCacheOnStop{false};
    Request request{};
    CurrentSongCacheKey rebindOrigin{};
    Request admittedRequest{};
    bool hasAdmittedRequest{};
    NativePlaybackResourceStatus admittedResult{NativePlaybackResourceStatus::Pending};
    Request lastOfferRequest{}, lastSealRequest{};
    CurrentSongCacheOfferResult lastOfferResult{};
    CurrentSongCacheSealStatus lastSealResult{};
    bool hasLastOffer{}, hasLastSeal{};
    std::uint64_t queuedAt{};
    std::array<float, 4'096U * 2U> samples{};
    std::unique_ptr<CurrentSongPcmCache> cache;
    std::unique_ptr<CurrentSongPcmCache> inheritedCache;
    CurrentSongCacheStorageStatus inheritedStorage{};
    bool success{};
    CurrentSongCacheStorageStatus storage{CurrentSongCacheStorageStatus::NotRequested};
    CurrentSongCacheOfferResult offerResult{};
    CurrentSongCacheSealStatus sealResult{CurrentSongCacheSealStatus::NotBegun};
    mutable std::mutex snapshotMutex;
    CurrentSongCacheSnapshot publishedCache{};
    CurrentSongCacheSnapshot resultCache{};
    mutable CurrentSongCacheSnapshot lastCache{};
    ResourceOperationMetrics metrics;
    DspPreparationOwner preparation;
    std::atomic<std::uint64_t> readQueuedAt{};
    std::atomic<LoopHeadCache *> loopWindows{};
    std::atomic<std::uint64_t> cacheProgress{};
    std::atomic<bool> cacheWaitersStopped{false};
    std::atomic<std::uint64_t> workEpoch{};
    std::thread worker;
};

std::uint64_t NativePlaybackResources::waitPcmCacheProgress(std::uint64_t after) noexcept { return mImpl->waitPcmCacheProgress(after); }
void NativePlaybackResources::stopPcmCacheProgressWaiters() noexcept { mImpl->stopPcmCacheProgressWaiters(); }
bool NativePlaybackResources::pcmCacheRangeReady(const CurrentSongCacheKey &key, std::uint32_t mask,
    std::uint64_t first, std::uint64_t end) noexcept {
    if (!key.valid() || mask == 0U || (mask & ~CurrentSongPcmCache::kAllLaneMask) != 0U || end <= first ||
        end - first > 4'096U) return false;
    std::unique_lock lock(mImpl->cacheLifetimeMutex, std::try_to_lock);
    if (!lock.owns_lock() || !mImpl->cache) return false;
    const auto &cache = *mImpl->cache;
    return cache.snapshot().key == key && cache.committedRange(mask, first, static_cast<std::size_t>(end - first)) &&
        cache.snapshot().key == key;
}
std::uint64_t NativePlaybackResources::beginPcmWindowRead(const CurrentSongCacheKey &key, std::size_t lane,
    std::uint64_t first, std::size_t frames) noexcept {
    if (!key.valid() || lane >= kCurrentSongCacheLaneCount || frames == 0U || frames > 480'000U ||
        first > std::numeric_limits<std::uint64_t>::max() - frames) return 0U;
    try {
        auto read = std::make_shared<SourceWindowRead>();
        read->key = key; read->lane = lane; read->first = first; read->frames = frames;
        read->samples.resize(frames * 2U);
        {
            std::lock_guard lock(mImpl->windowReadMutex);
            if (mImpl->stopping.load(std::memory_order_acquire) || mImpl->windowRead ||
                mImpl->nextWindowTicket == std::numeric_limits<std::uint64_t>::max()) return 0U;
            read->ticket = ++mImpl->nextWindowTicket;
            mImpl->windowRead = read;
        }
        mImpl->signal();
        return read->ticket;
    } catch (...) { return 0U; }
}
int NativePlaybackResources::pollPcmWindowRead(std::uint64_t ticket, float *samples, std::size_t frames) noexcept {
    std::lock_guard lock(mImpl->windowReadMutex);
    const auto read = mImpl->windowRead;
    if (!read || read->ticket != ticket || mImpl->stopping.load()) return -3;
    if (read->frames != frames || !samples) return -2;
    if (!read->complete.load(std::memory_order_acquire)) return 0;
    if (read->stale) return -3;
    if (!read->success) return -2;
    if (read->copied == 0U) return -1;
    std::copy_n(read->samples.data(), read->copied * 2U, samples);
    return static_cast<int>(read->copied);
}
void NativePlaybackResources::cancelPcmWindowRead(std::uint64_t ticket) noexcept {
    std::lock_guard lock(mImpl->windowReadMutex);
    if (mImpl->windowRead && mImpl->windowRead->ticket == ticket) mImpl->windowRead.reset();
}

bool NativePlaybackResources::readPcmCacheForAudit(const CurrentSongCacheKey &key, std::size_t lane,
    std::uint64_t first, float *samples, std::size_t frames) noexcept {
    if (!samples || frames == 0U || frames > 4'096U) return false;
    try {
        const auto read = std::make_shared<AuditRead>();
        read->key = key; read->lane = lane; read->first = first; read->frames = frames;
        { std::lock_guard lock(mImpl->auditReadMutex); if (mImpl->auditRead) return false; mImpl->auditRead = read; }
        mImpl->signal();
        std::unique_lock lock(read->mutex);
        if (!read->condition.wait_for(lock, std::chrono::seconds(5), [&] { return read->complete; }) || !read->success) return false;
        std::copy_n(read->samples.data(), frames * 2U, samples);
        return true;
    } catch (...) { return false; }
}

NativePlaybackResources::NativePlaybackResources(SongRenderPipelineConfiguration config, std::string directory, Wake wake, void *owner)
    : mImpl(std::make_unique<Impl>(config, std::move(directory), wake, owner)) {}
NativePlaybackResources::~NativePlaybackResources() = default;
void NativePlaybackResources::attachLoopWindowCache(LoopHeadCache &windows) noexcept {
    windows.enableAsyncReads(Impl::requestRead, mImpl.get());
    mImpl->loopWindows.store(&windows, std::memory_order_release);
}
void NativePlaybackResources::discardSupersededPreparation(std::size_t target, std::uint64_t revision) noexcept {
    if (mImpl->retirementPending()) return;
    mImpl->preparation.discardSuperseded(target, revision);
}
bool NativePlaybackResources::preparePipeline(std::size_t target, std::uint64_t revision, std::size_t count,
    std::unique_ptr<SongRenderPipeline> &pipeline, bool *failed) noexcept {
    if (failed) *failed = false;
    if (count > kStemwiseStemBusCount) {
        if (failed) *failed = true;
        return false;
    }
    if (pipeline) return true;
    if (mImpl->retirementPending()) return false;
    auto &preparation = mImpl->preparation;
    const PreparationRequest request{.kind=PreparationKind::Pipeline, .target=target, .count=count, .revision=revision};
    if (preparation.hasFailed && preparation.failedRequest == request) {
        if (failed) *failed = true;
        return false;
    }
    if (!preparation.ready(request)) return false;
    const bool result = preparation.success && preparation.pipeline;
    if (result) pipeline = std::move(preparation.pipeline);
    else {
        preparation.failedRequest = request;
        preparation.hasFailed = true;
        if (failed) *failed = true;
    }
    preparation.consumed();
    return result;
}

bool NativePlaybackResources::prepareLanes(std::size_t target, std::uint64_t revision, std::size_t count,
    SongRenderPipeline &pipeline, bool *failed) noexcept {
    if (failed) *failed = false;
    if (mImpl->retirementPending()) return false;
    auto &preparation = mImpl->preparation;
    const bool retirementPending = preparation.retirementPending();
    if (!retirementPending && preparation.state.load(std::memory_order_acquire) == State::Complete && preparation.request.kind == PreparationKind::Lanes &&
        preparation.request.target == target && (preparation.request.revision != revision || pipeline.stemLaneCount() == count))
        preparation.consumed();
    if (pipeline.stemLaneCount() == count) return true;
    if (retirementPending) return false;
    const PreparationRequest request{.kind=PreparationKind::Lanes, .target=target, .count=count, .revision=revision};
    if (preparation.hasFailed && preparation.failedRequest == request) {
        if (failed) *failed = true;
        return false;
    }
    if (!preparation.ready(request)) return false;
    const bool result = preparation.success && preparation.lanes && pipeline.adoptPreparedStemLaneCount(*preparation.lanes);
    if (!result) {
        preparation.failedRequest = request;
        preparation.hasFailed = true;
        if (failed) *failed = true;
    }
    preparation.consumed();
    return result;
}
CurrentSongPcmCache *NativePlaybackResources::createCache(const CurrentSongCacheKey &key, std::size_t count, std::uint64_t frames, bool &finished) noexcept {
    finished = false;
    if (!mImpl->ready({.operation=Operation::Create, .key=key, .count=count, .frame=frames})) return nullptr;
    finished = true;
    auto *result = mImpl->success ? mImpl->cache.get() : nullptr;
    mImpl->hasAdmittedRequest = result != nullptr;
    mImpl->admittedRequest = {.operation=Operation::Admit, .count=count, .frame=frames};
    mImpl->consumed();
    return result;
}
void NativePlaybackResources::retireCache() noexcept {
    mImpl->rebindOrigin = {};
    mImpl->preparation.retire();
    mImpl->hasLastOffer = false;
    mImpl->hasLastSeal = false;
    mImpl->hasAdmittedRequest = false;
    mImpl->retiring = true;
    mImpl->retirement.store(true, std::memory_order_release);
    mImpl->signal();
}
NativePlaybackCacheDiscardStatus NativePlaybackResources::discardSupersededCacheResult(
    const CurrentSongCacheKey &expected, std::uint64_t intentRevision) noexcept {
    if (mImpl->retirementPending()) return NativePlaybackCacheDiscardStatus::Pending;
    const auto state = mImpl->state.load(std::memory_order_acquire);
    if (state == State::Idle) return NativePlaybackCacheDiscardStatus::Unchanged;
    const auto &request = mImpl->request;
    if (request.operation == Operation::Create) return NativePlaybackCacheDiscardStatus::Unchanged;
    if (request.operation == Operation::Admit) {
        if (request.key == expected && request.revision == intentRevision)
            return NativePlaybackCacheDiscardStatus::Unchanged;
        if (state != State::Complete) return NativePlaybackCacheDiscardStatus::Pending;
        // The reservation may already have changed. Release this exact obsolete receipt; the
        // coordinator reconciles actual admitted capacity against the latest package below.
        mImpl->hasAdmittedRequest = false;
        mImpl->consumed();
        return NativePlaybackCacheDiscardStatus::Discarded;
    }
    const auto &target = request.operation == Operation::Rebind || request.operation == Operation::AdoptStems ||
        request.operation == Operation::RetireStems ? request.next : request.key;
    if (target == expected) return NativePlaybackCacheDiscardStatus::Unchanged;
    if (state != State::Complete) return NativePlaybackCacheDiscardStatus::Pending;
    mImpl->consumed();
    return NativePlaybackCacheDiscardStatus::Discarded;
}
CurrentSongCacheStorageStatus NativePlaybackResources::storageStatus() const noexcept { return snapshot().storageStatus; }
CurrentSongCacheSnapshot NativePlaybackResources::snapshot() const noexcept { return mImpl->snapshot(); }
NativePlaybackResourceStatus NativePlaybackResources::admitStorage(const CurrentSongCacheKey &owner, std::uint64_t intentRevision,
    std::size_t lanes, std::uint64_t frames) noexcept {
    if (snapshot().allocationFailed) return NativePlaybackResourceStatus::Rejected;
    const Request request{.operation=Operation::Admit, .key=owner, .count=lanes, .frame=frames, .revision=intentRevision};
    if (mImpl->hasAdmittedRequest && mImpl->admittedRequest == request)
        return mImpl->admittedResult;
    if (!mImpl->ready(request)) return NativePlaybackResourceStatus::Pending;
    const auto result = mImpl->success ? NativePlaybackResourceStatus::Succeeded : NativePlaybackResourceStatus::Rejected;
    mImpl->admittedRequest = request;
    mImpl->admittedResult = result;
    mImpl->hasAdmittedRequest = true;
    mImpl->consumed();
    return result;
}
CurrentSongCacheOfferResult NativePlaybackResources::offer(const CurrentSongCacheKey &key, std::size_t lane, std::uint64_t frame,
    const float *source, std::size_t count) noexcept {
    const auto bounded = std::min<std::size_t>(count, 4'096U);
    const Request request{.operation=Operation::Offer, .key=key, .lane=lane, .count=bounded, .frame=frame};
    if (mImpl->hasLastOffer && mImpl->lastOfferRequest == request) return mImpl->lastOfferResult;
    if (!mImpl->ready(request, source))
        return {.status=CurrentSongCacheOfferStatus::Pending};
    const auto result = mImpl->offerResult;
    mImpl->consumed();
    return result;
}
CurrentSongCacheSealStatus NativePlaybackResources::seal(const CurrentSongCacheKey &key, std::size_t lane, std::uint64_t end) noexcept {
    const Request request{.operation=Operation::Seal, .key=key, .lane=lane, .frame=end};
    if (mImpl->hasLastSeal && mImpl->lastSealRequest == request &&
        (mImpl->lastSealResult == CurrentSongCacheSealStatus::Accepted ||
         mImpl->lastSealResult == CurrentSongCacheSealStatus::AlreadyComplete)) return mImpl->lastSealResult;
    if (!mImpl->ready(request)) return CurrentSongCacheSealStatus::Pending;
    const auto result = mImpl->sealResult;
    mImpl->consumed();
    return result;
}
std::uint32_t NativePlaybackResources::pendingRebindLaneMask(const CurrentSongCacheKey &key) const noexcept {
    return mImpl->rebindOrigin == key && mImpl->state.load(std::memory_order_acquire) != State::Idle &&
        mImpl->request.operation == Operation::Rebind ? mImpl->request.mask : 0U;
}
bool NativePlaybackResources::rebind(const CurrentSongCacheKey &key, const CurrentSongCacheKey &next,
    std::uint32_t mask, bool streamingOriginal) noexcept {
    if (mImpl->retirementPending()) return false;
    if (mImpl->state.load(std::memory_order_acquire) == State::Complete &&
        mImpl->request.operation == Operation::Rebind && mImpl->request.next != next &&
        mImpl->rebindOrigin == key) {
        // Finish the accepted transfer before a newer selection continues from its actual key.
        const auto origin = mImpl->rebindOrigin;
        mImpl->consumed();
        mImpl->rebindOrigin = origin;
    }
    const auto actual = mImpl->snapshot().key;
    if (!mImpl->rebindOrigin.valid()) {
        if (actual != key) return false;
        mImpl->rebindOrigin = key;
    } else if (mImpl->rebindOrigin != key) return false;
    if (!mImpl->ready({.operation=Operation::Rebind, .key=actual, .next=next, .mask=mask,
            .streamingOriginal=streamingOriginal})) return false;
    const bool result = mImpl->success; mImpl->consumed(); return result;
}
NativePlaybackResourceStatus NativePlaybackResources::adoptStems(const CurrentSongCacheKey &key, const CurrentSongCacheKey &next) noexcept {
    if (!mImpl->ready({.operation=Operation::AdoptStems, .key=key, .next=next}))
        return NativePlaybackResourceStatus::Pending;
    const bool result = mImpl->success;
    mImpl->consumed();
    return result ? NativePlaybackResourceStatus::Succeeded : NativePlaybackResourceStatus::Rejected;
}
NativePlaybackCacheRetirementStatus NativePlaybackResources::retireStems(
    const CurrentSongCacheKey &key, const CurrentSongCacheKey &next) noexcept {
    if (!mImpl->ready({.operation=Operation::RetireStems, .key=key, .next=next}))
        return NativePlaybackCacheRetirementStatus::Pending;
    const bool result = mImpl->success;
    mImpl->consumed();
    return result ? NativePlaybackCacheRetirementStatus::Retired : NativePlaybackCacheRetirementStatus::Rejected;
}
NativePlaybackResourceDiagnostics NativePlaybackResources::diagnostics() const noexcept {
    CurrentSongCacheSnapshot cache;
    {
        // Control readers must not update the render owner's nonblocking fallback snapshot.
        const std::lock_guard lock(mImpl->snapshotMutex);
        cache = mImpl->publishedCache;
    }
    return {mImpl->metrics.operations.load(std::memory_order_relaxed), mImpl->metrics.lastDuration.load(std::memory_order_relaxed),
        mImpl->metrics.maximumDuration.load(std::memory_order_relaxed), mImpl->metrics.maximumQueueAge.load(std::memory_order_relaxed),
        cache.key, cache.io};
}
bool NativePlaybackResources::inheritCacheFrom(NativePlaybackResources &previous) noexcept {
    if (&previous == this || !mImpl->preparation.idle() || mImpl->state.load(std::memory_order_acquire) != State::Idle ||
        mImpl->cache || mImpl->retirement.load(std::memory_order_acquire)) return false;
    auto &from = *previous.mImpl;
    from.keepCacheOnStop = true;
    from.stop();
    mImpl->inheritedCache = std::move(from.cache);
    mImpl->inheritedStorage = from.storage;
    // Only the destination I/O owner mutates cache, including its first assignment.
    static_cast<void>(mImpl->ready({.operation=Operation::Inherit}));
    for (auto state = mImpl->state.load(std::memory_order_acquire); state != State::Complete;
         state = mImpl->state.load(std::memory_order_acquire)) mImpl->state.wait(state, std::memory_order_acquire);
    const bool inherited = mImpl->success;
    mImpl->consumed();
    from.publishCache();
    return inherited;
}
}
