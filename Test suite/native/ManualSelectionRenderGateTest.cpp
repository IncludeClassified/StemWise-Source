#include "ManualSelectionRenderGate.h"

#include <atomic>
#include <chrono>
#include <cstdint>
#include <iostream>
#include <stdexcept>
#include <thread>

namespace {

using stemwise::audio::ManualSelectionRenderGate;

void require(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}

bool waitFor(const std::atomic<int> &stage, int expected) {
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
    while (stage.load(std::memory_order_acquire) != expected) {
        if (std::chrono::steady_clock::now() >= deadline) return false;
        std::this_thread::yield();
    }
    return true;
}

void callbackDecisionBeforeArmIsBoundedByCapturedCeiling() {
    ManualSelectionRenderGate gate;
    std::atomic<std::uint64_t> callbackCount{0};
    std::atomic<std::uint64_t> callbackSequence{0};
    std::atomic<bool> allowed{false};
    std::atomic<bool> barrierReleased{false};
    std::atomic<int> stage{0};

    std::jthread callback([&] {
        const std::uint64_t sequence =
            callbackCount.fetch_add(1U, std::memory_order_seq_cst) + 1U;
        callbackSequence.store(sequence, std::memory_order_relaxed);
        allowed.store(gate.allowsRender(1U, true), std::memory_order_relaxed);
        stage.store(1, std::memory_order_release);
        barrierReleased.store(waitFor(stage, 2), std::memory_order_release);
    });
    const bool callbackEntered = waitFor(stage, 1);
    gate.arm();
    const std::uint64_t armCeiling = callbackCount.load(std::memory_order_seq_cst);
    stage.store(2, std::memory_order_release);
    callback.join();

    require(callbackEntered, "pre-arm callback reached the decision barrier");
    require(barrierReleased.load(std::memory_order_acquire), "pre-arm callback barrier released");
    require(allowed.load(std::memory_order_relaxed),
        "a callback that decided OPEN before arm remains the one legal outgoing tail");
    require(callbackSequence.load(std::memory_order_relaxed) <= armCeiling,
        "the legal pre-arm callback ticket is bounded by the captured ceiling");
}

void callbackCountedBeforeArmButDecidingAfterArmIsSilent() {
    ManualSelectionRenderGate gate;
    std::atomic<std::uint64_t> callbackCount{0};
    std::atomic<std::uint64_t> callbackSequence{0};
    std::atomic<bool> allowed{true};
    std::atomic<bool> barrierReleased{false};
    std::atomic<int> stage{0};

    std::jthread callback([&] {
        const std::uint64_t sequence =
            callbackCount.fetch_add(1U, std::memory_order_seq_cst) + 1U;
        callbackSequence.store(sequence, std::memory_order_relaxed);
        stage.store(1, std::memory_order_release);
        barrierReleased.store(waitFor(stage, 2), std::memory_order_release);
        allowed.store(gate.allowsRender(1U, true), std::memory_order_relaxed);
    });
    const bool callbackEntered = waitFor(stage, 1);
    gate.arm();
    const std::uint64_t armCeiling = callbackCount.load(std::memory_order_seq_cst);
    stage.store(2, std::memory_order_release);
    callback.join();

    require(callbackEntered, "counted callback reached the decision barrier");
    require(barrierReleased.load(std::memory_order_acquire), "counted callback barrier released");
    require(callbackSequence.load(std::memory_order_relaxed) <= armCeiling,
        "the ceiling may conservatively include a callback that has not decided yet");
    require(!allowed.load(std::memory_order_relaxed),
        "a callback deciding after arm must render silence even when its ticket is in ceiling");
}

void callbackEnteringAfterArmIsSilentAndAboveCeiling() {
    ManualSelectionRenderGate gate;
    std::atomic<std::uint64_t> callbackCount{0};
    gate.arm();
    const std::uint64_t armCeiling = callbackCount.load(std::memory_order_seq_cst);
    const std::uint64_t callbackSequence =
        callbackCount.fetch_add(1U, std::memory_order_seq_cst) + 1U;
    const bool allowed = gate.allowsRender(1U, true);

    require(callbackSequence > armCeiling,
        "a post-arm callback ticket is above the exact captured ceiling");
    require(!allowed, "a callback entering after arm must render silence");
}

}  // namespace

int main() {
    try {
        callbackDecisionBeforeArmIsBoundedByCapturedCeiling();
        callbackCountedBeforeArmButDecidingAfterArmIsSilent();
        callbackEnteringAfterArmIsSilentAndAboveCeiling();
        std::cout
            << "ManualSelectionRenderGate tests passed: pre-arm decision ceiling, "
            << "count-before/decision-after silence, and post-arm silence\n";
        return 0;
    } catch (const std::exception &error) {
        std::cerr << "ManualSelectionRenderGate test failure: " << error.what() << '\n';
        return 1;
    }
}
