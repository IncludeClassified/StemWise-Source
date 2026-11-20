#pragma once

#include "LoopHeadCache.h"

#include <cstdint>

namespace stemwise::audio::engine {

struct LoopCycleOwner final {
    std::uint64_t selectionToken{0};
    std::uint64_t renderGeneration{0};

    [[nodiscard]] constexpr bool operator==(const LoopCycleOwner &) const noexcept = default;
    [[nodiscard]] constexpr bool valid() const noexcept {
        return selectionToken != 0U && renderGeneration != 0U;
    }
};

/**
 * revision is the monotonic managed-loop command revision. cacheKey.loopRevision independently
 * versions immutable decoded content, allowing Off/On and source-policy changes to reuse an exact
 * committed head. A user seek may rebind the same command to a newer renderGeneration without
 * invalidating its content-addressed head cache.
 */
struct LoopCycleIntent final {
    std::uint64_t revision{0};
    bool enabled{false};
    LoopCycleOwner owner{};
    LoopHeadContentKey cacheKey{};
    std::uint32_t requiredLaneMask{0};
    std::uint32_t declickFrames{0};
    LoopHeadBoundaryMode boundaryMode{LoopHeadBoundaryMode::OriginalEos};
};

enum class LoopCycleMode : std::uint8_t {
    Disabled,
    CacheOnly,
    Streamed,
};

enum class LoopCyclePhase : std::uint8_t {
    Disabled,
    WaitingForCache,
    WaitingForSafeWindow,
    WaitingForIngress,
    AwaitingRenderCommit,
    AwaitingCallbackProof,
    Active,
    DeadlineFallback,
    /** The four-second runway is ready, but decoded Original EOS is not known yet. */
    WaitingForBoundary,
};

enum class LoopCycleActivation : std::uint8_t {
    None,
    WarmCurrentPass,
    ColdAtLoopStart,
};

enum class LoopCycleSubmitStatus : std::uint8_t {
    Accepted,
    AcceptedDisabled,
    Unchanged,
    OwnershipRebound,
    StaleRevision,
    RevisionCollision,
    InvalidIntent,
};

enum class LoopCycleCacheStatus : std::uint8_t {
    Accepted,
    AlreadyAccepted,
    NotWaiting,
    StaleRevision,
    KeyMismatch,
    InvalidSnapshot,
};

enum class LoopCycleIngressStatus : std::uint8_t {
    Accepted,
    StaleRevision,
    NoRequest,
    UnexpectedRequest,
    StaleOwner,
    StaleCycle,
    UnexpectedEpoch,
    MissingRequiredLanes,
    InvalidCoverage,
};

enum class LoopCycleBoundaryStatus : std::uint8_t {
    DirectivePublished,
    NotActive,
    DirectiveAlreadyPending,
    StaleRevision,
    StaleOwner,
    StaleCycle,
    StaleBoundary,
    UnexpectedIngressEpoch,
    InvalidBoundary,
    CycleOverflow,
};

enum class LoopCycleDirectiveAction : std::uint8_t {
    None,
    Clear,
    InstallWarmCacheOnly,
    InstallWarmStreamed,
    ActivateColdCacheOnly,
    ActivateColdStreamed,
    WrapCacheOnly,
    WrapStreamed,
    DeadlineFallback,
};

struct LoopCycleIngressRequest final {
    bool valid{false};
    std::uint64_t sequence{0};
    std::uint64_t loopRevision{0};
    std::uint64_t targetCycle{0};
    LoopCycleOwner owner{};
    std::uint64_t ingressEpoch{0};
    std::uint64_t targetTimelineFrame{0};
    std::uint64_t requiredEndTimelineFrameExclusive{0};
    std::uint32_t requiredLaneMask{0};
    LoopCycleActivation activation{LoopCycleActivation::None};
};

/** Exact render-side observation; sequences are monotonic within one revision/owner binding. */
struct LoopCyclePositionEvidence final {
    std::uint64_t sequence{0};
    std::uint64_t loopRevision{0};
    std::uint64_t cycle{0};
    LoopCycleOwner owner{};
    std::uint64_t sourceTimelineFrame{0};
    std::uint64_t currentIngressEpoch{0};
    bool tailRetainedThroughEnd{false};
};

enum class LoopCyclePositionStatus : std::uint8_t {
    Observed,
    IngressRequested,
    DirectivePublished,
    Disabled,
    StaleRevision,
    StaleOwner,
    StaleCycle,
    StaleObservation,
    UnexpectedIngressEpoch,
    CycleOverflow,
};

/** Decoder-ready proof must echo the complete request identity, not only its reusable epoch. */
struct LoopCycleIngressReadyEvidence final {
    std::uint64_t requestSequence{0};
    std::uint64_t loopRevision{0};
    std::uint64_t targetCycle{0};
    LoopCycleOwner owner{};
    std::uint64_t ingressEpoch{0};
    std::uint32_t readyLaneMask{0};
    /**
     * Required non-Original lanes whose exact EOS was published for this ingress epoch before
     * requiredEndTimelineFrameExclusive. Those lanes are logically covered by native zero-padding
     * after their EOS; the runtime validates every claimed bit against LaneIngress before this
     * evidence reaches the state machine.
     */
    std::uint32_t eosPaddedLaneMask{0};
    std::uint64_t firstTimelineFrame{0};
    std::uint64_t endTimelineFrameExclusive{0};
};

/** Exact render-side B event. Duplicate or delayed boundaries cannot advance a cycle. */
struct LoopCycleBoundaryEvidence final {
    std::uint64_t sequence{0};
    std::uint64_t loopRevision{0};
    std::uint64_t cycle{0};
    LoopCycleOwner owner{};
    std::uint64_t ingressEpoch{0};
    std::uint64_t sourceTimelineFrame{0};
};

/** Exact decoder/pipeline evidence for Repeat One's semantic endpoint. */
struct LoopCycleOriginalEosEvidence final {
    std::uint64_t loopRevision{0};
    LoopCycleOwner owner{};
    std::uint64_t ingressEpoch{0};
    std::uint64_t endTimelineFrameExclusive{0};
};

enum class LoopCycleOriginalEosStatus : std::uint8_t {
    Accepted,
    AlreadyResolved,
    NotRequired,
    NotReady,
    StaleRevision,
    StaleOwner,
    UnexpectedIngressEpoch,
    InvalidBoundary,
    ConflictingBoundary,
};

struct LoopCycleDirective final {
    bool valid{false};
    std::uint64_t sequence{0};
    LoopCycleDirectiveAction action{LoopCycleDirectiveAction::None};
    /** Monotonic policy/protocol revision used by boundary and callback evidence. */
    std::uint64_t loopRevision{0};
    /** Immutable decoded-head identity; deliberately independent from the policy revision. */
    std::uint64_t cacheRevision{0};
    std::uint64_t cycle{0};
    LoopCycleOwner owner{};
    std::uint64_t ingressEpoch{0};
    std::uint64_t startTimelineFrame{0};
    std::uint64_t endTimelineFrameExclusive{0};
    std::uint64_t headEndTimelineFrameExclusive{0};
    std::uint64_t cacheChecksum{0};
    std::uint32_t requiredLaneMask{0};
    std::uint32_t declickFrames{0};
    LoopHeadBoundaryMode boundaryMode{LoopHeadBoundaryMode::Unresolved};
};

enum class LoopCycleRenderCommitStatus : std::uint8_t {
    Accepted,
    NoDirective,
    StaleSequence,
};

enum class LoopCycleCallbackStatus : std::uint8_t {
    Accepted,
    AlreadyProven,
    NoActiveLoop,
    StaleRevision,
    StaleOwner,
    StaleCycle,
    StaleCallback,
    /**
     * One exact callback from the physical renderer replaced by a newer loop policy. The output
     * sequence is consumed, but this expected ring-drain evidence is not a current-policy fault.
     */
    SupersededPhysicalPolicy,
};

struct LoopCycleAudibleEvidence final {
    bool valid{false};
    std::uint64_t sequence{0};
    std::uint64_t loopRevision{0};
    std::uint64_t cycle{0};
    LoopCycleOwner owner{};
    std::uint64_t callbackSequence{0};
};

struct LoopCycleStateSnapshot final {
    LoopCycleMode mode{LoopCycleMode::Disabled};
    LoopCyclePhase phase{LoopCyclePhase::Disabled};
    LoopCycleActivation activation{LoopCycleActivation::None};
    std::uint64_t latestRevision{0};
    LoopCycleOwner owner{};
    std::uint64_t renderCycle{0};
    std::uint64_t activeIngressEpoch{0};
    std::uint64_t preparedIngressEpoch{0};
    std::uint64_t cacheChecksum{0};
    bool cacheAccepted{false};
    bool renderInstalled{false};
    /** True only after exact whole-callback proof for the current revision/owner/cycle. */
    bool renderActive{false};
    bool audiblyProven{false};
    bool ingressRequestPending{false};
    bool renderDirectivePending{false};
    LoopHeadBoundaryMode boundaryMode{LoopHeadBoundaryMode::OriginalEos};
    bool boundaryResolved{false};
    std::uint64_t resolvedLoopEndTimelineFrameExclusive{0};
};

struct LoopCycleDiagnostics final {
    std::uint64_t acceptedIntents{0};
    std::uint64_t unchangedIntents{0};
    std::uint64_t ownershipRebinds{0};
    std::uint64_t staleIntents{0};
    std::uint64_t revisionCollisions{0};
    std::uint64_t cachesAccepted{0};
    std::uint64_t cacheRejections{0};
    std::uint64_t ingressRequests{0};
    std::uint64_t ingressAcks{0};
    std::uint64_t ingressAckRejections{0};
    std::uint64_t positionObservations{0};
    std::uint64_t rejectedPositionObservations{0};
    std::uint64_t boundaryEvents{0};
    std::uint64_t rejectedBoundaryEvents{0};
    std::uint64_t renderDirectives{0};
    std::uint64_t renderCommits{0};
    std::uint64_t cacheOnlyWraps{0};
    std::uint64_t streamedWraps{0};
    std::uint64_t deadlineFallbacks{0};
    std::uint64_t callbackProofs{0};
    std::uint64_t rejectedCallbackProofs{0};
    std::uint64_t originalEosAccepted{0};
    std::uint64_t originalEosRejected{0};
};

/**
 * Thread-confined, allocation-free loop protocol state machine.
 *
 * It intentionally separates immutable public/render ownership from decoder ingress epochs.
 * Automatic wraps never mutate owner. Streamed cycles request one strictly newer ingress epoch
 * whose decoders target the first frame after the fixed cache head. The caller may authorize those
 * decoders while the old epoch's retained tail finishes; future ingress remains parked until the
 * exact wrap directive commits. No state is audibly proven until observeCallback() receives the
 * matching loop revision and cycle from one complete device callback.
 */
class LoopCycleStateMachine final {
public:
    static constexpr std::uint32_t kDefaultMinimumBodyPrimeFrames = 4'800U;

    explicit LoopCycleStateMachine(
        std::uint32_t minimumBodyPrimeFrames = kDefaultMinimumBodyPrimeFrames) noexcept;

    [[nodiscard]] LoopCycleSubmitStatus submit(const LoopCycleIntent &intent) noexcept;
    /** Exact physical renderer retirement preserves its accepted intent, head and resolved end. */
    [[nodiscard]] bool retireRenderer(std::uint64_t loopRevision, LoopCycleOwner owner) noexcept;
    [[nodiscard]] LoopCycleCacheStatus acceptCache(
        const LoopHeadCacheSnapshot &snapshot,
        std::uint64_t playbackEndFrameExclusive = 0U) noexcept;

    /** Resolves natural playback end from decoded EOF or an exact owner-bound window, never a duration estimate. */
    [[nodiscard]] LoopCycleOriginalEosStatus resolveOriginalEos(
        const LoopCycleOriginalEosEvidence &evidence) noexcept;

    /**
     * Supplies one render-boundary position. tailRetainedThroughEnd means every currently audible
     * lane can finish the old ingress epoch through loop B after its decoder is redirected.
     */
    [[nodiscard]] LoopCyclePositionStatus observePosition(
        const LoopCyclePositionEvidence &evidence) noexcept;

    [[nodiscard]] LoopCycleIngressStatus acknowledgeIngressReady(
        const LoopCycleIngressReadyEvidence &evidence) noexcept;

    /** Called at exact B only after the installed render loop reaches its boundary. */
    [[nodiscard]] LoopCycleBoundaryStatus onLoopBoundary(
        const LoopCycleBoundaryEvidence &evidence) noexcept;

    /** Called only after the render pipeline accepted the exact pending directive. */
    [[nodiscard]] LoopCycleRenderCommitStatus commitRenderDirective(
        std::uint64_t directiveSequence) noexcept;

    /** Exact whole-callback proof; older revision/cycle callbacks can never roll state back. */
    [[nodiscard]] LoopCycleCallbackStatus observeCallback(
        std::uint64_t loopRevision,
        std::uint64_t cycle,
        LoopCycleOwner owner,
        std::uint64_t callbackSequence) noexcept;

    [[nodiscard]] LoopCycleIngressRequest ingressRequest() const noexcept;
    [[nodiscard]] LoopCycleDirective renderDirective() const noexcept;
    [[nodiscard]] LoopCycleAudibleEvidence audibleEvidence() const noexcept;
    /**
     * True only when this exact owner still has an authoritative managed loop that must prevent
     * natural song-end publication. Any transport parked outside a fixed range temporarily returns
     * false until it re-enters [A, B).
     */
    [[nodiscard]] bool blocksNaturalEnd(
        std::uint64_t loopRevision,
        const LoopCycleOwner &owner) const noexcept;
    [[nodiscard]] LoopCycleStateSnapshot snapshot() const noexcept;
    [[nodiscard]] LoopCycleDiagnostics diagnostics() const noexcept;

private:
    [[nodiscard]] bool validIntent(const LoopCycleIntent &intent) const noexcept;
    [[nodiscard]] bool sameStableIdentity(const LoopCycleIntent &intent) const noexcept;
    [[nodiscard]] std::uint64_t loopStart() const noexcept;
    [[nodiscard]] std::uint64_t loopEnd() const noexcept;
    [[nodiscard]] std::uint64_t headEnd() const noexcept;
    [[nodiscard]] std::uint64_t deadlineFrame() const noexcept;
    [[nodiscard]] std::uint64_t requiredBodyEnd() const noexcept;
    [[nodiscard]] bool publishIngressRequest(
        std::uint64_t currentIngressEpoch,
        LoopCycleActivation activation) noexcept;
    [[nodiscard]] bool publishDirective(
        LoopCycleDirectiveAction action,
        std::uint64_t cycle,
        std::uint64_t ingressEpoch) noexcept;
    [[nodiscard]] bool publishDeadlineFallback() noexcept;
    [[nodiscard]] bool nextRenderCycle(std::uint64_t &cycle) const noexcept;
    [[nodiscard]] bool currentCycleAudiblyProven() const noexcept;
    [[nodiscard]] LoopCyclePhase installedSteadyPhase() const noexcept;
    void rememberPhysicalCallbackForRetirement() noexcept;
    void resetForIntent(const LoopCycleIntent &intent) noexcept;
    void clearTransientProtocol() noexcept;

    struct SupersededPhysicalCallbackIdentity final {
        bool valid{false};
        std::uint64_t loopRevision{0};
        std::uint64_t cycle{0};
        LoopCycleOwner owner{};
    };

    std::uint32_t mMinimumBodyPrimeFrames{0};
    LoopCycleIntent mIntent{};
    LoopCycleMode mMode{LoopCycleMode::Disabled};
    LoopCyclePhase mPhase{LoopCyclePhase::Disabled};
    LoopCyclePhase mPhaseAfterClear{LoopCyclePhase::Disabled};
    LoopCycleActivation mActivation{LoopCycleActivation::None};
    std::uint64_t mLatestRevision{0};
    std::uint64_t mCacheChecksum{0};
    std::uint64_t mResolvedLoopEnd{0};
    std::uint64_t mRenderCycle{0};
    /** First cycle physically installed for the current revision/owner binding. */
    std::uint64_t mFirstInstalledCycle{0};
    std::uint64_t mActiveIngressEpoch{0};
    std::uint64_t mPreparedIngressEpoch{0};
    std::uint64_t mPreparedTargetCycle{0};
    std::uint64_t mIngressRequestSequence{0};
    std::uint64_t mDirectiveSequence{0};
    std::uint64_t mAudibleEvidenceSequence{0};
    std::uint64_t mLastPositionSequence{0};
    std::uint64_t mLastBoundarySequence{0};
    std::uint64_t mLastCallbackSequence{0};
    bool mCacheAccepted{false};
    bool mBoundaryResolved{false};
    bool mRenderInstalled{false};
    /**
     * Callback tuples can remain queued for the renderer retired at an atomic replacement
     * boundary. They are kept separate from current callback sequencing until the replacement is
     * audibly proven, so consuming them cannot prove, reject, or roll back the new revision.
     */
    SupersededPhysicalCallbackIdentity mSupersededPhysicalCallback{};
    LoopCycleIngressRequest mIngressRequest{};
    LoopCycleDirective mDirective{};
    LoopCycleAudibleEvidence mAudibleEvidence{};
    LoopCycleDiagnostics mDiagnostics{};
};

}  // namespace stemwise::audio::engine
