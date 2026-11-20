#pragma once

#include <atomic>
#include <cstdint>
#include <limits>

namespace stemwise::audio {

enum class ManualSelectionRenderGateState : std::uint8_t {
    Open = 0,
    Closed = 1,
    Authorized = 2,
};

struct ManualSelectionRenderGateSnapshot final {
    ManualSelectionRenderGateState state{ManualSelectionRenderGateState::Open};
    std::uint64_t authorizedGeneration{0};
    std::uint64_t armCount{0};
    std::uint64_t authorizationCount{0};
    std::uint64_t rejectedAuthorizationCount{0};
    std::uint64_t openCount{0};
};

/**
 * Lock-free callback gate for one serialized manual-selection replacement.
 *
 * One atomic word is the complete authority: zero is open, UINT64_MAX is closed and every other
 * value is the sole generation allowed to reopen the callback. Re-arming overwrites an older
 * authorization, so a delayed callback can never release a newer selection's gate. The callback
 * opens only after the exact generation is active and has PCM ready; while closed it must not read
 * either output ring.
 */
class ManualSelectionRenderGate final {
public:
    static constexpr std::uint64_t kOpen = 0U;
    static constexpr std::uint64_t kClosed = std::numeric_limits<std::uint64_t>::max();

    void arm() noexcept {
        // This store and the callback's decision load participate in the same sequentially
        // consistent order as NativeAudioOutput's callback ticket. That makes the captured arm
        // ceiling an exact proof for the sole callback that may already have observed OPEN.
        mEncodedState.store(kClosed, std::memory_order_seq_cst);
        mArmCount.fetch_add(1U, std::memory_order_relaxed);
    }

    [[nodiscard]] bool authorize(std::uint64_t generation) noexcept {
        if (generation == kOpen || generation == kClosed) {
            mRejectedAuthorizationCount.fetch_add(1U, std::memory_order_relaxed);
            return false;
        }
        std::uint64_t expected = kClosed;
        if (!mEncodedState.compare_exchange_strong(
                expected,
                generation,
                std::memory_order_acq_rel,
                std::memory_order_acquire)) {
            mRejectedAuthorizationCount.fetch_add(1U, std::memory_order_relaxed);
            return false;
        }
        mAuthorizationCount.fetch_add(1U, std::memory_order_relaxed);
        return true;
    }

    /** Called once at a callback boundary, after applying any exact generation role swap. */
    [[nodiscard]] bool allowsRender(
        std::uint64_t appliedGeneration,
        bool hasReadyPcm) noexcept {
        std::uint64_t state = mEncodedState.load(std::memory_order_seq_cst);
        if (state == kOpen) return true;
        if (!hasReadyPcm || state != appliedGeneration) return false;
        if (mEncodedState.compare_exchange_strong(
                state,
                kOpen,
                std::memory_order_acq_rel,
                std::memory_order_acquire)) {
            mOpenCount.fetch_add(1U, std::memory_order_relaxed);
            return true;
        }
        // Only another callback or route reset may have opened it. A concurrent re-arm or a newer
        // authorization always remains closed to this stale callback.
        return state == kOpen;
    }

    /** Route recovery has stopped the old callback and republishes the existing logical owner. */
    void resetOpenForRouteReopen() noexcept {
        mEncodedState.store(kOpen, std::memory_order_release);
    }

    [[nodiscard]] ManualSelectionRenderGateSnapshot snapshot() const noexcept {
        const std::uint64_t encoded = mEncodedState.load(std::memory_order_acquire);
        return ManualSelectionRenderGateSnapshot{
            .state = encoded == kOpen
                ? ManualSelectionRenderGateState::Open
                : encoded == kClosed
                    ? ManualSelectionRenderGateState::Closed
                    : ManualSelectionRenderGateState::Authorized,
            .authorizedGeneration = encoded == kOpen || encoded == kClosed ? 0U : encoded,
            .armCount = mArmCount.load(std::memory_order_relaxed),
            .authorizationCount = mAuthorizationCount.load(std::memory_order_relaxed),
            .rejectedAuthorizationCount =
                mRejectedAuthorizationCount.load(std::memory_order_relaxed),
            .openCount = mOpenCount.load(std::memory_order_relaxed),
        };
    }

private:
    std::atomic<std::uint64_t> mEncodedState{kOpen};
    std::atomic<std::uint64_t> mArmCount{0};
    std::atomic<std::uint64_t> mAuthorizationCount{0};
    std::atomic<std::uint64_t> mRejectedAuthorizationCount{0};
    std::atomic<std::uint64_t> mOpenCount{0};
};

static_assert(std::atomic<std::uint64_t>::is_always_lock_free);

}  // namespace stemwise::audio
