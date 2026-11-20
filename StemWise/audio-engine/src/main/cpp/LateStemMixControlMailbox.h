#include "GuardedAtomicSnapshot.h"
#pragma once

#include "LateStemMixer.h"

#include <array>
#include <atomic>
#include <bit>
#include <cstddef>
#include <cstdint>

namespace stemwise::audio {

#ifdef STEMWISE_LATE_CONTROL_TEST_HOOKS
namespace late_control_test { void afterWriteBegins(std::uint32_t role, std::uint64_t publication) noexcept; }
#endif

inline constexpr std::size_t kLateStemMixOutputRoleCount = 2U;

/**
 * Latest-only control publication for the two physical output-ring roles.
 *
 * One non-callback publisher owns resetRole() and publish(). The audio callback is the only reader
 * of each role and calls tryRead() with the owner carried by that role's PCM. Every payload word is
 * atomic, so a failed seqlock read is data-race-free under the C++ memory model. tryRead() performs
 * one bounded attempt and leaves the caller's previous snapshot untouched on contention.
 *
 * Role reuse is explicit. resetRole() normally accepts the exact current owner (for route recovery)
 * or a strictly newer generation; a coordinator-proven inactive role may be rebound after a
 * cancelled preload. The process-wide publication high-water survives every reset. Owner-scoped
 * revision floors restart for every different exact owner, while generation and token matching
 * prevent an old A snapshot from becoming admissible after A -> B -> A-shaped reuse.
 */
class LateStemMixControlMailbox final {
public:
    static constexpr std::size_t kRoleCount = kLateStemMixOutputRoleCount;

    LateStemMixControlMailbox() noexcept = default;

    LateStemMixControlMailbox(const LateStemMixControlMailbox &) = delete;
    LateStemMixControlMailbox &operator=(const LateStemMixControlMailbox &) = delete;

    /**
     * Binds and invalidates one role. The next callback read succeeds only after publish().
     *
     * Rebinding normally requires a strictly newer generation. allowInactiveRoleRebind is reserved
     * for a coordinator-proven inactive ring (for example, reclaiming a preloaded successor for a
     * same-owner loop); that role may legitimately move from a future generation back to active.
     * Resetting the exact owner retains revision floors; every different owner restarts them.
     */
    [[nodiscard]] bool resetRole(
        std::uint32_t role,
        std::uint64_t generation,
        std::uint64_t selectionToken,
        bool allowInactiveRoleRebind = false) noexcept {
        Slot *slot = slotForRole(role);
        if (slot == nullptr || generation == 0U || selectionToken == 0U) return false;

        const std::uint64_t currentGeneration =
            slot->generation.load(std::memory_order_relaxed);
        const std::uint64_t currentToken =
            slot->selectionToken.load(std::memory_order_relaxed);
        const bool exactOwner = currentGeneration == generation && currentToken == selectionToken;
        const bool newerOwner = currentGeneration == 0U || generation > currentGeneration;
        if (!exactOwner && !newerOwner && !allowInactiveRoleRebind) return false;

        beginWrite(*slot);
        slot->readable.store(0U, std::memory_order_relaxed);
        slot->generation.store(generation, std::memory_order_relaxed);
        slot->selectionToken.store(selectionToken, std::memory_order_relaxed);
        if (!exactOwner) {
            slot->canonicalControlRevision.store(0U, std::memory_order_relaxed);
            slot->sourceIntentRevision.store(0U, std::memory_order_relaxed);
            slot->stemIntentRevision.store(0U, std::memory_order_relaxed);
        }
        endWrite(*slot);
        return true;
    }

    /**
     * Publishes one complete endpoint for the role's exact bound owner.
     *
     * publicationSequence is strictly increasing. Canonical/source/stem revisions may stay equal
     * for a gain-only endpoint, but none may regress. Rejected publications do not change the slot.
     */
    [[nodiscard]] bool publish(
        std::uint32_t role,
        const LateStemMixControlSnapshot &snapshot) noexcept {
        Slot *slot = slotForRole(role);
        if (slot == nullptr || !validSnapshot(snapshot) ||
            slot->generation.load(std::memory_order_relaxed) != snapshot.generation ||
            slot->selectionToken.load(std::memory_order_relaxed) != snapshot.selectionToken ||
            snapshot.publicationSequence <=
                slot->publicationSequence.load(std::memory_order_relaxed) ||
            snapshot.canonicalControlRevision <
                slot->canonicalControlRevision.load(std::memory_order_relaxed) ||
            snapshot.sourceIntentRevision <
                slot->sourceIntentRevision.load(std::memory_order_relaxed) ||
            snapshot.stemIntentRevision <
                slot->stemIntentRevision.load(std::memory_order_relaxed)) {
            return false;
        }

        beginWrite(*slot);
#ifdef STEMWISE_LATE_CONTROL_TEST_HOOKS
        late_control_test::afterWriteBegins(role, snapshot.publicationSequence);
#endif
        slot->readable.store(0U, std::memory_order_relaxed);
        slot->publicationSequence.store(
            snapshot.publicationSequence,
            std::memory_order_relaxed);
        slot->canonicalControlRevision.store(
            snapshot.canonicalControlRevision,
            std::memory_order_relaxed);
        slot->sourceIntentRevision.store(
            snapshot.sourceIntentRevision,
            std::memory_order_relaxed);
        slot->stemIntentRevision.store(
            snapshot.stemIntentRevision,
            std::memory_order_relaxed);
        slot->stemGainPreviewPublicationSequence.store(
            snapshot.stemGainPreviewPublicationSequence,
            std::memory_order_relaxed);
        slot->stemGainPreviewCoversOfferSequence.store(
            snapshot.stemGainPreviewCoversOfferSequence,
            std::memory_order_relaxed);
        slot->stemGainPreviewActiveMask.store(
            snapshot.stemGainPreviewActiveMask,
            std::memory_order_relaxed);
        for (std::size_t lane = 0U; lane < snapshot.stemGainPreviewGainBits.size(); ++lane) {
            slot->stemGainPreviewGainBits[lane].store(
                snapshot.stemGainPreviewGainBits[lane],
                std::memory_order_relaxed);
        }
        slot->sourceMode.store(
            static_cast<std::uint32_t>(snapshot.sourceMode),
            std::memory_order_relaxed);
        slot->requiredStemMask.store(snapshot.requiredStemMask, std::memory_order_relaxed);
        for (std::size_t lane = 0U; lane < snapshot.effectiveStemGains.size(); ++lane) {
            slot->effectiveStemGainBits[lane].store(
                std::bit_cast<std::uint32_t>(snapshot.effectiveStemGains[lane]),
                std::memory_order_relaxed);
        }
        slot->musicGainBits.store(
            std::bit_cast<std::uint32_t>(snapshot.musicGain),
            std::memory_order_relaxed);
        slot->mixHeadroomBits.store(
            std::bit_cast<std::uint32_t>(snapshot.mixHeadroom),
            std::memory_order_relaxed);
        slot->sourceRampFrames.store(snapshot.sourceRampFrames, std::memory_order_relaxed);
        slot->laneRampFrames.store(snapshot.laneRampFrames, std::memory_order_relaxed);
        slot->masterRampFrames.store(snapshot.masterRampFrames, std::memory_order_relaxed);
        slot->readable.store(1U, std::memory_order_relaxed);
        endWrite(*slot);
        return true;
    }

    /**
     * Audio-callback read. This method never retries, waits, locks, or allocates.
     *
     * A false result means unbound/reset/wrong-owner or one concurrent publication. The callback
     * should retain its last coherent exact-owner endpoint and try again on its next invocation.
     */
    [[nodiscard]] bool tryRead(
        std::uint32_t role,
        std::uint64_t expectedGeneration,
        std::uint64_t expectedSelectionToken,
        LateStemMixControlSnapshot &snapshot) const noexcept {
        const Slot *slot = slotForRole(role);
        if (slot == nullptr || expectedGeneration == 0U || expectedSelectionToken == 0U) {
            return false;
        }

        const std::uint64_t before = slot->guard.load(std::memory_order_acquire);
        if ((before & 1U) != 0U) return false;

        const bool readable = slot->readable.load(std::memory_order_relaxed) != 0U;
        LateStemMixControlSnapshot candidate;
        candidate.publicationSequence =
            slot->publicationSequence.load(std::memory_order_relaxed);
        candidate.generation = slot->generation.load(std::memory_order_relaxed);
        candidate.selectionToken = slot->selectionToken.load(std::memory_order_relaxed);
        candidate.canonicalControlRevision =
            slot->canonicalControlRevision.load(std::memory_order_relaxed);
        candidate.sourceIntentRevision =
            slot->sourceIntentRevision.load(std::memory_order_relaxed);
        candidate.stemIntentRevision =
            slot->stemIntentRevision.load(std::memory_order_relaxed);
        candidate.stemGainPreviewPublicationSequence =
            slot->stemGainPreviewPublicationSequence.load(std::memory_order_relaxed);
        candidate.stemGainPreviewCoversOfferSequence =
            slot->stemGainPreviewCoversOfferSequence.load(std::memory_order_relaxed);
        candidate.stemGainPreviewActiveMask =
            slot->stemGainPreviewActiveMask.load(std::memory_order_relaxed);
        for (std::size_t lane = 0U; lane < candidate.stemGainPreviewGainBits.size(); ++lane) {
            candidate.stemGainPreviewGainBits[lane] =
                slot->stemGainPreviewGainBits[lane].load(std::memory_order_relaxed);
        }
        const std::uint32_t sourceMode = slot->sourceMode.load(std::memory_order_relaxed);
        candidate.sourceMode = static_cast<LateStemSourceMode>(sourceMode);
        candidate.requiredStemMask = slot->requiredStemMask.load(std::memory_order_relaxed);
        for (std::size_t lane = 0U; lane < candidate.effectiveStemGains.size(); ++lane) {
            candidate.effectiveStemGains[lane] = std::bit_cast<float>(
                slot->effectiveStemGainBits[lane].load(std::memory_order_relaxed));
        }
        candidate.musicGain = std::bit_cast<float>(
            slot->musicGainBits.load(std::memory_order_relaxed));
        candidate.mixHeadroom = std::bit_cast<float>(
            slot->mixHeadroomBits.load(std::memory_order_relaxed));
        candidate.sourceRampFrames =
            slot->sourceRampFrames.load(std::memory_order_relaxed);
        candidate.laneRampFrames = slot->laneRampFrames.load(std::memory_order_relaxed);
        candidate.masterRampFrames =
            slot->masterRampFrames.load(std::memory_order_relaxed);

        // Keep every payload load inside the observed even-guard interval on weakly ordered CPUs.
        std::atomic_thread_fence(std::memory_order_acquire);
        const std::uint64_t after = slot->guard.load(std::memory_order_relaxed);
        if (!readable || before != after || (after & 1U) != 0U ||
            candidate.generation != expectedGeneration ||
            candidate.selectionToken != expectedSelectionToken ||
            !validSourceMode(sourceMode) || !candidate.valid()) {
            return false;
        }
        snapshot = candidate;
        return true;
    }

private:
    struct alignas(64) Slot final {
        std::atomic<std::uint64_t> guard{0U};
        std::atomic<std::uint64_t> generation{0U};
        std::atomic<std::uint64_t> selectionToken{0U};
        std::atomic<std::uint64_t> publicationSequence{0U};
        std::atomic<std::uint64_t> canonicalControlRevision{0U};
        std::atomic<std::uint64_t> sourceIntentRevision{0U};
        std::atomic<std::uint64_t> stemIntentRevision{0U};
        std::atomic<std::uint64_t> stemGainPreviewPublicationSequence{0U};
        std::atomic<std::uint64_t> stemGainPreviewCoversOfferSequence{0U};
        std::atomic<std::uint32_t> stemGainPreviewActiveMask{0U};
        std::array<std::atomic<std::uint32_t>, kStemwiseStemBusCount>
            stemGainPreviewGainBits{};
        std::atomic<std::uint32_t> sourceMode{0U};
        std::atomic<std::uint32_t> requiredStemMask{0U};
        std::array<std::atomic<std::uint32_t>, kStemwiseStemBusCount>
            effectiveStemGainBits{};
        std::atomic<std::uint32_t> musicGainBits{0U};
        std::atomic<std::uint32_t> mixHeadroomBits{0U};
        std::atomic<std::uint32_t> sourceRampFrames{0U};
        std::atomic<std::uint32_t> laneRampFrames{0U};
        std::atomic<std::uint32_t> masterRampFrames{0U};
        std::atomic<std::uint32_t> readable{0U};
    };

    static_assert(std::atomic<std::uint64_t>::is_always_lock_free);
    static_assert(std::atomic<std::uint32_t>::is_always_lock_free);

    [[nodiscard]] static bool validSourceMode(std::uint32_t sourceMode) noexcept {
        return sourceMode == static_cast<std::uint32_t>(LateStemSourceMode::Original) ||
            sourceMode == static_cast<std::uint32_t>(LateStemSourceMode::Stems);
    }

    [[nodiscard]] static bool validSnapshot(
        const LateStemMixControlSnapshot &snapshot) noexcept {
        return validSourceMode(static_cast<std::uint32_t>(snapshot.sourceMode)) &&
            snapshot.valid();
    }

    static void beginWrite(Slot &slot) noexcept {
        beginGuardedPublication(slot.guard);
    }

    static void endWrite(Slot &slot) noexcept {
        slot.guard.fetch_add(1U, std::memory_order_release);
    }

    [[nodiscard]] Slot *slotForRole(std::uint32_t role) noexcept {
        return role < mSlots.size() ? &mSlots[role] : nullptr;
    }

    [[nodiscard]] const Slot *slotForRole(std::uint32_t role) const noexcept {
        return role < mSlots.size() ? &mSlots[role] : nullptr;
    }

    std::array<Slot, kRoleCount> mSlots{};
};

}  // namespace stemwise::audio
