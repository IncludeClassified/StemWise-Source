#pragma once

#include "NativePlaybackRuntime.h"

#include <array>
#include <atomic>
#include <type_traits>

namespace stemwise::audio::engine {
#ifdef STEMWISE_MAILBOX_TEST_HOOKS
namespace command_mailbox_test { void afterReaderHazard() noexcept; }
#endif

/**
 * Three-slot SPSC hazard mailbox. The producer never writes the published or reader-hazard slot;
 * the consumer rechecks publication before copying. Plain snapshots therefore never race, while
 * every publication replaces (rather than queues behind) older user intent.
 */
class LatestCommandMailbox final {
public:
    struct PublishResult final {
        bool accepted{false};
        bool supersededUnread{false};
    };

    template <typename BeforeVisibility>
    [[nodiscard]] PublishResult publish(
        const NativePlaybackCommandSnapshot &snapshot,
        BeforeVisibility &&beforeVisibility) noexcept {
        if (snapshot.revision <= producerLastRevision) return {};
        const std::size_t current = publishedSlot.load(std::memory_order_seq_cst);
        const std::size_t hazard = readerSlot.load(std::memory_order_seq_cst);
        std::size_t destination = kMailboxSlotCount;
        for (std::size_t index = 0; index < kMailboxSlotCount; ++index) {
            if (index != current && index != hazard) {
                destination = index;
                break;
            }
        }
        if (destination == kMailboxSlotCount) return {};
        slots[destination] = snapshot;
        // Acceptance is final once the destination is selected and populated. Reserve and publish
        // the diagnostic event now, before the release point makes this slot readable. No rejected
        // or stale command can reach this callback.
        beforeVisibility();
        // Consumption and replacement claim the same pending revision, so they cannot both win.
        const bool superseded = unreadRevision.exchange(
            snapshot.revision, std::memory_order_acq_rel) != 0U;
        publishedSlot.store(destination, std::memory_order_seq_cst);
        producerLastRevision = snapshot.revision;
        return {true, superseded};
    }

    [[nodiscard]] bool hasUnreadPublication() const noexcept {
        return unreadRevision.load(std::memory_order_acquire) != 0U;
    }

    [[nodiscard]] bool readLatest(
        std::uint64_t lastReadRevision,
        NativePlaybackCommandSnapshot &snapshot) noexcept {
        for (std::size_t attempt = 0U; attempt < 8U; ++attempt) {
            const std::size_t candidate = publishedSlot.load(std::memory_order_seq_cst);
            readerSlot.store(candidate, std::memory_order_seq_cst);
#ifdef STEMWISE_MAILBOX_TEST_HOOKS
            command_mailbox_test::afterReaderHazard();
#endif
            if (publishedSlot.load(std::memory_order_seq_cst) != candidate) {
                readerSlot.store(kNoMailboxReader, std::memory_order_seq_cst);
                continue;
            }
            const NativePlaybackCommandSnapshot copied = slots[candidate];
            readerSlot.store(kNoMailboxReader, std::memory_order_seq_cst);
            if (copied.revision <= lastReadRevision) return false;
            auto expected = copied.revision;
            if (!unreadRevision.compare_exchange_strong(
                    expected, 0U, std::memory_order_acq_rel, std::memory_order_acquire)) return false;
            snapshot = copied;
            return true;
        }
        return false;
    }

private:
    static_assert(std::is_trivially_copyable_v<NativePlaybackCommandSnapshot>);
    static constexpr std::size_t kMailboxSlotCount = 3;
    static constexpr std::size_t kNoMailboxReader = kMailboxSlotCount;

    std::array<NativePlaybackCommandSnapshot, kMailboxSlotCount> slots{};
    std::atomic<std::size_t> publishedSlot{0};
    std::atomic<std::size_t> readerSlot{kNoMailboxReader};
    std::atomic<std::uint64_t> unreadRevision{0};
    std::uint64_t producerLastRevision{0};
};

} // namespace stemwise::audio::engine
