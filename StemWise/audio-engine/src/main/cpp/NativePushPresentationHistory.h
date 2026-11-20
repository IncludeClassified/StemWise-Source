#pragma once

#include "FinalPcmFrameTag.h"
#include "ImmutablePcmRunPool.h"
#include <array>
#include <cassert>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <vector>

namespace stemwise::audio {
enum class PushTransportSilenceKind : std::uint8_t { LogicalPause = 0U, SourceTransition = 1U };

/** The exact prepared/accepted frame; acceptance and presentation remain separate operations. */
struct NativePushPresentationFrame final {
    std::uint64_t sourceWriteBoundary{}, sourceEpoch{};
    FinalPcmFrameTag tag{};
    std::array<float, 2U> pcm{};
    std::uint64_t outputFrame{std::numeric_limits<std::uint64_t>::max()}, sequence{};
    std::uint32_t ring{};
    bool valid{}, terminal{}, control{}, tuning{}, stem{}, loop{}, eligible{}, qualification{};
    PushTransportSilenceKind silenceKind{PushTransportSilenceKind::LogicalPause};
    std::uint64_t continuityViolations{};
};

/** Sole push-consumer ledger. Run allocation is admitted before handing PCM to the sink;
 * accepting any later partial receipt allocates nothing and preserves every exact sample/tag. */
class NativePushPresentationHistory final {
    struct Run final { NativePushPresentationFrame value{}; std::size_t references{}; };
    struct Frame final {
        std::int64_t timeline{}, nextTimeline{};
        std::uint64_t terminalBoundary{}, sourceWriteBoundary{}, outputFrame{~std::uint64_t{0U}};
        std::array<float, 2U> pcm{};
        std::uint32_t run{~std::uint32_t{0U}};
        std::uint16_t flags{};
    };
public:
    [[nodiscard]] static AudioMemoryFootprint storageFootprint(std::size_t capacity, std::size_t blockFrames) {
        const auto fixed = addAudioMemoryBytes(sizeof(NativePushPresentationHistory),
            addAudioMemoryBytes(audioMemoryBytes(capacity, sizeof(Frame)), audioMemoryBytes(blockFrames, sizeof(std::uint32_t))));
        return {addAudioMemoryBytes(fixed, ImmutablePcmRunPool<Run>::storageBytes(capacity, false)),
            addAudioMemoryBytes(fixed, ImmutablePcmRunPool<Run>::storageBytes(capacity, true))};
    }
    NativePushPresentationHistory(std::size_t capacity, std::size_t blockFrames)
        : mFrames(capacity), mSourceRuns(blockFrames, kNoRun), mRuns(capacity) {}

    bool stageSource(const NativePushPresentationFrame *frames, std::size_t count, std::uint64_t sequence) noexcept {
        if (mSourceSequence == sequence) return true;
        releaseSource();
        for (std::size_t frame = 0U; frame < count; ++frame) {
            const bool previous = frame != 0U && sameIdentity(frames[frame - 1U], frames[frame]);
            if (previous) mSourceRuns[frame] = mSourceRuns[frame - 1U];
            else {
                const auto slot = mRuns.acquire();
                if (!slot) { releaseSource(); return false; }
                mSourceRuns[frame] = *slot;
                mRuns[*slot] = {frames[frame], 1U};
            }
        }
        mSourceSequence = sequence;
        return true;
    }

    bool stageTransport(PushTransportSilenceKind kind, std::uint64_t sequence) noexcept {
        releaseTransport();
        const auto slot = mRuns.acquire();
        if (!slot) return false;
        mTransportRun = *slot;
        mRuns[*slot] = {};
        mRuns[*slot].value.silenceKind = kind;
        mRuns[*slot].references = 1U;
        mTransportSequence = sequence;
        return true;
    }

    void acceptSource(std::size_t preparedFrame, const NativePushPresentationFrame &value) noexcept {
        assert(preparedFrame < mSourceRuns.size() && mSourceRuns[preparedFrame] != kNoRun);
        accept(mSourceRuns[preparedFrame], value);
    }
    void acceptTransport(std::uint64_t outputFrame) noexcept {
        assert(mTransportRun != kNoRun && mTransportSequence != 0U);
        auto value = mRuns[mTransportRun].value;
        value.outputFrame = outputFrame;
        accept(mTransportRun, value);
    }

    [[nodiscard]] NativePushPresentationFrame at(std::uint64_t outputFrame) const noexcept {
        const auto &frame = mFrames[outputFrame % mFrames.size()];
        if (frame.run == kNoRun || frame.outputFrame != outputFrame) return {};
        auto value = mRuns[frame.run].value;
        value.outputFrame = frame.outputFrame;
        value.sourceWriteBoundary = frame.sourceWriteBoundary;
        value.tag.timelineFrame = frame.timeline;
        value.tag.nextTimelineFrame = frame.nextTimeline;
        value.tag.terminalWriteBoundary = frame.terminalBoundary;
        value.pcm = frame.pcm;
        value.valid = frame.flags & 1U; value.terminal = frame.flags & 2U;
        value.control = frame.flags & 4U; value.tuning = frame.flags & 8U;
        value.stem = frame.flags & 16U; value.loop = frame.flags & 32U;
        value.eligible = frame.flags & 64U; value.qualification = frame.flags & 128U;
        return value;
    }
    [[nodiscard]] bool samePresentationRun(std::uint64_t first, std::uint64_t next) const noexcept {
        const auto &left = mFrames[first % mFrames.size()], &right = mFrames[next % mFrames.size()];
        return left.run != kNoRun && left.run == right.run && right.outputFrame == next &&
            (left.flags & ~2U) == (right.flags & ~2U);
    }
    void copyPcm(std::uint64_t first, std::size_t count, float *destination) const noexcept {
        for (std::size_t index = 0U; index < count; ++index) {
            const auto &frame = mFrames[(first + index) % mFrames.size()];
            destination[index * 2U] = frame.pcm[0U]; destination[index * 2U + 1U] = frame.pcm[1U];
        }
    }
    void retire(std::uint64_t first, std::size_t count) noexcept {
        for (std::size_t index = 0U; index < count; ++index) {
            auto &frame = mFrames[(first + index) % mFrames.size()];
            assert(frame.outputFrame == first + index && frame.run != kNoRun);
            drop(frame.run); frame.run = kNoRun;
        }
    }
    void releaseSource() noexcept {
        auto previous = kNoRun;
        for (auto &slot : mSourceRuns) {
            if (slot != kNoRun && slot != previous) drop(slot);
            previous = slot; slot = kNoRun;
        }
        mSourceSequence = 0U;
    }
    void releaseTransport() noexcept {
        if (mTransportRun != kNoRun) drop(mTransportRun);
        mTransportRun = kNoRun; mTransportSequence = 0U;
    }
    void discardAccepted() noexcept {
        for (auto &frame : mFrames) if (frame.run != kNoRun) { drop(frame.run); frame.run = kNoRun; }
    }
    [[nodiscard]] std::size_t allocatedBytes() const noexcept {
        return mFrames.size() * sizeof(Frame) + mSourceRuns.size() * sizeof(std::uint32_t) + mRuns.allocatedBytes();
    }
private:
    static bool sameIdentity(const NativePushPresentationFrame &left, const NativePushPresentationFrame &right) noexcept {
        auto first = left.tag, second = right.tag;
        first.timelineFrame = second.timelineFrame = 0;
        first.nextTimelineFrame = second.nextTimelineFrame = 0;
        first.terminalWriteBoundary = second.terminalWriteBoundary = 0U;
        return first == second && left.sourceEpoch == right.sourceEpoch && left.sequence == right.sequence &&
            left.ring == right.ring && left.silenceKind == right.silenceKind &&
            left.continuityViolations == right.continuityViolations;
    }
    void drop(std::uint32_t slot) noexcept {
        assert(mRuns[slot].references != 0U);
        if (--mRuns[slot].references == 0U) mRuns.release(slot);
    }
    void accept(std::uint32_t run, const NativePushPresentationFrame &value) noexcept {
        auto &frame = mFrames[value.outputFrame % mFrames.size()];
        assert(frame.run == kNoRun);
        frame = {value.tag.timelineFrame, value.tag.nextTimelineFrame, value.tag.terminalWriteBoundary,
            value.sourceWriteBoundary, value.outputFrame, value.pcm, run,
            static_cast<std::uint16_t>((value.valid ? 1U : 0U) | (value.terminal ? 2U : 0U) |
                (value.control ? 4U : 0U) | (value.tuning ? 8U : 0U) | (value.stem ? 16U : 0U) |
                (value.loop ? 32U : 0U) | (value.eligible ? 64U : 0U) | (value.qualification ? 128U : 0U))};
        ++mRuns[run].references;
    }
    static constexpr std::uint32_t kNoRun = ~std::uint32_t{0U};
    std::vector<Frame> mFrames;
    std::vector<std::uint32_t> mSourceRuns;
    ImmutablePcmRunPool<Run> mRuns;
    std::uint64_t mSourceSequence{}, mTransportSequence{};
    std::uint32_t mTransportRun{kNoRun};
};
} // namespace stemwise::audio
