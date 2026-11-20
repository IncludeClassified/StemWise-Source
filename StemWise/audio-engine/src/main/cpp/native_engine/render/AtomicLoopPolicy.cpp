#include "GuardedAtomicSnapshot.h"
#include "AtomicLoopPolicy.h"

#include <atomic>

namespace stemwise::audio::engine {

bool AtomicLoopPolicy::publish(const AtomicLoopPolicySnapshot &snapshot) noexcept {
    if (!snapshot.valid()) return false;
    const std::uint64_t current = mSlot.revision.load(std::memory_order_acquire);
    if (snapshot.revision < current) return false;
    if (snapshot.revision == current) {
        AtomicLoopPolicySnapshot published{};
        return tryRead(published) && published == snapshot;
    }

    beginGuardedPublication(mSlot.guard);
    mSlot.selectionToken.store(snapshot.selectionToken, std::memory_order_relaxed);
    mSlot.enabled.store(snapshot.enabled ? 1U : 0U, std::memory_order_relaxed);
    mSlot.boundaryArmedAtPublication.store(
        snapshot.boundaryArmedAtPublication ? 1U : 0U,
        std::memory_order_relaxed);
    mSlot.aSourceFrame.store(snapshot.aSourceFrame, std::memory_order_relaxed);
    mSlot.bSourceFrameExclusive.store(
        snapshot.bSourceFrameExclusive,
        std::memory_order_relaxed);
    mSlot.acceptedAtMonotonicMicros.store(
        snapshot.acceptedAtMonotonicMicros,
        std::memory_order_relaxed);
    mSlot.inputEventAtMonotonicMicros.store(
        snapshot.inputEventAtMonotonicMicros,
        std::memory_order_relaxed);
    mSlot.heldBGestureId.store(snapshot.heldBGestureId, std::memory_order_relaxed);
    mSlot.revision.store(snapshot.revision, std::memory_order_relaxed);
    mSlot.guard.fetch_add(1U, std::memory_order_release);
    return true;
}

AtomicLoopAdoption AtomicLoopPolicy::adoptLatest(
    std::uint64_t currentSelectionToken) noexcept {
    if (currentSelectionToken == 0U) {
        bindSelection(0U);
        return AtomicLoopAdoption::InvalidSelection;
    }
    if (currentSelectionToken != mCurrentSelectionToken) {
        bindSelection(currentSelectionToken);
    }

    AtomicLoopPolicySnapshot candidate;
    if (!tryRead(candidate)) return AtomicLoopAdoption::Contended;
    if (candidate.selectionToken != currentSelectionToken) {
        return AtomicLoopAdoption::SelectionMismatch;
    }

    const bool isNew = !mHasAdopted || candidate.revision > mAdopted.revision;
    if (isNew) {
        mAdopted = candidate;
        mHasAdopted = true;
    }

    return isNew ? AtomicLoopAdoption::Adopted : AtomicLoopAdoption::NoChange;
}

bool AtomicLoopPolicy::tryRead(AtomicLoopPolicySnapshot &snapshot) const noexcept {
    const std::uint64_t before = mSlot.guard.load(std::memory_order_acquire);
    if ((before & 1U) != 0U) return false;

    AtomicLoopPolicySnapshot candidate;
    candidate.revision = mSlot.revision.load(std::memory_order_relaxed);
    candidate.selectionToken = mSlot.selectionToken.load(std::memory_order_relaxed);
    candidate.enabled = mSlot.enabled.load(std::memory_order_relaxed) != 0U;
    candidate.boundaryArmedAtPublication =
        mSlot.boundaryArmedAtPublication.load(std::memory_order_relaxed) != 0U;
    candidate.aSourceFrame = mSlot.aSourceFrame.load(std::memory_order_relaxed);
    candidate.bSourceFrameExclusive =
        mSlot.bSourceFrameExclusive.load(std::memory_order_relaxed);
    candidate.acceptedAtMonotonicMicros =
        mSlot.acceptedAtMonotonicMicros.load(std::memory_order_relaxed);
    candidate.inputEventAtMonotonicMicros =
        mSlot.inputEventAtMonotonicMicros.load(std::memory_order_relaxed);
    candidate.heldBGestureId =
        mSlot.heldBGestureId.load(std::memory_order_relaxed);

    std::atomic_thread_fence(std::memory_order_acquire);
    const std::uint64_t after = mSlot.guard.load(std::memory_order_relaxed);
    if (before != after || (after & 1U) != 0U || !candidate.valid()) return false;
    snapshot = candidate;
    return true;
}

void AtomicLoopPolicy::bindSelection(std::uint64_t selectionToken) noexcept {
    mCurrentSelectionToken = selectionToken;
    mHasAdopted = false;
    mAdopted = {};
}

} // namespace stemwise::audio::engine
