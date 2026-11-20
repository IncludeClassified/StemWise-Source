#pragma once

#include "AllocationProbeAttributes.h"
#include "CurrentSongPcmCacheTestHooks.h"
#include "native_engine/runtime/NativePlaybackRuntime.h"
#include "native_engine/runtime/NativePlaybackResources.h"
#include "NativeAudioOutputLoopBoundaryGate.h"
#include "NativeAudioOutputLoopDemandRetirement.h"
#include "OutputCallbackOrchestrator.h"
#include "LateStemMixControlMailbox.h"
#include "GuardedAtomicSnapshot.h"

#include <algorithm>
#include <array>
#include <bit>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <iostream>
#include <limits>
#include <new>
#include <mutex>
#include <stdexcept>
#include <string>
#include <string_view>
#include <thread>
#include <type_traits>
#include <utility>
#include <vector>

#ifdef _WIN32
#define NOMINMAX
#include <Windows.h>
#include <timeapi.h>
#pragma comment(lib, "winmm.lib")
#endif

namespace allocation_probe {

std::atomic<bool> enabled{false};
std::atomic<std::uint64_t> count{0};
std::atomic<bool> failNext{false};
std::atomic<std::uint64_t> failed{0};
std::atomic<std::size_t> failSize{0U};
std::atomic<std::size_t> liveBytes{}, peakBytes{};
struct alignas(std::max_align_t) AllocationHeader { std::size_t size; bool tracked; };

}  // namespace allocation_probe

namespace stemwise::audio::engine::cache_test {
std::atomic<int> fault{0}, shortCalls{0};
std::atomic<bool> observed{false};
std::atomic<bool> blocked{false}, releaseBlocked{true}, blockTimedOut{false};
std::atomic<unsigned> prepareFailures{0U};
void blockBoundary(int mode) noexcept {
    if (fault.load() != mode) return;
    blocked.store(true, std::memory_order_release);
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(3);
    while (!releaseBlocked.load(std::memory_order_acquire)) {
        if (std::chrono::steady_clock::now() >= deadline) { blockTimedOut.store(true); return; }
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
}
void beforePrepareLanes() {
    blockBoundary(9);
    if (fault.load() == 10) { prepareFailures.fetch_add(1U); throw std::bad_alloc(); }
}
void beforeRetireCache() noexcept { blockBoundary(11); }
void beforeInheritCache() noexcept { blockBoundary(12); }
std::uint64_t availableStorageBytes() noexcept {
    blockBoundary(6);
    if (fault.load() == 2) { observed.store(true); return 512ULL * 1024ULL * 1024ULL; }
    return 64ULL * 1024ULL * 1024ULL * 1024ULL;
}
std::size_t beforeIo(bool writing, std::size_t requested) noexcept {
    if (!writing) if (auto hook = beforeReadIo.load()) hook();
    const int mode = fault.load();
    blockBoundary(writing ? 7 : 8);
    if (mode >= 6) return requested;
    if (mode == 5 && !writing) { observed.store(true); return 0U; }
    if (!writing || mode == 0 || mode == 2 || mode == 5) return requested;
    if (mode == 4 && requested == 2048U * 2U * sizeof(float)) return requested;
    observed.store(true);
    if (mode == 3 && shortCalls.fetch_add(1) == 0) return std::min(requested, std::size_t{37U});
    return 0U;
}
}

STEMWISE_ALLOCATION_HOOK void *operator new(std::size_t size) {
    bool armed = allocation_probe::failNext.load(std::memory_order_acquire);
    if (armed &&
        (allocation_probe::failSize.load(std::memory_order_relaxed) == 0U ||
         allocation_probe::failSize.load(std::memory_order_relaxed) == size) &&
        allocation_probe::failNext.compare_exchange_strong(
            armed,
            false,
            std::memory_order_acq_rel,
            std::memory_order_acquire)) {
        allocation_probe::failed.fetch_add(1U, std::memory_order_relaxed);
        throw std::bad_alloc();
    }
    const bool tracked = allocation_probe::enabled.load(std::memory_order_relaxed);
    auto *header = static_cast<allocation_probe::AllocationHeader *>(
        std::malloc(sizeof(allocation_probe::AllocationHeader) + size));
    if (!header) throw std::bad_alloc();
    header->size = size; header->tracked = tracked;
    if (tracked) {
        allocation_probe::count.fetch_add(1U, std::memory_order_relaxed);
        const auto live = allocation_probe::liveBytes.fetch_add(size) + size;
        auto peak = allocation_probe::peakBytes.load();
        while (peak < live && !allocation_probe::peakBytes.compare_exchange_weak(peak, live)) {}
    }
    return header + 1;
}

STEMWISE_ALLOCATION_HOOK void *operator new[](std::size_t size) {
    return ::operator new(size);
}

STEMWISE_ALLOCATION_HOOK void operator delete(void *allocation) noexcept {
    if (!allocation) return;
    auto *header = static_cast<allocation_probe::AllocationHeader *>(allocation) - 1;
    if (header->tracked) allocation_probe::liveBytes.fetch_sub(header->size);
    std::free(header);
}

STEMWISE_ALLOCATION_HOOK void operator delete[](void *allocation) noexcept {
    ::operator delete(allocation);
}

STEMWISE_ALLOCATION_HOOK void operator delete(void *allocation, std::size_t) noexcept {
    ::operator delete(allocation);
}

STEMWISE_ALLOCATION_HOOK void operator delete[](void *allocation, std::size_t) noexcept {
    ::operator delete(allocation);
}

using stemwise::audio::engine::NativeRenderBusSubmission;

namespace {

using stemwise::audio::MetronomeMode;
using stemwise::audio::MixerSourceMode;
using stemwise::audio::AtomicNativeAudioOutputLoopPolicy;
using stemwise::audio::NativeAudioOutputHandle;
using stemwise::audio::NativeAudioLoopBoundaryCoverage;
using stemwise::audio::NativeAudioLoopCutoverCommitStatus;
using stemwise::audio::NativeAudioOutputLoopPolicy;
using stemwise::audio::NativeAudioAppliedControlEvidence;
using stemwise::audio::NativeAudioAudiblePosition;
using stemwise::audio::NativeAudioAudibleLoopEvidence;
using stemwise::audio::NativeAudioDrainCancelStatus;
using stemwise::audio::NativeAudioFlushStatus;
using stemwise::audio::NativeAudioLateMixControl;
using stemwise::audio::NativeAudioPresentationEvidence;
using stemwise::audio::NativeAudioPresentedPosition;
using stemwise::audio::NativeAudioSourceMode;
using stemwise::audio::NativeAudioStemGainCallbackEvidence;
constexpr std::uint32_t kStemwiseAllStemBusesReadyMask = 0x3FU;
using stemwise::audio::kStemwiseMultibusStride;
using stemwise::audio::stemwiseOriginalChannel;
using stemwise::audio::stemwiseStemChannel;
using stemwise::audio::engine::LaneEndOfStreamStatus;
using stemwise::audio::engine::LaneIngress;
using stemwise::audio::engine::LaneInputBlock;
using stemwise::audio::engine::LaneInputFormat;
using stemwise::audio::engine::LaneOfferStatus;
using stemwise::audio::engine::LanePcmEncoding;
using stemwise::audio::engine::NativePlaybackBank;
using stemwise::audio::engine::NativePlaybackBankIntent;
using stemwise::audio::engine::NativePlaybackAutomaticSuccessorIntent;
using stemwise::audio::engine::NativePlaybackAutomaticSuccessorPhase;
using stemwise::audio::engine::NativePlaybackBeatGridUpdate;
using stemwise::audio::engine::NativePlaybackCommandSnapshot;
using stemwise::audio::engine::NativePlaybackEndOrderingEvent;
using stemwise::audio::engine::NativePlaybackIngressServicePlan;
using stemwise::audio::engine::NativePlaybackOutputPort;
using stemwise::audio::engine::NativePlaybackPublishStatus;
using stemwise::audio::engine::NativePlaybackRenderReadinessDiagnostics;
using stemwise::audio::engine::NativePlaybackRuntime;
using stemwise::audio::engine::NativePlaybackRuntimeConfiguration;
using stemwise::audio::engine::NativePlaybackRuntimeDiagnostics;
using stemwise::audio::engine::NativePlaybackRuntimeStatus;
using stemwise::audio::engine::NativePlaybackStemGainPreview;
using stemwise::audio::engine::NativePlaybackWorkerServiceAction;
using stemwise::audio::engine::NativePlaybackWorkerServiceKind;
using stemwise::audio::engine::SongLaneIngressBank;
using stemwise::audio::engine::LoopCycleIntent;
using stemwise::audio::engine::LoopCycleActivation;
using stemwise::audio::engine::LoopCycleIngressReadyEvidence;
using stemwise::audio::engine::LoopCycleIngressStatus;
using stemwise::audio::engine::LoopCycleMode;
using stemwise::audio::engine::LoopCyclePhase;
using stemwise::audio::engine::LoopHeadBoundaryMode;
using stemwise::audio::engine::LoopHeadCache;
using stemwise::audio::engine::LoopHeadContentKey;
using stemwise::audio::engine::LoopHeadPreparationBeginStatus;
using stemwise::audio::engine::LoopHeadPreparationConfigureStatus;
using stemwise::audio::engine::LoopHeadPreparationEndStatus;
using stemwise::audio::engine::LoopHeadPreparationOfferStatus;
using stemwise::audio::engine::SongRenderControlSnapshot;
using stemwise::audio::engine::SongRenderOwnership;
using stemwise::audio::engine::kNativePlaybackBankCount;
using stemwise::audio::engine::kSongRenderLaneCount;

constexpr std::uint32_t kSampleRate = 48'000;
// Legacy fixtures author six parts; capacity tests use kSongRenderLaneCount.
constexpr std::size_t kLaneCount = 7U;
constexpr std::size_t kMaximumFakeRecords = 65'536;
constexpr double kPi = 3.14159265358979323846;

void require(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}

[[nodiscard]] constexpr bool sameOwnership(
    SongRenderOwnership left,
    SongRenderOwnership right) noexcept {
    return left.selectionToken == right.selectionToken &&
        left.generation == right.generation;
}

template <typename Predicate>
void waitUntil(Predicate &&predicate, const char *message, std::uint32_t timeoutMs = 4'000) {
    const auto deadline = std::chrono::steady_clock::now() +
        std::chrono::milliseconds(timeoutMs);
    while (!predicate()) {
        if (std::chrono::steady_clock::now() >= deadline) {
            throw std::runtime_error(message);
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
}

template <typename Predicate, typename Diagnostic>
void waitUntilDiagnosed(Predicate &&predicate, Diagnostic &&diagnostic, const char *message) {
    try {
        waitUntil(std::forward<Predicate>(predicate), message);
    } catch (const std::runtime_error &) {
        throw std::runtime_error(std::string(message) + " " + diagnostic());
    }
}

std::uint64_t testMonotonicMicros() noexcept {
    const auto elapsed = std::chrono::steady_clock::now().time_since_epoch();
    return static_cast<std::uint64_t>(
        std::chrono::duration_cast<std::chrono::microseconds>(elapsed).count());
}

template <typename Action>
class ScopeExit final {
public:
    explicit ScopeExit(Action action) noexcept(std::is_nothrow_move_constructible_v<Action>)
        : mAction(std::move(action)) {}

    ScopeExit(const ScopeExit &) = delete;
    ScopeExit &operator=(const ScopeExit &) = delete;

    ~ScopeExit() noexcept {
        mAction();
    }

private:
    Action mAction;
};

template <typename Action>
[[nodiscard]] auto onScopeExit(Action &&action) {
    return ScopeExit<std::decay_t<Action>>(std::forward<Action>(action));
}

void waitForRenderProgress(
    NativePlaybackRuntime &runtime,
    std::uint64_t priorRenderCycles,
    const char *message,
    std::uint64_t additionalCycles = 1U) {
    waitUntil([&] {
        return runtime.diagnostics().renderCycles >= priorRenderCycles + additionalCycles;
    }, message);
}

#include "NativePlaybackRuntimeTestOutput.inc"

NativePlaybackRuntimeConfiguration runtimeConfiguration(
    std::size_t reservoirFrames = 16'384,
    std::uint32_t blockFrames = 64) {
    NativePlaybackRuntimeConfiguration result{};
    result.pipeline.diagnosticPayloadFingerprints = true;
    result.pipeline.maximumBlockFrames = blockFrames;
    result.pipeline.laneReservoirCapacityFrames = std::max<std::size_t>(16'384, reservoirFrames);
    result.pipeline.tuneTransitionFrames = 32;
    result.targetBursts = 4;
    // Most legacy runtime cases intentionally exercise the historical four-burst target. The
    // dedicated reserve-floor case below verifies the 48 ms production default end to end.
    result.minimumOutputReserveMicros =
        NativePlaybackRuntimeConfiguration::kMinimumOutputReserveMicros;
    result.fallbackFramesPerBurst = 64;
    result.ingressPullFrames = 256;
    result.maximumPullsPerLanePerCycle = 4;
    result.maximumOutputBlocksPerCycle = 16;
    result.idleSleepMicros = 100;
    return result;
}

struct CommandPublicationVisibilityBarrier final {
    explicit CommandPublicationVisibilityBarrier(std::uint64_t target) noexcept
        : targetRevision(target) {}

    static void hook(
        void *context,
        const NativePlaybackCommandSnapshot &snapshot) noexcept {
        auto &self = *static_cast<CommandPublicationVisibilityBarrier *>(context);
        if (snapshot.revision != self.targetRevision) return;
        self.visible.store(true, std::memory_order_release);
        self.visible.notify_all();
        self.released.wait(false, std::memory_order_acquire);
    }

    void release() noexcept {
        released.store(true, std::memory_order_release);
        released.notify_all();
    }

    std::uint64_t targetRevision{0};
    std::atomic<bool> visible{false};
    std::atomic<bool> released{false};
};

void writeEndOrderingEvent(
    const char *label,
    const NativePlaybackEndOrderingEvent &event) {
    std::cout << ' ' << label << "={sequence:" << event.sequence
              << ",worker-state:" << event.workerStateSequence
              << ",revision:" << event.commandRevision
              << ",token:" << event.ownership.selectionToken
              << ",generation:" << event.ownership.generation
              << ",desired:" << event.desiredPlaying
              << ",output:" << event.outputPlaying
              << ",unread:" << event.commandUnread
              << ",pending:" << event.pendingOutputFrames
              << ",queued:" << event.queuedOutputFrames
              << ",eos:" << event.pipelineEndObserved
              << ",terminal-valid:" << event.terminalPositionValid
              << ",terminal-after:" << event.terminalAfterFrame << '}';
}

SongRenderControlSnapshot controls(
    std::uint64_t revision,
    float tempo = 1.0F,
    float pitch = 0.0F,
    MixerSourceMode source = MixerSourceMode::Original) {
    SongRenderControlSnapshot result{};
    result.revision = revision;
    result.sourceIntentRevision = 1U;
    result.stemIntentRevision = 1U;
    result.mixer.revision = revision;
    result.mixer.sourceMode = source;
    result.mixer.sourceTransitionFrames = 8;
    result.mixer.laneRampFrames = 8;
    result.mixer.masterRampFrames = 8;
    result.mixer.defaultDeclickFrames = 8;
    result.mixer.metronome.mode = MetronomeMode::Off;
    result.tempo = tempo;
    result.pitchSemitones = pitch;
    return result;
}

SongRenderControlSnapshot stemPreviewControls(
    std::uint64_t revision,
    std::uint64_t sourceIntentRevision = 101U,
    std::uint64_t stemIntentRevision = 201U) {
    SongRenderControlSnapshot result = controls(
        revision,
        1.0F,
        0.0F,
        MixerSourceMode::Stems);
    result.sourceIntentRevision = sourceIntentRevision;
    result.stemIntentRevision = stemIntentRevision;
    result.mixer.sourceTransitionFrames = 1U;
    result.mixer.laneRampFrames = 1U;
    result.mixer.masterRampFrames = 1U;
    result.mixer.stems[0].soloed = true;
    return result;
}

NativePlaybackStemGainPreview stemGainPreview(
    std::uint64_t publicationSequence,
    std::uint64_t coversOfferSequence,
    SongRenderOwnership ownership,
    std::uint64_t canonicalControlFloorRevision,
    std::uint32_t activeMask,
    float firstStemGain,
    std::uint64_t sourceIntentRevision = 101U,
    std::uint64_t stemIntentRevision = 201U) {
    NativePlaybackStemGainPreview result{};
    result.publicationSequence = publicationSequence;
    result.coversOfferSequence = coversOfferSequence;
    result.ownership = ownership;
    result.sourceIntentRevision = sourceIntentRevision;
    result.stemIntentRevision = stemIntentRevision;
    result.canonicalControlFloorRevision = canonicalControlFloorRevision;
    result.activeMask = activeMask;
    result.gains.fill(1.0F);
    result.gains[0] = firstStemGain;
    return result;
}

NativePlaybackBankIntent assignedBank(
    std::uint64_t revision,
    SongRenderOwnership ownership,
    std::uint64_t firstFrame = 0,
    std::uint32_t partCount = 6U) {
    return {
        revision,
        true,
        ownership,
        firstFrame,
        0,
        0x1111'2222'3333'4444ULL,
        0x5555'6666'7777'8888ULL,
        0x9999'AAAA'BBBB'CCCCULL,
        partCount,
        firstFrame + 1'048'576U,
    };
}

NativePlaybackCommandSnapshot selectedCommand(
    std::uint64_t revision,
    NativePlaybackBank selected,
    SongRenderOwnership owner,
    const std::array<NativePlaybackBankIntent, 2> &banks,
    SongRenderControlSnapshot control,
    bool playing = true) {
    NativePlaybackCommandSnapshot result{};
    result.revision = revision;
    result.outputGeneration = owner.generation;
    result.banks = banks;
    result.selectionAssigned = true;
    result.selectedBank = selected;
    result.selectedOwnership = owner;
    result.playing = playing;
    result.controls = control;
    return result;
}

NativePlaybackCommandSnapshot unselectedCommand(
    std::uint64_t revision,
    std::uint64_t outputGeneration,
    const std::array<NativePlaybackBankIntent, 2> &banks,
    SongRenderControlSnapshot control) {
    NativePlaybackCommandSnapshot result{};
    result.revision = revision;
    result.outputGeneration = outputGeneration;
    result.banks = banks;
    result.selectionAssigned = false;
    result.playing = false;
    result.controls = control;
    result.controls.sourceIntentRevision = 0U;
    result.controls.stemIntentRevision = 0U;
    return result;
}

void configureLanes(
    SongLaneIngressBank &bank,
    std::uint64_t generation,
    std::size_t count = kLaneCount) {
    for (std::size_t lane = 0; lane < count; ++lane) {
        require(bank.configureLane(lane, kSampleRate, generation), "configure lane");
    }
}

std::vector<float> tone(
    std::size_t frameCount,
    std::uint64_t firstFrame,
    float amplitude,
    double frequency) {
    std::vector<float> result(frameCount * 2U);
    for (std::size_t frame = 0; frame < frameCount; ++frame) {
        const double phase = 2.0 * kPi * frequency *
            static_cast<double>(firstFrame + frame) / static_cast<double>(kSampleRate);
        const float value = amplitude * static_cast<float>(std::sin(phase));
        result[frame * 2U] = value;
        result[frame * 2U + 1U] = -value;
    }
    return result;
}

void offerLane(
    SongLaneIngressBank &bank,
    std::size_t laneIndex,
    std::uint64_t generation,
    std::uint64_t firstFrame,
    std::size_t frameCount,
    bool eos = true) {
    auto *lane = bank.lane(laneIndex);
    require(lane != nullptr, "offered lane configured");
    constexpr std::size_t quantum = 480;
    std::size_t offset = 0;
    while (offset < frameCount) {
        const std::size_t count = std::min(quantum, frameCount - offset);
        const auto samples = tone(count, firstFrame + offset, 0.025F + 0.002F *
            static_cast<float>(laneIndex), 220.0 + 17.0 * static_cast<double>(laneIndex));
        LaneInputBlock block{};
        block.data = samples.data();
        block.frameCount = count;
        block.format = LaneInputFormat{kSampleRate, 2, LanePcmEncoding::Float32};
        block.generation = generation;
        block.presentationTimeUs = (firstFrame + offset) * 1'000'000U / kSampleRate;
        const auto offered = lane->offer(block);
        require(
            offered.status == LaneOfferStatus::Accepted &&
                offered.inputFramesAccepted == count,
            "lane offer accepted");
        offset += count;
    }
    if (eos) {
        const auto marked = lane->markEndOfStream(generation, firstFrame + frameCount);
        require(
            marked.status == LaneEndOfStreamStatus::Accepted ||
                marked.status == LaneEndOfStreamStatus::AlreadyMarked,
            "lane EOS accepted");
    }
}

void offerAllLanes(
    SongLaneIngressBank &bank,
    std::uint64_t generation,
    std::size_t frameCount,
    std::size_t count = kLaneCount) {
    for (std::size_t lane = 0; lane < count; ++lane) {
        offerLane(bank, lane, generation, 0, frameCount);
    }
}

[[nodiscard]] LoopCycleIntent managedLoopIntent(
    std::uint64_t revision,
    SongRenderOwnership ownership,
    std::uint64_t startFrame,
    std::uint32_t laneMask = 1U) noexcept {
    const std::uint64_t cacheEnd = startFrame + LoopHeadCache::kCapacityFrames;
    return {
        .revision = revision,
        .enabled = true,
        .owner = {
            .selectionToken = ownership.selectionToken,
            .renderGeneration = ownership.generation,
        },
        .cacheKey = {
            .songIdentityHash = 0x1111'2222'3333'4444ULL,
            .originalContentHash = 0x5555'6666'7777'8888ULL,
            .stemPackageHash = laneMask == 1U ? 0U : 0x9999'AAAA'BBBB'CCCCULL,
            .contentRevision = 7U,
            .loopRevision = revision,
            .startTimelineFrame = startFrame,
            .endTimelineFrameExclusive = cacheEnd,
        },
        .requiredLaneMask = laneMask,
        .declickFrames = 8U,
        .boundaryMode = LoopHeadBoundaryMode::OriginalEos,
    };
}

void prepareManagedLoopOriginal(
    NativePlaybackRuntime &runtime,
    const LoopCycleIntent &intent,
    std::uint64_t preparationGeneration) {
    require(runtime.beginLoopHeadPreparation(
        intent.cacheKey,
        intent.requiredLaneMask) == LoopHeadPreparationBeginStatus::Accepted,
        "managed loop cache begin");
    require(runtime.configureLoopHeadPreparationLane(
        intent.cacheKey.loopRevision,
        0U,
        kSampleRate,
        preparationGeneration) == LoopHeadPreparationConfigureStatus::Accepted,
        "managed loop original prep lane");

    const std::uint64_t length = intent.cacheKey.endTimelineFrameExclusive -
        intent.cacheKey.startTimelineFrame;
    const std::size_t requiredFrames = static_cast<std::size_t>(
        std::min<std::uint64_t>(length, 192'000U));
    // Feed a small suffix beyond a streamed head so normal SRC latency can produce the exact
    // 192000-frame cache boundary without padding or a direct-decoder shortcut.
    const std::size_t decodedFrames = requiredFrames == 192'000U
        ? requiredFrames + 2'048U
        : requiredFrames;
    const auto pcm = tone(
        decodedFrames,
        intent.cacheKey.startTimelineFrame,
        0.04F,
        233.0);
    std::size_t offset = 0U;
    while (offset < decodedFrames) {
        const std::size_t count = std::min<std::size_t>(480U, decodedFrames - offset);
        const auto offered = runtime.offerLoopHeadPreparationPcm(
            intent.cacheKey.loopRevision,
            0U,
            LaneInputBlock{
                .data = pcm.data() + offset * 2U,
                .frameCount = count,
                .format = {kSampleRate, 2U, LanePcmEncoding::Float32},
                .generation = preparationGeneration,
                .presentationTimeUs =
                    (intent.cacheKey.startTimelineFrame + offset) * 1'000'000U /
                    kSampleRate,
            });
        require(offered.inputFramesAccepted <= count, "managed cache accepted prefix bounded");
        offset += offered.inputFramesAccepted;
        if (offered.status == LoopHeadPreparationOfferStatus::CacheCommitted) return;
        require(
            offered.status == LoopHeadPreparationOfferStatus::Accepted ||
                offered.status == LoopHeadPreparationOfferStatus::Partial ||
                offered.status == LoopHeadPreparationOfferStatus::Backpressured,
            "managed cache offer accepted");
        require(offered.inputFramesAccepted != 0U, "managed cache offer progresses");
    }
    const LoopHeadPreparationEndStatus ended =
        runtime.markLoopHeadPreparationEndOfStream(
            intent.cacheKey.loopRevision,
            0U,
            preparationGeneration,
            intent.cacheKey.startTimelineFrame + decodedFrames);
    require(ended == LoopHeadPreparationEndStatus::CacheCommitted,
        "managed cache EOS commits");
}

void prepareShortManagedOriginalEos(
    NativePlaybackRuntime &runtime,
    const LoopCycleIntent &intent,
    std::uint64_t preparationGeneration,
    std::size_t decodedFrames) {
    require(decodedFrames > 0U && decodedFrames < 192'000U,
        "short Original-EOS preparation length");
    require(runtime.beginLoopHeadPreparation(
        intent.cacheKey,
        intent.requiredLaneMask,
        intent.boundaryMode) == LoopHeadPreparationBeginStatus::Accepted,
        "short Original-EOS cache begin");
    require(runtime.configureLoopHeadPreparationLane(
        intent.cacheKey.loopRevision,
        0U,
        kSampleRate,
        preparationGeneration) == LoopHeadPreparationConfigureStatus::Accepted,
        "short Original-EOS prep lane");
    const auto pcm = tone(decodedFrames, 0U, 0.04F, 233.0);
    std::size_t offset = 0U;
    while (offset < decodedFrames) {
        const std::size_t count = std::min<std::size_t>(480U, decodedFrames - offset);
        const auto offered = runtime.offerLoopHeadPreparationPcm(
            intent.cacheKey.loopRevision,
            0U,
            LaneInputBlock{
                .data = pcm.data() + offset * 2U,
                .frameCount = count,
                .format = {kSampleRate, 2U, LanePcmEncoding::Float32},
                .generation = preparationGeneration,
                .presentationTimeUs = offset * 1'000'000U / kSampleRate,
            });
        require(offered.status == LoopHeadPreparationOfferStatus::Accepted ||
                offered.status == LoopHeadPreparationOfferStatus::Partial ||
                offered.status == LoopHeadPreparationOfferStatus::Backpressured,
            "short Original-EOS cache offer accepted");
        require(offered.inputFramesAccepted != 0U,
            "short Original-EOS cache offer progresses");
        offset += offered.inputFramesAccepted;
    }
    require(runtime.markLoopHeadPreparationEndOfStream(
        intent.cacheKey.loopRevision,
        0U,
        preparationGeneration,
        decodedFrames) == LoopHeadPreparationEndStatus::CacheCommitted,
        "short Original EOS shrinks and commits cache");
}

void prepareFullManagedLoopHead(
    NativePlaybackRuntime &runtime,
    const LoopCycleIntent &intent,
    std::uint64_t preparationGenerationBase,
    std::size_t laneCount = kLaneCount, SongRenderOwnership ownership = {}) {
    require(runtime.beginLoopHeadPreparation(
        intent.cacheKey,
        intent.requiredLaneMask,
        intent.boundaryMode, ownership) == LoopHeadPreparationBeginStatus::Accepted,
        "multi-lane managed loop cache begin");
    for (std::size_t lane = 0U; lane < laneCount; ++lane) {
        const std::uint32_t bit = 1U << static_cast<std::uint32_t>(lane);
        if ((intent.requiredLaneMask & bit) == 0U) continue;
        const std::uint64_t generation = preparationGenerationBase + lane;
        require(runtime.configureLoopHeadPreparationLane(
            intent.cacheKey.loopRevision,
            lane,
            kSampleRate,
            generation) == LoopHeadPreparationConfigureStatus::Accepted,
            "multi-lane managed loop cache lane configured");
        const std::size_t decodedFrames = (intent.boundaryMode == LoopHeadBoundaryMode::RetainedWindow
            ? static_cast<std::size_t>(intent.cacheKey.endTimelineFrameExclusive - intent.cacheKey.startTimelineFrame)
            : LoopHeadCache::kCapacityFrames) + 2'048U;
        const auto pcm = tone(decodedFrames, intent.cacheKey.startTimelineFrame, 0.03F, 211.0 + lane * 19.0);
        std::size_t offset = 0U;
        bool laneComplete = false;
        while (offset < decodedFrames && !laneComplete) {
            const std::size_t count = std::min<std::size_t>(480U, decodedFrames - offset);
            const auto offered = runtime.offerLoopHeadPreparationPcm(
                intent.cacheKey.loopRevision,
                lane,
                LaneInputBlock{
                    .data = pcm.data() + offset * 2U,
                    .frameCount = count,
                    .format = {kSampleRate, 2U, LanePcmEncoding::Float32},
                    .generation = generation,
                    .presentationTimeUs = (intent.cacheKey.startTimelineFrame + offset) * 1'000'000U / kSampleRate,
                });
            require(offered.inputFramesAccepted <= count,
                "multi-lane managed loop offer bounded");
            offset += offered.inputFramesAccepted;
            laneComplete = offered.status == LoopHeadPreparationOfferStatus::LaneCompleted ||
                offered.status == LoopHeadPreparationOfferStatus::CacheCommitted;
            if (!laneComplete) {
                require(offered.status == LoopHeadPreparationOfferStatus::Accepted ||
                        offered.status == LoopHeadPreparationOfferStatus::Partial ||
                        offered.status == LoopHeadPreparationOfferStatus::Backpressured,
                    "multi-lane managed loop offer accepted");
                require(offered.inputFramesAccepted != 0U,
                    "multi-lane managed loop offer progresses");
            }
        }
        require(laneComplete, "multi-lane managed loop lane reaches immutable head boundary");
    }
}

void stopAndJoin(NativePlaybackRuntime &runtime) {
    runtime.requestStop();
    runtime.join();
    require(runtime.status() == NativePlaybackRuntimeStatus::Stopped, "runtime stopped");
}

template <typename Predicate>
std::size_t waitForRecord(
    FakeOutput &output,
    std::size_t firstIndex,
    Predicate &&predicate,
    const char *message,
    std::uint32_t timeoutMs = 4'000U,
    NativePlaybackRuntime *wakeAfterDrain = nullptr) {
    std::size_t match = kMaximumFakeRecords;
    waitUntil([&] {
        const std::size_t count = output.recordCount();
        for (std::size_t index = firstIndex; index < count; ++index) {
            if (predicate(output.record(index))) {
                match = index;
                return true;
            }
        }
        // Model the device callback draining queued PCM without manufacturing control evidence.
        // This also prevents an old fully queued revision from starving the newer render intent.
        output.consumeAll();
        // Some cases drive output explicitly; notify its owner after that external state change.
        if (wakeAfterDrain != nullptr) wakeAfterDrain->notifyIngressAvailable();
        return false;
    }, message, timeoutMs);
    return match;
}

} // namespace
