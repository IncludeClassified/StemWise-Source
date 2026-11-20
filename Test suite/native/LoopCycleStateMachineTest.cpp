#include "AllocationProbeAttributes.h"
#include "LoopCycleStateMachine.h"

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <new>

namespace allocation_probe {

std::atomic<bool> enabled{false};
std::atomic<std::uint64_t> count{0};

}  // namespace allocation_probe

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

using stemwise::audio::engine::LoopCycleActivation;
using stemwise::audio::engine::LoopCycleBoundaryEvidence;
using stemwise::audio::engine::LoopCycleBoundaryStatus;
using stemwise::audio::engine::LoopCycleCacheStatus;
using stemwise::audio::engine::LoopCycleCallbackStatus;
using stemwise::audio::engine::LoopCycleDirective;
using stemwise::audio::engine::LoopCycleDirectiveAction;
using stemwise::audio::engine::LoopCycleIngressStatus;
using stemwise::audio::engine::LoopCycleIngressReadyEvidence;
using stemwise::audio::engine::LoopCycleIntent;
using stemwise::audio::engine::LoopCycleMode;
using stemwise::audio::engine::LoopCycleOriginalEosStatus;
using stemwise::audio::engine::LoopCycleOwner;
using stemwise::audio::engine::LoopCyclePhase;
using stemwise::audio::engine::LoopCyclePositionEvidence;
using stemwise::audio::engine::LoopCyclePositionStatus;
using stemwise::audio::engine::LoopCycleRenderCommitStatus;
using stemwise::audio::engine::LoopCycleStateMachine;
using stemwise::audio::engine::LoopCycleSubmitStatus;
using stemwise::audio::engine::LoopHeadCache;
using stemwise::audio::engine::LoopHeadBoundaryMode;
using stemwise::audio::engine::LoopHeadCacheSnapshot;
using stemwise::audio::engine::LoopHeadContentKey;
using stemwise::audio::engine::kLoopHeadAllLanesMask;
using stemwise::audio::engine::kLoopHeadLaneCount;

[[noreturn]] void failRequirement(
    const char *expression,
    const char *file,
    int line) noexcept {
    allocation_probe::enabled.store(false, std::memory_order_release);
    std::fprintf(
        stderr,
        "StemWise loop-cycle requirement failed: %s (%s:%d)\n",
        expression,
        file,
        line);
    std::fflush(stderr);
    std::_Exit(EXIT_FAILURE);
}

#define REQUIRE(expression) \
    ((expression) ? static_cast<void>(0) : failRequirement(#expression, __FILE__, __LINE__))

constexpr std::uint64_t kStart = 96'000U;
constexpr LoopCycleOwner kOwner{700U, 900U};

[[nodiscard]] LoopCycleIntent enabledIntent(
    std::uint64_t revision,
    std::uint64_t lengthFrames,
    LoopCycleOwner owner = kOwner,
    std::uint32_t requiredLaneMask = 1U,
    std::uint64_t songHash = 0x1111U) noexcept {
    static_cast<void>(lengthFrames);
    return {
        .revision = revision,
        .enabled = true,
        .owner = owner,
        .cacheKey = {
            .songIdentityHash = songHash,
            .originalContentHash = 0x2222U,
            .stemPackageHash = requiredLaneMask == 1U ? 0U : 0x3333U,
            .contentRevision = 5U,
            .loopRevision = revision,
            .startTimelineFrame = kStart,
            .endTimelineFrameExclusive = kStart + LoopHeadCache::kCapacityFrames,
        },
        .requiredLaneMask = requiredLaneMask,
        .declickFrames = 128U,
        .boundaryMode = LoopHeadBoundaryMode::OriginalEos,
    };
}

[[nodiscard]] LoopCycleIntent disabledIntent(
    std::uint64_t revision,
    LoopCycleOwner owner = kOwner) noexcept {
    return {
        .revision = revision,
        .enabled = false,
        .owner = owner,
    };
}

[[nodiscard]] LoopHeadCacheSnapshot cacheSnapshot(
    const LoopCycleIntent &intent,
    std::uint64_t checksum = 0xABCDU) noexcept {
    const std::uint64_t length = intent.cacheKey.endTimelineFrameExclusive -
        intent.cacheKey.startTimelineFrame;
    LoopHeadCacheSnapshot result{
        .valid = true,
        .slotIndex = 0U,
        .key = intent.cacheKey,
        .requiredLaneMask = intent.requiredLaneMask,
        .completedLaneMask = intent.requiredLaneMask,
        .frameCount = static_cast<std::uint32_t>(
            length <= LoopHeadCache::kCapacityFrames
                ? length
                : LoopHeadCache::kCapacityFrames),
        .cacheOnly = length <= LoopHeadCache::kCapacityFrames,
        .laneChecksums = {},
        .combinedChecksum = checksum,
    };
    for (std::size_t lane = 0; lane < kLoopHeadLaneCount; ++lane) {
        const std::uint32_t bit = 1U << static_cast<std::uint32_t>(lane);
        if ((intent.requiredLaneMask & bit) != 0U) {
            result.laneChecksums[lane] = 0x100U + lane;
        }
    }
    return result;
}

[[nodiscard]] LoopCycleIntent originalEosIntent(std::uint64_t revision) noexcept {
    LoopCycleIntent result = enabledIntent(revision, LoopHeadCache::kCapacityFrames);
    result.boundaryMode = LoopHeadBoundaryMode::OriginalEos;
    return result;
}

[[nodiscard]] LoopHeadCacheSnapshot unresolvedOriginalEosCache(
    const LoopCycleIntent &intent) noexcept {
    LoopHeadCacheSnapshot result = cacheSnapshot(intent);
    result.cacheOnly = false;
    result.boundaryMode = LoopHeadBoundaryMode::OriginalEos;
    result.originalEndResolved = false;
    result.resolvedOriginalEndTimelineFrameExclusive = 0U;
    return result;
}

[[nodiscard]] LoopHeadCacheSnapshot shortOriginalEosCache(
    const LoopCycleIntent &intent,
    std::uint32_t frameCount) noexcept {
    LoopHeadCacheSnapshot result = unresolvedOriginalEosCache(intent);
    result.frameCount = frameCount;
    result.cacheOnly = true;
    result.originalEndResolved = true;
    result.resolvedOriginalEndTimelineFrameExclusive =
        intent.cacheKey.startTimelineFrame + frameCount;
    return result;
}

[[nodiscard]] LoopCycleDirective requireDirective(
    LoopCycleStateMachine &machine,
    LoopCycleDirectiveAction expected) noexcept {
    const LoopCycleDirective directive = machine.renderDirective();
    REQUIRE(directive.valid);
    REQUIRE(directive.action == expected);
    REQUIRE(directive.sequence != 0U);
    return directive;
}

void commitDirective(
    LoopCycleStateMachine &machine,
    LoopCycleDirectiveAction expected) noexcept {
    const auto directive = requireDirective(machine, expected);
    REQUIRE(machine.commitRenderDirective(directive.sequence) ==
            LoopCycleRenderCommitStatus::Accepted);
}

[[nodiscard]] LoopCyclePositionStatus observeExact(
    LoopCycleStateMachine &machine,
    std::uint64_t sequence,
    std::uint64_t sourceTimelineFrame,
    bool tailRetainedThroughEnd,
    std::uint64_t currentIngressEpoch) noexcept {
    const auto snapshot = machine.snapshot();
    return machine.observePosition({
        .sequence = sequence,
        .loopRevision = snapshot.latestRevision,
        .cycle = snapshot.renderCycle,
        .owner = snapshot.owner,
        .sourceTimelineFrame = sourceTimelineFrame,
        .currentIngressEpoch = currentIngressEpoch,
        .tailRetainedThroughEnd = tailRetainedThroughEnd,
    });
}

[[nodiscard]] LoopCycleIngressReadyEvidence exactReadyEvidence(
    const stemwise::audio::engine::LoopCycleIngressRequest &request) noexcept {
    return {
        .requestSequence = request.sequence,
        .loopRevision = request.loopRevision,
        .targetCycle = request.targetCycle,
        .owner = request.owner,
        .ingressEpoch = request.ingressEpoch,
        .readyLaneMask = request.requiredLaneMask,
        .firstTimelineFrame = request.targetTimelineFrame,
        .endTimelineFrameExclusive = request.targetTimelineFrame +
            LoopCycleStateMachine::kDefaultMinimumBodyPrimeFrames,
    };
}

[[nodiscard]] LoopCycleBoundaryStatus boundaryExact(
    LoopCycleStateMachine &machine,
    std::uint64_t sequence,
    std::uint64_t loopEnd) noexcept {
    const auto snapshot = machine.snapshot();
    return machine.onLoopBoundary({
        .sequence = sequence,
        .loopRevision = snapshot.latestRevision,
        .cycle = snapshot.renderCycle,
        .owner = snapshot.owner,
        .ingressEpoch = snapshot.activeIngressEpoch,
        .sourceTimelineFrame = loopEnd,
    });
}

void acknowledgeExactRequest(LoopCycleStateMachine &machine) noexcept {
    const auto request = machine.ingressRequest();
    REQUIRE(request.valid);
    REQUIRE(machine.acknowledgeIngressReady(exactReadyEvidence(request)) ==
            LoopCycleIngressStatus::Accepted);
}

void tenThousandCacheOnlyWrapsKeepOwnerAndRequireCallbackProof() {
    LoopCycleStateMachine machine;
    const auto intent = enabledIntent(1U, 4'800U);
    REQUIRE(machine.submit(intent) == LoopCycleSubmitStatus::Accepted);
    REQUIRE(machine.acceptCache(shortOriginalEosCache(intent, 4'800U)) == LoopCycleCacheStatus::Accepted);

    const std::uint64_t allocationsBefore =
        allocation_probe::count.load(std::memory_order_relaxed);
    allocation_probe::enabled.store(true, std::memory_order_release);

    REQUIRE(observeExact(machine, 1U, kStart, true, 50U) ==
            LoopCyclePositionStatus::DirectivePublished);
    auto install = requireDirective(
        machine,
        LoopCycleDirectiveAction::InstallWarmCacheOnly);
    REQUIRE(install.cycle == 0U);
    REQUIRE(machine.snapshot().renderActive == false);
    REQUIRE(machine.commitRenderDirective(install.sequence) ==
            LoopCycleRenderCommitStatus::Accepted);
    REQUIRE(machine.snapshot().renderInstalled);
    REQUIRE(!machine.snapshot().renderActive);
    REQUIRE(machine.snapshot().phase == LoopCyclePhase::AwaitingCallbackProof);
    REQUIRE(!machine.snapshot().audiblyProven);
    REQUIRE(machine.observeCallback(1U, 0U, kOwner, 1U) ==
            LoopCycleCallbackStatus::Accepted);
    REQUIRE(machine.snapshot().audiblyProven);
    REQUIRE(machine.snapshot().renderActive);

    for (std::uint64_t cycle = 1U; cycle <= 10'000U; ++cycle) {
        REQUIRE(boundaryExact(machine, cycle, kStart + 4'800U) ==
                LoopCycleBoundaryStatus::DirectivePublished);
        const auto wrap = requireDirective(
            machine,
            LoopCycleDirectiveAction::WrapCacheOnly);
        REQUIRE(wrap.loopRevision == intent.revision);
        REQUIRE(wrap.cycle == cycle);
        REQUIRE(wrap.owner == kOwner);
        REQUIRE(machine.commitRenderDirective(wrap.sequence) ==
                LoopCycleRenderCommitStatus::Accepted);
        REQUIRE(machine.snapshot().activation == LoopCycleActivation::None);
        REQUIRE(!machine.snapshot().renderActive);
        REQUIRE(!machine.snapshot().audiblyProven);
        REQUIRE(machine.observeCallback(1U, cycle, kOwner, cycle + 1U) ==
                LoopCycleCallbackStatus::Accepted);
        REQUIRE(machine.snapshot().audiblyProven);
    }
    const auto diagnostics = machine.diagnostics();

    allocation_probe::enabled.store(false, std::memory_order_release);
    REQUIRE(allocation_probe::count.load(std::memory_order_relaxed) ==
            allocationsBefore);
    REQUIRE(machine.snapshot().renderCycle == 10'000U);
    REQUIRE(machine.snapshot().owner == kOwner);
    REQUIRE(machine.snapshot().activeIngressEpoch == 50U);
    REQUIRE(diagnostics.cacheOnlyWraps == 10'000U);
    REQUIRE(diagnostics.streamedWraps == 0U);
    REQUIRE(diagnostics.callbackProofs == 10'001U);
}

void oneThousandStreamedCyclesAdvanceOnlyIngressEpochAndCycle() {
    LoopCycleStateMachine machine;
    constexpr std::uint64_t initialIngressEpoch = 100U;
    const auto intent = enabledIntent(
        1U,
        30U * LoopHeadCache::kSampleRateHz,
        kOwner,
        kLoopHeadAllLanesMask);
    REQUIRE(machine.submit(intent) == LoopCycleSubmitStatus::Accepted);
    REQUIRE(machine.acceptCache(unresolvedOriginalEosCache(intent)) == LoopCycleCacheStatus::Accepted);
    constexpr auto decodedEnd = kStart + 30U * LoopHeadCache::kSampleRateHz;
    REQUIRE(machine.resolveOriginalEos({intent.revision, intent.owner,
        intent.owner.renderGeneration, decodedEnd}) == LoopCycleOriginalEosStatus::Accepted);
    const std::uint64_t safeFrame = decodedEnd -
        24'000U - 1U;

    const std::uint64_t allocationsBefore =
        allocation_probe::count.load(std::memory_order_relaxed);
    allocation_probe::enabled.store(true, std::memory_order_release);

    REQUIRE(observeExact(machine, 1U, safeFrame, true, initialIngressEpoch) ==
            LoopCyclePositionStatus::IngressRequested);
    auto request = machine.ingressRequest();
    REQUIRE(request.valid);
    REQUIRE(request.ingressEpoch == initialIngressEpoch + 1U);
    REQUIRE(request.targetCycle == 1U);
    REQUIRE(request.activation == LoopCycleActivation::WarmCurrentPass);
    acknowledgeExactRequest(machine);
    REQUIRE(!machine.renderDirective().valid);
    REQUIRE(observeExact(machine, 2U, safeFrame, true, initialIngressEpoch) ==
            LoopCyclePositionStatus::DirectivePublished);
    auto install = requireDirective(
        machine,
        LoopCycleDirectiveAction::InstallWarmStreamed);
    REQUIRE(install.cycle == 0U);
    REQUIRE(install.ingressEpoch == initialIngressEpoch);
    REQUIRE(machine.commitRenderDirective(install.sequence) ==
            LoopCycleRenderCommitStatus::Accepted);
    REQUIRE(machine.observeCallback(1U, 0U, kOwner, 1U) ==
            LoopCycleCallbackStatus::Accepted);

    REQUIRE(boundaryExact(machine, 1U, decodedEnd) ==
            LoopCycleBoundaryStatus::DirectivePublished);
    auto firstWrap = requireDirective(machine, LoopCycleDirectiveAction::WrapStreamed);
    REQUIRE(firstWrap.cycle == 1U);
    REQUIRE(firstWrap.ingressEpoch == initialIngressEpoch + 1U);
    REQUIRE(machine.commitRenderDirective(firstWrap.sequence) ==
            LoopCycleRenderCommitStatus::Accepted);
    REQUIRE(machine.observeCallback(1U, 1U, kOwner, 2U) ==
            LoopCycleCallbackStatus::Accepted);

    for (std::uint64_t cycle = 2U; cycle <= 1'000U; ++cycle) {
        const std::uint64_t currentEpoch = initialIngressEpoch + cycle - 1U;
        REQUIRE(observeExact(machine, cycle + 1U, safeFrame, true, currentEpoch) ==
                LoopCyclePositionStatus::IngressRequested);
        request = machine.ingressRequest();
        REQUIRE(request.valid);
        REQUIRE(request.ingressEpoch == currentEpoch + 1U);
        REQUIRE(request.targetCycle == cycle);
        REQUIRE(request.owner == kOwner);
        acknowledgeExactRequest(machine);
        REQUIRE(boundaryExact(
                    machine,
                    cycle,
                    decodedEnd) ==
                LoopCycleBoundaryStatus::DirectivePublished);
        const auto wrap = requireDirective(
            machine,
            LoopCycleDirectiveAction::WrapStreamed);
        REQUIRE(wrap.cycle == cycle);
        REQUIRE(wrap.ingressEpoch == initialIngressEpoch + cycle);
        REQUIRE(wrap.owner == kOwner);
        REQUIRE(machine.commitRenderDirective(wrap.sequence) ==
                LoopCycleRenderCommitStatus::Accepted);
        REQUIRE(machine.snapshot().activation == LoopCycleActivation::None);
        REQUIRE(machine.observeCallback(1U, cycle, kOwner, cycle + 1U) ==
                LoopCycleCallbackStatus::Accepted);
    }
    const auto diagnostics = machine.diagnostics();

    allocation_probe::enabled.store(false, std::memory_order_release);
    REQUIRE(allocation_probe::count.load(std::memory_order_relaxed) ==
            allocationsBefore);
    REQUIRE(machine.snapshot().renderCycle == 1'000U);
    REQUIRE(machine.snapshot().activeIngressEpoch == initialIngressEpoch + 1'000U);
    REQUIRE(machine.snapshot().owner == kOwner);
    REQUIRE(machine.snapshot().audiblyProven);
    REQUIRE(diagnostics.ingressRequests == 1'000U);
    REQUIRE(diagnostics.ingressAcks == 1'000U);
    REQUIRE(diagnostics.streamedWraps == 1'000U);
    REQUIRE(diagnostics.cacheOnlyWraps == 0U);
}

void latestIntentCancelsStalePreparationAndActiveOwnershipCanRebindCache() {
    LoopCycleStateMachine machine;
    auto first = enabledIntent(1U, 30U * LoopHeadCache::kSampleRateHz);
    REQUIRE(machine.submit(first) == LoopCycleSubmitStatus::Accepted);
    REQUIRE(machine.acceptCache(unresolvedOriginalEosCache(first)) == LoopCycleCacheStatus::Accepted);
    constexpr auto decodedEnd = kStart + 30U * LoopHeadCache::kSampleRateHz;
    REQUIRE(machine.resolveOriginalEos({first.revision, first.owner,
        first.owner.renderGeneration, decodedEnd}) == LoopCycleOriginalEosStatus::Accepted);
    const std::uint64_t safe = decodedEnd -
        24'000U - 1U;
    REQUIRE(observeExact(machine, 1U, safe, true, 10U) ==
            LoopCyclePositionStatus::IngressRequested);
    const auto oldRequest = machine.ingressRequest();

    const auto latest = enabledIntent(2U, 4'800U, kOwner, 1U, 0x5555U);
    REQUIRE(machine.submit(latest) == LoopCycleSubmitStatus::Accepted);
    REQUIRE(!machine.ingressRequest().valid);
    REQUIRE(machine.acknowledgeIngressReady(exactReadyEvidence(oldRequest)) ==
            LoopCycleIngressStatus::StaleRevision);
    REQUIRE(machine.acceptCache(cacheSnapshot(first)) == LoopCycleCacheStatus::StaleRevision);
    REQUIRE(machine.acceptCache(shortOriginalEosCache(latest, 4'800U)) == LoopCycleCacheStatus::Accepted);
    REQUIRE(observeExact(machine, 1U, kStart, true, 20U) ==
            LoopCyclePositionStatus::DirectivePublished);
    commitDirective(machine, LoopCycleDirectiveAction::InstallWarmCacheOnly);
    REQUIRE(machine.observeCallback(2U, 0U, kOwner, 1U) ==
            LoopCycleCallbackStatus::Accepted);

    const std::uint64_t rejectedBeforeRebind =
        machine.diagnostics().rejectedCallbackProofs;
    auto rebound = latest;
    rebound.owner.renderGeneration += 1U;
    REQUIRE(machine.submit(rebound) == LoopCycleSubmitStatus::OwnershipRebound);
    commitDirective(machine, LoopCycleDirectiveAction::Clear);
    REQUIRE(machine.snapshot().cacheAccepted);
    REQUIRE(machine.snapshot().phase == LoopCyclePhase::WaitingForSafeWindow);
    REQUIRE(machine.observeCallback(2U, 0U, kOwner, 2U) ==
            LoopCycleCallbackStatus::SupersededPhysicalPolicy);
    REQUIRE(machine.diagnostics().rejectedCallbackProofs == rejectedBeforeRebind);
    REQUIRE(observeExact(machine, 1U, kStart, true, 21U) ==
            LoopCyclePositionStatus::DirectivePublished);
    const auto reinstall = requireDirective(
        machine,
        LoopCycleDirectiveAction::InstallWarmCacheOnly);
    REQUIRE(reinstall.owner == rebound.owner);
    REQUIRE(machine.commitRenderDirective(reinstall.sequence) ==
            LoopCycleRenderCommitStatus::Accepted);
    REQUIRE(!machine.snapshot().audiblyProven);
    REQUIRE(machine.observeCallback(2U, 0U, rebound.owner, 1U) ==
            LoopCycleCallbackStatus::Accepted);
    REQUIRE(machine.snapshot().audiblyProven);

    REQUIRE(machine.submit(disabledIntent(3U, rebound.owner)) ==
            LoopCycleSubmitStatus::AcceptedDisabled);
    commitDirective(machine, LoopCycleDirectiveAction::Clear);
    REQUIRE(machine.snapshot().phase == LoopCyclePhase::Disabled);
    REQUIRE(!machine.snapshot().renderActive);
}

void staleIngressReadyCannotCrossSameRevisionOwnershipRebind() {
    LoopCycleStateMachine machine;
    const auto first = enabledIntent(1U, 30U * LoopHeadCache::kSampleRateHz);
    REQUIRE(machine.submit(first) == LoopCycleSubmitStatus::Accepted);
    REQUIRE(machine.acceptCache(unresolvedOriginalEosCache(first)) == LoopCycleCacheStatus::Accepted);
    constexpr auto decodedEnd = kStart + 30U * LoopHeadCache::kSampleRateHz;
    REQUIRE(machine.resolveOriginalEos({first.revision, first.owner,
        first.owner.renderGeneration, decodedEnd}) == LoopCycleOriginalEosStatus::Accepted);
    const std::uint64_t safe = decodedEnd -
        24'000U - 1U;
    REQUIRE(observeExact(machine, 1U, safe, true, 10U) ==
            LoopCyclePositionStatus::IngressRequested);
    const auto oldRequest = machine.ingressRequest();
    REQUIRE(oldRequest.ingressEpoch == 11U);

    auto rebound = first;
    rebound.owner.renderGeneration += 1U;
    REQUIRE(machine.submit(rebound) == LoopCycleSubmitStatus::OwnershipRebound);
    REQUIRE(machine.snapshot().cacheAccepted);
    REQUIRE(observeExact(machine, 1U, safe, true, 10U) ==
            LoopCyclePositionStatus::IngressRequested);
    const auto newRequest = machine.ingressRequest();
    REQUIRE(newRequest.sequence > oldRequest.sequence);
    REQUIRE(newRequest.ingressEpoch == oldRequest.ingressEpoch);
    REQUIRE(newRequest.owner == rebound.owner);

    REQUIRE(machine.acknowledgeIngressReady(exactReadyEvidence(oldRequest)) ==
            LoopCycleIngressStatus::UnexpectedRequest);
    REQUIRE(machine.ingressRequest().sequence == newRequest.sequence);
    REQUIRE(machine.acknowledgeIngressReady(exactReadyEvidence(newRequest)) ==
            LoopCycleIngressStatus::Accepted);
    REQUIRE(!machine.renderDirective().valid);
    REQUIRE(observeExact(machine, 2U, safe, true, 10U) ==
            LoopCyclePositionStatus::DirectivePublished);
    REQUIRE(requireDirective(machine, LoopCycleDirectiveAction::InstallWarmStreamed).owner ==
            rebound.owner);
}

void positionBoundaryAndCallbackEvidenceAreFencedAndIdempotent() {
    LoopCycleStateMachine machine;
    const auto intent = enabledIntent(1U, 4'800U);
    REQUIRE(machine.submit(intent) == LoopCycleSubmitStatus::Accepted);
    REQUIRE(machine.acceptCache(shortOriginalEosCache(intent, 4'800U)) == LoopCycleCacheStatus::Accepted);

    auto wrongPosition = LoopCyclePositionEvidence{
        .sequence = 999U,
        .loopRevision = intent.revision,
        .cycle = 0U,
        .owner = {kOwner.selectionToken, kOwner.renderGeneration + 1U},
        .sourceTimelineFrame = kStart,
        .currentIngressEpoch = 50U,
        .tailRetainedThroughEnd = true,
    };
    REQUIRE(machine.observePosition(wrongPosition) == LoopCyclePositionStatus::StaleOwner);
    auto staleCyclePosition = wrongPosition;
    staleCyclePosition.owner = kOwner;
    staleCyclePosition.cycle = 1U;
    REQUIRE(machine.observePosition(staleCyclePosition) ==
            LoopCyclePositionStatus::StaleCycle);
    REQUIRE(observeExact(machine, 1U, kStart, true, 50U) ==
            LoopCyclePositionStatus::StaleObservation);
    REQUIRE(observeExact(machine, 1'000U, kStart, true, 50U) ==
            LoopCyclePositionStatus::DirectivePublished);
    commitDirective(machine, LoopCycleDirectiveAction::InstallWarmCacheOnly);
    REQUIRE(machine.snapshot().renderInstalled);
    REQUIRE(!machine.snapshot().renderActive);

    const LoopCycleOwner wrongOwner{kOwner.selectionToken, kOwner.renderGeneration + 1U};
    REQUIRE(machine.observeCallback(1U, 0U, wrongOwner, 999U) ==
            LoopCycleCallbackStatus::StaleOwner);
    REQUIRE(machine.observeCallback(1U, 0U, kOwner, 1U) ==
            LoopCycleCallbackStatus::Accepted);

    const LoopCycleBoundaryEvidence firstBoundary{
        .sequence = 1U,
        .loopRevision = intent.revision,
        .cycle = 0U,
        .owner = kOwner,
        .ingressEpoch = 50U,
        .sourceTimelineFrame = kStart + 4'800U,
    };
    REQUIRE(machine.onLoopBoundary(firstBoundary) ==
            LoopCycleBoundaryStatus::DirectivePublished);
    REQUIRE(machine.onLoopBoundary(firstBoundary) ==
            LoopCycleBoundaryStatus::StaleBoundary);
    auto duplicateCycle = firstBoundary;
    duplicateCycle.sequence = 2U;
    REQUIRE(machine.onLoopBoundary(duplicateCycle) ==
            LoopCycleBoundaryStatus::DirectiveAlreadyPending);
    const auto firstWrap = requireDirective(machine, LoopCycleDirectiveAction::WrapCacheOnly);
    REQUIRE(machine.commitRenderDirective(firstWrap.sequence) ==
            LoopCycleRenderCommitStatus::Accepted);
    REQUIRE(machine.onLoopBoundary(duplicateCycle) ==
            LoopCycleBoundaryStatus::StaleBoundary);
    auto staleCycleBoundary = firstBoundary;
    staleCycleBoundary.sequence = 3U;
    REQUIRE(machine.onLoopBoundary(staleCycleBoundary) ==
            LoopCycleBoundaryStatus::StaleCycle);
    const std::uint64_t rejectedBeforeRetiredCycle =
        machine.diagnostics().rejectedCallbackProofs;
    REQUIRE(machine.observeCallback(1U, 0U, kOwner, 999U) ==
            LoopCycleCallbackStatus::SupersededPhysicalPolicy);
    REQUIRE(machine.diagnostics().rejectedCallbackProofs == rejectedBeforeRetiredCycle);
    REQUIRE(machine.observeCallback(1U, 1U, kOwner, 2U) ==
            LoopCycleCallbackStatus::StaleCallback);
    REQUIRE(machine.observeCallback(1U, 1U, kOwner, 1'000U) ==
            LoopCycleCallbackStatus::Accepted);

    REQUIRE(boundaryExact(machine, 4U, kStart + 4'800U) ==
            LoopCycleBoundaryStatus::DirectivePublished);
    const auto supersededWrap = requireDirective(
        machine,
        LoopCycleDirectiveAction::WrapCacheOnly);
    const auto reversal = enabledIntent(2U, 4'801U, kOwner, 1U, 0x7777U);
    REQUIRE(machine.submit(reversal) == LoopCycleSubmitStatus::Accepted);
    REQUIRE(machine.commitRenderDirective(supersededWrap.sequence) ==
            LoopCycleRenderCommitStatus::StaleSequence);
    const auto clear = requireDirective(machine, LoopCycleDirectiveAction::Clear);
    REQUIRE(machine.commitRenderDirective(clear.sequence) ==
            LoopCycleRenderCommitStatus::Accepted);
    REQUIRE(machine.snapshot().phase == LoopCyclePhase::WaitingForCache);
}

void enablingRepeatMidSongNeverColdRestartsBeforeEos() {
    LoopCycleStateMachine machine;
    const auto intent = originalEosIntent(1U);
    const auto end = kStart + 30U * LoopHeadCache::kSampleRateHz;
    REQUIRE(machine.submit(intent) == LoopCycleSubmitStatus::Accepted);
    REQUIRE(machine.acceptCache(unresolvedOriginalEosCache(intent)) == LoopCycleCacheStatus::Accepted);
    REQUIRE(machine.resolveOriginalEos({
        .loopRevision = intent.revision,
        .owner = intent.owner,
        .ingressEpoch = kOwner.renderGeneration,
        .endTimelineFrameExclusive = end,
    }) == LoopCycleOriginalEosStatus::Accepted);
    REQUIRE(observeExact(machine, 1U, kStart + 10U * 48'000U, false, kOwner.renderGeneration) ==
            LoopCyclePositionStatus::Observed);
    REQUIRE(!machine.ingressRequest().valid);
    REQUIRE(!machine.renderDirective().valid);
    REQUIRE(observeExact(machine, 2U, end - 48'000U, true, kOwner.renderGeneration) ==
            LoopCyclePositionStatus::IngressRequested);
    const auto request = machine.ingressRequest();
    REQUIRE(request.activation == LoopCycleActivation::WarmCurrentPass);
    REQUIRE(machine.acknowledgeIngressReady(exactReadyEvidence(request)) == LoopCycleIngressStatus::Accepted);
    REQUIRE(observeExact(machine, 3U, end - 1U, true, kOwner.renderGeneration) ==
            LoopCyclePositionStatus::DirectivePublished);
    static_cast<void>(requireDirective(machine, LoopCycleDirectiveAction::InstallWarmStreamed));
}

void decodedOriginalEosOwnsShortExactAndLongRepeatBoundaries() {
    {
        LoopCycleStateMachine shortSong;
        const auto intent = originalEosIntent(1U);
        constexpr std::uint32_t decodedFrames = 72'000U;
        REQUIRE(shortSong.submit(intent) == LoopCycleSubmitStatus::Accepted);
        REQUIRE(shortSong.acceptCache(shortOriginalEosCache(intent, decodedFrames)) ==
                LoopCycleCacheStatus::Accepted);
        const auto state = shortSong.snapshot();
        REQUIRE(state.boundaryMode == LoopHeadBoundaryMode::OriginalEos);
        REQUIRE(state.boundaryResolved);
        REQUIRE(state.mode == LoopCycleMode::CacheOnly);
        REQUIRE(state.resolvedLoopEndTimelineFrameExclusive == kStart + decodedFrames);
    }

    {
        LoopCycleStateMachine exactFourSeconds;
        const auto intent = originalEosIntent(1U);
        REQUIRE(exactFourSeconds.submit(intent) == LoopCycleSubmitStatus::Accepted);
        REQUIRE(exactFourSeconds.acceptCache(unresolvedOriginalEosCache(intent)) ==
                LoopCycleCacheStatus::Accepted);
        REQUIRE(exactFourSeconds.snapshot().phase == LoopCyclePhase::WaitingForBoundary);
        REQUIRE(!exactFourSeconds.snapshot().boundaryResolved);
        REQUIRE(observeExact(exactFourSeconds, 1U, kStart, true, kOwner.renderGeneration) ==
                LoopCyclePositionStatus::Observed);
        REQUIRE(!exactFourSeconds.renderDirective().valid);
        REQUIRE(exactFourSeconds.resolveOriginalEos({
                    .loopRevision = intent.revision,
                    .owner = intent.owner,
                    .ingressEpoch = kOwner.renderGeneration - 1U,
                    .endTimelineFrameExclusive =
                        kStart + LoopHeadCache::kCapacityFrames,
                }) == LoopCycleOriginalEosStatus::UnexpectedIngressEpoch);
        REQUIRE(exactFourSeconds.resolveOriginalEos({
                    .loopRevision = intent.revision,
                    .owner = intent.owner,
                    .ingressEpoch = kOwner.renderGeneration,
                    .endTimelineFrameExclusive =
                        kStart + LoopHeadCache::kCapacityFrames,
                }) == LoopCycleOriginalEosStatus::Accepted);
        REQUIRE(exactFourSeconds.snapshot().boundaryResolved);
        REQUIRE(exactFourSeconds.snapshot().mode == LoopCycleMode::CacheOnly);
        REQUIRE(exactFourSeconds.snapshot().resolvedLoopEndTimelineFrameExclusive ==
                kStart + LoopHeadCache::kCapacityFrames);
    }

    {
        LoopCycleStateMachine longSong;
        const auto intent = originalEosIntent(1U);
        constexpr std::uint64_t decodedEnd = kStart + 30U * LoopHeadCache::kSampleRateHz;
        REQUIRE(longSong.submit(intent) == LoopCycleSubmitStatus::Accepted);
        REQUIRE(longSong.acceptCache(unresolvedOriginalEosCache(intent)) ==
                LoopCycleCacheStatus::Accepted);
        REQUIRE(longSong.resolveOriginalEos({
                    .loopRevision = intent.revision,
                    .owner = intent.owner,
                    .ingressEpoch = kOwner.renderGeneration,
                    .endTimelineFrameExclusive = decodedEnd,
                }) == LoopCycleOriginalEosStatus::Accepted);
        REQUIRE(longSong.snapshot().mode == LoopCycleMode::Streamed);
        REQUIRE(longSong.snapshot().resolvedLoopEndTimelineFrameExclusive == decodedEnd);
        REQUIRE(observeExact(longSong, 1U, kStart, true, kOwner.renderGeneration) ==
                LoopCyclePositionStatus::IngressRequested);
        const auto request = longSong.ingressRequest();
        REQUIRE(request.targetTimelineFrame ==
                kStart + LoopHeadCache::kCapacityFrames);
        REQUIRE(request.requiredEndTimelineFrameExclusive ==
                request.targetTimelineFrame +
                    LoopCycleStateMachine::kDefaultMinimumBodyPrimeFrames);
        REQUIRE(longSong.resolveOriginalEos({
                    .loopRevision = intent.revision,
                    .owner = intent.owner,
                    .ingressEpoch = kOwner.renderGeneration,
                    .endTimelineFrameExclusive = decodedEnd,
                }) == LoopCycleOriginalEosStatus::AlreadyResolved);
        REQUIRE(longSong.resolveOriginalEos({
                    .loopRevision = intent.revision,
                    .owner = intent.owner,
                    .ingressEpoch = kOwner.renderGeneration,
                    .endTimelineFrameExclusive = decodedEnd + 1U,
                }) == LoopCycleOriginalEosStatus::ConflictingBoundary);
    }
}

void exactPhysicalRetirementReinstallsTheRetainedCacheWithoutChangingIntent() {
    LoopCycleStateMachine machine;
    const auto intent = enabledIntent(7U, 4'800U);
    REQUIRE(machine.submit(intent) == LoopCycleSubmitStatus::Accepted);
    REQUIRE(machine.acceptCache(shortOriginalEosCache(intent, 4'800U)) == LoopCycleCacheStatus::Accepted);
    REQUIRE(observeExact(machine, 1U, kStart + 100U, true, 50U) == LoopCyclePositionStatus::DirectivePublished);
    commitDirective(machine, LoopCycleDirectiveAction::InstallWarmCacheOnly);
    REQUIRE(machine.observeCallback(intent.revision, 0U, kOwner, 1U) == LoopCycleCallbackStatus::Accepted);
    const auto installed = machine.snapshot();
    REQUIRE(!machine.retireRenderer(intent.revision - 1U, kOwner));
    REQUIRE(!machine.retireRenderer(intent.revision, {kOwner.selectionToken, kOwner.renderGeneration + 1U}));
    REQUIRE(machine.snapshot().renderInstalled && machine.snapshot().audiblyProven);
    REQUIRE(machine.retireRenderer(intent.revision, kOwner));
    const auto retired = machine.snapshot();
    REQUIRE(retired.latestRevision == installed.latestRevision && retired.owner == installed.owner);
    REQUIRE(retired.cacheAccepted && retired.cacheChecksum == installed.cacheChecksum && retired.boundaryResolved);
    REQUIRE(retired.resolvedLoopEndTimelineFrameExclusive == installed.resolvedLoopEndTimelineFrameExclusive);
    REQUIRE(!retired.renderInstalled && !retired.audiblyProven && !retired.renderDirectivePending);
    REQUIRE(!machine.retireRenderer(intent.revision, kOwner));
    REQUIRE(machine.submit(intent) == LoopCycleSubmitStatus::Unchanged);
    REQUIRE(observeExact(machine, 2U, kStart + 300U, true, 51U) == LoopCyclePositionStatus::DirectivePublished);
    const auto replacement = requireDirective(machine, LoopCycleDirectiveAction::InstallWarmCacheOnly);
    REQUIRE(replacement.cacheChecksum == installed.cacheChecksum && replacement.owner == kOwner);
    REQUIRE(replacement.ingressEpoch == 51U);
    commitDirective(machine, LoopCycleDirectiveAction::InstallWarmCacheOnly);
    REQUIRE(!machine.snapshot().audiblyProven);
    REQUIRE(machine.observeCallback(intent.revision, 0U, kOwner, 2U) == LoopCycleCallbackStatus::Accepted);
    REQUIRE(machine.snapshot().renderActive);
}

void rejectedOriginalEosCacheIsTransactionalAndEosPaddingIsStrictlyFenced() {
    {
        LoopCycleStateMachine machine;
        LoopCycleIntent intent = originalEosIntent(1U);
        intent.requiredLaneMask = 0b11U;
        intent.cacheKey.stemPackageHash = 0x3333U;
        REQUIRE(machine.submit(intent) == LoopCycleSubmitStatus::Accepted);
        auto rejected = shortOriginalEosCache(intent, 72'000U);
        rejected.laneChecksums[1] = 0U;
        REQUIRE(machine.acceptCache(rejected) == LoopCycleCacheStatus::InvalidSnapshot);
        const auto unchanged = machine.snapshot();
        REQUIRE(!unchanged.boundaryResolved);
        REQUIRE(unchanged.resolvedLoopEndTimelineFrameExclusive == 0U);
        REQUIRE(unchanged.mode == LoopCycleMode::Streamed);
        REQUIRE(unchanged.phase == LoopCyclePhase::WaitingForCache);

        rejected.laneChecksums[1] = 0x101U;
        REQUIRE(machine.acceptCache(rejected) == LoopCycleCacheStatus::Accepted);
        REQUIRE(machine.snapshot().boundaryResolved);
        REQUIRE(machine.snapshot().mode == LoopCycleMode::CacheOnly);
    }

    {
        LoopCycleStateMachine machine;
        const auto intent = enabledIntent(2U, 300'000U, kOwner, 0b11U);
        REQUIRE(machine.submit(intent) == LoopCycleSubmitStatus::Accepted);
        REQUIRE(machine.acceptCache(unresolvedOriginalEosCache(intent)) == LoopCycleCacheStatus::Accepted);
        REQUIRE(machine.resolveOriginalEos({intent.revision, intent.owner,
            intent.owner.renderGeneration, kStart + 300'000U}) == LoopCycleOriginalEosStatus::Accepted);
        REQUIRE(observeExact(machine, 1U, kStart, true, kOwner.renderGeneration) ==
                LoopCyclePositionStatus::IngressRequested);
        const auto request = machine.ingressRequest();

        auto originalPadding = exactReadyEvidence(request);
        originalPadding.eosPaddedLaneMask = 0b01U;
        REQUIRE(machine.acknowledgeIngressReady(originalPadding) ==
                LoopCycleIngressStatus::InvalidCoverage);
        auto outsideRequired = exactReadyEvidence(request);
        outsideRequired.readyLaneMask |= 0b100U;
        outsideRequired.eosPaddedLaneMask = 0b100U;
        REQUIRE(machine.acknowledgeIngressReady(outsideRequired) ==
                LoopCycleIngressStatus::InvalidCoverage);
        auto missingReadyBit = exactReadyEvidence(request);
        missingReadyBit.eosPaddedLaneMask = 0b100U;
        REQUIRE(machine.acknowledgeIngressReady(missingReadyBit) ==
                LoopCycleIngressStatus::InvalidCoverage);

        auto exactStemPadding = exactReadyEvidence(request);
        exactStemPadding.eosPaddedLaneMask = 0b10U;
        REQUIRE(machine.acknowledgeIngressReady(exactStemPadding) ==
                LoopCycleIngressStatus::Accepted);
    }
}

}  // namespace

int main() {
    tenThousandCacheOnlyWrapsKeepOwnerAndRequireCallbackProof();
    oneThousandStreamedCyclesAdvanceOnlyIngressEpochAndCycle();
    latestIntentCancelsStalePreparationAndActiveOwnershipCanRebindCache();
    staleIngressReadyCannotCrossSameRevisionOwnershipRebind();
    positionBoundaryAndCallbackEvidenceAreFencedAndIdempotent();
    enablingRepeatMidSongNeverColdRestartsBeforeEos();
    decodedOriginalEosOwnsShortExactAndLongRepeatBoundaries();
    exactPhysicalRetirementReinstallsTheRetainedCacheWithoutChangingIntent();
    rejectedOriginalEosCacheIsTransactionalAndEosPaddingIsStrictlyFenced();
    std::puts("StemWise Repeat One tests passed: 10,000 cache wraps, 1,000 streamed cycles, "
        "exact ownership/epoch/evidence fencing, decoded EOS and zero steady allocations.");
    return EXIT_SUCCESS;
}
