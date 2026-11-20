#include "LoopHeadPreparationIngress.h"

#include <algorithm>
#include <limits>
#include <mutex>
#include <new>
#include <stdexcept>

namespace stemwise::audio::engine {
namespace {

[[nodiscard]] constexpr std::uint32_t laneBit(std::size_t lane) noexcept {
    return 1U << static_cast<std::uint32_t>(lane);
}

void saturatingAdd(std::uint64_t &value, std::uint64_t amount) noexcept {
    const std::uint64_t maximum = std::numeric_limits<std::uint64_t>::max();
    value = amount > maximum - value ? maximum : value + amount;
}

void saturatingIncrement(std::uint64_t &value) noexcept {
    saturatingAdd(value, 1U);
}

[[nodiscard]] LoopHeadPreparationConfiguration checkedConfiguration(
    LoopHeadPreparationConfiguration value) {
    if (value.queueCapacityFrames < LaneIngress::kMinimumQueueCapacityFrames ||
        value.queueCapacityFrames > LaneIngress::kMaximumQueueCapacityFrames ||
        value.maximumInputQuantumFrames == 0U ||
        value.maximumInputQuantumFrames > value.queueCapacityFrames ||
        value.pullCapacityFrames == 0U ||
        value.pullCapacityFrames > LaneIngress::kMaximumQueueCapacityFrames) {
        throw std::invalid_argument("Loop-head preparation configuration is invalid");
    }
    return value;
}

}  // namespace

class LoopHeadPreparationIngress::Impl final {
public:
    explicit Impl(const LoopHeadPreparationConfiguration &configured)
        : configuration(checkedConfiguration(configured)),
          headCache(configuration.cacheDirectory),
          pullScratch(std::make_unique<float[]>(configuration.pullCapacityFrames * 2U)) {}

    struct DrainResult final {
        LoopHeadPreparationOfferStatus offerStatus{
            LoopHeadPreparationOfferStatus::Accepted};
        LoopHeadPreparationEndStatus endStatus{
            LoopHeadPreparationEndStatus::LaneCompleted};
        std::size_t framesWritten{0};
        bool failed{false};
        bool laneCompleted{false};
        bool cacheCommitted{false};
    };

    [[nodiscard]] bool exactActiveLane(
        std::uint64_t loopRevision,
        std::size_t laneIndex,
        std::uint64_t generation) const noexcept {
        return phase == LoopHeadPreparationPhase::LaneConfigured && ingress != nullptr &&
            key.loopRevision == loopRevision && activeLane == laneIndex &&
            activePreparationGeneration == generation;
    }

    void failLocked() noexcept {
        ingress.reset();
        activeLane = kLoopHeadLaneCount;
        activeSourceRateHz = 0U;
        activePreparationGeneration = 0U;
        phase = LoopHeadPreparationPhase::Failed;
        saturatingIncrement(failures);
    }

    [[nodiscard]] bool completeLaneLocked(DrainResult &result) noexcept {
        const LoopHeadCacheSnapshot staging = headCache.stagingSnapshot();
        completedLaneMask = staging.completedLaneMask;
        ingress.reset();
        activeLane = kLoopHeadLaneCount;
        activeSourceRateHz = 0U;
        activePreparationGeneration = 0U;
        result.laneCompleted = true;
        saturatingIncrement(lanesCompleted);

        if ((completedLaneMask & requiredLaneMask) != requiredLaneMask) {
            phase = LoopHeadPreparationPhase::Staging;
            return true;
        }
        if (headCache.commitStaging(key.loopRevision) != LoopHeadCommitStatus::Accepted) {
            failLocked();
            result.failed = true;
            result.offerStatus = LoopHeadPreparationOfferStatus::CacheRejected;
            result.endStatus = LoopHeadPreparationEndStatus::CacheRejected;
            return false;
        }
        phase = LoopHeadPreparationPhase::Committed;
        result.cacheCommitted = true;
        saturatingIncrement(commits);
        return true;
    }

    [[nodiscard]] DrainResult drainLocked(bool endOfStreamExpected) noexcept {
        DrainResult result{};
        if (ingress == nullptr) {
            result.failed = true;
            result.offerStatus = LoopHeadPreparationOfferStatus::NoPreparation;
            result.endStatus = LoopHeadPreparationEndStatus::NoPreparation;
            return result;
        }

        for (;;) {
            const LaneIngressDiagnostics ingressDiagnostics = ingress->diagnostics();
            const std::size_t capacity = std::min(
                configuration.pullCapacityFrames,
                ingressDiagnostics.maximumOutputQuantumFrames);
            if (capacity == 0U) {
                failLocked();
                result.failed = true;
                result.offerStatus = LoopHeadPreparationOfferStatus::ResamplerFailure;
                result.endStatus = LoopHeadPreparationEndStatus::ResamplerFailure;
                return result;
            }
            const LanePullResult pulled = ingress->pull48k(
                activePreparationGeneration,
                pullScratch.get(),
                capacity);
            if (pulled.status == LanePullStatus::Empty) {
                if (endOfStreamExpected) {
                    failLocked();
                    result.failed = true;
                    result.offerStatus = LoopHeadPreparationOfferStatus::ResamplerFailure;
                    result.endStatus = LoopHeadPreparationEndStatus::ResamplerFailure;
                }
                return result;
            }
            if (pulled.status == LanePullStatus::StaleGeneration) {
                failLocked();
                result.failed = true;
                result.offerStatus = LoopHeadPreparationOfferStatus::StaleGeneration;
                result.endStatus = LoopHeadPreparationEndStatus::StaleGeneration;
                return result;
            }
            if (pulled.status == LanePullStatus::InvalidArgument ||
                pulled.status == LanePullStatus::ResamplerFailure ||
                pulled.status == LanePullStatus::EndOfStreamFlushFailure) {
                failLocked();
                result.failed = true;
                result.offerStatus = LoopHeadPreparationOfferStatus::ResamplerFailure;
                result.endStatus = LoopHeadPreparationEndStatus::ResamplerFailure;
                return result;
            }
            if (hasDiscontinuity(pulled.discontinuity, LaneDiscontinuity::TimestampGap) ||
                hasDiscontinuity(pulled.discontinuity, LaneDiscontinuity::Explicit)) {
                failLocked();
                result.failed = true;
                result.offerStatus = LoopHeadPreparationOfferStatus::TimelineDiscontinuity;
                result.endStatus = LoopHeadPreparationEndStatus::TimelineDiscontinuity;
                return result;
            }

            if (pulled.outputFramesProduced != 0U) {
                const LoopHeadWriteResult written = headCache.writeStaging(
                    key.loopRevision,
                    activeLane,
                    pulled.firstTimelineFrame48k,
                    pullScratch.get(),
                    pulled.outputFramesProduced);
                if (written.status != LoopHeadWriteStatus::Accepted &&
                    written.status != LoopHeadWriteStatus::LaneComplete) {
                    failLocked();
                    result.failed = true;
                    result.offerStatus = written.status == LoopHeadWriteStatus::GapRejected
                        ? LoopHeadPreparationOfferStatus::TimelineDiscontinuity
                        : LoopHeadPreparationOfferStatus::CacheRejected;
                    result.endStatus = written.status == LoopHeadWriteStatus::GapRejected
                        ? LoopHeadPreparationEndStatus::TimelineDiscontinuity
                        : LoopHeadPreparationEndStatus::CacheRejected;
                    return result;
                }
                nextExpectedTimelineFrame = written.nextExpectedTimelineFrame;
                result.framesWritten += written.framesWritten;
                saturatingAdd(cacheFramesWritten, written.framesWritten);
                if (written.status == LoopHeadWriteStatus::LaneComplete) {
                    static_cast<void>(completeLaneLocked(result));
                    return result;
                }
            }

            if (pulled.status == LanePullStatus::EndOfStream) {
                const LoopHeadSealStatus sealed = headCache.sealStagingLaneWithSilence(
                    key.loopRevision,
                    activeLane,
                    nextExpectedTimelineFrame);
                if (sealed == LoopHeadSealStatus::PaddingPending) {
                    result.endStatus = LoopHeadPreparationEndStatus::PaddingPending;
                    return result;
                }
                if (sealed != LoopHeadSealStatus::Accepted &&
                    sealed != LoopHeadSealStatus::AlreadyComplete) {
                    failLocked();
                    result.failed = true;
                    result.offerStatus = LoopHeadPreparationOfferStatus::CacheRejected;
                    result.endStatus = LoopHeadPreparationEndStatus::CacheRejected;
                    return result;
                }
                static_cast<void>(completeLaneLocked(result));
                return result;
            }

            if (pulled.sourceFramesConsumed == 0U && pulled.outputFramesProduced == 0U) {
                failLocked();
                result.failed = true;
                result.offerStatus = LoopHeadPreparationOfferStatus::ResamplerFailure;
                result.endStatus = LoopHeadPreparationEndStatus::ResamplerFailure;
                return result;
            }
        }
    }

    LoopHeadPreparationConfiguration configuration;
    LoopHeadCache headCache;
    std::unique_ptr<float[]> pullScratch;
    std::unique_ptr<LaneIngress> ingress;
    mutable std::mutex mutex;
    LoopHeadPreparationPhase phase{LoopHeadPreparationPhase::Idle};
    LoopHeadContentKey key{};
    std::uint32_t requiredLaneMask{0U};
    std::uint32_t completedLaneMask{0U};
    std::size_t activeLane{kLoopHeadLaneCount};
    std::uint32_t activeSourceRateHz{0U};
    std::uint64_t activePreparationGeneration{0U};
    std::uint64_t nextExpectedTimelineFrame{0U};
    std::uint64_t beginCalls{0U};
    std::uint64_t configuredLanes{0U};
    std::uint64_t offerCalls{0U};
    std::uint64_t inputFramesAccepted{0U};
    std::uint64_t cacheFramesWritten{0U};
    std::uint64_t lanesCompleted{0U};
    std::uint64_t commits{0U};
    std::uint64_t failures{0U};
    std::uint64_t aborts{0U};
};

LoopHeadPreparationIngress::LoopHeadPreparationIngress(
    const LoopHeadPreparationConfiguration &configuration)
    : mImpl(std::make_unique<Impl>(configuration)) {}

LoopHeadPreparationIngress::~LoopHeadPreparationIngress() = default;

LoopHeadPreparationBeginStatus LoopHeadPreparationIngress::begin(
    const LoopHeadContentKey &key,
    std::uint32_t requiredLaneMask,
    LoopHeadBoundaryMode boundaryMode) noexcept {
    const std::scoped_lock lock(mImpl->mutex);
    saturatingIncrement(mImpl->beginCalls);
    const LoopHeadBeginStatus status =
        mImpl->headCache.beginStaging(key, requiredLaneMask, boundaryMode);
    switch (status) {
    case LoopHeadBeginStatus::Accepted:
        mImpl->ingress.reset();
        mImpl->phase = LoopHeadPreparationPhase::Staging;
        mImpl->key = key;
        mImpl->requiredLaneMask = requiredLaneMask;
        mImpl->completedLaneMask = 0U;
        mImpl->activeLane = kLoopHeadLaneCount;
        mImpl->activeSourceRateHz = 0U;
        mImpl->activePreparationGeneration = 0U;
        mImpl->nextExpectedTimelineFrame = key.startTimelineFrame;
        return LoopHeadPreparationBeginStatus::Accepted;
    case LoopHeadBeginStatus::InvalidKey:
        return LoopHeadPreparationBeginStatus::InvalidKey;
    case LoopHeadBeginStatus::InvalidLaneMask:
        return LoopHeadPreparationBeginStatus::InvalidLaneMask;
    case LoopHeadBeginStatus::StaleRevision:
        return LoopHeadPreparationBeginStatus::StaleRevision;
    case LoopHeadBeginStatus::ReadersActive:
        return LoopHeadPreparationBeginStatus::ReadersActive;
    case LoopHeadBeginStatus::AllocationFailed:
        return LoopHeadPreparationBeginStatus::AllocationFailure;
    case LoopHeadBeginStatus::InsufficientSpace:
        return LoopHeadPreparationBeginStatus::InsufficientSpace;
    }
    return LoopHeadPreparationBeginStatus::InvalidKey;
}

LoopHeadPreparationConfigureStatus LoopHeadPreparationIngress::configureLane(
    std::uint64_t loopRevision,
    std::size_t laneIndex,
    std::uint32_t sourceRateHz,
    std::uint64_t preparationGeneration) noexcept {
    const std::scoped_lock lock(mImpl->mutex);
    if (mImpl->phase == LoopHeadPreparationPhase::Idle ||
        mImpl->phase == LoopHeadPreparationPhase::Aborted ||
        mImpl->phase == LoopHeadPreparationPhase::Failed) {
        return LoopHeadPreparationConfigureStatus::NoPreparation;
    }
    if (loopRevision != mImpl->key.loopRevision) {
        return LoopHeadPreparationConfigureStatus::StaleRevision;
    }
    if (laneIndex >= kLoopHeadLaneCount) {
        return LoopHeadPreparationConfigureStatus::InvalidLane;
    }
    const std::uint32_t bit = laneBit(laneIndex);
    if ((mImpl->requiredLaneMask & bit) == 0U) {
        return LoopHeadPreparationConfigureStatus::LaneNotRequested;
    }
    if ((mImpl->completedLaneMask & bit) != 0U ||
        mImpl->phase == LoopHeadPreparationPhase::Committed) {
        return LoopHeadPreparationConfigureStatus::LaneAlreadyCompleted;
    }
    if (mImpl->ingress != nullptr) {
        return mImpl->activeLane == laneIndex &&
                mImpl->activeSourceRateHz == sourceRateHz &&
                mImpl->activePreparationGeneration == preparationGeneration
            ? LoopHeadPreparationConfigureStatus::AlreadyConfigured
            : LoopHeadPreparationConfigureStatus::LaneBusy;
    }
    if (sourceRateHz == 0U || preparationGeneration == 0U) {
        return LoopHeadPreparationConfigureStatus::InvalidConfiguration;
    }

    try {
        mImpl->ingress = std::make_unique<LaneIngress>(
            sourceRateHz,
            mImpl->configuration.queueCapacityFrames,
            mImpl->configuration.maximumInputQuantumFrames,
            preparationGeneration);
    } catch (const std::invalid_argument &) {
        return LoopHeadPreparationConfigureStatus::InvalidConfiguration;
    } catch (...) {
        return LoopHeadPreparationConfigureStatus::AllocationFailure;
    }
    mImpl->activeLane = laneIndex;
    mImpl->activeSourceRateHz = sourceRateHz;
    mImpl->activePreparationGeneration = preparationGeneration;
    const LoopHeadCacheSnapshot staging = mImpl->headCache.stagingSnapshot();
    mImpl->nextExpectedTimelineFrame = staging.key.startTimelineFrame;
    mImpl->phase = LoopHeadPreparationPhase::LaneConfigured;
    saturatingIncrement(mImpl->configuredLanes);
    return LoopHeadPreparationConfigureStatus::Accepted;
}

LoopHeadPreparationOfferResult LoopHeadPreparationIngress::offer(
    std::uint64_t loopRevision,
    std::size_t laneIndex,
    const LaneInputBlock &block) noexcept {
    const std::scoped_lock lock(mImpl->mutex);
    saturatingIncrement(mImpl->offerCalls);
    if (mImpl->phase == LoopHeadPreparationPhase::Idle ||
        mImpl->phase == LoopHeadPreparationPhase::Aborted ||
        mImpl->phase == LoopHeadPreparationPhase::Failed) {
        return {LoopHeadPreparationOfferStatus::NoPreparation};
    }
    if (loopRevision != mImpl->key.loopRevision) {
        return {LoopHeadPreparationOfferStatus::StaleRevision};
    }
    if (laneIndex >= kLoopHeadLaneCount || mImpl->activeLane != laneIndex ||
        mImpl->ingress == nullptr) {
        return {LoopHeadPreparationOfferStatus::WrongLane};
    }
    if (!mImpl->exactActiveLane(loopRevision, laneIndex, block.generation) ||
        block.format.sampleRateHz != mImpl->activeSourceRateHz) {
        return {LoopHeadPreparationOfferStatus::StaleGeneration};
    }

    const LaneOfferResult offered = mImpl->ingress->offer(block);
    saturatingAdd(mImpl->inputFramesAccepted, offered.inputFramesAccepted);
    if (offered.status == LaneOfferStatus::InvalidArgument) {
        return {LoopHeadPreparationOfferStatus::InvalidArgument};
    }
    if (offered.status == LaneOfferStatus::StaleGeneration) {
        return {
            LoopHeadPreparationOfferStatus::StaleGeneration,
            offered.inputFramesAccepted,
            0U,
        };
    }
    if (offered.status == LaneOfferStatus::AfterEndOfStream) {
        return {LoopHeadPreparationOfferStatus::AfterEndOfStream};
    }

    Impl::DrainResult drained = mImpl->drainLocked(false);
    if (drained.failed) {
        return {drained.offerStatus, offered.inputFramesAccepted, drained.framesWritten};
    }
    if (drained.cacheCommitted) {
        return {
            LoopHeadPreparationOfferStatus::CacheCommitted,
            offered.inputFramesAccepted,
            drained.framesWritten,
        };
    }
    if (drained.laneCompleted) {
        return {
            LoopHeadPreparationOfferStatus::LaneCompleted,
            offered.inputFramesAccepted,
            drained.framesWritten,
        };
    }
    const LoopHeadPreparationOfferStatus status = offered.status == LaneOfferStatus::Partial
        ? LoopHeadPreparationOfferStatus::Partial
        : (offered.status == LaneOfferStatus::Backpressured
            ? LoopHeadPreparationOfferStatus::Backpressured
            : LoopHeadPreparationOfferStatus::Accepted);
    return {status, offered.inputFramesAccepted, drained.framesWritten};
}

LoopHeadPreparationEndStatus LoopHeadPreparationIngress::markEndOfStream(
    std::uint64_t loopRevision,
    std::size_t laneIndex,
    std::uint64_t preparationGeneration,
    std::uint64_t finalSourceFrameExclusive) noexcept {
    const std::scoped_lock lock(mImpl->mutex);
    if (laneIndex < kLoopHeadLaneCount &&
        mImpl->phase == LoopHeadPreparationPhase::Committed &&
        loopRevision == mImpl->key.loopRevision &&
        (mImpl->completedLaneMask & laneBit(laneIndex)) != 0U) {
        return LoopHeadPreparationEndStatus::AlreadyCompleted;
    }
    if (mImpl->phase == LoopHeadPreparationPhase::Idle ||
        mImpl->phase == LoopHeadPreparationPhase::Aborted ||
        mImpl->phase == LoopHeadPreparationPhase::Failed) {
        return LoopHeadPreparationEndStatus::NoPreparation;
    }
    if (loopRevision != mImpl->key.loopRevision) {
        return LoopHeadPreparationEndStatus::StaleRevision;
    }
    if (laneIndex >= kLoopHeadLaneCount || mImpl->activeLane != laneIndex ||
        mImpl->ingress == nullptr) {
        return LoopHeadPreparationEndStatus::WrongLane;
    }
    if (!mImpl->exactActiveLane(
            loopRevision,
            laneIndex,
            preparationGeneration)) {
        return LoopHeadPreparationEndStatus::StaleGeneration;
    }

    const LaneEndOfStreamResult marked = mImpl->ingress->markEndOfStream(
        preparationGeneration,
        finalSourceFrameExclusive);
    if (marked.status == LaneEndOfStreamStatus::StaleGeneration) {
        return LoopHeadPreparationEndStatus::StaleGeneration;
    }
    if (marked.status == LaneEndOfStreamStatus::BoundaryMismatch) {
        return LoopHeadPreparationEndStatus::BoundaryMismatch;
    }
    if (marked.status == LaneEndOfStreamStatus::InvalidArgument) {
        return LoopHeadPreparationEndStatus::InvalidArgument;
    }

    Impl::DrainResult drained = mImpl->drainLocked(true);
    if (drained.failed) return drained.endStatus;
    if (drained.endStatus == LoopHeadPreparationEndStatus::PaddingPending) return drained.endStatus;
    return drained.cacheCommitted
        ? LoopHeadPreparationEndStatus::CacheCommitted
        : LoopHeadPreparationEndStatus::LaneCompleted;
}

LoopHeadPreparationAbortStatus LoopHeadPreparationIngress::abort(
    std::uint64_t loopRevision) noexcept {
    const std::scoped_lock lock(mImpl->mutex);
    const LoopHeadAbortStatus status = mImpl->headCache.abortStaging(loopRevision);
    if (status == LoopHeadAbortStatus::NoStagingSlot) {
        return LoopHeadPreparationAbortStatus::NoPreparation;
    }
    if (status == LoopHeadAbortStatus::StaleRevision) {
        return LoopHeadPreparationAbortStatus::StaleRevision;
    }
    mImpl->ingress.reset();
    mImpl->phase = LoopHeadPreparationPhase::Aborted;
    mImpl->activeLane = kLoopHeadLaneCount;
    mImpl->activeSourceRateHz = 0U;
    mImpl->activePreparationGeneration = 0U;
    saturatingIncrement(mImpl->aborts);
    return LoopHeadPreparationAbortStatus::Accepted;
}

const LoopHeadCache &LoopHeadPreparationIngress::cache() const noexcept {
    return mImpl->headCache;
}

LoopHeadCache &LoopHeadPreparationIngress::cache() noexcept {
    return mImpl->headCache;
}

LoopHeadPreparationSnapshot LoopHeadPreparationIngress::snapshot() const noexcept {
    const std::scoped_lock lock(mImpl->mutex);
    return {
        .phase = mImpl->phase,
        .key = mImpl->key,
        .requiredLaneMask = mImpl->requiredLaneMask,
        .completedLaneMask = mImpl->completedLaneMask,
        .activeLane = mImpl->activeLane,
        .activeSourceRateHz = mImpl->activeSourceRateHz,
        .activePreparationGeneration = mImpl->activePreparationGeneration,
        .nextExpectedTimelineFrame = mImpl->nextExpectedTimelineFrame,
        .beginCalls = mImpl->beginCalls,
        .configuredLanes = mImpl->configuredLanes,
        .offerCalls = mImpl->offerCalls,
        .inputFramesAccepted = mImpl->inputFramesAccepted,
        .cacheFramesWritten = mImpl->cacheFramesWritten,
        .lanesCompleted = mImpl->lanesCompleted,
        .commits = mImpl->commits,
        .failures = mImpl->failures,
        .aborts = mImpl->aborts,
    };
}

}  // namespace stemwise::audio::engine
