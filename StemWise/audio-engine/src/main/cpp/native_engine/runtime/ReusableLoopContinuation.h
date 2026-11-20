#pragma once

#include "NativePlaybackRuntime.h"
#include "../../NativeAudioOutputLoopBoundaryGate.h"
#include <algorithm>
#include <vector>
#include <limits>
#include <cmath>
#include <stdexcept>

namespace stemwise::audio::engine {

struct OutputPcmMetadata {
    void clear() noexcept {
        offsetFrames = 0;
        remainingFrames = 0;
        totalFrames = 0;
        ownership = {};
        fingerprintSequence = 0;
        renderPayloadHash = 0;
        controlRevision = 0;
        sourceIntentRevision = 0U;
        stemIntentRevision = 0U;
        stemBusReadyMask = 0U;
        actualTempo = 0.0F;
        actualPitchSemitones = 0.0F;
        lateMixControl = {};
        lateMixControlPublished = false;
        loopPolicyRevision = 0U;
        loopRevision = 0U;
        loopCycle = 0U;
        loopEnabled = false;
        managedLoopContext = {};
        endOfStreamAfterBlock = false;
        sourceStartFrame = 0.0;
        sourceEndFrameExclusive = 0.0;
    }

    [[nodiscard]] double remainingSourceStart() const noexcept {
        if (totalFrames == 0U) return sourceStartFrame;
        const double fraction = static_cast<double>(offsetFrames) /
            static_cast<double>(totalFrames);
        return sourceStartFrame +
            (sourceEndFrameExclusive - sourceStartFrame) * fraction;
    }

    std::size_t offsetFrames{0};
    std::size_t remainingFrames{0};
    std::size_t totalFrames{0};
    SongRenderOwnership ownership{};
    std::uint64_t fingerprintSequence{0};
    std::uint64_t renderPayloadHash{0};
    std::uint64_t controlRevision{0};
    std::uint64_t sourceIntentRevision{0};
    std::uint64_t stemIntentRevision{0};
    std::uint32_t stemBusReadyMask{0U};
    /** Numerical DSP tuple represented by this payload; zero tempo means transition/unknown. */
    float actualTempo{0.0F};
    float actualPitchSemitones{0.0F};

    NativeAudioLateMixControl lateMixControl{};
    bool lateMixControlPublished{false};
    std::uint64_t loopPolicyRevision{0};
    std::uint64_t loopRevision{0};
    std::uint64_t loopCycle{0};
    bool loopEnabled{false};
    ManagedLoopRenderContext managedLoopContext{};
    bool endOfStreamAfterBlock{false};
    double sourceStartFrame{0.0};
    double sourceEndFrameExclusive{0.0};
};


struct PendingOutputPcm final : OutputPcmMetadata {
    explicit PendingOutputPcm(std::size_t capacityFrames)
        : samples(capacityFrames * kStemwiseMultibusStride) {}
    std::vector<float> samples;
};

struct DirectLoopCutoverPlan final {
    enum class Phase { Preparing, Staged, Published };
    void clear() noexcept { *this = {}; }
    [[nodiscard]] bool stageOpen() const noexcept { return phase == Phase::Staged; }
    [[nodiscard]] bool commitPublished() const noexcept { return phase == Phase::Published; }

    NativeAudioOutputLoopPolicy policy{};
    /** Physical loop state of the prepared continuation at policy.continuationTimelineFrame. */
    bool continuationEngaged{false};
    Phase phase{Phase::Preparing};
    bool lateMixControlBound{false};
    bool endObserved{false};
    std::size_t stagedFrames{0U};
    std::size_t currentOutputRefillHighWater{0U};
    std::uint64_t preparationWrapBaseline{0U};
    CurrentSongCacheKey cacheKey{};
    std::uint64_t controlRevision{0U};
    std::uint64_t controlApplicationEpoch{0U};
    std::uint64_t beatGridRevision{0U};
    bool allStemBusesPrepared{false};
    std::uint64_t sourceIntentRevision{0U};
    std::uint64_t stemIntentRevision{0U};
};


/** Worker-owned fixed-capacity PCM continuation, retained across compatible loop generations. */
class ReusableLoopContinuation final {
public:
    ReusableLoopContinuation(std::size_t blockFrames, std::size_t reserveFrames)
        : maximumBlockFrames(blockFrames) {
        if (blockFrames == 0U || reserveFrames > std::numeric_limits<std::size_t>::max() - (blockFrames - 1U))
            throw std::length_error("prepared continuation capacity overflow");
        // Preserve the original whole-block budget, including a reserve smaller than one block.
        const auto capacity = ((reserveFrames + blockFrames - 1U) / blockFrames) * blockFrames;
        if (capacity > std::numeric_limits<std::size_t>::max() / kStemwiseMultibusStride)
            throw std::length_error("prepared continuation capacity overflow");
        // A valid render contributes at least one frame; metadata never needs more descriptors.
        blocks.resize(capacity);
        samples.resize(capacity * kStemwiseMultibusStride);
    }
    void discard() noexcept { phase = Phase::Empty; }
    void invalidateReuse() noexcept {
        if (phase == Phase::Ready) phase = Phase::Empty;
        else if (phase == Phase::Replaying) phase = Phase::RetiredReplay;
    }
    [[nodiscard]] bool ready() const noexcept { return phase == Phase::Ready || phase == Phase::Replaying; }
    [[nodiscard]] bool replaying() const noexcept { return phase == Phase::Replaying; }
#if defined(STEMWISE_CACHE_IO_PROFILING)
    [[nodiscard]] std::int64_t profilingPhase() const noexcept { return static_cast<std::int64_t>(phase); }
    [[nodiscard]] std::int64_t profilingFrames() const noexcept { return static_cast<std::int64_t>(frames); }
    [[nodiscard]] std::int64_t profilingUnloadedFrames() const noexcept {
        return static_cast<std::int64_t>(frames - (read < count ? blocks[read].firstFrame : frames));
    }
#endif
    [[nodiscard]] const DirectLoopCutoverPlan &plan() const noexcept { return savedPlan; }
    [[nodiscard]] std::uint32_t declickFrames() const noexcept { return declick; }
    [[nodiscard]] bool fitsBoundary(std::uint64_t end, std::uint32_t defaultDeclick) const noexcept {
        if (end == savedPlan.policy.bTimelineFrameExclusive) return true;
        const auto earliest = std::min(end, savedPlan.policy.bTimelineFrameExclusive);
        const auto fade = declick != 0U ? declick : defaultDeclick;
        return earliest > feedEnd && earliest - feedEnd > fade;
    }
    void begin(bool reuse, bool collect, std::uint32_t requestedDeclick) noexcept {
        read = 0U;
        if (!reuse) {
            count = frames = 0U;
            declick = requestedDeclick;
        }
        phase = reuse ? Phase::Replaying : collect ? Phase::Collecting : Phase::Empty;
    }
    void append(const PendingOutputPcm &pending) noexcept {
        if (phase != Phase::Collecting) return;
        const auto capacity = samples.size() / kStemwiseMultibusStride;
        // Collection precedes the first sink receipt: partial source mapping cannot be truncated.
        if (pending.offsetFrames != 0U || pending.remainingFrames == 0U ||
            pending.totalFrames != pending.remainingFrames || pending.remainingFrames > maximumBlockFrames ||
            pending.remainingFrames > pending.samples.size() / kStemwiseMultibusStride ||
            count == blocks.size() || pending.remainingFrames > capacity - frames) {
            phase = Phase::Empty;
            return;
        }
        blocks[count++] = {static_cast<const OutputPcmMetadata &>(pending), frames};
        std::copy_n(pending.samples.data(), pending.remainingFrames * kStemwiseMultibusStride,
            samples.data() + frames * kStemwiseMultibusStride);
        frames += pending.remainingFrames;
    }
    [[nodiscard]] bool hasReplayBlock() const noexcept { return isReplay() && read < count; }
    void takeNext(PendingOutputPcm &pending) noexcept {
        if (!hasReplayBlock() || blocks[read].metadata.remainingFrames >
                pending.samples.size() / kStemwiseMultibusStride) {
            pending.clear();
            phase = Phase::Empty;
            return;
        }
        const auto &block = blocks[read++];
        static_cast<OutputPcmMetadata &>(pending) = block.metadata;
        std::copy_n(samples.data() + block.firstFrame * kStemwiseMultibusStride,
            pending.remainingFrames * kStemwiseMultibusStride, pending.samples.data());
    }
    [[nodiscard]] bool completeTraversal(float tempo, float pitch) const noexcept {
        if (!ready() || count == 0U || frames != savedPlan.stagedFrames ||
            !savedPlan.policy.enabled || savedPlan.policy.continuationTimelineFrame != savedPlan.policy.aTimelineFrame)
            return false;
        const auto &first = blocks[0U].metadata;
        if (!std::isfinite(first.actualTempo) || !std::isfinite(first.actualPitchSemitones) ||
            first.actualTempo <= 0.F || first.actualTempo != tempo || first.actualPitchSemitones != pitch ||
            first.sourceStartFrame != static_cast<double>(savedPlan.policy.aTimelineFrame))
            return false;
        auto end = first.sourceStartFrame;
        for (std::size_t index = 0U; index < count; ++index) {
            const auto &block = blocks[index].metadata;
            if (block.sourceStartFrame != end || block.sourceEndFrameExclusive <= end ||
                block.actualTempo != first.actualTempo || block.actualPitchSemitones != first.actualPitchSemitones)
                return false;
            end = block.sourceEndFrameExclusive;
        }
        return end == static_cast<double>(savedPlan.policy.bTimelineFrameExclusive);
    }
    void finishStage(const DirectLoopCutoverPlan &plan, std::uint64_t nextFeed,
        bool sameWrap) noexcept {
        const bool replay = isReplay();
        const bool retained = (phase == Phase::Collecting || replay) &&
            frames == plan.stagedFrames && sameWrap;
        if (retained) { savedPlan = plan; feedEnd = nextFeed; }
        phase = replay ? (retained ? Phase::Replaying : Phase::RetiredReplay) :
            (retained ? Phase::Ready : Phase::Empty);
    }
private:
    struct SavedBlock final { OutputPcmMetadata metadata{}; std::size_t firstFrame{}; };
    std::vector<SavedBlock> blocks;
    std::vector<float> samples;
    std::size_t maximumBlockFrames{};
    DirectLoopCutoverPlan savedPlan{};
    std::size_t count{}, frames{}, read{};
    std::uint32_t declick{};
    std::uint64_t feedEnd{};
    enum class Phase { Empty, Collecting, Ready, Replaying, RetiredReplay };
    Phase phase{Phase::Empty};
    [[nodiscard]] bool isReplay() const noexcept {
        return phase == Phase::Replaying || phase == Phase::RetiredReplay;
    }
};
} // namespace stemwise::audio::engine
