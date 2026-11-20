#pragma once

#include <atomic>
#include <cstdint>

namespace stemwise::audio::engine {

/** The complete latest-only loop command. B is exclusive. */
struct AtomicLoopPolicySnapshot final {
    std::uint64_t revision{0U};
    std::uint64_t selectionToken{0U};
    bool enabled{false};
    std::uint64_t aSourceFrame{0U};
    std::uint64_t bSourceFrameExclusive{0U};
    std::uint64_t acceptedAtMonotonicMicros{0U};
    std::uint64_t inputEventAtMonotonicMicros{0U};
    std::uint64_t heldBGestureId{0U};
    /** Audible cursor was inside A..B when the control thread published this revision. */
    bool boundaryArmedAtPublication{false};

    /** Timing evidence is diagnostic metadata, not part of the idempotent command identity. */
    [[nodiscard]] constexpr bool operator==(
        const AtomicLoopPolicySnapshot &other) const noexcept {
        return revision == other.revision && selectionToken == other.selectionToken &&
            enabled == other.enabled && aSourceFrame == other.aSourceFrame &&
            bSourceFrameExclusive == other.bSourceFrameExclusive &&
            heldBGestureId == other.heldBGestureId;
    }

    [[nodiscard]] constexpr bool valid() const noexcept {
        return revision != 0U && selectionToken != 0U &&
            (!enabled || aSourceFrame < bSourceFrameExclusive);
    }
};

enum class AtomicLoopAdoption : std::uint8_t {
    NoChange,
    Contended,
    InvalidSelection,
    SelectionMismatch,
    Adopted,
};

/**
 * Single-publisher, single-render-consumer loop policy.
 *
 * Publication and render consumption are bounded, allocation-free and lock-free. The consumer
 * adopts exact-owner snapshots. Audible engagement and B/A enforcement belong to the output
 * callback's emitted PCM evidence, not this command publication slot.
 * Releasing a handle requires no call: MOVE publication is already final.
 */
class AtomicLoopPolicy final {
public:
    AtomicLoopPolicy() noexcept = default;

    AtomicLoopPolicy(const AtomicLoopPolicy &) = delete;
    AtomicLoopPolicy &operator=(const AtomicLoopPolicy &) = delete;

    /** Publishes only a strictly newer complete snapshot. One non-render thread owns this call. */
    [[nodiscard]] bool publish(const AtomicLoopPolicySnapshot &snapshot) noexcept;

    /** Lock-free read of the producer slot; valid output is never partially observed. */
    [[nodiscard]] bool readLatest(AtomicLoopPolicySnapshot &snapshot) const noexcept {
        return tryRead(snapshot);
    }

    /** One render consumer adopts the latest complete exact-owner command. */
    [[nodiscard]] AtomicLoopAdoption adoptLatest(std::uint64_t currentSelectionToken) noexcept;

    [[nodiscard]] const AtomicLoopPolicySnapshot &adopted() const noexcept {
        return mAdopted;
    }

private:
    struct alignas(64) Slot final {
        std::atomic<std::uint64_t> guard{0U};
        std::atomic<std::uint64_t> revision{0U};
        std::atomic<std::uint64_t> selectionToken{0U};
        std::atomic<std::uint64_t> aSourceFrame{0U};
        std::atomic<std::uint64_t> bSourceFrameExclusive{0U};
        std::atomic<std::uint64_t> acceptedAtMonotonicMicros{0U};
        std::atomic<std::uint64_t> inputEventAtMonotonicMicros{0U};
        std::atomic<std::uint64_t> heldBGestureId{0U};
        std::atomic<std::uint32_t> enabled{0U};
        std::atomic<std::uint32_t> boundaryArmedAtPublication{0U};
    };

    [[nodiscard]] bool tryRead(AtomicLoopPolicySnapshot &snapshot) const noexcept;
    void bindSelection(std::uint64_t selectionToken) noexcept;

    static_assert(std::atomic<std::uint64_t>::is_always_lock_free);
    static_assert(std::atomic<std::uint32_t>::is_always_lock_free);

    Slot mSlot{};

    // Render-thread-owned state; no atomics are needed on the single consumer.
    std::uint64_t mCurrentSelectionToken{0U};
    bool mHasAdopted{false};
    AtomicLoopPolicySnapshot mAdopted{};
};

} // namespace stemwise::audio::engine
