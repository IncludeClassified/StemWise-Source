#include "NativeAudioOutputLoopBoundaryGate.h"
#include "OutputRingRoleCoordinator.h"

#include <array>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <vector>

namespace stemwise::audio::role_test {
void (*claimHook)() noexcept = nullptr;
void (*cancellationHook)() noexcept = nullptr;
void beforeCancellationClaim() noexcept {
    const auto hook = cancellationHook;
    cancellationHook = nullptr;
    if (hook) hook();
}
void beforeCallbackClaim() noexcept {
    const auto hook = claimHook;
    claimHook = nullptr;
    if (hook) hook();
}
}

namespace {

using stemwise::audio::AtomicNativeAudioOutputLoopPolicy;
using stemwise::audio::LatestNativeAudioOutputLoopPolicyMailbox;
using stemwise::audio::NativeAudioOutputLoopCaptureEvidence;
using stemwise::audio::NativeAudioOutputLoopGestureCaptureGate;
using stemwise::audio::NativeAudioOutputLoopPcmAuthority;
using stemwise::audio::NativeAudioOutputLoopPolicy;
using stemwise::audio::RenderBusFrameTag;
using stemwise::audio::OutputHandoffStatus;
using stemwise::audio::OutputDrainCancelStatus;
using stemwise::audio::OutputRingRoleCoordinator;
using stemwise::audio::SpscTaggedMultibusRingBuffer;
using stemwise::audio::bindLogicalDemandToPreparedContinuation;
using stemwise::audio::kStemwiseMultibusStride;
using stemwise::audio::nativeAudioOutputLoopPcmAuthority;
using stemwise::audio::nativeAudioOutputCapturedLoopCutover;
using stemwise::audio::nativeAudioOutputLoopDemandBlocksDrain;
using stemwise::audio::nativeAudioOutputPreparedLoopBlocksEnd;
using stemwise::audio::nativeAudioOutputPreparedLoopOwnsTerminalCutover;
using stemwise::audio::taggedFramesBeforeDormantLoopBoundary;
using stemwise::audio::taggedFramesBeforeLoopBoundary;

[[noreturn]] void failRequirement(const char *expression, const char *file, int line) noexcept {
    std::fprintf(stderr, "StemWise output-loop gate requirement failed: %s (%s:%d)\n", expression, file, line);
    std::fflush(stderr);
    std::_Exit(EXIT_FAILURE);
}

#define REQUIRE(expression) \
    ((expression) ? static_cast<void>(0) : failRequirement(#expression, __FILE__, __LINE__))

OutputRingRoleCoordinator *racingRoles = nullptr;

void testCancelledPublicationCannotStrandReplacementClaim() {
    for (const bool loop : {false, true}) {
        OutputRingRoleCoordinator roles(1U);
        racingRoles = &roles;
        REQUIRE((loop ? roles.beginLoopCutover(1U, 7U) : roles.beginDrain(1U, 2U, 7U)).accepted());
        REQUIRE((loop ? roles.commitLoopCutover(1U, 7U, 10U) : roles.commitDrain(1U, 2U, 7U, 10U)) ==
            OutputHandoffStatus::Accepted);
        stemwise::audio::role_test::claimHook = loop ? +[]() noexcept {
            REQUIRE(racingRoles->cancelLoopCutover(1U, 7U) == OutputDrainCancelStatus::AcceptedOpenStage);
            REQUIRE(racingRoles->beginLoopCutover(1U, 8U).accepted());
            REQUIRE(racingRoles->commitLoopCutover(1U, 8U, 10U) == OutputHandoffStatus::Accepted);
        } : +[]() noexcept {
            REQUIRE(racingRoles->cancelDrain(1U, 2U, 7U) == OutputDrainCancelStatus::AcceptedAwaitingCallback);
            const auto cancelled = racingRoles->callbackCancellation(0U);
            REQUIRE(cancelled.valid());
            racingRoles->acknowledgeCancellation(cancelled);
            REQUIRE(racingRoles->beginDrain(1U, 3U, 8U).accepted());
            REQUIRE(racingRoles->commitDrain(1U, 3U, 8U, 10U) == OutputHandoffStatus::Accepted);
        };
        const auto stale = loop ? roles.callbackLoopSwap(0U, 1U, 7U, true) : roles.callbackSwap(0U, true, true);
        REQUIRE(!stale.valid());
        const auto replacement = loop ? roles.callbackLoopSwap(0U, 1U, 8U, true) : roles.callbackSwap(0U, true, true);
        REQUIRE(replacement.valid() && replacement.transitionRevision == 8U);
        REQUIRE(roles.commitCallbackSwap(replacement));
        roles.acknowledge(replacement);
        REQUIRE(!roles.commitAwaitingAcknowledgement());
        REQUIRE(roles.begin(4U).accepted());
    }
}

stemwise::audio::OutputHandoffMode replacementMode;

void testStaleDrainCancellationCannotCancelReplacement() {
    using stemwise::audio::OutputHandoffMode;
    for (const auto mode : {OutputHandoffMode::Immediate, OutputHandoffMode::DrainActive,
                           OutputHandoffMode::LoopCutover}) {
        OutputRingRoleCoordinator roles(1U);
        racingRoles = &roles;
        replacementMode = mode;
        REQUIRE(roles.beginDrain(1U, 2U, 7U).accepted());
        REQUIRE(roles.commitDrain(1U, 2U, 7U, 10U) == OutputHandoffStatus::Accepted);
        stemwise::audio::role_test::cancellationHook = +[]() noexcept {
            REQUIRE(racingRoles->cancelDrain(1U, 2U, 7U) == OutputDrainCancelStatus::AcceptedAwaitingCallback);
            const auto cancelled = racingRoles->callbackCancellation(0U);
            REQUIRE(cancelled.valid());
            racingRoles->acknowledgeCancellation(cancelled);
            REQUIRE(racingRoles->clearAutomaticDrainFenceForAuthorizedGeneration(3U));
            if (replacementMode == OutputHandoffMode::Immediate) {
                REQUIRE(racingRoles->begin(3U).accepted());
                REQUIRE(racingRoles->commit(3U, 10U) == OutputHandoffStatus::Accepted);
            } else if (replacementMode == OutputHandoffMode::DrainActive) {
                REQUIRE(racingRoles->beginDrain(1U, 3U, 8U).accepted());
                REQUIRE(racingRoles->commitDrain(1U, 3U, 8U, 10U) == OutputHandoffStatus::Accepted);
            } else {
                REQUIRE(racingRoles->beginLoopCutover(1U, 8U).accepted());
                REQUIRE(racingRoles->commitLoopCutover(1U, 8U, 10U) == OutputHandoffStatus::Accepted);
            }
        };
        roles.armAutomaticDrainFence();
        REQUIRE(!roles.callbackCancellation(0U).valid());
        REQUIRE(!roles.automaticDrainFenced());
        const auto replacement = mode == OutputHandoffMode::LoopCutover
            ? roles.callbackLoopSwap(0U, 1U, 8U, true) : roles.callbackSwap(0U, true, true);
        REQUIRE(replacement.valid() && replacement.sequence == 2U);
        REQUIRE(roles.commitCallbackSwap(replacement));
        roles.acknowledge(replacement);
        REQUIRE(!roles.commitAwaitingAcknowledgement());
        REQUIRE(roles.begin(4U).accepted());
    }
}

void testLogicalDemandNeverOwnsPcmBeforePhysicalReadiness() {
    REQUIRE(nativeAudioOutputLoopPcmAuthority(false) ==
        NativeAudioOutputLoopPcmAuthority::Linear);
    REQUIRE(nativeAudioOutputLoopPcmAuthority(true) ==
        NativeAudioOutputLoopPcmAuthority::PreparedCutover);
}

void testLogicalDemandNeverBlocksEndWithoutExactPreparedA() {
    REQUIRE(!nativeAudioOutputPreparedLoopBlocksEnd(false, false, false));
    REQUIRE(!nativeAudioOutputPreparedLoopBlocksEnd(true, false, true));
    REQUIRE(!nativeAudioOutputPreparedLoopBlocksEnd(true, true, false));
    REQUIRE(nativeAudioOutputPreparedLoopBlocksEnd(true, true, true));
}

void testArmedPreparedAOwnsAnExactEmptyTerminalStream() {
    REQUIRE(nativeAudioOutputPreparedLoopOwnsTerminalCutover(
        true, true, true, true, true));
    REQUIRE(!nativeAudioOutputPreparedLoopOwnsTerminalCutover(
        false, true, true, true, true));
    REQUIRE(!nativeAudioOutputPreparedLoopOwnsTerminalCutover(
        true, false, true, true, true));
    REQUIRE(!nativeAudioOutputPreparedLoopOwnsTerminalCutover(
        true, true, false, true, true));
    REQUIRE(!nativeAudioOutputPreparedLoopOwnsTerminalCutover(
        true, true, true, false, true));
    REQUIRE(!nativeAudioOutputPreparedLoopOwnsTerminalCutover(
        true, true, true, true, false));
}

void testDormantPastDemandNeverFencesDrain() {
    const NativeAudioOutputLoopPolicy dormantPast{
        .revision = 1U,
        .generation = 7U,
        .selectionToken = 11U,
        .enabled = true,
        .physicalReady = false,
        .aTimelineFrame = 100U,
        .bTimelineFrameExclusive = 200U,
    };
    REQUIRE(!nativeAudioOutputLoopDemandBlocksDrain(dormantPast, 7U, 11U, 200));
    REQUIRE(!nativeAudioOutputLoopDemandBlocksDrain(dormantPast, 7U, 11U, 240));
    REQUIRE(nativeAudioOutputLoopDemandBlocksDrain(dormantPast, 7U, 11U, 199));
    REQUIRE(nativeAudioOutputLoopDemandBlocksDrain(dormantPast, 8U, 11U, 240));

    NativeAudioOutputLoopPolicy armed = dormantPast;
    armed.boundaryArmedAtPublication = true;
    REQUIRE(nativeAudioOutputLoopDemandBlocksDrain(armed, 7U, 11U, 240));

    NativeAudioOutputLoopPolicy disabled = dormantPast;
    disabled.enabled = false;
    REQUIRE(!nativeAudioOutputLoopDemandBlocksDrain(disabled, 0U, 0U, -1));
}

void testCrossedBRemainsTheNextCutoverWhileVisibleBMovesAhead() {
    REQUIRE(nativeAudioOutputCapturedLoopCutover(1'200U, 1'300U, 1'050U, true) ==
        1'050U);
    REQUIRE(nativeAudioOutputCapturedLoopCutover(1'200U, 1'000U, 1'050U, true) ==
        1'000U);
    REQUIRE(nativeAudioOutputCapturedLoopCutover(1'200U, 1'300U, 1'050U, false) ==
        1'200U);
    NativeAudioOutputLoopPolicy prepared{};
    prepared.cutoverTimelineFrameExclusive = 1'200U;
    prepared.boundaryArmedAtPublication = false;
    const NativeAudioOutputLoopPolicy captured =
        bindCapturedLoopCutover(prepared, 1'000U, 1'050U);
    REQUIRE(captured.cutoverTimelineFrameExclusive == 1'000U);
    REQUIRE(captured.boundaryArmedAtPublication);
}

void testHeldBRearmsOnlyAfterExactPhysicalCutoverProof() {
    NativeAudioOutputLoopGestureCaptureGate gate;
    NativeAudioOutputLoopPolicy policy{
        .revision = 1U,
        .generation = 7U,
        .selectionToken = 11U,
        .enabled = true,
        .physicalReady = false,
        .aTimelineFrame = 100U,
        .bTimelineFrameExclusive = 200U,
        .heldBGestureId = 41U,
        .boundaryArmedAtPublication = true,
    };
    REQUIRE(!gate.shouldCapture(policy, false, 7U, 11U, 150, 8U, {}));

    policy.revision = 2U;
    policy.bTimelineFrameExclusive = 140U;
    REQUIRE(gate.shouldCapture(policy, true, 7U, 11U, 150, 9U, {}));

    const NativeAudioOutputLoopCaptureEvidence firstCutover{
        .revision = 2U,
        .callbackSequence = 10U,
        .generation = 7U,
        .selectionToken = 11U,
        .aTimelineFrame = 100U,
        .heldBGestureId = 41U,
    };
    policy.revision = 3U;
    policy.bTimelineFrameExclusive = 130U;
    REQUIRE(!gate.shouldCapture(policy, true, 7U, 11U, 150, 9U, {}));
    policy.bTimelineFrameExclusive = 180U;
    policy.boundaryArmedAtPublication = false;
    REQUIRE(!gate.shouldCapture(policy, false, 7U, 11U, 150, 9U, firstCutover));
    REQUIRE(!gate.shouldCapture(policy, false, 7U, 11U, 105, 10U, firstCutover));
    policy.bTimelineFrameExclusive = 140U;
    REQUIRE(!gate.shouldCapture(policy, false, 7U, 11U, 150, 10U, firstCutover));

    // The controller can remain logically parked after A. The same held gesture still captures
    // its next physical B crossing, but exactly once until another cutover is proven.
    policy.revision = 4U;
    policy.bTimelineFrameExclusive = 140U;
    REQUIRE(gate.shouldCapture(policy, false, 7U, 11U, 150, 11U, firstCutover));
    policy.revision = 5U;
    policy.bTimelineFrameExclusive = 130U;
    REQUIRE(!gate.shouldCapture(policy, false, 7U, 11U, 150, 11U, firstCutover));

    const NativeAudioOutputLoopCaptureEvidence secondCutover{
        .revision = 4U,
        .callbackSequence = 12U,
        .generation = 7U,
        .selectionToken = 11U,
        .aTimelineFrame = 100U,
        .heldBGestureId = 41U,
    };
    policy.revision = 6U;
    policy.bTimelineFrameExclusive = 180U;
    REQUIRE(!gate.shouldCapture(policy, false, 7U, 11U, 105, 11U, secondCutover));
    REQUIRE(!gate.shouldCapture(policy, false, 7U, 11U, 105, 12U, secondCutover));

    policy.revision = 7U;
    policy.bTimelineFrameExclusive = 140U;
    REQUIRE(gate.shouldCapture(policy, false, 7U, 11U, 150, 13U, secondCutover));

    policy.revision = 8U;
    policy.heldBGestureId = 42U;
    policy.boundaryArmedAtPublication = false;
    REQUIRE(!gate.shouldCapture(policy, false, 7U, 11U, 150, 13U, secondCutover));
}

[[nodiscard]] RenderBusFrameTag tag(
    std::int64_t timeline,
    std::uint64_t loopRevision = 0U,
    std::uint64_t loopPolicyRevision = 0U) noexcept {
    RenderBusFrameTag value{};
    value.generation = 7U;
    value.selectionToken = 11U;
    value.payloadSequence = 1U;
    value.payloadHash = 1U;
    value.canonicalControlRevision = 1U;
    value.sourceIntentRevision = 1U;
    value.stemIntentRevision = 1U;
    value.timelineFrame = timeline;
    value.loopPolicyRevision = loopPolicyRevision;
    value.loopRevision = loopRevision;
    value.loopEnabled = loopRevision != 0U;
    return value;
}

void testExactPreparedRoleWrapsBToAInSameCallback() {
    constexpr std::size_t reserveFrames = 2'304U;
    constexpr std::size_t callbackFrames = 512U;
    constexpr std::uint64_t generation = 7U;
    constexpr std::uint64_t token = 11U;
    constexpr std::uint64_t revision = 12U;
    constexpr std::uint64_t a = 1'000U;
    constexpr std::uint64_t b = 1'200U;
    SpscTaggedMultibusRingBuffer first(4'096U);
    SpscTaggedMultibusRingBuffer second(4'096U);
    OutputRingRoleCoordinator roles(generation);
    std::vector<float> payload(reserveFrames * kStemwiseMultibusStride, 0.0F);
    const RenderBusFrameTag stale = tag(static_cast<std::int64_t>(a), 0U, 8U);
    REQUIRE(first.writeTaggedTimeline(
        payload.data(), reserveFrames, stale, static_cast<double>(a),
        static_cast<double>(a + reserveFrames), false) == reserveFrames);

    const auto begin = roles.beginLoopCutover(generation, revision);
    REQUIRE(begin.accepted());
    REQUIRE(begin.stagingRing == 1U);
    const RenderBusFrameTag current = tag(static_cast<std::int64_t>(a), revision, revision);
    std::size_t staged = 0U;
    while (staged < reserveFrames) {
        const std::size_t cycleFrames = std::min<std::size_t>(
            static_cast<std::size_t>(b - a),
            reserveFrames - staged);
        REQUIRE(second.writeTaggedTimeline(
            payload.data() + staged * kStemwiseMultibusStride,
            cycleFrames,
            current,
            static_cast<double>(a),
            static_cast<double>(a + cycleFrames),
            false) == cycleFrames);
        staged += cycleFrames;
    }
    REQUIRE(roles.commitLoopCutover(generation, revision, second.queuedFrames()) ==
        OutputHandoffStatus::Accepted);

    // Production captures the prepared A role before scanning the active callback prefix. A
    // simultaneous handle retarget therefore cannot cancel the only continuation after B has
    // already become this callback's boundary.
    const auto swap = roles.callbackLoopSwap(0U, generation, revision, true);
    REQUIRE(swap.valid());
    REQUIRE(swap.ring == 1U);
    REQUIRE(roles.cancelLoopCutover(generation, revision) ==
        OutputDrainCancelStatus::TooLate);
    bool boundaryReached = false;
    const std::size_t prefix = taggedFramesBeforeLoopBoundary(
        first, generation, token, b, callbackFrames, boundaryReached, true, -1, 8U);
    REQUIRE(boundaryReached);
    REQUIRE(prefix == static_cast<std::size_t>(b - a));
    const auto staleLease = first.acquireRead(prefix);
    REQUIRE(staleLease.frameCount == prefix);
    REQUIRE(staleLease.spans[0U].tags[0U].timelineFrame == static_cast<std::int64_t>(a));
    REQUIRE(staleLease.spans[staleLease.spanCount - 1U]
                .tags[staleLease.spans[staleLease.spanCount - 1U].frameCount - 1U]
                .timelineFrame == static_cast<std::int64_t>(b - 1U));
    REQUIRE(first.advanceRead(staleLease));

    REQUIRE(roles.commitCallbackSwap(swap));
    roles.acknowledge(swap);
    const auto continuation = second.acquireRead(callbackFrames - prefix);
    REQUIRE(continuation.frameCount == callbackFrames - prefix);
    REQUIRE(continuation.spans[0U].tags[0U].timelineFrame ==
        static_cast<std::int64_t>(a));
    REQUIRE(continuation.spans[0U].tags[0U].loopPolicyRevision == revision);
    REQUIRE(continuation.spans[0U].tags[0U].loopPolicyRevision >
        stale.loopPolicyRevision);
    bool wrappedInsideCallback = false;
    std::int64_t priorTimeline = -1;
    for (std::size_t span = 0U; span < continuation.spanCount; ++span) {
        for (std::size_t frame = 0U; frame < continuation.spans[span].frameCount; ++frame) {
            const std::int64_t timeline = continuation.spans[span].tags[frame].timelineFrame;
            REQUIRE(timeline >= static_cast<std::int64_t>(a));
            REQUIRE(timeline < static_cast<std::int64_t>(b));
            wrappedInsideCallback = wrappedInsideCallback ||
                (priorTimeline >= 0 && timeline < priorTimeline);
            priorTimeline = timeline;
        }
    }
    REQUIRE(wrappedInsideCallback);
    REQUIRE(prefix + continuation.frameCount == callbackFrames);
    REQUIRE(second.advanceRead(continuation));
    REQUIRE(first.queuedFrames() == reserveFrames - prefix);
    REQUIRE(roles.activeRing() == 1U);
}

void testPreparedCutoverUsesTheLastOutgoingFramesMappedEnd() {
    constexpr std::uint64_t a = 100U;
    constexpr std::uint64_t b = 110U;
    for (const std::size_t frames : {20U, 10U, 5U}) {
        SpscTaggedMultibusRingBuffer active(64U);
        std::vector<float> pcm(frames * kStemwiseMultibusStride, .25F);
        const auto outgoing = tag(a, 8U, 8U);
        REQUIRE(active.writeTaggedTimeline(pcm.data(), frames, outgoing,
            static_cast<double>(a), static_cast<double>(b), false) == frames);
        bool reached = false;
        REQUIRE(taggedFramesBeforeLoopBoundary(active, 7U, 11U, b, 64U,
            reached, true, -1, 8U) == frames);
        REQUIRE(reached);
        reached = false;
        REQUIRE(taggedFramesBeforeLoopBoundary(active, 7U, 11U, b, frames - 1U,
            reached, true, -1, 8U) == frames - 1U);
        REQUIRE(!reached);
        REQUIRE(taggedFramesBeforeLoopBoundary(active, 7U, 11U, b, 64U,
            reached, true, -1, 9U) == frames);
        REQUIRE(!reached);
        bool entered = false;
        REQUIRE(taggedFramesBeforeDormantLoopBoundary(active, 7U, 11U, a, b,
            64U, entered, reached, 8U) == frames);
        REQUIRE(entered && reached);
        const auto lease = active.acquireRead(64U);
        REQUIRE(lease.frameCount == frames);
        REQUIRE(lease.spans[lease.spanCount - 1U]
            .tags[lease.spans[lease.spanCount - 1U].frameCount - 1U].timelineFrame <
                static_cast<std::int64_t>(b));
    }
}

void testFastBCrossingSurvivesAStillHeldMoveBackAhead(bool wrappedBeforePublication = false) {
    constexpr std::uint64_t generation = 7U;
    constexpr std::uint64_t token = 11U;
    constexpr std::uint64_t physicalRevision = 18U;
    constexpr std::uint64_t a = 1'000U;
    constexpr std::uint64_t capturedB = 1'110U;
    constexpr std::uint64_t latestB = 1'300U;
    SpscTaggedMultibusRingBuffer active(1'024U);
    SpscTaggedMultibusRingBuffer prepared(1'024U);
    OutputRingRoleCoordinator roles(generation);
    std::vector<float> payload(512U * kStemwiseMultibusStride, 0.0F);
    const std::int64_t outgoingStart = wrappedBeforePublication ? 1'048 : 1'100;
    REQUIRE(active.writeTaggedTimeline(
        payload.data(), 512U, tag(outgoingStart, 0U, 0U), static_cast<double>(outgoingStart),
        static_cast<double>(outgoingStart + 512), false) == 512U);

    const auto begin = roles.beginLoopCutover(generation, physicalRevision);
    REQUIRE(begin.accepted());
    REQUIRE(prepared.writeTaggedTimeline(
        payload.data(), 512U, tag(a, physicalRevision, physicalRevision),
        static_cast<double>(a), static_cast<double>(a + 512U), false) == 512U);
    REQUIRE(roles.commitLoopCutover(generation, physicalRevision, 512U) ==
        OutputHandoffStatus::Accepted);

    const std::uint64_t enforcedB = nativeAudioOutputCapturedLoopCutover(
        latestB, latestB, capturedB, true);
    REQUIRE(enforcedB == capturedB);
    const auto swap = roles.callbackLoopSwap(0U, generation, physicalRevision, true);
    REQUIRE(swap.valid());
    bool boundaryReached = false;
    if (wrappedBeforePublication) {
        // A reference never grants a different physical owner permission to cut PCM.
        REQUIRE(taggedFramesBeforeLoopBoundary(
            active, generation, token, enforcedB, 256U, boundaryReached,
            true, outgoingStart - 1, physicalRevision, nullptr, 1'200) == 256U);
        REQUIRE(!boundaryReached);
    }
    const std::size_t prefix = taggedFramesBeforeLoopBoundary(
        active,
        generation,
        token,
        enforcedB,
        256U,
        boundaryReached,
        true,
        outgoingStart - 1,
        0U,
        nullptr,
        wrappedBeforePublication ? 1'200 : -1);
    REQUIRE(boundaryReached);
    REQUIRE(prefix == (wrappedBeforePublication ? 0U : 10U));
    if (prefix != 0U) REQUIRE(active.advanceRead(active.acquireRead(prefix)));
    REQUIRE(roles.commitCallbackSwap(swap));
    roles.acknowledge(swap);
    const auto continuation = prepared.acquireRead(256U - prefix);
    REQUIRE(continuation.frameCount == 256U - prefix);
    REQUIRE(continuation.spans[0U].tags[0U].timelineFrame ==
        static_cast<std::int64_t>(a));
    REQUIRE(continuation.spans[0U].tags[0U].timelineFrame <
        static_cast<std::int64_t>(capturedB));
}

void testDormantDemandUsesUnreadTagsWhenSubmittedCursorIsPastB() {
    constexpr std::uint64_t generation = 7U;
    constexpr std::uint64_t token = 11U;
    constexpr std::uint64_t a = 100U;
    constexpr std::uint64_t b = 120U;
    constexpr std::uint64_t physicalRevision = 8U;
    constexpr std::uint64_t submittedCursor = 140U;
    static_assert(submittedCursor > b);

    SpscTaggedMultibusRingBuffer ring(64U);
    std::vector<float> payload(32U * kStemwiseMultibusStride, 0.0F);
    const RenderBusFrameTag queued = tag(105, physicalRevision, physicalRevision);
    REQUIRE(ring.writeTaggedTimeline(
        payload.data(), 32U, queued, 105.0, 137.0, false) == 32U);

    bool rangeEntered = false;
    bool boundaryReached = false;
    const std::size_t prefix = taggedFramesBeforeDormantLoopBoundary(
        ring,
        generation,
        token,
        a,
        b,
        32U,
        rangeEntered,
        boundaryReached,
        physicalRevision);
    REQUIRE(rangeEntered);
    REQUIRE(boundaryReached);
    REQUIRE(prefix == 15U);
    const auto lease = ring.acquireRead(prefix);
    REQUIRE(lease.frameCount == prefix);
    REQUIRE(lease.spans[0U].tags[0U].timelineFrame == 105);
    REQUIRE(lease.spans[0U].tags[prefix - 1U].timelineFrame == 119);
}

void testPhysicalCutoverFindsOldLoopWrapByExactRevision() {
    constexpr std::uint64_t generation = 7U;
    constexpr std::uint64_t token = 11U;
    constexpr std::uint64_t oldPhysicalRevision = 8U;
    constexpr std::uint64_t cutover = 1'200U;
    SpscTaggedMultibusRingBuffer ring(128U);
    std::vector<float> payload(32U * kStemwiseMultibusStride, 0.0F);

    // An unrelated revision may move backwards and must not satisfy coverage for C.
    const RenderBusFrameTag unrelated = tag(700, 7U, 7U);
    REQUIRE(ring.writeTaggedTimeline(
        payload.data(), 4U, unrelated, 700.0, 704.0, false) == 4U);
    const RenderBusFrameTag oldTail = tag(1'192, oldPhysicalRevision, oldPhysicalRevision);
    REQUIRE(ring.writeTaggedTimeline(
        payload.data(), 8U, oldTail, 1'192.0, 1'200.0, false) == 8U);
    const RenderBusFrameTag wrappedHead = tag(1'000, oldPhysicalRevision, oldPhysicalRevision);
    REQUIRE(ring.writeTaggedTimeline(
        payload.data(), 16U, wrappedHead, 1'000.0, 1'016.0, false) == 16U);

    bool boundaryReached = false;
    std::uint64_t boundaryRevision = 0U;
    const std::size_t prefix = taggedFramesBeforeLoopBoundary(
        ring,
        generation,
        token,
        cutover,
        28U,
        boundaryReached,
        true,
        1'191,
        oldPhysicalRevision,
        &boundaryRevision);
    REQUIRE(boundaryReached);
    REQUIRE(prefix == 12U);
    REQUIRE(boundaryRevision == oldPhysicalRevision);
}

void testManualSelectionFenceBlocksSuspendedLookupSuccessor() {
    constexpr std::uint64_t outgoingGeneration = 30U;
    constexpr std::uint64_t successorGeneration = 31U;
    constexpr std::uint64_t drainRevision = 8U;
    OutputRingRoleCoordinator roles(outgoingGeneration);

    const auto begin = roles.beginDrain(
        outgoingGeneration,
        successorGeneration,
        drainRevision);
    REQUIRE(begin.accepted());
    REQUIRE(roles.commitDrain(
        outgoingGeneration,
        successorGeneration,
        drainRevision,
        512U) == OutputHandoffStatus::Accepted);

    // Manual intent is admitted before its provider lookup. No actor work has run yet.
    roles.armAutomaticDrainFence();
    REQUIRE(roles.automaticDrainFenced());
    REQUIRE(!roles.callbackSwap(0U, true, false).valid());
    REQUIRE(roles.activeGeneration() == outgoingGeneration);

    // The existing actor-owned exact cancellation retires the stale publication.
    REQUIRE(roles.cancelDrain(
        outgoingGeneration,
        successorGeneration,
        drainRevision) == OutputDrainCancelStatus::AlreadyRequested);
    const auto cancellation = roles.callbackCancellation(0U);
    REQUIRE(cancellation.valid());
    roles.acknowledgeCancellation(cancellation);
    REQUIRE(!roles.commitAwaitingAcknowledgement());
    REQUIRE(roles.activeGeneration() == outgoingGeneration);

    // A zero/stale authorization cannot release the fence; exact new-owner authorization can.
    REQUIRE(!roles.clearAutomaticDrainFenceForAuthorizedGeneration(0U));
    REQUIRE(roles.automaticDrainFenced());
    REQUIRE(roles.clearAutomaticDrainFenceForAuthorizedGeneration(32U));
    REQUIRE(!roles.automaticDrainFenced());
}

void testManualSelectionFenceWinsAfterCallbackClaim() {
    constexpr std::uint64_t outgoingGeneration = 40U;
    constexpr std::uint64_t successorGeneration = 41U;
    constexpr std::uint64_t drainRevision = 9U;
    OutputRingRoleCoordinator roles(outgoingGeneration);
    REQUIRE(roles.beginDrain(
        outgoingGeneration,
        successorGeneration,
        drainRevision).accepted());
    REQUIRE(roles.commitDrain(
        outgoingGeneration,
        successorGeneration,
        drainRevision,
        512U) == OutputHandoffStatus::Accepted);

    // Reproduce the former race exactly: callback has returned a swap claim but has not changed
    // its physical ring/generation when synchronous manual admission closes the fence.
    const auto claimed = roles.callbackSwap(0U, true, true);
    REQUIRE(claimed.valid());
    roles.armAutomaticDrainFence();
    REQUIRE(!roles.commitCallbackSwap(claimed));
    REQUIRE(roles.activeGeneration() == outgoingGeneration);
    const auto cancellation = roles.callbackCancellation(0U);
    REQUIRE(cancellation.valid());
    roles.acknowledgeCancellation(cancellation);
    REQUIRE(roles.activeGeneration() == outgoingGeneration);
}

void testExplicitLoopFenceOwnsSuccessorRaceIndependently() {
    constexpr std::uint64_t outgoingGeneration = 50U;
    constexpr std::uint64_t successorGeneration = 51U;
    constexpr std::uint64_t drainRevision = 10U;
    OutputRingRoleCoordinator roles(outgoingGeneration);
    REQUIRE(roles.beginDrain(
        outgoingGeneration,
        successorGeneration,
        drainRevision).accepted());
    REQUIRE(roles.commitDrain(
        outgoingGeneration,
        successorGeneration,
        drainRevision,
        512U) == OutputHandoffStatus::Accepted);

    REQUIRE(!roles.setExplicitLoopDrainFence(true));
    REQUIRE(roles.automaticDrainFenced());
    REQUIRE(!roles.callbackSwap(0U, true, false).valid());
    REQUIRE(roles.cancelDrain(
        outgoingGeneration,
        successorGeneration,
        drainRevision) == OutputDrainCancelStatus::AlreadyRequested);
    const auto cancellation = roles.callbackCancellation(0U);
    REQUIRE(cancellation.valid());
    roles.acknowledgeCancellation(cancellation);
    REQUIRE(roles.activeGeneration() == outgoingGeneration);

    roles.armAutomaticDrainFence();
    REQUIRE(roles.setExplicitLoopDrainFence(false));
    REQUIRE(roles.automaticDrainFenced());
    REQUIRE(roles.clearAutomaticDrainFenceForAuthorizedGeneration(52U));
    REQUIRE(!roles.automaticDrainFenced());
}

void testUnknownLoopFenceClearsAfterExactOwnerPassesB() {
    constexpr std::uint64_t outgoingGeneration = 60U;
    constexpr std::uint64_t outgoingToken = 601U;
    constexpr std::uint64_t successorGeneration = 61U;
    constexpr std::uint64_t drainRevision = 11U;
    const NativeAudioOutputLoopPolicy dormantDemand{
        .revision = 12U,
        .generation = outgoingGeneration,
        .selectionToken = outgoingToken,
        .enabled = true,
        .physicalReady = false,
        .aTimelineFrame = 1'000U,
        .bTimelineFrameExclusive = 1'200U,
    };
    OutputRingRoleCoordinator roles(outgoingGeneration);

    // Publication is deliberately fail-closed while presentation ownership is unknown.
    REQUIRE(!roles.setExplicitLoopDrainFence(
        nativeAudioOutputLoopDemandBlocksDrain(dormantDemand, 0U, 0U, -1)));
    REQUIRE(roles.automaticDrainFenced());
    REQUIRE(roles.beginDrain(
        outgoingGeneration,
        successorGeneration,
        drainRevision).status == OutputHandoffStatus::AutomaticDrainFenced);

    // Wrong-owner evidence cannot release it, but exact current-owner PCM past B must.
    static_cast<void>(roles.setExplicitLoopDrainFence(
        nativeAudioOutputLoopDemandBlocksDrain(
            dormantDemand,
            successorGeneration,
            outgoingToken,
            1'240)));
    REQUIRE(roles.automaticDrainFenced());
    static_cast<void>(roles.setExplicitLoopDrainFence(
        nativeAudioOutputLoopDemandBlocksDrain(
            dormantDemand,
            outgoingGeneration,
            outgoingToken,
            1'240)));
    REQUIRE(!roles.automaticDrainFenced());

    // EOS can now stage and atomically adopt the automatic successor.
    REQUIRE(roles.beginDrain(
        outgoingGeneration,
        successorGeneration,
        drainRevision).accepted());
    REQUIRE(roles.commitDrain(
        outgoingGeneration,
        successorGeneration,
        drainRevision,
        512U) == OutputHandoffStatus::Accepted);
    const auto successor = roles.callbackSwap(0U, true, false);
    REQUIRE(successor.valid());
    REQUIRE(roles.commitCallbackSwap(successor));
    roles.acknowledge(successor);
    REQUIRE(roles.activeGeneration() == successorGeneration);

    NativeAudioOutputLoopPolicy armed = dormantDemand;
    armed.boundaryArmedAtPublication = true;
    REQUIRE(nativeAudioOutputLoopDemandBlocksDrain(
        armed,
        outgoingGeneration,
        outgoingToken,
        1'240));
}

void testCallbackFreeFirstHandoffMustCommitBeforeAcknowledgement() {
    constexpr std::uint64_t initialGeneration = 1U;
    constexpr std::uint64_t selectedGeneration = 4U;
    constexpr std::uint64_t loopRevision = 1U;
    OutputRingRoleCoordinator roles(initialGeneration);

    REQUIRE(roles.begin(selectedGeneration).accepted());
    REQUIRE(roles.commit(selectedGeneration, 2'304U) == OutputHandoffStatus::Accepted);
    const auto selected = roles.callbackSwap(0U);
    REQUIRE(selected.valid());

    // Acknowledgement alone is deliberately rejected: the exact swap must first reach the same
    // commit linearization point used by the audio callback.
    roles.acknowledge(selected);
    REQUIRE(roles.commitAwaitingAcknowledgement());
    REQUIRE(!roles.beginLoopCutover(selectedGeneration, loopRevision).accepted());

    REQUIRE(roles.commitCallbackSwap(selected));
    roles.acknowledge(selected);
    REQUIRE(!roles.commitAwaitingAcknowledgement());
    REQUIRE(roles.activeGeneration() == selectedGeneration);
    REQUIRE(roles.beginLoopCutover(selectedGeneration, loopRevision).accepted());
}

void testCallbackClaimPinsPreparedAForTheWholeBoundaryScan() {
    constexpr std::uint64_t generation = 50U;
    constexpr std::uint64_t loopRevision = 10U;

    OutputRingRoleCoordinator noBoundary(generation);
    const auto staged = noBoundary.beginLoopCutover(generation, loopRevision);
    REQUIRE(staged.accepted());
    REQUIRE(noBoundary.commitLoopCutover(generation, loopRevision, 512U) ==
        OutputHandoffStatus::Accepted);
    const auto claim = noBoundary.callbackLoopSwap(0U, generation, loopRevision, true);
    REQUIRE(claim.valid());
    REQUIRE(noBoundary.cancelLoopCutover(generation, loopRevision) ==
        OutputDrainCancelStatus::TooLate);
    REQUIRE(noBoundary.releaseCallbackLoopSwap(claim));
    REQUIRE(noBoundary.cancelLoopCutover(generation, loopRevision) ==
        OutputDrainCancelStatus::AcceptedOpenStage);

    OutputRingRoleCoordinator reachesBoundary(generation);
    const auto exactA = reachesBoundary.beginLoopCutover(generation, loopRevision);
    REQUIRE(exactA.accepted());
    REQUIRE(reachesBoundary.commitLoopCutover(generation, loopRevision, 512U) ==
        OutputHandoffStatus::Accepted);
    const auto boundaryClaim =
        reachesBoundary.callbackLoopSwap(0U, generation, loopRevision, true);
    REQUIRE(boundaryClaim.valid());
    REQUIRE(reachesBoundary.cancelLoopCutover(generation, loopRevision) ==
        OutputDrainCancelStatus::TooLate);
    REQUIRE(reachesBoundary.commitCallbackSwap(boundaryClaim));
    reachesBoundary.acknowledge(boundaryClaim);
    REQUIRE(!reachesBoundary.releaseCallbackLoopSwap(boundaryClaim));
    REQUIRE(reachesBoundary.activeRing() == exactA.stagingRing);
}

void testPublishedPhysicalCutoverIsImmutableUntilCallbackAcknowledgesIt() {
    constexpr std::uint64_t generation = 52U;
    constexpr std::uint64_t physicalRevision = 10U;
    OutputRingRoleCoordinator roles(generation);
    REQUIRE(roles.beginLoopCutover(generation, physicalRevision).accepted());
    REQUIRE(roles.commitLoopCutover(generation, physicalRevision, 512U) ==
        OutputHandoffStatus::Accepted);
    const auto before = roles.publishedLoopCutover(0U);
    REQUIRE(before.valid());

    REQUIRE(roles.commitLoopCutover(generation, physicalRevision, 512U) ==
        OutputHandoffStatus::AwaitingAcknowledgement);
    const auto after = roles.publishedLoopCutover(0U);
    REQUIRE(after.valid());
    REQUIRE(after.sequence == before.sequence);
    REQUIRE(after.transitionRevision == physicalRevision);
}

void testClaimedPreparedARetainsLatestBWithoutPhysicalRepublish() {
    constexpr std::uint64_t generation = 56U;
    constexpr std::uint64_t token = 11U;
    constexpr std::uint64_t physicalRevision = 10U;
    OutputRingRoleCoordinator roles(generation);
    REQUIRE(roles.beginLoopCutover(generation, physicalRevision).accepted());
    REQUIRE(roles.commitLoopCutover(generation, physicalRevision, 512U) ==
        OutputHandoffStatus::Accepted);

    NativeAudioOutputLoopPolicy demand{
        .revision = 1U,
        .generation = generation,
        .selectionToken = token,
        .enabled = true,
        .physicalReady = false,
        .aTimelineFrame = 1'000U,
        .bTimelineFrameExclusive = 1'200U,
        .continuationRevision = 1U,
    };
    NativeAudioOutputLoopPolicy physical = demand;
    physical.physicalReady = true;
    physical.cutoverTimelineFrameExclusive = demand.bTimelineFrameExclusive;
    physical.continuationTimelineFrame = demand.aTimelineFrame;
    physical.continuationRevision = physicalRevision;
    AtomicNativeAudioOutputLoopPolicy committed;
    LatestNativeAudioOutputLoopPolicyMailbox demands;
    REQUIRE(committed.publish(physical));
    REQUIRE(demands.publish(demand));

    const auto firstClaim = roles.callbackLoopSwap(
        0U, generation, physicalRevision, true);
    REQUIRE(firstClaim.valid());

    NativeAudioOutputLoopPolicy movedDemand = demand;
    movedDemand.revision = 2U;
    movedDemand.bTimelineFrameExclusive = 1'150U;
    movedDemand.continuationRevision = 2U;
    REQUIRE(demands.publish(movedDemand));
    REQUIRE(!roles.publishedLoopCutover(0U).valid());
    NativeAudioOutputLoopPolicy observedPhysical{};
    REQUIRE(committed.tryRead(observedPhysical));
    REQUIRE(!observedPhysical.realizesLogicalDemand(movedDemand));
    REQUIRE(observedPhysical.canRetargetPreparedContinuationTo(movedDemand));

    REQUIRE(roles.releaseCallbackLoopSwap(firstClaim));
    REQUIRE(roles.publishedLoopCutover(0U).valid());

    NativeAudioOutputLoopPolicy observedDemand{};
    REQUIRE(demands.readLatestForGeneration(generation, observedDemand));
    REQUIRE(committed.tryRead(observedPhysical));
    const NativeAudioOutputLoopPolicy callbackPolicy =
        bindLogicalDemandToPreparedContinuation(observedPhysical, observedDemand);
    REQUIRE(callbackPolicy.revision == movedDemand.revision);
    REQUIRE(callbackPolicy.bTimelineFrameExclusive ==
        movedDemand.bTimelineFrameExclusive);
    REQUIRE(callbackPolicy.cutoverTimelineFrameExclusive ==
        movedDemand.bTimelineFrameExclusive);
    REQUIRE(callbackPolicy.physicalRevision() == physicalRevision);
    const auto retriedClaim = roles.callbackLoopSwap(
        0U, generation, observedPhysical.physicalRevision(), true);
    REQUIRE(retriedClaim.valid());

    SpscTaggedMultibusRingBuffer active(512U);
    SpscTaggedMultibusRingBuffer prepared(512U);
    std::vector<float> activePcm(200U * kStemwiseMultibusStride, 0.0F);
    std::vector<float> preparedPcm(200U * kStemwiseMultibusStride, 0.0F);
    RenderBusFrameTag activeTag = tag(1'000);
    activeTag.generation = generation;
    RenderBusFrameTag preparedTag = tag(1'000, physicalRevision, physicalRevision);
    preparedTag.generation = generation;
    REQUIRE(active.writeTaggedTimeline(
        activePcm.data(), 200U, activeTag, 1'000.0, 1'200.0, false) == 200U);
    REQUIRE(prepared.writeTaggedTimeline(
        preparedPcm.data(), 200U, preparedTag,
        1'000.0, 1'200.0, false) == 200U);
    bool boundaryReached = false;
    const std::size_t prefix = taggedFramesBeforeLoopBoundary(
        active,
        generation,
        token,
        movedDemand.bTimelineFrameExclusive,
        512U,
        boundaryReached,
        true,
        -1,
        0U);
    REQUIRE(boundaryReached);
    REQUIRE(prefix == 150U);
    REQUIRE(roles.commitCallbackSwap(retriedClaim));
    roles.acknowledge(retriedClaim);
    const auto continuation = prepared.acquireRead(50U);
    REQUIRE(continuation.frameCount == 50U);
    REQUIRE(continuation.spans[0U].tags[0U].timelineFrame == 1'000);
    REQUIRE(continuation.spans[0U].tags[0U].loopPolicyRevision == physicalRevision);
}

void testFarBoundaryPublicationKeepsOutgoingProducerWritableUntilExactSwap() {
    constexpr std::uint64_t generation = 53U;
    constexpr std::uint64_t physicalRevision = 11U;
    OutputRingRoleCoordinator roles(generation);
    const auto staged = roles.beginLoopCutover(generation, physicalRevision);
    REQUIRE(staged.accepted());
    REQUIRE(roles.commitLoopCutover(generation, physicalRevision, 1'536U) ==
        OutputHandoffStatus::Accepted);

    const auto outgoing = roles.writableRingForGeneration(generation);
    REQUIRE(outgoing.valid());
    REQUIRE(outgoing.ring != staged.stagingRing);
    REQUIRE(!roles.callbackLoopSwap(0U, generation, physicalRevision, false).valid());
    REQUIRE(roles.activeRing() == outgoing.ring);

    const auto swap = roles.callbackLoopSwap(0U, generation, physicalRevision, true);
    REQUIRE(swap.valid());
    REQUIRE(swap.ring == staged.stagingRing);
    REQUIRE(roles.commitCallbackSwap(swap));
    roles.acknowledge(swap);
    REQUIRE(!roles.writableRingForGeneration(generation).valid());
    REQUIRE(!roles.releaseLoopCutoverWrites(generation, physicalRevision - 1U));
    REQUIRE(roles.releaseLoopCutoverWrites(generation, physicalRevision));
    const auto installed = roles.writableRingForGeneration(generation);
    REQUIRE(installed.valid());
    REQUIRE(installed.ring == staged.stagingRing);
}

void testObservedLoopCutoverCanBeSynchronouslyReplacedBeforeExactSwap() {
    constexpr std::uint64_t generation = 54U;
    constexpr std::uint64_t obsoleteRevision = 11U;
    constexpr std::uint64_t replacementRevision = 12U;
    OutputRingRoleCoordinator roles(generation);

    const auto obsoleteStage = roles.beginLoopCutover(generation, obsoleteRevision);
    REQUIRE(obsoleteStage.accepted());
    REQUIRE(roles.commitLoopCutover(generation, obsoleteRevision, 512U) ==
        OutputHandoffStatus::Accepted);
    const auto observedObsolete = roles.publishedLoopCutover(0U);
    REQUIRE(observedObsolete.valid());
    REQUIRE(observedObsolete.transitionRevision == obsoleteRevision);

    REQUIRE(roles.cancelLoopCutover(generation, obsoleteRevision) ==
        OutputDrainCancelStatus::AcceptedOpenStage);
    const auto replacementStage = roles.beginLoopCutover(generation, replacementRevision);
    REQUIRE(replacementStage.accepted());
    REQUIRE(roles.commitLoopCutover(generation, replacementRevision, 512U) ==
        OutputHandoffStatus::Accepted);
    const auto observedReplacement =
        roles.publishedLoopCutover(observedObsolete.sequence);
    REQUIRE(observedReplacement.valid());
    REQUIRE(observedReplacement.transitionRevision == replacementRevision);
    REQUIRE(!roles.callbackLoopSwap(0U, generation, obsoleteRevision, true).valid());
    REQUIRE(!roles.callbackLoopSwap(0U, generation, replacementRevision, false).valid());

    const auto outgoing = roles.writableRingForGeneration(generation);
    REQUIRE(outgoing.valid());
    REQUIRE(outgoing.ring != replacementStage.stagingRing);
    const auto replacementSwap =
        roles.callbackLoopSwap(0U, generation, replacementRevision, true);
    REQUIRE(replacementSwap.valid());
    REQUIRE(replacementSwap.ring == replacementStage.stagingRing);
    REQUIRE(roles.commitCallbackSwap(replacementSwap));
    roles.acknowledge(replacementSwap);
    REQUIRE(!roles.writableRingForGeneration(generation).valid());
    REQUIRE(!roles.releaseLoopCutoverWrites(generation, obsoleteRevision));
    REQUIRE(roles.releaseLoopCutoverWrites(generation, replacementRevision));
    const auto installed = roles.writableRingForGeneration(generation);
    REQUIRE(installed.valid());
    REQUIRE(installed.ring == replacementStage.stagingRing);
}

void testProvisionalPhysicalCutoverCannotBeClaimedByCallback() {
    constexpr std::uint64_t generation = 55U;
    constexpr std::uint64_t loopRevision = 12U;
    const NativeAudioOutputLoopPolicy provisional{
        .revision = loopRevision,
        .generation = generation,
        .selectionToken = 11U,
        .enabled = true,
        .physicalReady = false,
        .aTimelineFrame = 1'000U,
        .bTimelineFrameExclusive = 1'200U,
        .cutoverTimelineFrameExclusive = 1'200U,
        .continuationTimelineFrame = 1'000U,
        .continuationRevision = loopRevision,
    };
    REQUIRE(provisional.valid());
    AtomicNativeAudioOutputLoopPolicy mailbox;
    REQUIRE(mailbox.publish(provisional));
    NativeAudioOutputLoopPolicy observed{};
    REQUIRE(mailbox.tryRead(observed));
    REQUIRE(observed.hasPhysicalCutover());
    REQUIRE(!observed.callbackPhysicalCutoverReady());

    OutputRingRoleCoordinator preparingStage(generation);
    REQUIRE(preparingStage.beginLoopCutover(generation, loopRevision).accepted());
    REQUIRE(preparingStage.commitLoopCutover(generation, loopRevision, 512U) ==
        OutputHandoffStatus::Accepted);

    OutputRingRoleCoordinator committedStage(generation);
    const auto staged = committedStage.beginLoopCutover(generation, loopRevision);
    REQUIRE(staged.accepted());
    REQUIRE(committedStage.commitLoopCutover(generation, loopRevision, 512U) ==
        OutputHandoffStatus::Accepted);
    if (observed.callbackPhysicalCutoverReady()) {
        const auto swap = committedStage.callbackLoopSwap(0U, generation, loopRevision, true);
        if (swap.valid()) {
            REQUIRE(committedStage.commitCallbackSwap(swap));
            committedStage.acknowledge(swap);
        }
    }
    REQUIRE(committedStage.activeRing() != staged.stagingRing);
}

void testCancelledLoopCutoverCannotPromoteLater() {
    constexpr std::uint64_t generation = 60U;
    constexpr std::uint64_t loopRevision = 11U;
    OutputRingRoleCoordinator roles(generation);
    REQUIRE(roles.beginLoopCutover(generation, loopRevision).accepted());
    REQUIRE(roles.commitLoopCutover(generation, loopRevision, 512U) ==
        OutputHandoffStatus::Accepted);
    REQUIRE(roles.cancelLoopCutover(generation, loopRevision) ==
        OutputDrainCancelStatus::AcceptedOpenStage);
    REQUIRE(!roles.callbackLoopSwap(0U, generation, loopRevision, true).valid());
    REQUIRE(!roles.callbackLoopSwap(0U, generation, loopRevision, true).valid());
}

void testDirectDemandMailboxKeepsOnlyNewestPublication() {
    LatestNativeAudioOutputLoopPolicyMailbox mailbox;
    const NativeAudioOutputLoopPolicy first{
        .revision = 1U,
        .generation = 7U,
        .selectionToken = 11U,
        .enabled = true,
        .physicalReady = false,
        .aTimelineFrame = 1'000U,
        .bTimelineFrameExclusive = 1'200U,
    };
    NativeAudioOutputLoopPolicy second = first;
    second.revision = 2U;
    second.bTimelineFrameExclusive = 1'100U;
    NativeAudioOutputLoopPolicy third = second;
    third.revision = 3U;
    third.bTimelineFrameExclusive = 1'050U;
    REQUIRE(mailbox.publish(first));
    REQUIRE(mailbox.publish(second));
    REQUIRE(mailbox.publish(third));

    NativeAudioOutputLoopPolicy observed{};
    REQUIRE(mailbox.readLatest(observed));
    REQUIRE(observed == third);
    REQUIRE(!mailbox.readLatest(observed));
    REQUIRE(mailbox.publish(third));
    NativeAudioOutputLoopPolicy conflicting = third;
    conflicting.bTimelineFrameExclusive = 1'099U;
    REQUIRE(!mailbox.publish(conflicting));
    NativeAudioOutputLoopPolicy stale = first;
    REQUIRE(!mailbox.publish(stale));
}

void testDirectDemandMailboxClosesFutureGenerationSwapRace() {
    LatestNativeAudioOutputLoopPolicyMailbox mailbox;
    const NativeAudioOutputLoopPolicy first{
        .revision = 1U,
        .generation = 8U,
        .selectionToken = 13U,
        .enabled = true,
        .physicalReady = false,
        .aTimelineFrame = 1'000U,
        .bTimelineFrameExclusive = 1'200U,
    };
    NativeAudioOutputLoopPolicy observed{};
    REQUIRE(mailbox.publish(first));
    REQUIRE(!mailbox.readLatestForGeneration(7U, observed));

    // This MOVE publishes after the outgoing callback's last read but before its role swap.
    NativeAudioOutputLoopPolicy newest = first;
    newest.revision = 2U;
    newest.bTimelineFrameExclusive = 1'100U;
    REQUIRE(mailbox.publish(newest));
    REQUIRE(mailbox.readLatestForGeneration(8U, observed));
    REQUIRE(observed == newest);
    REQUIRE(!mailbox.readLatestForGeneration(8U, observed));
}

void testHigherUnrelatedPhysicalRevisionCannotRealizeDirectDemand() {
    const NativeAudioOutputLoopPolicy directDemand{
        .revision = 7U,
        .generation = 19U,
        .selectionToken = 23U,
        .enabled = true,
        .physicalReady = false,
        .aTimelineFrame = 1'000U,
        .bTimelineFrameExclusive = 1'200U,
        .continuationRevision = 7U,
        .acceptedAtMonotonicMicros = 100U,
        .publishedAtMonotonicMicros = 110U,
        .inputEventAtMonotonicMicros = 90U,
        .boundaryArmedAtPublication = true,
    };
    NativeAudioOutputLoopPolicy exactPhysical = directDemand;
    exactPhysical.physicalReady = true;
    exactPhysical.cutoverTimelineFrameExclusive = 1'200U;
    exactPhysical.continuationTimelineFrame = 1'000U;
    REQUIRE(exactPhysical.realizesLogicalDemand(directDemand));
    NativeAudioOutputLoopPolicy reusedPhysicalA = exactPhysical;
    reusedPhysicalA.continuationRevision = 6U;
    REQUIRE(reusedPhysicalA.physicalRevision() == 6U);
    REQUIRE(reusedPhysicalA.realizesLogicalDemand(directDemand));

    NativeAudioOutputLoopPolicy movedB = directDemand;
    movedB.revision = 8U;
    movedB.bTimelineFrameExclusive = 1'300U;
    movedB.acceptedAtMonotonicMicros = 120U;
    movedB.publishedAtMonotonicMicros = 130U;
    movedB.inputEventAtMonotonicMicros = 115U;
    REQUIRE(exactPhysical.canRetargetPreparedContinuationTo(movedB));
    const NativeAudioOutputLoopPolicy retargetedPhysical =
        bindLogicalDemandToPreparedContinuation(exactPhysical, movedB);
    REQUIRE(retargetedPhysical.physicalRevision() == exactPhysical.physicalRevision());
    REQUIRE(retargetedPhysical.realizesLogicalDemand(movedB));

    NativeAudioOutputLoopPolicy activePrepared = exactPhysical;
    activePrepared.cutoverSourceRevision = 6U;
    activePrepared.cutoverTimelineFrameExclusive = 1'200U;
    LatestNativeAudioOutputLoopPolicyMailbox rapidDemands;
    for (std::uint64_t revision = 8U; revision < 408U; ++revision) {
        NativeAudioOutputLoopPolicy rapidDemand = movedB;
        rapidDemand.revision = revision;
        rapidDemand.continuationRevision = revision;
        rapidDemand.bTimelineFrameExclusive = 1'050U + (revision % 551U);
        REQUIRE(rapidDemands.publish(rapidDemand));
        NativeAudioOutputLoopPolicy observedRapidDemand{};
        REQUIRE(rapidDemands.readLatestForGeneration(
            rapidDemand.generation,
            observedRapidDemand));
        const NativeAudioOutputLoopPolicy rapidBound =
            bindLogicalDemandToPreparedContinuation(
                activePrepared,
                observedRapidDemand);
        REQUIRE(rapidBound.revision == observedRapidDemand.revision);
        REQUIRE(rapidBound.bTimelineFrameExclusive ==
            rapidDemand.bTimelineFrameExclusive);
        REQUIRE(rapidBound.cutoverTimelineFrameExclusive ==
            (rapidDemand.bTimelineFrameExclusive < 1'200U
                ? rapidDemand.bTimelineFrameExclusive
                : 1'200U));
        REQUIRE(rapidBound.physicalRevision() ==
            activePrepared.physicalRevision());
    }
    // Host responsiveness is qualified separately with repeated samples; this case owns tuple semantics.
    NativeAudioOutputLoopPolicy expansionContinuation = activePrepared;
    expansionContinuation.continuationTimelineFrame = 1'200U;
    REQUIRE(expansionContinuation.canRetargetPreparedContinuationTo(movedB));
    NativeAudioOutputLoopPolicy contractionAcrossOldB = movedB;
    contractionAcrossOldB.bTimelineFrameExclusive = 1'100U;
    REQUIRE(!expansionContinuation.canRetargetPreparedContinuationTo(
        contractionAcrossOldB));

    NativeAudioOutputLoopPolicy dormantAHead = activePrepared;
    dormantAHead.nextRevisionOnly = true;
    REQUIRE(!dormantAHead.realizesLogicalDemand(directDemand));
    REQUIRE(dormantAHead.canRetargetPreparedContinuationTo(contractionAcrossOldB));
    REQUIRE(!dormantAHead.canRetargetPreparedContinuationTo(movedB));
    const NativeAudioOutputLoopPolicy boundDormantAHead =
        bindLogicalDemandToPreparedContinuation(dormantAHead, contractionAcrossOldB);
    REQUIRE(!boundDormantAHead.nextRevisionOnly);
    REQUIRE(boundDormantAHead.revision == contractionAcrossOldB.revision);

    NativeAudioOutputLoopPolicy movedA = movedB;
    movedA.revision = 9U;
    movedA.aTimelineFrame = 1'010U;
    REQUIRE(!exactPhysical.canRetargetPreparedContinuationTo(movedA));

    NativeAudioOutputLoopPolicy unrelatedLegacy = exactPhysical;
    unrelatedLegacy.revision = 700U;
    unrelatedLegacy.continuationRevision = 700U;
    REQUIRE(unrelatedLegacy.revision > directDemand.revision);
    REQUIRE(!unrelatedLegacy.realizesLogicalDemand(directDemand));

    NativeAudioOutputLoopPolicy staleCoordinates = exactPhysical;
    staleCoordinates.bTimelineFrameExclusive = 1'300U;
    REQUIRE(!staleCoordinates.realizesLogicalDemand(directDemand));

    NativeAudioOutputLoopPolicy nextCycle = exactPhysical;
    nextCycle.cutoverSourceRevision = exactPhysical.physicalRevision();
    nextCycle.continuationRevision = exactPhysical.physicalRevision() + 1U;
    REQUIRE(nextCycle.isPhysicalCycleSuccessorOf(exactPhysical));
    AtomicNativeAudioOutputLoopPolicy physicalMailbox;
    REQUIRE(physicalMailbox.publish(exactPhysical));
    REQUIRE(physicalMailbox.publish(nextCycle));
    NativeAudioOutputLoopPolicy observed{};
    REQUIRE(physicalMailbox.tryRead(observed));
    REQUIRE(observed == nextCycle);
}

} // namespace

int main() {
    testCancelledPublicationCannotStrandReplacementClaim();
    testStaleDrainCancellationCannotCancelReplacement();
    testLogicalDemandNeverOwnsPcmBeforePhysicalReadiness();
    testLogicalDemandNeverBlocksEndWithoutExactPreparedA();
    testArmedPreparedAOwnsAnExactEmptyTerminalStream();
    testDormantPastDemandNeverFencesDrain();
    testCrossedBRemainsTheNextCutoverWhileVisibleBMovesAhead();
    testHeldBRearmsOnlyAfterExactPhysicalCutoverProof();
    testCallbackFreeFirstHandoffMustCommitBeforeAcknowledgement();
    testExactPreparedRoleWrapsBToAInSameCallback();
    testPreparedCutoverUsesTheLastOutgoingFramesMappedEnd();
    testFastBCrossingSurvivesAStillHeldMoveBackAhead();
    testFastBCrossingSurvivesAStillHeldMoveBackAhead(true);
    testDormantDemandUsesUnreadTagsWhenSubmittedCursorIsPastB();
    testPhysicalCutoverFindsOldLoopWrapByExactRevision();
    testManualSelectionFenceBlocksSuspendedLookupSuccessor();
    testManualSelectionFenceWinsAfterCallbackClaim();
    testExplicitLoopFenceOwnsSuccessorRaceIndependently();
    testUnknownLoopFenceClearsAfterExactOwnerPassesB();
    testCallbackClaimPinsPreparedAForTheWholeBoundaryScan();
    testPublishedPhysicalCutoverIsImmutableUntilCallbackAcknowledgesIt();
    testClaimedPreparedARetainsLatestBWithoutPhysicalRepublish();
    testFarBoundaryPublicationKeepsOutgoingProducerWritableUntilExactSwap();
    testObservedLoopCutoverCanBeSynchronouslyReplacedBeforeExactSwap();
    testProvisionalPhysicalCutoverCannotBeClaimedByCallback();
    testCancelledLoopCutoverCannotPromoteLater();
    testDirectDemandMailboxKeepsOnlyNewestPublication();
    testDirectDemandMailboxClosesFutureGenerationSwapRace();
    testHigherUnrelatedPhysicalRevisionCannotRealizeDirectDemand();
    std::puts("NativeAudioOutputLoopBoundaryGate: loop cutover and manual fence PASS");
    return EXIT_SUCCESS;
}
