#pragma once

#include "LoopCycleStateMachine.h"
#include "StemwiseMixer.h"
#include "TimelineStereoBuffer.h"
#include "TimePitchProcessor.h"
#include "TimePitchTransition.h"
#include "../NativePlaybackLimits.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <memory>

namespace stemwise::audio::engine {

class CurrentSongPcmCache;
struct CurrentSongCacheKey;

/** Original is named; selectable parts use their validated declared numeric index. */
enum class SongRenderLane : std::uint8_t { Original = 0 };

inline constexpr std::size_t kSongRenderLaneCount = kStemwiseMusicBusCount;
static_assert(kSongRenderLaneCount == kMixerLaneCount);
static_assert(kSongRenderLaneCount == kLoopHeadLaneCount);
static_assert(static_cast<std::uint8_t>(SongRenderLane::Original) == 0);

struct SongRenderOwnership final {
    std::uint64_t selectionToken{0};
    std::uint64_t generation{0};
};

enum class SongRenderResetStatus : std::uint8_t {
    Ok,
    InvalidOwnership,
    StaleGeneration,
    InvalidTimeline,
};

enum class SongRenderEosStatus : std::uint8_t {
    Accepted,
    InvalidLane,
    InvalidOwnership,
    StaleGeneration,
    ConflictingEndFrame,
};

enum class SongRenderControlStatus : std::uint8_t {
    Accepted,
    StaleRevision,
    InvalidRevision,
    InvalidValues,
};

enum class SongRenderStemGainPreviewStatus : std::uint8_t {
    Accepted,
    InvalidOwnership,
    StaleSequence,
    InvalidValues,
};

enum class SongRenderLoopStatus : std::uint8_t {
    Accepted,
    Disabled,
    InvalidOwnership,
    StaleGeneration,
    InvalidRange,
    RangeNotRetained,
    Preparing,
};

enum class SongRenderLoopDirectiveStatus : std::uint8_t {
    Accepted,
    Cleared,
    InvalidDirective,
    InvalidOwnership,
    StaleDirective,
    CacheUnavailable,
    CacheMismatch,
    BoundaryNotPending,
    BoundaryMismatch,
};

enum class SongRenderStatus : std::uint8_t {
    Rendered,
    RenderedTuneDeferred,
    /** Exact loop B was reached; no PCM is produced until one fenced wrap/fallback directive. */
    LoopBoundaryPending,
    EndOfStream,
    NeedOriginalData,
    NeedStemData,
    InvalidOwnership,
    StaleGeneration,
    InvalidArgument,
    TimelineOverflow,
    MixerFailure,
    TimePitchFailure,
    /** Bounded spectral pre-roll advanced; no source/output frames have been consumed. */
    PreparingTune,
};

/**
 * One user/control-actor revision. The revision is authoritative at the next render boundary.
 * The pipeline replaces mixer.revision with its own monotonic internal revision because a single
 * user request may first be safely held on Original, then promoted to Stems when all declared lanes
 * have the required aligned timeline.
 */
struct SongRenderControlSnapshot final {
    std::uint64_t revision{0};
    /**
     * Internal execution ordering for repeated applications of one canonical user revision.
     * Must be positive and increase strictly across submissions; permits revision to equal the already
     * accepted canonical revision. This value is never exposed as audible control evidence.
     */
    std::uint64_t applicationEpoch{0};
    /** Package/source identity changes retire any resident high-frequency gain overlay. */
    std::uint64_t sourceIntentRevision{0};
    std::uint64_t stemIntentRevision{0};
    MixerControlSnapshot mixer{};
    float tempo{1.0F};
    float pitchSemitones{0.0F};
};

struct SongRenderPipelineConfiguration final {
    static constexpr std::uint32_t kRequiredSampleRateHz = 48'000;
    static constexpr std::uint32_t kMaximumSupportedBlockFrames = 480;

    std::uint32_t sampleRateHz{kRequiredSampleRateHz};
    std::uint32_t maximumBlockFrames{kMaximumSupportedBlockFrames};
    std::size_t laneReservoirCapacityFrames{131'072};
    std::uint32_t tuneTransitionFrames{512};
    std::uint32_t maximumBeatGridPoints{kMaximumNativeBeatGridPoints};
    std::uint32_t stemLaneCount{0U};
    bool diagnosticPayloadFingerprints{false};
};

/** Evidence for the rendered buses, before callback gains, ramps, mixing and limiting. */
struct RenderPayloadFingerprint final {
    std::uint64_t sequence{0};
    std::uint64_t selectionToken{0};
    std::uint64_t generation{0};
    std::int64_t outputClockStartFrame{0};
    SourceTimelineInterval sourceTimeline{};
    std::uint32_t frameCount{0};
    std::uint64_t hash{0};
};

/**
 * Complete callback-authoritative target represented by one production multibus block.
 *
 * The pipeline owns this policy decision: values are the exact sanitized controls accepted by
 * StemwiseMixer after safe-source fallback, canonical mute/solo, and the admitted latest-only
 * gain overlay. The output callback owns only bounded ramps and final mixing; it never rebuilds
 * user policy from separate fields.
 */
struct SongRenderLateMixEndpoint final {
    std::uint64_t canonicalControlRevision{0U};
    std::uint64_t sourceIntentRevision{0U};
    std::uint64_t stemIntentRevision{0U};
    MixerSourceMode sourceMode{MixerSourceMode::Original};
    std::uint32_t requiredStemMask{0U};
    std::array<float, kStemLaneCount> effectiveStemGains{};
    float musicGain{1.0F};
    float mixHeadroom{0.9F};
    std::uint32_t sourceRampFrames{240U};
    std::uint32_t laneRampFrames{240U};
    std::uint32_t masterRampFrames{240U};
    std::uint64_t previewPublicationSequence{0U};
    std::uint64_t previewCoversOfferSequence{0U};
    std::uint32_t previewActiveMask{0U};
    /** Raw admitted preview gains retained separately from effective mute/solo mixer gains. */
    std::array<float, kStemLaneCount> previewGains{};

    [[nodiscard]] bool valid() const noexcept;
};

struct SongRenderResult final {
    SongRenderStatus status{SongRenderStatus::InvalidArgument};
    std::uint32_t framesProduced{0};
    std::uint32_t framesRequested{0};
    SongRenderOwnership ownership{};
    std::int64_t outputClockStartFrame{0};
    SourceTimelineInterval sourceTimeline{};
    RenderPayloadFingerprint payloadFingerprint{};
    /** Exact user/control-actor revision represented by this render payload. */
    std::uint64_t controlRevision{0};
    /** Exact latest-only gain overlay represented by this render payload. */
    std::uint64_t stemGainPreviewPublicationSequence{0};
    std::uint64_t stemGainPreviewCoversOfferSequence{0};
    std::uint32_t stemGainPreviewActiveMask{0};
    std::array<float, kStemLaneCount> stemGainPreviewGains{};
    /** Exact processed stem buses present in a multibus render payload. */
    std::uint32_t stemBusReadyMask{0U};
    /** Exact endpoint applied later by the callback-owned final mixer. */
    SongRenderLateMixEndpoint lateMixEndpoint{};
    /** Numerical DSP tuple represented by this payload; zero tempo means transition/unknown. */
    float actualTempo{0.0F};
    float actualPitchSemitones{0.0F};
    bool tuningProcessed{false};
    /** Exact managed-loop tuple carried by every render-payload frame in this block. */
    std::uint64_t loopRevision{0};
    std::uint64_t loopCycle{0};
    bool loopEnabled{false};
    bool loopWrappedBeforeBlock{false};
    bool loopBoundaryPending{false};
    bool endOfStreamAfterBlock{false};
};

struct SongRenderLoopCycleSnapshot final {
    bool installed{false};
    bool cacheOnly{false};
    bool boundaryPending{false};
    std::uint64_t lastDirectiveSequence{0};
    std::uint64_t loopRevision{0};
    std::uint64_t cycle{0};
    std::uint64_t ingressEpoch{0};
    std::uint64_t startTimelineFrame{0};
    std::uint64_t endTimelineFrameExclusive{0};
    std::uint64_t headEndTimelineFrameExclusive{0};
    std::uint64_t cacheChecksum{0};
    std::uint32_t requiredLaneMask{0};
};

/** Exact lane EOS retained for the current ingress epoch. */
struct SongRenderLaneEndSnapshot final {
    bool marked{false};
    std::uint64_t ingressEpoch{0};
    std::uint64_t endTimelineFrameExclusive{0};
};

struct SongRenderPipelineDiagnostics final {
    std::uint64_t beginSongCount{0};
    std::uint64_t seekCount{0};
    std::uint64_t staleOwnershipRequests{0};
    std::uint64_t appendCalls{0};
    std::uint64_t staleAppendCalls{0};
    std::uint64_t eosMarkersAccepted{0};
    std::uint64_t controlSnapshotsAccepted{0};
    std::uint64_t controlSnapshotsRejected{0};
    std::uint64_t mixerSnapshotsApplied{0};
    /** Render-boundary applications of canonical tempo/pitch state, including neutral state. */
    std::uint64_t tuneSnapshotsApplied{0};
    std::uint64_t stemActivationDeferrals{0};
    std::uint64_t stemSafetyFallbacks{0};
    std::uint64_t tuneActivationDeferrals{0};
    std::uint64_t tuneActivations{0};
    std::uint64_t tunePrimes{0};
    std::uint64_t renderCalls{0};
    std::uint64_t renderedBlocks{0};
    std::uint64_t renderedFrames{0};
    std::uint64_t directVirginFrames{0};
    std::uint64_t processedFrames{0};
    std::uint64_t needOriginalDataCount{0};
    std::uint64_t needStemDataCount{0};
    std::uint64_t loopWraps{0};
    std::uint64_t loopDirectivesAccepted{0};
    std::uint64_t loopDirectivesRejected{0};
    std::uint64_t loopCacheFramesCopied{0};
    std::uint64_t loopBodyFramesCopied{0};
    std::uint64_t loopBoundaryEvents{0};
    std::uint64_t loopBoundaryWaits{0};
    std::uint64_t endOfStreamCount{0};
    std::uint64_t renderPayloadFingerprintsCompleted{0};
    std::uint64_t nonFiniteRenderPayloadSamples{0};
    std::uint64_t internalFailures{0};
};

/**
 * Thread-confined render pipeline for exactly one resident song bank.
 *
 * Ownership and real-time contract:
 * - Construct/destroy on a setup thread. Every other method is called by one render worker.
 * - appendLanePcm() receives already-resampled, interleaved stereo float PCM from the render-side
 *   drain of LaneIngress. Decoder ingress epoch is independent from public/render ownership: a
 *   normal selection/seek may assign equal values, while an automatic loop cycle advances only the
 *   ingress epoch and keeps render ownership stable.
 * - Lane-count configuration is a worker setup operation. Rendering performs no allocation, locks, JNI or logging; an attached PCM cache can perform bounded reads.
 * - renderMultibus() is the only render path. Its mandatory order is PRE mixer -> TimePitch ->
 *   POST mixer -> exact whole-block payload fingerprint.
 * - A Stems target is not applied until all declared stem reservoirs cover every source frame required
 *   for that render. Missing live stems target Original; callback ramps determine the emitted transition.
 * - Neutral tuning uses the zero-DSP-latency direct path. Returning from processed tuning
 *   crosses the owned transition before restoring exact direct PCM.
 */
struct SongRenderPreparedLanes final {
    SongRenderPipelineConfiguration configuration{};
    std::array<std::unique_ptr<TimelineStereoBuffer>, kSongRenderLaneCount> reservoirs{};
};

class SongRenderPipeline final {
public:
    explicit SongRenderPipeline(const SongRenderPipelineConfiguration &configuration);
    /** Worker setup operation; allocates only declared lane reservoirs. */
    [[nodiscard]] bool configureStemLaneCount(std::size_t count) noexcept;
    [[nodiscard]] bool awaitingCacheRead() const noexcept;
    [[nodiscard]] static std::unique_ptr<SongRenderPreparedLanes> prepareStemLaneCount(
        const SongRenderPipelineConfiguration &configuration, std::size_t count);
    /** Exchanges only prepared resources; the returned old resources remain in prepared. */
    [[nodiscard]] bool adoptPreparedStemLaneCount(SongRenderPreparedLanes &prepared) noexcept;
    [[nodiscard]] std::size_t stemLaneCount() const noexcept;
    ~SongRenderPipeline();

    /** Only after this producer's output writes retire; preserves its prepared DSP resources. */
    void releaseRetiredLoopHeadReaders() noexcept;
    [[nodiscard]] std::uint32_t boundedLoopHeadLaneMask() const noexcept;

    SongRenderPipeline(const SongRenderPipeline &) = delete;
    SongRenderPipeline &operator=(const SongRenderPipeline &) = delete;
    SongRenderPipeline(SongRenderPipeline &&) = delete;
    SongRenderPipeline &operator=(SongRenderPipeline &&) = delete;

    [[nodiscard]] SongRenderResetStatus beginSong(
        SongRenderOwnership ownership,
        std::uint64_t ingressEpoch,
        std::uint64_t firstTimelineFrame,
        std::int64_t outputClockStartFrame,
        std::uint64_t playbackEndFrameExclusive = 0U) noexcept;


    /** A seek requires one strictly newer generation and preserves this song's tuning mode. */
    [[nodiscard]] SongRenderResetStatus seek(
        SongRenderOwnership ownership,
        std::uint64_t ingressEpoch,
        std::uint64_t firstTimelineFrame,
        std::int64_t outputClockStartFrame,
        std::uint64_t playbackEndFrameExclusive = 0U) noexcept;


    [[nodiscard]] TimelineAppendResult appendLanePcm(
        SongRenderLane lane,
        std::uint64_t generation,
        std::uint64_t firstTimelineFrame,
        const float *interleavedStereo,
        std::size_t frameCount) noexcept;

    /** Worker-owned catch-up also runs while paused; preserves all active loop/copy floors. */
    void discardConsumedPcm() noexcept;

    [[nodiscard]] SongRenderEosStatus markLaneEndOfStream(
        SongRenderLane lane,
        std::uint64_t generation,
        std::uint64_t endTimelineFrameExclusive) noexcept;

    [[nodiscard]] bool bindCurrentSongPcmCache(
        SongRenderOwnership ownership,
        const CurrentSongPcmCache *cache) noexcept;

    /** The coordinator validates immutable source identity before attaching a retained raw head. */
    [[nodiscard]] bool bindRetainedLoopHead(
        SongRenderOwnership ownership,
        LoopHeadCache::ReadView head) noexcept;
    /** Only the current pipeline's source-qualified pinned raw head can authorize this range. */
    [[nodiscard]] LoopHeadCache::ReadView acquireRetainedLoopHead(
        const CurrentSongCacheKey &key, std::uint32_t requiredLaneMask,
        std::uint64_t firstTimelineFrame, std::uint64_t endTimelineFrameExclusive) const noexcept;
    [[nodiscard]] std::uint32_t retainedLoopHeadReadFailureLaneMask(const CurrentSongCacheKey &key) const noexcept;

    /** Exact preparation retirement releases residency, without calling through the cache lifetime. */
    void releaseCacheReadRequirements() noexcept;
    /** Retries interrupted publication for the live cache without rendering or reviving retired readers. */
    [[nodiscard]] bool retryPendingCacheReadRequirements(const CurrentSongPcmCache *liveCache) noexcept;
    void setCacheReadPreparation(bool preparation) noexcept;

    [[nodiscard]] bool retainOriginalTransitionHead(
        SongRenderOwnership ownership,
        bool retain) noexcept;

    /** Starts the ordinary Original reservoir at an exact failed-cache prefix boundary. */
    [[nodiscard]] bool beginCurrentSongPcmCacheFallback(
        SongRenderOwnership ownership,
        std::uint64_t firstReservoirFrame) noexcept;

    [[nodiscard]] TimelineCopyResult copyRetainedLanePcm(
        SongRenderOwnership ownership,
        SongRenderLane lane,
        std::uint64_t firstTimelineFrame,
        float *interleavedStereoOutput,
        std::size_t outputCapacityFrames,
        std::size_t requestedFrames) const noexcept;

    [[nodiscard]] SongRenderControlStatus submitControls(
        const SongRenderControlSnapshot &snapshot) noexcept;

    /**
     * Applies declared stem gains at the next render boundary. Mute/Solo and every non-mixer
     * control remain sourced from the latest canonical [submitControls] snapshot.
     */
    [[nodiscard]] SongRenderStemGainPreviewStatus submitStemGainPreview(
        SongRenderOwnership ownership,
        std::uint64_t sequence,
        std::uint64_t coversOfferSequence,
        std::uint32_t activeMask,
        const std::array<float, kStemLaneCount> &gains) noexcept;

    /**
     * Immediately materializes the exact sanitized callback target for the current owner.
     *
     * This does not render PCM or wait for queue space. It exists so a validated high-frequency
     * preview can update callback-owned ramps while previously rendered multibus frames remain in
     * the output reserve. Per-frame stemBusReadyMask still enforces safe Original fallback.
     */
    [[nodiscard]] SongRenderLateMixEndpoint prepareLateMixEndpoint(
        SongRenderOwnership ownership) noexcept;

    /**
     * Retires only the overlay owned by the exact resident song. The preview sequence high-water
     * remains intact so selection churn or a pipeline reset cannot make an older publication
     * admissible again.
     */
    [[nodiscard]] bool retireStemGainPreview(SongRenderOwnership ownership) noexcept;


    /** Explicit song-authored accent/downbeat flags, one strict 0/1 value per base beat. */
    [[nodiscard]] bool setBeatGrid(
        SongRenderOwnership ownership,
        const std::int64_t *beatFrames,
        const std::uint8_t *accentFlags,
        std::size_t beatCount) noexcept;

    [[nodiscard]] bool beatGridMatches(SongRenderOwnership ownership,
        const std::int64_t *frames, const std::uint8_t *accents, std::size_t count) const noexcept;
    void clearBeatGrid(SongRenderOwnership ownership) noexcept;

    [[nodiscard]] std::size_t loopContinuationHeadFrames(
        std::uint64_t startTimelineFrame, std::uint64_t endTimelineFrameExclusive) const noexcept;

    [[nodiscard]] SongRenderLoopStatus clearLoop(
        SongRenderOwnership ownership) noexcept;

    /** Retargets B without resetting an already-prepared continuation whose A is unchanged. */
    [[nodiscard]] SongRenderLoopStatus retargetAtomicLoopEnd(
        SongRenderOwnership ownership,
        std::uint64_t startTimelineFrame,
        std::uint64_t endTimelineFrameExclusive) noexcept;

    /** Repositions the producer onto cache-backed replacement PCM for an output-ring cutover. */
    [[nodiscard]] SongRenderLoopStatus prepareAtomicLoopCutover(
        SongRenderOwnership ownership,
        bool engageLoop,
        std::uint64_t startTimelineFrame,
        std::uint64_t endTimelineFrameExclusive,
        std::uint64_t replacementStartTimelineFrame,
        std::uint32_t declickFrames = 0U) noexcept;

    [[nodiscard]] SongRenderLoopStatus prepareAtomicLoopCutover(
        SongRenderOwnership ownership,
        bool engageLoop,
        std::uint64_t startTimelineFrame,
        std::uint64_t endTimelineFrameExclusive,
        std::uint64_t replacementStartTimelineFrame,
        std::uint32_t declickFrames,
        std::uint32_t requiredLaneMask) noexcept;

    /**
     * Copies at most 4096 stereo source frames across all lanes per call. Preparing requires
     * another service turn; keep both producers alive and cancel with the source before abandonment.
     */
    static constexpr std::size_t kRetainedBodyCopyFramesPerTurn = 4'096U;
    [[nodiscard]] SongRenderLoopStatus prepareAtomicLoopContinuationFrom(
        SongRenderPipeline &liveProducer,
        SongRenderOwnership ownership,
        bool engageLoop,
        std::uint64_t startTimelineFrame,
        std::uint64_t endTimelineFrameExclusive,
        std::uint64_t replacementStartTimelineFrame,
        std::uint32_t declickFrames,
        std::uint32_t requiredLaneMask,
        const LoopHeadCache *boundedHead = nullptr,
        LoopHeadCache::ReadView retainedHead = {}) noexcept;
    /** Releases only this spare's pending transfer and its exact source discard floor. */
    void cancelAtomicLoopContinuationPreparationFrom(SongRenderPipeline &liveProducer) noexcept;

    /**
     * Reuses sealed multibus PCM only when changed controls affect the final mix alone.
     * Rebinding also requires the caller to prove unchanged cache content/range and retain the
     * complete unconsumed head. Rebinding copies live raw ingress within the existing per-turn
     * budget; Preparing retains both producers until retry or exact cancellation. Metronome-enabled
     * heads require fresh output-clock preparation.
     */
    [[nodiscard]] SongRenderLoopStatus refreshAtomicLoopContinuationControlsFrom(
        SongRenderPipeline &liveProducer, std::uint32_t retainedHeadLaneMask = 0U) noexcept;

    /**
     * Applies one exact LoopCycleStateMachine directive at a render boundary. The accepted immutable
     * head is required for install/activate/wrap actions; Clear/DeadlineFallback accept an empty view.
     * Automatic cycles never mutate SongRenderOwnership.
     */
    [[nodiscard]] SongRenderLoopDirectiveStatus applyLoopDirective(
        const LoopCycleDirective &directive,
        LoopHeadCache::ReadView acceptedHead) noexcept;

    /** Stable evidence for the one pending B event; repeated reads are idempotent. */
    [[nodiscard]] LoopCycleBoundaryEvidence pendingLoopBoundary() const noexcept;
    [[nodiscard]] SongRenderLoopCycleSnapshot loopCycleSnapshot() const noexcept;
    [[nodiscard]] SongRenderLaneEndSnapshot laneEndSnapshot(
        SongRenderLane lane) const noexcept;

    /**
     * Output has bounded padded transport: declared processed stereo music buses,
     * mono metronome click, and the render-authored declick envelope. No stem/source gain,
     * limiter, clamp, or final-PCM fingerprint has yet been applied.
     */
    [[nodiscard]] SongRenderResult renderMultibus(
        SongRenderOwnership ownership,
        float *interleavedRenderPayload,
        std::uint32_t outputCapacityFrames,
        std::uint32_t requestedFrames,
        std::uint32_t maximumPrerollOutputFrames = 0U) noexcept;

    /** Active consumer requests a bounded reserve of its actual required logical cache input. */
    void requestUpcomingCacheInput() const noexcept;

    [[nodiscard]] SongRenderOwnership ownership() const noexcept;
    [[nodiscard]] std::uint64_t ingressEpoch() const noexcept;
    [[nodiscard]] double sourceTimelineFrame() const noexcept;
    /** Exact next pre-mixer input frame; includes time/pitch processor feed-ahead. */
    [[nodiscard]] std::uint64_t nextPreDspTimelineFrame() const noexcept;
    [[nodiscard]] std::int64_t nextOutputClockFrame() const noexcept;
    [[nodiscard]] bool processedTimelineActive() const noexcept;
    [[nodiscard]] bool loopEnabled() const noexcept;

    /**
     * Authoritative render-time coverage query for one logical lane range. Unlike
     * [laneDiagnostics], this follows the exact source path used by renderMultibus(): a managed-loop
     * cache, retained body reservoir, and a logical loop wrap may jointly cover the range.
     * Ownership is part of the proof so a prior seek generation can never authorize a newer one.
     */
    [[nodiscard]] bool laneCoversLogicalRange(
        SongRenderOwnership ownership,
        SongRenderLane lane,
        std::uint64_t firstTimelineFrame,
        std::size_t frameCount) const noexcept;

    [[nodiscard]] TimelineStereoBufferDiagnostics laneDiagnostics(
        SongRenderLane lane) const noexcept;
    [[nodiscard]] const StemwiseMixerDiagnostics &mixerDiagnostics() const noexcept;
    [[nodiscard]] TimePitchDiagnostics timePitchDiagnostics() const noexcept;
    [[nodiscard]] TimePitchTransitionDiagnostics transitionDiagnostics() const noexcept;
    /** Owner-thread diagnostic opt-in; ownership/sequence/finite validation always remains active. */
    void setDiagnosticPayloadFingerprints(bool enabled) noexcept;
    [[nodiscard]] SongRenderPipelineDiagnostics diagnostics() const noexcept;

private:
    class Impl;
    std::unique_ptr<Impl> mImpl;
};

}  // namespace stemwise::audio::engine
