#pragma once

#include "GuardedAtomicSnapshot.h"
#include "NativeAudioOutputBridge.h"
#include <array>
#include <bit>

namespace stemwise::audio {

struct LoopAdoptionPublication final {
    std::uint64_t generation{}, selectionToken{}, revision{};
    bool enabled{};
    std::uint64_t aFrame{}, bFrame{}, adoptionMicros{}, inputToPublicationMicros{}, inputToAdoptionMicros{};
};

// Sole writer: physical callback, or lifecycle transaction after the stream has stopped.
// Position and logical adoption have separate meanings but one owner-qualified publication.
class PresentedPositionPublication final {
public:
    void reset() noexcept { position_ = {}; adoption_ = {}; publish(); }
    void position(NativeAudioPresentedPosition value) noexcept { position_ = value; publish(); }
    void adopt(LoopAdoptionPublication value) noexcept { adoption_ = value; publish(); }
    std::uint64_t adoptedRevision() const noexcept { return adoption_.revision; }
    // Writer-only access; no other thread may inspect this ordinary callback-owned mirror.
    const NativeAudioPresentedPosition &callbackPosition() const noexcept { return position_; }
    NativeAudioPresentedPosition read() const noexcept {
        return guard_.read([this](std::uint64_t sequence) noexcept {
            std::array<std::uint64_t, 17U> values{};
            for (std::size_t index = 0U; index != values.size(); ++index) values[index] = fields_[index].load(std::memory_order_acquire);
            return NativeAudioPresentedPosition{
                .sequence = sequence, .generation = values[0], .selectionToken = values[1],
                .fingerprintSequence = values[2], .loopPolicyRevision = values[3],
                .lastTimelineFrame = std::bit_cast<std::int64_t>(values[4]), .lastOutputFrame = values[5],
                .callbackSequence = values[6], .pcmFrames = values[7], .terminalAfterFrame = values[8] != 0U,
                .loopPolicyAdoptedRevision = values[9], .loopPolicyAdoptedEnabled = values[10] != 0U,
                .loopPolicyAdoptedAFrame = values[11], .loopPolicyAdoptedBFrameExclusive = values[12],
                .loopPolicyAdoptionLatencyMicros = values[13],
                .loopPolicyInputToAtomicPublicationLatencyMicros = values[14],
                .loopPolicyInputToCallbackAdoptionLatencyMicros = values[15],
                .lastNextTimelineFrame = std::bit_cast<std::int64_t>(values[16]),
            };
        });
    }
private:
    void publish() noexcept {
        const bool exact = position_.generation == adoption_.generation && position_.selectionToken == adoption_.selectionToken;
        const auto adoption = exact ? adoption_ : LoopAdoptionPublication{};
        const std::array<std::uint64_t, 17U> values{
            position_.generation, position_.selectionToken, position_.fingerprintSequence, position_.loopPolicyRevision,
            std::bit_cast<std::uint64_t>(position_.lastTimelineFrame), position_.lastOutputFrame,
            position_.callbackSequence, position_.pcmFrames, position_.terminalAfterFrame ? 1U : 0U,
            adoption.revision, adoption.enabled ? 1U : 0U, adoption.aFrame, adoption.bFrame,
            adoption.adoptionMicros, adoption.inputToPublicationMicros, adoption.inputToAdoptionMicros,
            std::bit_cast<std::uint64_t>(position_.lastNextTimelineFrame)};
        const auto publication = guard_.publish();
        for (std::size_t index = 0U; index != values.size(); ++index) fields_[index].store(values[index], std::memory_order_release);
    }
    NativeAudioPresentedPosition position_{};
    LoopAdoptionPublication adoption_{};
    GuardedAtomicSnapshot guard_;
    std::array<std::atomic<std::uint64_t>, 17U> fields_{};
};

} // namespace stemwise::audio
