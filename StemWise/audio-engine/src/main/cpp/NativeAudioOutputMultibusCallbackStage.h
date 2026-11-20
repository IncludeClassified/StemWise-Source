#pragma once

#include "FinalPcmFrameTag.h"
#include "PresentationSpanJournal.h"
#include "LateStemMixControlMailbox.h"
#include "SpscTaggedMultibusRingBuffer.h"

#include <atomic>
#include <cstddef>
#include <cstdint>

namespace stemwise::audio {

struct NativeAudioOutputMultibusStageDiagnostics final {
    std::uint64_t callbackLeases{0U};
    std::uint64_t tagRuns{0U};
    std::uint64_t invalidTagFrames{0U};
    std::uint64_t missingControlFrames{0U};
    std::uint64_t staleControlFrames{0U};
    std::uint64_t mixerRejectedFrames{0U};
    std::uint64_t advanceFailures{0U};
};

struct NativeAudioOutputMultibusCallbackSnapshot final {
    std::uint64_t generation{0U}, selectionToken{0U}, controlRevision{0U};
    NativeAudioOutputMultibusStageDiagnostics stage{};
    LateStemMixerDiagnostics mixer{};
};

/**
 * Callback-owned consumer for one physical output-ring role.
 *
 * It validates every immutable tag run, resolves the newest exact-owner endpoint from the role
 * mailbox, applies source/stem/master control through LateStemMixer, and advances the multibus
 * queue only after all source payload has been consumed. Every callback method is bounded,
 * allocation-free, lock-free, and noexcept.
 */
class NativeAudioOutputMultibusCallbackStage final {
public:
    explicit NativeAudioOutputMultibusCallbackStage(std::uint32_t sampleRateHz = 48'000U);

    NativeAudioOutputMultibusCallbackStage(
        const NativeAudioOutputMultibusCallbackStage &) = delete;
    NativeAudioOutputMultibusCallbackStage &operator=(
        const NativeAudioOutputMultibusCallbackStage &) = delete;

    /** Inactive-role or stopped-callback reset. The next exact control initializes immediately. */
    void reset() noexcept;

    /**
     * Acquires, mixes, and then releases one bounded ring prefix.
     *
     * The returned tag span feeds NativeAudioOutput's post-read global-gain,
     * generation-transition, and evidence ordering.
     */
    [[nodiscard]] TaggedStereoReadResult readAndMix(
        std::uint32_t role,
        std::uint64_t expectedGeneration,
        SpscTaggedMultibusRingBuffer &ring,
        const LateStemMixControlMailbox &mailbox,
        float *interleavedStereo,
        std::size_t maximumFrames,
        OutputTimelineFrameTag *outputTimelineTags = nullptr,
        OutputTimelineLoopPolicy loopPolicy = {},
        NativeAudioPresentationSpan *mappingRuns = nullptr,
        FinalPcmFrameTag *finalFrameTags = nullptr) noexcept;

    [[nodiscard]] const NativeAudioOutputMultibusStageDiagnostics &diagnostics() const noexcept;
    /** Read only on the current callback's owned role, after readAndMix. */
    [[nodiscard]] NativeAudioOutputMultibusCallbackSnapshot callbackSnapshot() const noexcept {
        return {mAttemptedGeneration, mAttemptedToken, mAttemptedControlRevision, mDiagnostics, mMixer.diagnostics()};
    }

private:
    [[nodiscard]] bool validateAndApplyControl(
        std::uint32_t role,
        const RenderBusFrameTag &tag,
        const LateStemMixControlSnapshot *baseControl,
        const LateStemMixControlMailbox &mailbox,
        std::size_t frameCount) noexcept;
    [[nodiscard]] bool cachedControlCovers(const RenderBusFrameTag &tag) const noexcept;
    [[nodiscard]] FinalPcmFrameTag finalTag(
        const RenderBusFrameTag &tag,
        bool transitionSettled) const noexcept;
    void accountSettlementForPayload(const RenderBusFrameTag &tag) noexcept;
    void consumeSettlementFrames(std::size_t frameCount) noexcept;

    LateStemMixer mMixer;
    // Overlay publication numbers belong to the output owner, not the render block's producer.
    LateStemMixControlSnapshot mOverlay{};
    bool mHasOverlay{false};
    LateStemMixControlSnapshot mControl{};
    bool mHasControl{false};
    std::uint64_t mMixerApplicationSequence{0U};
    std::uint32_t mSettlementFramesRemaining{0U};
    std::uint32_t mLastStemBusReadyMask{0U};
    NativeAudioOutputMultibusStageDiagnostics mDiagnostics{};
    std::uint64_t mAttemptedGeneration{0U}, mAttemptedToken{0U}, mAttemptedControlRevision{0U};
};

static_assert(std::atomic<std::uint8_t>::is_always_lock_free);

}  // namespace stemwise::audio
