#include "GuardedAtomicSnapshot.h"
#include "../../StemGainCallbackPublication.h"
#include "NativePlaybackRuntime.h"
#include "NativePlaybackResources.h"
#include "LatestCommandMailbox.h"
#include "ReusableLoopContinuation.h"

#include "../dsp/TimePitchProcessor.h"
#include "../render/AtomicLoopPolicy.h"
#include "../render/CurrentSongPcmCache.h"
#include "../../NativeAudioOutputLoopBoundaryGate.h"
#include "../../AudioTrace.h"

#include <algorithm>
#include <atomic>
#include <bit>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstring>
#include <limits>
#include <mutex>
#include <stdexcept>
#include <thread>
#include <type_traits>
#include <utility>

#if defined(__ANDROID__)
#include <android/log.h>
#include <pthread.h>
#include <sys/resource.h>
#include <time.h>
#include <unistd.h>
#endif

namespace stemwise::audio::engine {
namespace {

constexpr std::size_t kMailboxSlotCount = 3;
constexpr std::size_t kNoMailboxReader = kMailboxSlotCount;
/** Mirrors the controller's minimum first-Stems activation quantum. */
constexpr std::size_t kMinimumStemActivationReadinessFrames = 1'024U;
/** Covers successor decoder retirement without increasing the current song's output reserve. */
constexpr std::uint64_t kAutomaticSuccessorReserveFrames =
    (static_cast<std::uint64_t>(NativePlaybackRuntimeConfiguration::kOutputSampleRateHz) *
         NativePlaybackRuntimeConfiguration::kMaximumOutputReserveMicros +
     NativePlaybackRuntimeConfiguration::kMicrosPerSecond - 1U) /
    NativePlaybackRuntimeConfiguration::kMicrosPerSecond;

[[nodiscard]] std::uint64_t diagnosticMonotonicMicros() noexcept {
#if defined(__ANDROID__)
    timespec now{};
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return 0U;
    return static_cast<std::uint64_t>(now.tv_sec) * 1'000'000ULL +
        static_cast<std::uint64_t>(now.tv_nsec) / 1'000ULL;
#else
    const auto elapsed = std::chrono::steady_clock::now().time_since_epoch();
    const auto micros = std::chrono::duration_cast<std::chrono::microseconds>(elapsed).count();
    return micros > 0 ? static_cast<std::uint64_t>(micros) : 0U;
#endif
}

/**
 * Keep the producer that feeds the real-time callback in Android's audio scheduling class.
 *
 * AudioTrack owns device scheduling; this producer requests audio priority for its separate 50 ms
 * render reserve. Canonical reads run on the I/O owner, independently of future DSP preparation.
 * A denied priority request is non-fatal and remains visible in diagnostics.
 */
struct RenderWorkerSchedulingResult final {
    bool audioPriorityRequested{false};
    bool audioPriorityApplied{false};
    std::int32_t niceValue{0};
    std::int32_t errorCode{0};
};

[[nodiscard]] RenderWorkerSchedulingResult configureCurrentRenderWorkerThread() noexcept {
#if defined(__ANDROID__)
    constexpr int kAndroidThreadPriorityAudio = -16;
    static_cast<void>(pthread_setname_np(pthread_self(), "StemwiseRender"));
    errno = 0;
    const int setResult = setpriority(
        PRIO_PROCESS,
        static_cast<id_t>(gettid()),
        kAndroidThreadPriorityAudio);
    const int setError = setResult == 0 ? 0 : errno;
    errno = 0;
    const int appliedNice = getpriority(
        PRIO_PROCESS,
        static_cast<id_t>(gettid()));
    const int getError = appliedNice == -1 && errno != 0 ? errno : 0;
    return {
        true,
        setResult == 0 && getError == 0 &&
            appliedNice <= kAndroidThreadPriorityAudio,
        static_cast<std::int32_t>(appliedNice),
        static_cast<std::int32_t>(setError != 0 ? setError : getError),
    };
#else
    return {};
#endif
}


// These counters belong to the native process, not to a Kotlin wrapper or one diagnostics caller.
// A leaked/duplicate runtime therefore remains visible from every surviving runtime snapshot.
std::atomic<std::uint64_t> gRuntimeConstructionCount{0};
std::atomic<std::uint64_t> gRuntimeLiveInstanceCount{0};
// Process-cumulative fault evidence cannot be laundered by replacing one runtime instance.
std::atomic<std::uint64_t> gRuntimeOutputInvalidRenderResultCount{0};
std::atomic<std::uint64_t> gRuntimeOutputFlushFailureCount{0};

[[nodiscard]] constexpr std::size_t bankIndex(NativePlaybackBank bank) noexcept {
    return static_cast<std::size_t>(bank);
}

[[nodiscard]] constexpr SongRenderLane renderLane(std::size_t lane) noexcept {
    return static_cast<SongRenderLane>(lane);
}

[[nodiscard]] constexpr bool validOwnership(SongRenderOwnership ownership) noexcept {
    return ownership.selectionToken != 0U && ownership.generation != 0U;
}

[[nodiscard]] constexpr bool sameOwnership(
    SongRenderOwnership left,
    SongRenderOwnership right) noexcept {
    return left.selectionToken == right.selectionToken &&
        left.generation == right.generation;
}

[[nodiscard]] constexpr bool validLoopOwner(LoopCycleOwner owner) noexcept {
    return owner.selectionToken != 0U && owner.renderGeneration != 0U;
}

[[nodiscard]] constexpr bool validLoopLaneMask(std::uint32_t mask) noexcept {
    return mask != 0U && (mask & 1U) != 0U &&
        (mask & kLoopHeadAllLanesMask) == mask;
}

[[nodiscard]] bool validLoopIntent(const LoopCycleIntent &intent) noexcept {
    if (intent.revision == 0U || !validLoopOwner(intent.owner)) return false;
    if (!intent.enabled) return intent.requiredLaneMask == 0U && !intent.cacheKey.valid();
    return intent.boundaryMode == LoopHeadBoundaryMode::OriginalEos &&
        intent.cacheKey.valid() && validLoopLaneMask(intent.requiredLaneMask) &&
        ((intent.requiredLaneMask & ~1U) == 0U || intent.cacheKey.stemPackageHash != 0U) &&
        intent.cacheKey.endTimelineFrameExclusive - intent.cacheKey.startTimelineFrame ==
            LoopHeadCache::kCapacityFrames;
}

[[nodiscard]] constexpr bool sameLoopStableIdentity(
    const LoopCycleIntent &left,
    const LoopCycleIntent &right) noexcept {
    if (left.revision != right.revision || left.enabled != right.enabled ||
        left.owner.selectionToken != right.owner.selectionToken) {
        return false;
    }
    return !left.enabled ||
        (left.cacheKey == right.cacheKey &&
         left.requiredLaneMask == right.requiredLaneMask &&
         left.declickFrames == right.declickFrames &&
         left.boundaryMode == right.boundaryMode);
}

[[nodiscard]] constexpr bool validLoopIngressReady(
    const LoopCycleIngressReadyEvidence &evidence) noexcept {
    return evidence.requestSequence != 0U && evidence.loopRevision != 0U &&
        evidence.targetCycle != 0U && validLoopOwner(evidence.owner) &&
        evidence.ingressEpoch != 0U && validLoopLaneMask(evidence.readyLaneMask) &&
        (evidence.eosPaddedLaneMask & ~kLoopHeadAllLanesMask) == 0U &&
        (evidence.eosPaddedLaneMask & ~evidence.readyLaneMask) == 0U &&
        (evidence.eosPaddedLaneMask & 1U) == 0U &&
        evidence.endTimelineFrameExclusive > evidence.firstTimelineFrame;
}

[[nodiscard]] constexpr bool validBeatUnitDenominator(std::uint8_t value) noexcept {
    return value == 1U || value == 2U || value == 4U || value == 8U || value == 16U ||
        value == 32U;
}

[[nodiscard]] bool validMetronomeControl(const MetronomeControl &control) noexcept {
    const bool validMode = control.mode == MetronomeMode::Off ||
        control.mode == MetronomeMode::FreeBpm || control.mode == MetronomeMode::BeatGrid;
    const bool validSubdivision = control.subdivision == 1U || control.subdivision == 2U ||
        control.subdivision == 4U;
    return validMode && std::isfinite(control.bpm) && control.bpm >= 20.0F &&
        control.bpm <= 400.0F && std::isfinite(control.gain) && control.gain >= 0.0F &&
        control.gain <= 1.0F && validSubdivision && control.beatsPerBar >= 1U &&
        control.beatsPerBar <= 32U &&
        validBeatUnitDenominator(control.beatUnitDenominator);
}

[[nodiscard]] bool validBeatGridPayload(
    const NativePlaybackBeatGridUpdate &update,
    const std::int64_t *frames,
    const std::uint8_t *accentFlags,
    std::size_t maximumPointCount) noexcept {
    if (update.revision == 0U || !validOwnership(update.ownership) ||
        update.frameCount > maximumPointCount) {
        return false;
    }
    if (update.frameCount == 0U) {
        return frames == nullptr && accentFlags == nullptr;
    }
    if (update.frameCount < 2U || frames == nullptr ||
        accentFlags == nullptr) {
        return false;
    }
    for (std::size_t index = 0; index < update.frameCount; ++index) {
        if (frames[index] < 0 ||
            (index != 0U && frames[index] <= frames[index - 1U]) ||
            accentFlags[index] > 1U) {
            return false;
        }
    }
    return true;
}

void saturatingAdd(std::atomic<std::uint64_t> &counter, std::uint64_t amount) noexcept {
    std::uint64_t previous = counter.load(std::memory_order_relaxed);
    while (true) {
        const std::uint64_t maximum = std::numeric_limits<std::uint64_t>::max();
        const std::uint64_t updated = amount > maximum - previous ? maximum : previous + amount;
        if (counter.compare_exchange_weak(
                previous,
                updated,
                std::memory_order_relaxed,
                std::memory_order_relaxed)) {
            return;
        }
    }
}

void saturatingIncrement(std::atomic<std::uint64_t> &counter) noexcept {
    saturatingAdd(counter, 1U);
}

[[nodiscard]] constexpr std::uint64_t saturatingIncremented(std::uint64_t value) noexcept {
    return value == std::numeric_limits<std::uint64_t>::max() ? value : value + 1U;
}

/**
 * Reads one writer-published diagnostics tuple without accepting fields from two publications.
 * The writer makes the guard odd around relaxed field stores and even with release semantics.
 * Diagnostics are polled off the real-time callback, so retrying a very short writer window is
 * preferable to exporting a token/generation/revision combination that never existed.
 */
template <typename Read>
bool readStableDiagnosticsGroup(
    const std::atomic<std::uint64_t> &guard,
    Read &&read) noexcept {
    for (unsigned attempt = 0U; attempt < 8U; ++attempt) {
        const std::uint64_t before = guard.load(std::memory_order_acquire);
        if ((before & 1U) != 0U) continue;
        read();
        std::atomic_thread_fence(std::memory_order_acquire);
        const std::uint64_t after = guard.load(std::memory_order_acquire);
        if (before == after) return true;
    }
    return false;
}

[[nodiscard]] bool validBankIntent(const NativePlaybackBankIntent &intent) noexcept {
    if (!intent.assigned) {
        return intent.songIdentityHash == 0U && intent.originalContentHash == 0U &&
            intent.stemPackageHash == 0U && intent.stemLaneCount == 0U && intent.cacheFrameUpperBound == 0U && intent.playbackEndFrameExclusive == 0U;
    }
    return intent.revision != 0U && validOwnership(intent.ownership) &&
        intent.stemLaneCount <= kStemLaneCount &&
        intent.stemLaneCount != 1U &&
        ((intent.stemPackageHash == 0U) == (intent.stemLaneCount == 0U)) &&
        intent.songIdentityHash != 0U && intent.originalContentHash != 0U &&
        intent.cacheFrameUpperBound != 0U &&
        (intent.playbackEndFrameExclusive == 0U || (intent.firstTimelineFrame < intent.playbackEndFrameExclusive &&
            intent.playbackEndFrameExclusive <= static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max()))) &&
        intent.firstTimelineFrame <=
            static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max()) &&
        intent.outputClockStartFrame >= 0;
}

[[nodiscard]] bool validCommandSnapshot(
    const NativePlaybackCommandSnapshot &snapshot) noexcept {
    if (snapshot.revision == 0U || snapshot.outputGeneration == 0U ||
        snapshot.controls.revision == 0U ||
        snapshot.controls.mixer.revision == 0U ||
        !std::isfinite(snapshot.controls.tempo) ||
        !std::isfinite(snapshot.controls.pitchSemitones) ||
        snapshot.controls.tempo < TimePitchProcessor::kMinimumTempo ||
        snapshot.controls.tempo > TimePitchProcessor::kMaximumTempo ||
        snapshot.controls.pitchSemitones < TimePitchProcessor::kMinimumPitchSemitones ||
        snapshot.controls.pitchSemitones > TimePitchProcessor::kMaximumPitchSemitones ||
        !validMetronomeControl(snapshot.controls.mixer.metronome)) {
        return false;
    }
    for (const NativePlaybackBankIntent &intent : snapshot.banks) {
        if (!validBankIntent(intent)) return false;
    }
    if (snapshot.selectionAssigned) {
        const std::size_t selected = bankIndex(snapshot.selectedBank);
        if (snapshot.controls.sourceIntentRevision == 0U ||
            snapshot.controls.stemIntentRevision == 0U ||
            selected >= kNativePlaybackBankCount || !validOwnership(snapshot.selectedOwnership) ||
            !snapshot.banks[selected].assigned ||
            !sameOwnership(snapshot.banks[selected].ownership, snapshot.selectedOwnership) ||
            snapshot.outputGeneration != snapshot.selectedOwnership.generation) {
            return false;
        }
    } else if (snapshot.playing || snapshot.controls.sourceIntentRevision != 0U ||
               snapshot.controls.stemIntentRevision != 0U) {
        return false;
    }
    if (snapshot.automaticSuccessor.enabled) {
        const NativePlaybackAutomaticSuccessorIntent &successor =
            snapshot.automaticSuccessor;
        const std::size_t successorBank = bankIndex(successor.successorBank);
        if (successor.revision == 0U || !snapshot.selectionAssigned || !snapshot.playing ||
            !validOwnership(successor.outgoingOwnership) ||
            !validOwnership(successor.successorOwnership) ||
            successorBank >= kNativePlaybackBankCount ||
            successor.successorBank == snapshot.selectedBank ||
            !sameOwnership(successor.outgoingOwnership, snapshot.selectedOwnership) ||
            successor.successorOwnership.generation <=
                successor.outgoingOwnership.generation ||
            !snapshot.banks[successorBank].assigned ||
            !sameOwnership(
                snapshot.banks[successorBank].ownership,
                successor.successorOwnership)) {
            return false;
        }
    }
    return true;
}

[[nodiscard]] bool validStemGainPreview(
    const NativePlaybackStemGainPreview &preview) noexcept {
    constexpr std::uint32_t kAllStemGainPreviewLanes =
        (1U << static_cast<std::uint32_t>(kStemLaneCount)) - 1U;
    return preview.publicationSequence != 0U && preview.coversOfferSequence != 0U &&
        validOwnership(preview.ownership) && preview.sourceIntentRevision != 0U &&
        preview.stemIntentRevision != 0U && preview.canonicalControlFloorRevision != 0U &&
        (preview.activeMask & ~kAllStemGainPreviewLanes) == 0U &&
        std::all_of(preview.gains.begin(), preview.gains.end(), [](float gain) noexcept {
            return std::isfinite(gain) && gain >= 0.0F && gain <= 2.0F;
        });
}

/** Latest-only SPSC publication for sibling loop messages whose semantic fence is validated by
 * LoopCycleStateMachine, not by the mailbox's private publication sequence. */
template <typename Value>
class LatestLoopValueMailbox final {
public:
    [[nodiscard]] bool publish(const Value &value) noexcept {
        const std::size_t current = publishedSlot.load(std::memory_order_seq_cst);
        const std::size_t hazard = readerSlot.load(std::memory_order_seq_cst);
        std::size_t destination = kMailboxSlotCount;
        for (std::size_t index = 0; index < kMailboxSlotCount; ++index) {
            if (index != current && index != hazard) {
                destination = index;
                break;
            }
        }
        if (destination == kMailboxSlotCount ||
            producerPublication == std::numeric_limits<std::uint64_t>::max()) {
            return false;
        }
        ++producerPublication;
        slots[destination] = {producerPublication, value};
        publishedSlot.store(destination, std::memory_order_seq_cst);
        return true;
    }

    [[nodiscard]] bool readLatest(
        std::uint64_t &lastPublication,
        Value &value) noexcept {
        for (unsigned attempt = 0U; attempt != 8U; ++attempt) {
            const std::size_t candidate = publishedSlot.load(std::memory_order_seq_cst);
            readerSlot.store(candidate, std::memory_order_seq_cst);
            if (publishedSlot.load(std::memory_order_seq_cst) != candidate) {
                readerSlot.store(kNoMailboxReader, std::memory_order_seq_cst);
                continue;
            }
            const Slot copied = slots[candidate];
            readerSlot.store(kNoMailboxReader, std::memory_order_seq_cst);
            if (copied.publication <= lastPublication) return false;
            lastPublication = copied.publication;
            value = copied.value;
            return true;
        }
        return false;
    }

private:
    struct Slot final {
        std::uint64_t publication{0U};
        Value value{};
    };

    static_assert(std::is_trivially_copyable_v<Value>);
    std::array<Slot, kMailboxSlotCount> slots{};
    std::atomic<std::size_t> publishedSlot{0U};
    std::atomic<std::size_t> readerSlot{kNoMailboxReader};
    std::uint64_t producerPublication{0U};
};

/** One-writer/many-reader RCU snapshot. The render worker never waits for a diagnostics reader. */
class LoopProtocolSnapshotPublisher final {
public:
    void publish(const NativePlaybackLoopProtocolSnapshot &snapshot) noexcept {
        const std::size_t current = publishedSlot.load(std::memory_order_seq_cst);
        for (std::size_t offset = 1U; offset <= kSlotCount; ++offset) {
            const std::size_t candidate = (current + offset) % kSlotCount;
            if (candidate == current ||
                readers[candidate].load(std::memory_order_seq_cst) != 0U) {
                continue;
            }
            slots[candidate] = snapshot;
            publishedSlot.store(candidate, std::memory_order_seq_cst);
            return;
        }
    }

    [[nodiscard]] NativePlaybackLoopProtocolSnapshot read() const noexcept {
        for (unsigned attempt = 0U; attempt != 8U; ++attempt) {
            const std::size_t candidate = publishedSlot.load(std::memory_order_seq_cst);
            readers[candidate].fetch_add(1U, std::memory_order_seq_cst);
            if (publishedSlot.load(std::memory_order_seq_cst) != candidate) {
                readers[candidate].fetch_sub(1U, std::memory_order_seq_cst);
                continue;
            }
            const NativePlaybackLoopProtocolSnapshot copied = slots[candidate];
            readers[candidate].fetch_sub(1U, std::memory_order_seq_cst);
            return copied;
        }
        return {};
    }

private:
    static constexpr std::size_t kSlotCount = 4U;
    static_assert(std::is_trivially_copyable_v<NativePlaybackLoopProtocolSnapshot>);
    std::array<NativePlaybackLoopProtocolSnapshot, kSlotCount> slots{};
    mutable std::array<std::atomic<std::uint32_t>, kSlotCount> readers{};
    std::atomic<std::size_t> publishedSlot{0U};
};

enum class BeatGridApplyResult : std::uint8_t {
    Applied,
    Retry,
    Discard,
};

class LatestBeatGridMailbox final {
public:
    explicit LatestBeatGridMailbox(std::size_t maximumPoints)
        : maximumPointCount(maximumPoints) {
        for (Slot &slot : slots) {
            slot.frames = std::make_unique<std::int64_t[]>(maximumPointCount);
            slot.accentFlags = std::make_unique<std::uint8_t[]>(maximumPointCount);
        }
    }

    [[nodiscard]] bool publish(
        const NativePlaybackBeatGridUpdate &update,
        const std::int64_t *frames,
        const std::uint8_t *accentFlags) noexcept {
        if (update.revision <= producerLastRevision ||
            !validBeatGridPayload(update, frames, accentFlags, maximumPointCount)) {
            return false;
        }

        const std::size_t current = publishedSlot.load(std::memory_order_seq_cst);
        const std::size_t hazard = readerSlot.load(std::memory_order_seq_cst);
        std::size_t destination = kMailboxSlotCount;
        for (std::size_t index = 0; index < kMailboxSlotCount; ++index) {
            if (index != current && index != hazard) {
                destination = index;
                break;
            }
        }
        if (destination == kMailboxSlotCount) return false;

        Slot &slot = slots[destination];
        slot.update = update;
        if (update.frameCount != 0U) {
            std::copy_n(frames, update.frameCount, slot.frames.get());
            std::copy_n(accentFlags, update.frameCount, slot.accentFlags.get());
        }
        publishedSlot.store(destination, std::memory_order_seq_cst);
        producerLastRevision = update.revision;
        return true;
    }

    template <typename Apply>
    bool applyLatest(std::uint64_t &lastAppliedRevision, Apply &&apply) noexcept {
        for (unsigned attempt = 0U; attempt != 8U; ++attempt) {
            const std::size_t candidate = publishedSlot.load(std::memory_order_seq_cst);
            readerSlot.store(candidate, std::memory_order_seq_cst);
            if (publishedSlot.load(std::memory_order_seq_cst) != candidate) {
                readerSlot.store(kNoMailboxReader, std::memory_order_seq_cst);
                continue;
            }
            const Slot &slot = slots[candidate];
            if (slot.update.revision <= lastAppliedRevision) {
                readerSlot.store(kNoMailboxReader, std::memory_order_seq_cst);
                return false;
            }
            const BeatGridApplyResult result = apply(
                slot.update,
                slot.frames.get(),
                slot.update.frameCount != 0U ? slot.accentFlags.get() : nullptr);
            const std::uint64_t revision = slot.update.revision;
            readerSlot.store(kNoMailboxReader, std::memory_order_seq_cst);
            if (result == BeatGridApplyResult::Retry) return false;
            lastAppliedRevision = revision;
            return result == BeatGridApplyResult::Applied;
        }
        return false;
    }

private:
    struct Slot final {
        NativePlaybackBeatGridUpdate update{};
        std::unique_ptr<std::int64_t[]> frames;
        std::unique_ptr<std::uint8_t[]> accentFlags;
    };

    std::size_t maximumPointCount;
    std::array<Slot, kMailboxSlotCount> slots{};
    std::atomic<std::size_t> publishedSlot{0};
    std::atomic<std::size_t> readerSlot{kNoMailboxReader};
    std::uint64_t producerLastRevision{0};
};

#include "NativePlaybackRuntimeDiagnostics.inc"

struct PendingLanePcm final {
    explicit PendingLanePcm(std::size_t capacityFrames)
        : samples(std::make_unique<float[]>(capacityFrames * 2U)) {}

    void clear() noexcept {
        offsetFrames = 0;
        remainingFrames = 0;
        generation = 0;
        firstTimelineFrame = 0;
        endOfStreamPending = false;
        endTimelineFrameExclusive = 0;
        futureLoopRequest = {};
    }

    std::unique_ptr<float[]> samples;
    std::size_t offsetFrames{0};
    std::size_t remainingFrames{0};
    std::uint64_t generation{0};
    std::uint64_t firstTimelineFrame{0};
    bool endOfStreamPending{false};
    std::uint64_t endTimelineFrameExclusive{0};
    std::uint64_t endOfStreamMarkedGeneration{0};
    LoopCycleIngressRequest futureLoopRequest{};
};

enum class NativePlaybackControlPolicyVariant : std::uint8_t {
    Normal = 0,
    NeutralAutomaticSuccessor = 1,
};

struct NativePlaybackControlTargetKey final {
    std::uint64_t canonicalRevision{0U};
    NativePlaybackControlPolicyVariant policyVariant{
        NativePlaybackControlPolicyVariant::Normal};
    SongRenderOwnership ownership{};

    [[nodiscard]] constexpr bool valid() const noexcept {
        return canonicalRevision != 0U;
    }

    [[nodiscard]] constexpr bool operator==(
        const NativePlaybackControlTargetKey &other) const noexcept {
        return canonicalRevision == other.canonicalRevision &&
               policyVariant == other.policyVariant &&
               ownership.selectionToken == other.ownership.selectionToken &&
               ownership.generation == other.ownership.generation;
    }
};

struct BankRuntimeState final {
    explicit BankRuntimeState(std::size_t ingressPullFrames) {
        for (std::unique_ptr<PendingLanePcm> &lane : lanes) {
            lane = std::make_unique<PendingLanePcm>(ingressPullFrames);
        }
    }

    void clearPendingLanes() noexcept {
        for (std::unique_ptr<PendingLanePcm> &lane : lanes) {
            lane->clear();
            lane->endOfStreamMarkedGeneration = 0;
        }
    }

    std::unique_ptr<SongRenderPipeline> pipeline;
    std::array<std::unique_ptr<PendingLanePcm>, kSongRenderLaneCount> lanes{};
    NativePlaybackBankIntent appliedIntent{};
    std::uint64_t appliedControlRevision{0};
    std::uint64_t rejectedResourceRevision{0};
    NativePlaybackControlTargetKey targetControlKey{};
    NativePlaybackControlTargetKey appliedControlKey{};
    std::uint64_t controlApplicationEpoch{0U};
    std::uint64_t targetControlApplicationEpoch{0U};
    std::uint64_t appliedControlApplicationEpoch{0U};
    bool hasTargetControlKey{false};
    bool hasAppliedControlKey{false};
    std::uint64_t appliedBeatGridRevision{0};
    std::uint64_t consumedBeatGridRevision{0};
    SongRenderLateMixEndpoint pendingLateMixEndpoint{};
    SongRenderOwnership pendingLateMixOwnership{};
    bool hasPendingLateMixEndpoint{false};
    bool assigned{false};
};


struct PendingPresentationCommit final {
    SongRenderOwnership ownership{};
    std::uint64_t fingerprintSequence{0};
    std::uint64_t evidenceBaseline{0};
};

struct DirectLoopState final {
    void retireLogical() noexcept {
        logicalRevision = aFrame = bFrame = wrapBaseline = 0U;
        engaged = false;
    }

    void replaceOwner() noexcept {
        retireLogical();
        physicalRevision = 0U;
    }

    void adoptPhysicalReceipt(const NativeAudioOutputLoopPolicy &policy,
                              std::uint64_t acknowledgedRevision,
                              std::uint64_t preparationWrapBaseline,
                              bool continuationEngaged) noexcept {
        logicalRevision = std::min(policy.revision, acknowledgedRevision);
        physicalRevision = policy.physicalRevision();
        aFrame = policy.aTimelineFrame;
        bFrame = policy.bTimelineFrameExclusive;
        wrapBaseline = preparationWrapBaseline;
        engaged = continuationEngaged;
    }

    std::uint64_t logicalRevision{0U};
    std::uint64_t physicalRevision{0U};
    std::uint64_t aFrame{0U};
    std::uint64_t bFrame{0U};
    std::uint64_t wrapBaseline{0U};
    bool engaged{false};
    std::uint64_t retiredRevision{0U};
    std::uint64_t observedRevision{0U};
};

using AutomaticSuccessorPhase = NativePlaybackAutomaticSuccessorPhase;

struct AutomaticSuccessorPlan final {
    void clear() noexcept { *this = {}; }

    AutomaticSuccessorPhase phase{AutomaticSuccessorPhase::Idle};
    std::uint64_t revision{0};
    std::uint64_t transitionRevision{0};
    std::size_t outgoingBank{0};
    std::size_t successorBank{0};
    SongRenderOwnership outgoingOwnership{};
    SongRenderOwnership successorOwnership{};
    bool inheritOutgoingControls{false};
    bool cancellationDesired{false};
    bool successorEndObserved{false};
    std::uint64_t firstFingerprintSequence{0};
    std::uint64_t evidenceBaseline{0};
};


}  // namespace

class NativePlaybackRuntime::Impl final {
public:
    Impl(
        const NativePlaybackRuntimeConfiguration &configured,
        SongLaneIngressBank &firstBank,
        SongLaneIngressBank &secondBank,
        NativePlaybackOutputPort configuredOutput)
        : configuration(checkedConfiguration(configured)),
          ingressBanks{&firstBank, &secondBank},
          output(checkedOutput(configuredOutput)),
          repeatLoopPreparation(LoopHeadPreparationConfiguration{
              .cacheDirectory = configuration.currentSongCacheDirectory}),
          beatGridMailboxes{
              std::make_unique<LatestBeatGridMailbox>(
                  configuration.pipeline.maximumBeatGridPoints),
              std::make_unique<LatestBeatGridMailbox>(
                  configuration.pipeline.maximumBeatGridPoints)},
          bankStates{
              std::make_unique<BankRuntimeState>(configuration.ingressPullFrames),
              std::make_unique<BankRuntimeState>(configuration.ingressPullFrames)},
          currentSongCacheScratch(std::make_unique<float[]>(
              static_cast<std::size_t>(configuration.ingressPullFrames) * 2U)),
          pendingOutput(configuration.pipeline.maximumBlockFrames),
          loopContinuationPendingOutput(configuration.pipeline.maximumBlockFrames),
          reusableLoopHead(configuration.pipeline.maximumBlockFrames,
              configuration.minimumOutputReserveFrames()),
          automaticSuccessorPendingOutput(configuration.pipeline.maximumBlockFrames) {
        if (output.setDiagnosticPayloadFingerprints != nullptr) {
            output.setDiagnosticPayloadFingerprints(output.handle, configuration.pipeline.diagnosticPayloadFingerprints);
        }
        resources = std::make_unique<NativePlaybackResources>(configuration.pipeline,
            configuration.currentSongCacheDirectory, [](void *owner) noexcept {
                static_cast<Impl *>(owner)->notifyIngressAvailable();
            }, this);
        resources->attachLoopWindowCache(repeatLoopPreparation.cache());
    }

    static NativePlaybackRuntimeConfiguration checkedConfiguration(
        NativePlaybackRuntimeConfiguration value) {
        if (value.targetBursts < NativePlaybackRuntimeConfiguration::kMinimumTargetBursts ||
            value.targetBursts > NativePlaybackRuntimeConfiguration::kMaximumTargetBursts ||
            value.minimumOutputReserveMicros <
                NativePlaybackRuntimeConfiguration::kMinimumOutputReserveMicros ||
            value.minimumOutputReserveMicros >
                NativePlaybackRuntimeConfiguration::kMaximumOutputReserveMicros ||
            value.fallbackFramesPerBurst == 0U || value.ingressPullFrames == 0U ||
            value.ingressPullFrames >
                NativePlaybackRuntimeConfiguration::kMaximumIngressPullFrames ||
            value.maximumPullsPerLanePerCycle == 0U ||
            value.maximumPullsPerLanePerCycle >
                NativePlaybackRuntimeConfiguration::kMaximumPullsPerLanePerCycle ||
            value.maximumOutputBlocksPerCycle == 0U || value.idleSleepMicros == 0U ||
            value.pipeline.maximumBlockFrames == 0U ||
            value.pipeline.maximumBlockFrames >
                SongRenderPipelineConfiguration::kMaximumSupportedBlockFrames ||
            value.pipeline.maximumBeatGridPoints < 2U) {
            throw std::invalid_argument("NativePlaybackRuntime configuration is invalid");
        }
        return value;
    }

    static NativePlaybackOutputPort checkedOutput(NativePlaybackOutputPort value) {
        if (!value.valid()) {
            throw std::invalid_argument("NativePlaybackRuntime output port is invalid");
        }
        return value;
    }

    ~Impl() {
        requestStop();
        join();
    }

    [[nodiscard]] bool start() noexcept {
        NativePlaybackRuntimeStatus expected = NativePlaybackRuntimeStatus::Constructed;
        if (!runtimeStatus.compare_exchange_strong(
                expected,
                NativePlaybackRuntimeStatus::Running,
                std::memory_order_acq_rel,
                std::memory_order_acquire)) {
            return false;
        }
        try {
            worker = std::thread(&Impl::workerMain, this);
            return true;
        } catch (...) {
            runtimeStatus.store(NativePlaybackRuntimeStatus::Stopped, std::memory_order_release);
            return false;
        }
    }

    void requestStop() noexcept {
        NativePlaybackRuntimeStatus current = runtimeStatus.load(std::memory_order_acquire);
        while (current == NativePlaybackRuntimeStatus::Running &&
               !runtimeStatus.compare_exchange_weak(
                   current,
                   NativePlaybackRuntimeStatus::StopRequested,
                   std::memory_order_acq_rel,
                   std::memory_order_acquire)) {}
        notifyIngressAvailable();
        audibleControlProgressEpoch.fetch_add(1U, std::memory_order_release);
        audibleControlProgressEpoch.notify_all();
        resources->stopPcmCacheProgressWaiters();
    }

    void join() noexcept {
        if (worker.joinable()) worker.join();
    }

    [[nodiscard]] std::uint64_t nextEndOrderingSequence() noexcept {
        // Diagnostic order saturates at UINT64_MAX instead of wrapping. Product command
        // acceptance remains authoritative if that process-lifetime evidence budget is exhausted;
        // later diagnostic events stay bounded in the existing last-event slots.
        std::uint64_t current = endOrderingSequence.load(std::memory_order_relaxed);
        while (current != std::numeric_limits<std::uint64_t>::max() &&
               !endOrderingSequence.compare_exchange_weak(
                   current,
                   current + 1U,
                   std::memory_order_acq_rel,
                   std::memory_order_relaxed)) {}
        return current == std::numeric_limits<std::uint64_t>::max()
            ? current
            : current + 1U;
    }

    [[nodiscard]] NativePlaybackEndOrderingEvent workerEndOrderingEvent(
        std::uint64_t sequence,
        const NativeAudioPresentedPosition &terminalPosition = {},
        std::size_t queuedFrames = 0U) const noexcept {
        return {
            sequence,
            sequence,
            hasDesiredSnapshot ? desiredSnapshot.revision : 0U,
            requestedOwnership,
            hasDesiredSnapshot && desiredSnapshot.playing,
            outputPlaying,
            pipelineEndObserved,
            commandMailbox.hasUnreadPublication(),
            static_cast<std::uint64_t>(pendingOutput.remainingFrames),
            static_cast<std::uint64_t>(queuedFrames),
            terminalPosition.valid(),
            terminalPosition.terminalAfterFrame,
        };
    }

    void recordWorkerEndOrderingEvent(
        AtomicEndOrderingEvent &destination,
        const NativeAudioPresentedPosition &terminalPosition = {},
        std::size_t queuedFrames = 0U) noexcept {
        const NativePlaybackEndOrderingEvent event = workerEndOrderingEvent(
            nextEndOrderingSequence(), terminalPosition, queuedFrames);
        endOrderingWorkerState.publish(event);
        destination.publish(event);
    }

    void recordCommandPublished(
        const NativePlaybackCommandSnapshot &snapshot) noexcept {
        // This event linearizes at accepted-commit preparation immediately before the mailbox
        // visibility store. Worker-owned fields come from one completed seqlock checkpoint named
        // by workerStateSequence; command fields name the newly accepted intent. commandUnread is
        // the exact pre-visibility mailbox state, not an unconditional assumption.
        NativePlaybackEndOrderingEvent event = endOrderingWorkerState.snapshot();
        event.sequence = nextEndOrderingSequence();
        event.commandRevision = snapshot.revision;
        event.ownership = snapshot.selectionAssigned
            ? snapshot.selectedOwnership
            : SongRenderOwnership{};
        event.desiredPlaying = snapshot.playing;
        event.commandUnread = commandMailbox.hasUnreadPublication();
        endOrderingCommandPublished.publish(event);
    }

    [[nodiscard]] NativePlaybackPublishStatus publishCommand(
        const NativePlaybackCommandSnapshot &snapshot) noexcept {
        const NativePlaybackRuntimeStatus current = runtimeStatus.load(std::memory_order_acquire);
        if (current == NativePlaybackRuntimeStatus::StopRequested ||
            current == NativePlaybackRuntimeStatus::Stopped) {
            return NativePlaybackPublishStatus::Stopped;
        }
        if (!validCommandSnapshot(snapshot)) {
            saturatingIncrement(rejectedCommandSnapshots);
            return NativePlaybackPublishStatus::InvalidSnapshot;
        }
        for (std::size_t bank = 0U; bank < snapshot.banks.size(); ++bank) {
            const auto &before = producerLastBankIntents[bank];
            const auto &after = snapshot.banks[bank];
            if (before.assigned && after.assigned && sameOwnership(before.ownership, after.ownership) &&
                ((before.stemPackageHash != 0U && after.stemPackageHash != 0U &&
                  before.stemPackageHash != after.stemPackageHash) ||
                 (before.stemPackageHash == after.stemPackageHash &&
                  before.stemLaneCount != after.stemLaneCount))) {
                saturatingIncrement(rejectedCommandSnapshots);
                return NativePlaybackPublishStatus::InvalidSnapshot;
            }
        }
        if (snapshot.outputGeneration < producerLastOutputGeneration ||
            (producerLastSelectionAssigned && !snapshot.selectionAssigned &&
             snapshot.outputGeneration <= producerLastOutputGeneration) ||
            (!producerLastSelectionAssigned && snapshot.selectionAssigned &&
             producerLastOutputGeneration != 0U &&
             snapshot.outputGeneration <= producerLastOutputGeneration) ||
            (producerLastSelectionAssigned && snapshot.selectionAssigned &&
             validOwnership(producerLastSelectedOwnership) &&
             !sameOwnership(snapshot.selectedOwnership, producerLastSelectedOwnership) &&
             snapshot.outputGeneration <= producerLastOutputGeneration)) {
            saturatingIncrement(rejectedCommandSnapshots);
            return NativePlaybackPublishStatus::InvalidSnapshot;
        }
        const LatestCommandMailbox::PublishResult result = commandMailbox.publish(
            snapshot,
            [this, &snapshot]() noexcept { recordCommandPublished(snapshot); });
        if (!result.accepted) {
            saturatingIncrement(rejectedCommandSnapshots);
            return NativePlaybackPublishStatus::StaleRevision;
        }
        producerLastOutputGeneration = snapshot.outputGeneration;
        producerLastBankIntents = snapshot.banks;
        producerLastSelectionAssigned = snapshot.selectionAssigned;
        producerLastSelectedOwnership = snapshot.selectionAssigned
            ? snapshot.selectedOwnership
            : SongRenderOwnership{};
        directPolicyOwnerToken.store(
            producerLastSelectedOwnership.selectionToken,
            std::memory_order_relaxed);
        directPolicyOwnerGeneration.store(
            producerLastSelectedOwnership.generation,
            std::memory_order_release);
        if (configuration.commandPublicationVisibleHook != nullptr) {
            configuration.commandPublicationVisibleHook(
                configuration.commandPublicationVisibleHookContext,
                snapshot);
        }
        saturatingIncrement(commandSnapshotsPublished);
        if (result.supersededUnread) saturatingIncrement(commandSnapshotsSuperseded);
        notifyIngressAvailable();
        return NativePlaybackPublishStatus::Accepted;
    }

    [[nodiscard]] NativePlaybackPublishStatus publishStemGainPreview(
        const NativePlaybackStemGainPreview &preview) noexcept {
        const NativePlaybackRuntimeStatus current = runtimeStatus.load(std::memory_order_acquire);
        if (current == NativePlaybackRuntimeStatus::StopRequested ||
            current == NativePlaybackRuntimeStatus::Stopped) {
            return NativePlaybackPublishStatus::Stopped;
        }
        if (!validStemGainPreview(preview)) {
            return NativePlaybackPublishStatus::InvalidSnapshot;
        }
        if (preview.publicationSequence <= producerLastStemGainPreviewPublication ||
            preview.coversOfferSequence < producerLastStemGainPreviewOffer ||
            !stemGainPreviewMailbox.publish(preview)) {
            return NativePlaybackPublishStatus::StaleRevision;
        }
        producerLastStemGainPreviewPublication = preview.publicationSequence;
        producerLastStemGainPreviewOffer = preview.coversOfferSequence;
        notifyIngressAvailable();
        return NativePlaybackPublishStatus::Accepted;
    }

    [[nodiscard]] NativePlaybackPublishStatus publishBeatGrid(
        const NativePlaybackBeatGridUpdate &update,
        const std::int64_t *beatFrames,
        const std::uint8_t *accentFlags) noexcept {
        const NativePlaybackRuntimeStatus current = runtimeStatus.load(std::memory_order_acquire);
        if (current == NativePlaybackRuntimeStatus::StopRequested ||
            current == NativePlaybackRuntimeStatus::Stopped) {
            return NativePlaybackPublishStatus::Stopped;
        }
        const std::size_t index = bankIndex(update.bank);
        if (index >= kNativePlaybackBankCount || !validBeatGridPayload(
                update,
                beatFrames,
                accentFlags,
                configuration.pipeline.maximumBeatGridPoints)) {
            saturatingIncrement(rejectedBeatGridUpdates);
            return NativePlaybackPublishStatus::InvalidSnapshot;
        }
        if (!beatGridMailboxes[index]->publish(update, beatFrames, accentFlags)) {
            saturatingIncrement(rejectedBeatGridUpdates);
            return NativePlaybackPublishStatus::StaleRevision;
        }
        saturatingIncrement(beatGridUpdatesPublished);
        notifyIngressAvailable();
        return NativePlaybackPublishStatus::Accepted;
    }

    [[nodiscard]] NativePlaybackPublishStatus publishLoopCycleIntent(
        const LoopCycleIntent &intent) noexcept {
        const NativePlaybackRuntimeStatus current = runtimeStatus.load(std::memory_order_acquire);
        if (current == NativePlaybackRuntimeStatus::StopRequested ||
            current == NativePlaybackRuntimeStatus::Stopped) {
            return NativePlaybackPublishStatus::Stopped;
        }
        if (!validLoopIntent(intent)) return NativePlaybackPublishStatus::InvalidSnapshot;
        if (hasProducerLastLoopIntent) {
            if (intent.revision < producerLastLoopIntent.revision ||
                (intent.revision == producerLastLoopIntent.revision &&
                 intent.owner.renderGeneration <
                     producerLastLoopIntent.owner.renderGeneration)) {
                return NativePlaybackPublishStatus::StaleRevision;
            }
            if (intent.revision == producerLastLoopIntent.revision) {
                if (!sameLoopStableIdentity(intent, producerLastLoopIntent)) {
                    return NativePlaybackPublishStatus::InvalidSnapshot;
                }
                if (intent.owner.renderGeneration ==
                    producerLastLoopIntent.owner.renderGeneration) {
                    // Idempotent retry: the original latest-only publication remains live even if
                    // the render worker has not consumed it yet.
                    return NativePlaybackPublishStatus::Accepted;
                }
            }
        }
        if (!loopIntentMailbox.publish(intent)) {
            return NativePlaybackPublishStatus::StaleRevision;
        }
        producerLastLoopIntent = intent;
        hasProducerLastLoopIntent = true;
        notifyIngressAvailable();
        return NativePlaybackPublishStatus::Accepted;
    }

    [[nodiscard]] NativePlaybackPublishStatus publishDirectLoopPolicy(
        std::uint64_t revision,
        std::uint64_t selectionToken,
        std::uint64_t generation,
        bool enabled,
        std::uint64_t aTimelineFrame,
        std::uint64_t bTimelineFrameExclusive,
        std::uint64_t inputEventAtMonotonicMicros,
        bool callerBoundaryArmedAtPublication,
        bool callerBoundaryCrossedAtPublication,
        std::uint64_t heldBGestureId,
        std::uint64_t capturedBTimelineFrameExclusive,
        std::int64_t captureReferenceTimelineFrame) noexcept {
        const NativePlaybackRuntimeStatus current = runtimeStatus.load(std::memory_order_acquire);
        if (current == NativePlaybackRuntimeStatus::StopRequested ||
            current == NativePlaybackRuntimeStatus::Stopped) {
            return NativePlaybackPublishStatus::Stopped;
        }
        if (directPolicyOwnerGeneration.load(std::memory_order_acquire) != generation ||
            directPolicyOwnerToken.load(std::memory_order_relaxed) != selectionToken) {
            return NativePlaybackPublishStatus::InvalidSnapshot;
        }
        if (callerBoundaryCrossedAtPublication &&
            (!enabled || !callerBoundaryArmedAtPublication)) {
            return NativePlaybackPublishStatus::InvalidSnapshot;
        }
        if ((!callerBoundaryCrossedAtPublication &&
             capturedBTimelineFrameExclusive != 0U) ||
            (capturedBTimelineFrameExclusive != 0U &&
             aTimelineFrame >= capturedBTimelineFrameExclusive)) {
            return NativePlaybackPublishStatus::InvalidSnapshot;
        }
        const std::uint64_t capturedB = callerBoundaryCrossedAtPublication &&
                capturedBTimelineFrameExclusive == 0U
            ? bTimelineFrameExclusive
            : capturedBTimelineFrameExclusive;
        if (captureReferenceTimelineFrame < -1 ||
            (captureReferenceTimelineFrame >= 0 &&
             (!callerBoundaryCrossedAtPublication ||
              static_cast<std::uint64_t>(captureReferenceTimelineFrame) <
                  capturedB))) {
            return NativePlaybackPublishStatus::InvalidSnapshot;
        }
        // The pointer command already captured whether the cursor was inside its authored range.
        // Do not resample output later and overturn that fact: during rapid B movement the later
        // timestamp may already be beyond B, which would park the policy and miss the boundary.
        // This also keeps the finger publication free of the stream lifecycle mutex/getTimestamp.
        const AtomicLoopPolicySnapshot policy{
            .revision = revision,
            .selectionToken = selectionToken,
            .enabled = enabled,
            .aSourceFrame = enabled ? aTimelineFrame : 0U,
            .bSourceFrameExclusive = enabled ? bTimelineFrameExclusive : 0U,
            .acceptedAtMonotonicMicros = diagnosticMonotonicMicros(),
            .inputEventAtMonotonicMicros = inputEventAtMonotonicMicros,
            .heldBGestureId = heldBGestureId,
            .boundaryArmedAtPublication = enabled && callerBoundaryArmedAtPublication,
        };
        if (!policy.valid()) return NativePlaybackPublishStatus::InvalidSnapshot;
        const auto publishDemand = [
                                       this,
                                       generation,
                                       callerBoundaryCrossedAtPublication,
                                       capturedB,
                                       captureReferenceTimelineFrame](
                                       const AtomicLoopPolicySnapshot &snapshot,
                                       std::uint64_t publishedAt) noexcept {
            return output.publishLoopDemand(
                output.handle,
                NativeAudioOutputLoopPolicy{
                    .revision = snapshot.revision,
                    .generation = generation,
                    .selectionToken = snapshot.selectionToken,
                    .enabled = snapshot.enabled,
                    .physicalReady = false,
                    .aTimelineFrame = snapshot.enabled ? snapshot.aSourceFrame : 0U,
                    .bTimelineFrameExclusive = snapshot.enabled
                        ? snapshot.bSourceFrameExclusive
                        : 0U,
                    .continuationRevision = snapshot.revision,
                    .acceptedAtMonotonicMicros =
                        snapshot.acceptedAtMonotonicMicros,
                    .publishedAtMonotonicMicros = publishedAt,
                    .inputEventAtMonotonicMicros =
                        snapshot.inputEventAtMonotonicMicros,
                    .captureReferenceTimelineFrame = captureReferenceTimelineFrame,
                    .heldBGestureId = snapshot.heldBGestureId,
                    .boundaryArmedAtPublication =
                        snapshot.boundaryArmedAtPublication,
                }, callerBoundaryCrossedAtPublication, capturedB);
        };
        AtomicLoopPolicySnapshot published{};
        if (atomicLoopPolicy.readLatest(published) && published.revision == revision) {
            if (published != policy) {
                return NativePlaybackPublishStatus::InvalidSnapshot;
            }
            const std::uint64_t committedRevision =
                directAtomicPolicyGenerationRevision.load(std::memory_order_acquire);
            if (committedRevision > revision ||
                (committedRevision == revision &&
                 directAtomicPolicyGeneration.load(std::memory_order_relaxed) != generation)) {
                return NativePlaybackPublishStatus::InvalidSnapshot;
            }
            if (!publishDemand(published, directAtomicPolicyPublishedAt(revision))) {
                return NativePlaybackPublishStatus::InvalidSnapshot;
            }
            if (committedRevision != revision) {
                directAtomicPolicyGeneration.store(generation, std::memory_order_relaxed);
                directAtomicPolicyGenerationRevision.store(revision, std::memory_order_release);
                notifyIngressAvailable();
            }
            return NativePlaybackPublishStatus::Accepted;
        }
        if (!atomicLoopPolicy.publish(policy)) return NativePlaybackPublishStatus::StaleRevision;
        const std::uint64_t publishedAtMonotonicMicros = diagnosticMonotonicMicros();
        directAtomicPolicyPublishedRevision.store(0U, std::memory_order_release);
        directAtomicPolicyPublishedAtMonotonicMicros.store(
            publishedAtMonotonicMicros,
            std::memory_order_relaxed);
        directAtomicPolicyPublishedRevision.store(revision, std::memory_order_release);
        // The complete latest A/B tuple reaches the callback immediately. It is demand only and
        // therefore cannot own B until an exact physical continuation is published separately.
        if (!publishDemand(policy, publishedAtMonotonicMicros)) {
            return NativePlaybackPublishStatus::InvalidSnapshot;
        }
        // This is the worker-visible commit marker. A failed output publication leaves the atomic
        // tuple retryable but cannot create a ghost render policy.
        directAtomicPolicyGeneration.store(generation, std::memory_order_relaxed);
        directAtomicPolicyGenerationRevision.store(revision, std::memory_order_release);
        notifyIngressAvailable();
        return NativePlaybackPublishStatus::Accepted;
    }

    [[nodiscard]] NativePlaybackPublishStatus publishLoopIngressReady(
        const LoopCycleIngressReadyEvidence &evidence) noexcept {
        const NativePlaybackRuntimeStatus current = runtimeStatus.load(std::memory_order_acquire);
        if (current == NativePlaybackRuntimeStatus::StopRequested ||
            current == NativePlaybackRuntimeStatus::Stopped) {
            return NativePlaybackPublishStatus::Stopped;
        }
        if (!validLoopIngressReady(evidence)) {
            return NativePlaybackPublishStatus::InvalidSnapshot;
        }
        if (!loopIngressReadyMailbox.publish(evidence)) {
            return NativePlaybackPublishStatus::StaleRevision;
        }
        notifyIngressAvailable();
        return NativePlaybackPublishStatus::Accepted;
    }

    [[nodiscard]] LoopHeadPreparationBeginStatus beginLoopHeadPreparation(
        const LoopHeadContentKey &key,
        std::uint32_t requiredLaneMask,
        LoopHeadBoundaryMode boundaryMode, SongRenderOwnership ownership) noexcept {
        const NativePlaybackRuntimeStatus current = runtimeStatus.load(std::memory_order_acquire);
        if (current == NativePlaybackRuntimeStatus::StopRequested ||
            current == NativePlaybackRuntimeStatus::Stopped) {
            return LoopHeadPreparationBeginStatus::Stopped;
        }
        if (boundaryMode != LoopHeadBoundaryMode::OriginalEos &&
            boundaryMode != LoopHeadBoundaryMode::RetainedWindow)
            return LoopHeadPreparationBeginStatus::InvalidKey;
        const auto result = repeatLoopPreparation.begin(key, requiredLaneMask, boundaryMode);
        if (result == LoopHeadPreparationBeginStatus::Accepted) {
            pendingPreparedHeadOwnership = ownership;
            pendingPreparedHeadRevision = key.loopRevision;
            notifyIngressAvailable();
        }
        return result;
    }

    [[nodiscard]] LoopHeadPreparationConfigureStatus configureLoopHeadPreparationLane(
        std::uint64_t loopRevision,
        std::size_t laneIndexValue,
        std::uint32_t sourceRateHz,
        std::uint64_t preparationGeneration) noexcept {
        const NativePlaybackRuntimeStatus current = runtimeStatus.load(std::memory_order_acquire);
        if (current == NativePlaybackRuntimeStatus::StopRequested ||
            current == NativePlaybackRuntimeStatus::Stopped) {
            return LoopHeadPreparationConfigureStatus::Stopped;
        }
        return repeatLoopPreparation.configureLane(
            loopRevision,
            laneIndexValue,
            sourceRateHz,
            preparationGeneration);
    }

    [[nodiscard]] LoopHeadPreparationOfferResult offerLoopHeadPreparationPcm(
        std::uint64_t loopRevision,
        std::size_t laneIndexValue,
        const LaneInputBlock &block) noexcept {
        const NativePlaybackRuntimeStatus current = runtimeStatus.load(std::memory_order_acquire);
        if (current == NativePlaybackRuntimeStatus::StopRequested ||
            current == NativePlaybackRuntimeStatus::Stopped) {
            return {LoopHeadPreparationOfferStatus::Stopped};
        }
        const LoopHeadPreparationOfferResult result = repeatLoopPreparation.offer(
            loopRevision,
            laneIndexValue,
            block);
        if (result.status == LoopHeadPreparationOfferStatus::CacheCommitted) {
            publishCommittedHeadBinding(loopRevision);
            notifyIngressAvailable();
        }
        return result;
    }

    [[nodiscard]] LoopHeadPreparationEndStatus markLoopHeadPreparationEndOfStream(
        std::uint64_t loopRevision,
        std::size_t laneIndexValue,
        std::uint64_t preparationGeneration,
        std::uint64_t finalSourceFrameExclusive) noexcept {
        const NativePlaybackRuntimeStatus current = runtimeStatus.load(std::memory_order_acquire);
        if (current == NativePlaybackRuntimeStatus::StopRequested ||
            current == NativePlaybackRuntimeStatus::Stopped) {
            return LoopHeadPreparationEndStatus::Stopped;
        }
        const LoopHeadPreparationEndStatus result =
            repeatLoopPreparation.markEndOfStream(
            loopRevision,
            laneIndexValue,
            preparationGeneration,
            finalSourceFrameExclusive);
        if (result == LoopHeadPreparationEndStatus::CacheCommitted) {
            publishCommittedHeadBinding(loopRevision);
            notifyIngressAvailable();
        }
        return result;
    }

    [[nodiscard]] LoopHeadPreparationAbortStatus abortLoopHeadPreparation(
        std::uint64_t loopRevision) noexcept {
        const NativePlaybackRuntimeStatus current = runtimeStatus.load(std::memory_order_acquire);
        if (current == NativePlaybackRuntimeStatus::StopRequested ||
            current == NativePlaybackRuntimeStatus::Stopped) {
            return LoopHeadPreparationAbortStatus::Stopped;
        }
        return repeatLoopPreparation.abort(loopRevision);
    }

    [[nodiscard]] NativePlaybackLoopProtocolSnapshot loopProtocolSnapshot() const noexcept {
        NativePlaybackLoopProtocolSnapshot result = loopProtocolSnapshots.read();
        result.preparation = repeatLoopPreparation.snapshot();
        return result;
    }

    void notifyIngressAvailable() noexcept {
        {
            std::lock_guard lock(wakeMutex);
            wakeEpoch.fetch_add(1U, std::memory_order_release);
        }
        wakeCondition.notify_one();
    }

    [[nodiscard]] NativePlaybackRenderReadinessDiagnostics
    requestRenderReadinessDiagnostics() noexcept {
        renderReadinessRequested.store(true, std::memory_order_release);
        notifyIngressAvailable();
        return renderReadinessDiagnosticsPublisher.snapshot();
    }

    bool publishCurrentCacheReadFailures() noexcept {
        if (!selectionAssigned || renderBank >= bankStates.size()) return false;
        auto failures = currentSongCache && currentSongCacheBank == renderBank
            ? resources->snapshot().readFailureLaneMask : 0U;
        failures |= bankStates[renderBank]->pipeline->retainedLoopHeadReadFailureLaneMask(directCacheIdentity());
        const auto head = preparedLoopHeadForOwner(directCacheIdentity());
        if (head.valid()) {
            const auto &key = head.snapshot().key;
            const auto current = directCacheIdentity();
            const auto failed = head.readFailureLaneMask();
            if (key.songIdentityHash == current.songIdentityHash &&
                key.originalContentHash == current.originalContentHash &&
                ((failed & ~1U) == 0U || key.stemPackageHash == current.stemPackageHash))
                failures |= failed;
        }
        bool changed = false;
        const auto epoch = bankStates[renderBank]->pipeline->ingressEpoch();
        for (std::size_t lane = 0U; lane < kCurrentSongCacheLaneCount; ++lane) {
            if ((failures & (1U << lane)) != 0U &&
                bankDiagnostics[renderBank].lanes[lane].failureGeneration.exchange(
                    epoch, std::memory_order_acq_rel) != epoch) {
                reportLaneIngressFailure(renderBank, lane, epoch, "cache-read", failures);
                changed = true;
            }
        }
        if (changed) renderReadinessRequested.store(true, std::memory_order_release);
        return changed;
    }

    void publishRenderReadinessOnWorker() noexcept {
        NativePlaybackRenderReadinessDiagnostics result{};
        result.sequence = saturatingIncremented(renderReadinessSequenceValue);
        renderReadinessSequenceValue = result.sequence;
        result.assigned = selectionAssigned && renderBank < bankStates.size() &&
            bankStates[renderBank]->assigned;
        result.bank = static_cast<NativePlaybackBank>(renderBank);
        result.outputQueuedFrames = output.queuedFrames(output.handle);
        if (!result.assigned) {
            renderReadinessDiagnosticsPublisher.publish(result);
            return;
        }

        BankRuntimeState &state = *bankStates[renderBank];
        SongRenderPipeline &pipeline = *state.pipeline;
        result.ownership = pipeline.ownership();
        result.requiredSongLaneMask = stemwiseRequiredSongMask(state.appliedIntent.stemLaneCount);
        result.cacheStorageStatus = currentSongCacheStorageStatus;
        if (currentSongCache != nullptr) {
            const CurrentSongCacheSnapshot cache = resources->snapshot();
            if (cache.storageStatus != CurrentSongCacheStorageStatus::NotRequested) result.cacheStorageStatus = cache.storageStatus;
            result.currentSongCacheCompleteLaneMask = cache.completeLaneMask;
            result.currentSongCacheBoundToSelectedOwner =
                currentSongCacheInstallation == CacheInstallation::Installed && currentSongCacheBank == renderBank && cache.begun && !cache.cancelled &&
                (!cache.allocationFailed || cache.publishedFrames[0U] != 0U) &&
                cache.key.selectionToken == result.ownership.selectionToken &&
                cache.key.generation == result.ownership.generation &&
                cache.key.songIdentityHash == state.appliedIntent.songIdentityHash &&
                cache.key.originalContentHash == state.appliedIntent.originalContentHash &&
                cache.key.stemPackageHash == state.appliedIntent.stemPackageHash &&
                hasDesiredSnapshot && desiredSnapshot.selectionAssigned &&
                bankIndex(desiredSnapshot.selectedBank) == renderBank &&
                sameOwnership(desiredSnapshot.selectedOwnership, result.ownership);
            result.currentSongCacheCanGrow = result.currentSongCacheBoundToSelectedOwner &&
                !cache.allocationFailed && cache.io.admittedLaneCount != 0U &&
                (cache.completeLaneMask & ((1U << cache.io.admittedLaneCount) - 1U)) !=
                    ((1U << cache.io.admittedLaneCount) - 1U);
        }
        result.controlRevision = state.appliedControlRevision;
        result.ingressBankConfiguredLaneCount = ingressBanks[renderBank]->configuredLaneCount();
        result.directAtomicPolicyGeneration =
            directAtomicPolicyGeneration.load(std::memory_order_acquire);
        result.directAtomicPolicyPublishedRevision =
            directAtomicPolicyGenerationRevision.load(std::memory_order_acquire);
        result.directAtomicPolicyAdoptedRevision = atomicLoopPolicy.adopted().revision;
        result.directAtomicPolicyObservedRevision = directLoopState.observedRevision;
        if (sameOwnership(loopPreparationFailure.ownership, result.ownership))
            result.loopPreparationFailure = loopPreparationFailure;
        AtomicLoopPolicySnapshot preparedDemand{};
        result.fixedLoopHeadPrepared = directLoopCutover.commitPublished() &&
            atomicLoopPolicy.readLatest(preparedDemand) && preparedDemand.enabled &&
            directLoopStageRealizes(preparedDemand) &&
            directLoopCutover.policy.continuationTimelineFrame == preparedDemand.aSourceFrame;
        result.ingressServicePolicyBreaks = ingressServicePolicyBreaks;
        result.ingressBankIdentity =
            reinterpret_cast<std::uintptr_t>(ingressBanks[renderBank]) &
            0x00ff'ffff'ffff'ffffULL;
        result.desiredSourceMode = hasDesiredSnapshot
            ? desiredSnapshot.controls.mixer.sourceMode
            : MixerSourceMode::Original;
        // Readiness must start at the exact input frame the next render will ask the mixer for.
        // With active tempo/key DSP, feedCursor legitimately runs ahead of the audible source
        // cursor and those earlier raw frames have already been consumed into processor state.
        // Asking reservoirs to cover sourceCursor can therefore demand permanently discarded PCM.
        result.renderStartTimelineFrame = pipeline.nextPreDspTimelineFrame();
        if (pendingOutput.remainingFrames != 0U) {
            result.pendingControlRevision = pendingOutput.controlRevision;
            result.pendingActualSourceMode = pendingOutput.lateMixControl.sourceMode;
            const double pendingStart = pendingOutput.remainingSourceStart();
            if (std::isfinite(pendingStart) && pendingStart >= 0.0) {
                result.pendingSourceStartTimelineFrame =
                    static_cast<std::uint64_t>(pendingStart);
            }
            if (std::isfinite(pendingOutput.sourceEndFrameExclusive) &&
                pendingOutput.sourceEndFrameExclusive >= 0.0) {
                result.pendingSourceEndTimelineFrameExclusive =
                    static_cast<std::uint64_t>(pendingOutput.sourceEndFrameExclusive);
            }
            result.pendingFrames = pendingOutput.remainingFrames;
        }
        const SongRenderPipelineDiagnostics pipelineDiagnostics = pipeline.diagnostics();
        result.stemActivationDeferrals = pipelineDiagnostics.stemActivationDeferrals;
        result.stemSafetyFallbacks = pipelineDiagnostics.stemSafetyFallbacks;
        result.needStemDataCount = pipelineDiagnostics.needStemDataCount;
        // This is a renderability proof, not a storage-layout guess. A fixed managed-loop cache
        // deliberately moves the ordinary reservoirs to B while the live cursor remains inside
        // cached A..B; requiring raw-reservoir overlap there permanently deadlocks Stems on
        // Original after a seek/owner rebind. Ask the renderer that will actually mix the block so
        // cache, body reservoir, and logical wrap semantics stay identical to render(). Cover at
        // least both the activation quantum and this device's complete output-reserve target.
        const std::size_t activationHorizonFrames = std::max(
            kMinimumStemActivationReadinessFrames,
            outputTargetFrames());
        for (std::size_t lane = 0U; lane < result.lanes.size(); ++lane) {
            const TimelineStereoBufferDiagnostics reservoir =
                pipeline.laneDiagnostics(renderLane(lane));
            const LaneIngress *ingress = ingressBanks[renderBank]->lane(lane);
            const LaneIngressDiagnostics ingressDiagnostics = ingress != nullptr
                ? ingress->diagnostics()
                : LaneIngressDiagnostics{};
            const NativePlaybackLaneDiagnostics runtimeLaneDiagnostics =
                bankDiagnostics[renderBank].lanes[lane].snapshot();
            const bool effectivelyCovered = pipeline.laneCoversLogicalRange(
                result.ownership,
                renderLane(lane),
                result.renderStartTimelineFrame,
                activationHorizonFrames);
            result.lanes[lane] = {
                reservoir.generation,
                reservoir.firstTimelineFrame,
                reservoir.endTimelineFrameExclusive,
                effectivelyCovered
                    ? static_cast<std::uint64_t>(activationHorizonFrames)
                    : 0U,
                reservoir.gapRejectionCount,
                bankDiagnostics[renderBank].lanes[lane].failureGeneration.load(std::memory_order_acquire),
                ingressDiagnostics.activeGeneration,
                ingressDiagnostics.producerGeneration,
                ingressDiagnostics.consumerGeneration,
                ingressDiagnostics.readableSourceFrames,
                runtimeLaneDiagnostics.pulls,
                runtimeLaneDiagnostics.staleAfterPull,
                runtimeLaneDiagnostics.futureGenerationDeferrals,
            };
        }
        renderReadinessDiagnosticsPublisher.publish(result, pipeline.ingressEpoch());
    }

    [[nodiscard]] std::uint32_t requiredBankLaneMask(std::size_t bank) const noexcept {
        return bank < bankStates.size() && bankStates[bank]->assigned
            ? stemwiseRequiredSongMask(bankStates[bank]->appliedIntent.stemLaneCount) : 0U;
    }

    void workerMain() noexcept {
        const RenderWorkerSchedulingResult scheduling =
            configureCurrentRenderWorkerThread();
        renderWorkerAudioPriorityRequested.store(
            scheduling.audioPriorityRequested,
            std::memory_order_relaxed);
        renderWorkerAudioPriorityApplied.store(
            scheduling.audioPriorityApplied,
            std::memory_order_relaxed);
        renderWorkerNiceValue.store(scheduling.niceValue, std::memory_order_relaxed);
        renderWorkerPriorityErrorCode.store(
            scheduling.errorCode,
            std::memory_order_relaxed);
        // Release-publish last so a diagnostics reader that observes Started sees the complete
        // scheduling result from this exact worker.
        renderWorkerStarted.store(true, std::memory_order_release);
        while (runtimeStatus.load(std::memory_order_acquire) ==
               NativePlaybackRuntimeStatus::Running) {
            const std::uint64_t cycleWakeEpoch =
                wakeEpoch.load(std::memory_order_acquire);
            const auto turnStarted = diagnosticMonotonicMicros();
            const auto refillBudgetFrames = outputPlaying && validOwnership(committedOwnership)
                ? queuedFramesForGeneration(committedOwnership.generation) : 0U;
            saturatingIncrement(renderCycles);
            // Acquire the narrow publication first. A terminal retirement is published only after
            // canonical command C, so this acquire also exposes C before the command-mailbox read.
            bool progressed = consumeLatestStemGainPreview();
            progressed = consumeLatestCommand() || progressed;
            if (hasDesiredSnapshot) {
                for (std::size_t bank = 0U; bank < kNativePlaybackBankCount; ++bank)
                    resources->discardSupersededPreparation(bank, desiredSnapshot.banks[bank].revision);
                const auto loopRevision = desiredSnapshot.selectionAssigned &&
                    directAtomicPolicyGeneration.load(std::memory_order_acquire) ==
                        desiredSnapshot.selectedOwnership.generation
                    ? directAtomicPolicyGenerationRevision.load(std::memory_order_acquire) : 0U;
                resources->discardSupersededPreparation(kNativePlaybackBankCount, loopRevision);
            }
            progressed = reconcileBanks() || progressed;
            progressed = reconcileAutomaticSuccessorPlan() || progressed;
            progressed = reconcileSelection() || progressed;
            progressed = installSameSongFrameZeroCacheBuilder() || progressed;
            progressed = promoteCurrentSongCacheToOwner(renderBank, requestedOwnership) || progressed;
            progressed = reconcileAutomaticSuccessorPlan() || progressed;
            progressed = applyControls() || progressed;
            progressed = publishCurrentCacheReadFailures() || progressed;
            progressed = applyStemGainPreview() || progressed;
            progressed = publishSelectedPreparedLateMixEndpoint() || progressed;
            progressed = applyBeatGrids() || progressed;
            // A full output queue must not strand a prior render's interrupted input publication.
            if (currentSongCache && currentSongCacheBank < bankStates.size()) {
                const SongRenderOwnership cacheOwner{currentSongCacheKey.selectionToken, currentSongCacheKey.generation};
                auto &bank = *bankStates[currentSongCacheBank];
                if (bank.assigned && sameOwnership(bank.pipeline->ownership(), cacheOwner))
                    progressed = bank.pipeline->retryPendingCacheReadRequirements(currentSongCache) || progressed;
                if (loopContinuationPipeline && selectionAssigned && currentSongCacheBank == renderBank &&
                    sameOwnership(requestedOwnership, cacheOwner) &&
                    sameOwnership(loopContinuationPipeline->ownership(), cacheOwner))
                    progressed = loopContinuationPipeline->retryPendingCacheReadRequirements(currentSongCache) || progressed;
            }
            // Prime/render the audible owner before preparing its spare loop continuation.
            // Both may need a spectral prime after tuning changes; doing both first can drain
            // the device's ready PCM even though each operation fits inside the normal reserve.
            progressed = fillOutput(false) || progressed;
            progressed = advanceManagedLoopProtocol() || progressed;
            progressed = reconcileTerminalSilenceAuthorization() || progressed;
            progressed = serviceIngressAndCurrentOutput() || progressed;
            progressed = fillAutomaticSuccessorOutput() || progressed;
            progressed = commitAutomaticSuccessorIfReady() || progressed;
            progressed = commitStagedHandoffIfReady() || progressed;
            progressed = installCommittedHandoffWhilePaused() || progressed;
            progressed = reconcileOutputPlaying() || progressed;
            progressed = consumePresentationEvidence() || progressed;
            progressed = consumeAppliedControlEvidence() || progressed;
            progressed = consumeStemGainCallbackEvidence() || progressed;
            progressed = consumePresentedPosition() || progressed;
            progressed = publishAudibleEndIfDrained() || progressed;
            if (renderReadinessRequested.exchange(false, std::memory_order_acq_rel)) {
                publishRenderReadinessOnWorker();
            }

            if (refillBudgetFrames != 0U && diagnosticMonotonicMicros() - turnStarted >
                    static_cast<std::uint64_t>(refillBudgetFrames) * 1'000'000ULL / 48'000ULL)
                saturatingIncrement(refillDeadlineMisses);
            if (!progressed) {
                saturatingIncrement(idleSleeps);
                std::unique_lock lock(wakeMutex);
                const auto woken = [this, cycleWakeEpoch] {
                        return wakeEpoch.load(std::memory_order_acquire) != cycleWakeEpoch ||
                            runtimeStatus.load(std::memory_order_acquire) !=
                                NativePlaybackRuntimeStatus::Running;
                    };
                const bool transportWantsOutput = hasDesiredSnapshot &&
                    desiredSnapshot.selectionAssigned && desiredSnapshot.playing;
                // The device callback cannot signal wakeEpoch when it releases a claimed drain.
                // Keep the bounded poll alive until that exact acknowledgement is observable.
                const bool loopRetirementPending = directLoopCutover.commitPublished() &&
                    newerDirectAtomicPolicyPublishedThan(directLoopCutover.policy.revision);
                const bool loopPreparationPending = directLoopCutover.policy.revision != 0U &&
                    !directLoopCutover.commitPublished();
                const bool administrativeFlushPending = hasDesiredSnapshot &&
                    !desiredSnapshot.selectionAssigned &&
                    desiredSnapshot.outputGeneration > appliedOutputGeneration;
                const bool callbackAcknowledgementPending =
                    automaticSuccessor.cancellationDesired || loopRetirementPending || loopPreparationPending ||
                    administrativeFlushPending;
                if (!outputPlaying && !transportWantsOutput &&
                    !callbackAcknowledgementPending) {
                    // Paused transport has no deadline-driven render work. Commands, ingress,
                    // readiness probes, and STOP all advance wakeEpoch, so park instead of burning
                    // one CPU core on a 1 ms timer while the app is idle or backgrounded.
                    wakeCondition.wait(lock, woken);
                } else {
                    // Audio uses 10 ms blocks, or hardware bursts while tuned. Acknowledgements and
                    // interruptions must still wake this wait without adding another block.
                    // A callback-owned old continuation cannot be cancelled until that callback
                    // releases it, and callbacks cannot notify this condition variable. Poll only
                    // pending retirement and preparation promptly; neither callback availability
                    // change can notify us. Settled playback keeps its ordinary idle budget.
                    const auto waitMicros = loopRetirementPending || loopPreparationPending
                        ? std::min(configuration.idleSleepMicros, 1'000U)
                        : configuration.idleSleepMicros;
#if defined(STEMWISE_CACHE_IO_PROFILING)
                    if (transportWantsOutput && directLoopState.observedRevision != 0U) {
                        AudioTrace trace("StemWise.audio.loop.idle");
                        trace.counter("StemWise.audio.loop.stage_phase", static_cast<std::int64_t>(directLoopCutover.phase));
                        trace.counter("StemWise.audio.loop.logical_revision", static_cast<std::int64_t>(directLoopCutover.policy.revision));
                        trace.counter("StemWise.audio.loop.physical_revision", static_cast<std::int64_t>(directLoopCutover.policy.physicalRevision()));
                        trace.counter("StemWise.audio.loop.observed_revision", static_cast<std::int64_t>(directLoopState.observedRevision));
                        trace.counter("StemWise.audio.loop.active_physical_revision", static_cast<std::int64_t>(directLoopState.physicalRevision));
                        trace.counter("StemWise.audio.loop.staged_frames", static_cast<std::int64_t>(directLoopCutover.stagedFrames));
                        trace.counter("StemWise.audio.loop.wait_us", waitMicros);
                        trace.counter("StemWise.audio.loop.wake_epoch", static_cast<std::int64_t>(cycleWakeEpoch));
                    }
#endif
                    wakeCondition.wait_for(lock, std::chrono::microseconds(waitMicros), woken);
                }
            }
        }
        // No PCM enqueue is performed after the loop observes StopRequested. join() is the
        // definitive lifetime barrier before Kotlin may close output or either ingress bank.
        cancelPendingRetainedBodyCopy();
        output.setTerminalSilenceAuthorization(output.handle, 0U, false);
        terminalSilenceAuthorizedGeneration = 0U;
        static_cast<void>(output.setPlaying(output.handle, false));
        outputPlaying = false;
        runtimeStatus.store(NativePlaybackRuntimeStatus::Stopped, std::memory_order_release);
    }

    [[nodiscard]] std::uint32_t cachePreservationLaneMask(const NativePlaybackBankIntent &intent) const noexcept {
        const auto intended = intent.stemPackageHash == 0U ? CurrentSongPcmCache::kOriginalLaneMask :
            stemwiseRequiredSongMask(intent.stemLaneCount);
        const auto admitted = resources->snapshot().io.admittedLaneCount;
        const auto capacity = admitted == 0U ? 0U : (1U << admitted) - 1U;
        return (capacity & intended) == intended ? intended : CurrentSongPcmCache::kOriginalLaneMask;
    }

    [[nodiscard]] std::uint32_t preservedCurrentSongCacheLaneMask(const NativePlaybackBankIntent &intent) const noexcept {
        if (currentSongCache == nullptr || !intent.assigned ||
            currentSongCacheKey.songIdentityHash != intent.songIdentityHash ||
            currentSongCacheKey.originalContentHash != intent.originalContentHash ||
            currentSongCacheKey.stemPackageHash != intent.stemPackageHash) return 0U;
        const auto completeMask = cachePreservationLaneMask(intent);
        if (currentSongCache->complete(completeMask)) return completeMask;
        // A second seek cannot discard the prefix already transferred from a live frame-zero
        // producer. Only that exact producer can fill the remaining gap; a sought suffix cannot.
        if (currentSongCacheInstallation == CacheInstallation::Building || currentSongCacheBuildFailed ||
            currentSongCacheKey.selectionToken != intent.ownership.selectionToken ||
            currentSongCacheBank >= kNativePlaybackBankCount || !hasDesiredSnapshot ||
            desiredSnapshot.controls.mixer.sourceMode != MixerSourceMode::Original ||
            !desiredSnapshot.selectionAssigned ||
            bankIndex(desiredSnapshot.selectedBank) != currentSongCacheBank ||
            !sameOwnership(desiredSnapshot.selectedOwnership, intent.ownership) ||
            currentSongCache->availableFrames(0U) == 0U ||
            !matchingFrameZeroBuilderForCurrentCache(1U - currentSongCacheBank)) return 0U;
        return CurrentSongPcmCache::kOriginalLaneMask;
    }

    [[nodiscard]] bool consumeLatestCommand() noexcept {
        NativePlaybackCommandSnapshot latest{};
        if (!commandMailbox.readLatest(consumedCommandRevision, latest)) return false;
        consumedCommandRevision = latest.revision;
        desiredSnapshot = latest;
        hasDesiredSnapshot = true;
        if (currentSongCacheCreatePending) {
            const auto &intent = desiredSnapshot.banks[cacheCreationBank];
            if (!intent.assigned || intent.ownership.selectionToken != cacheCreationKey.selectionToken ||
                intent.ownership.generation != cacheCreationKey.generation ||
                intent.stemPackageHash != cacheCreationKey.stemPackageHash) clearCurrentSongCache();
        }
        if (currentSongCache && currentSongCacheBank < kNativePlaybackBankCount) {
            const auto &intent = desiredSnapshot.banks[currentSongCacheBank];
            if (!intent.assigned || (!sameOwnership(intent.ownership, bankStates[currentSongCacheBank]->pipeline
                ? bankStates[currentSongCacheBank]->pipeline->ownership() : SongRenderOwnership{}) &&
                preservedCurrentSongCacheLaneMask(intent) == 0U)) clearCurrentSongCache();
        }
        const std::size_t queued = validOwnership(requestedOwnership)
            ? queuedFramesForGeneration(requestedOwnership.generation)
            : 0U;
        recordWorkerEndOrderingEvent(endOrderingCommandConsumed, {}, queued);
        saturatingIncrement(commandSnapshotsConsumed);
        return true;
    }

    [[nodiscard]] bool consumeLatestStemGainPreview() noexcept {
        NativePlaybackStemGainPreview latest{};
        if (!stemGainPreviewMailbox.readLatest(
                consumedStemGainPreviewMailboxPublication,
                latest)) {
            return false;
        }
        pendingStemGainPreview = latest;
        hasPendingStemGainPreview = true;
        return true;
    }

    void retireStemGainPreviewForBank(
        std::size_t bank,
        SongRenderOwnership ownership) noexcept {
        if (bank >= kNativePlaybackBankCount || !validOwnership(ownership) || !bankStates[bank]->pipeline) return;
        // SongRenderPipeline validates the exact resident owner and deliberately retains its
        // preview-sequence high-water mark. A late retirement for an old owner therefore cannot
        // erase a newer owner's overlay or make an older publication admissible after reset.
        static_cast<void>(bankStates[bank]->pipeline->retireStemGainPreview(ownership));
    }

    void clearCurrentSongCache() noexcept {
        if (loopContinuationPipeline) static_cast<void>(loopContinuationPipeline->bindCurrentSongPcmCache(
            loopContinuationPipeline->ownership(), nullptr));
        if (currentSongCacheBank < kNativePlaybackBankCount && bankStates[currentSongCacheBank]->pipeline) {
            static_cast<void>(bankStates[currentSongCacheBank]->pipeline->bindCurrentSongPcmCache(
                bankStates[currentSongCacheBank]->pipeline->ownership(),
                nullptr));
        }
        if (currentSongCache != nullptr || currentSongCacheCreatePending) resources->retireCache();
        currentSongCache = nullptr;
        currentSongCacheInstallation = CacheInstallation::Building;
        currentSongCacheCreatePending = false;
        cacheCreationKey = {};
        cacheCreationBank = kNativePlaybackBankCount;
        currentSongCacheKey = {};
        currentSongCacheBank = kNativePlaybackBankCount;
        currentSongCacheBuildFailed = false;
        currentSongCacheStorageStatus = CurrentSongCacheStorageStatus::NotRequested;
    }

    [[nodiscard]] bool installCurrentSongCacheImpl(std::size_t bank, SongRenderOwnership ownership) {
        if (bank >= kNativePlaybackBankCount || !validOwnership(ownership)) return false;
        BankRuntimeState &state = *bankStates[bank];
        if (!state.assigned || !sameOwnership(state.pipeline->ownership(), ownership)) return false;
        const CurrentSongCacheKey key{ownership.selectionToken, ownership.generation,
            state.appliedIntent.songIdentityHash, state.appliedIntent.originalContentHash,
            state.appliedIntent.stemPackageHash};
        if (currentSongCache && currentSongCacheKey == key && currentSongCacheBank == bank &&
            currentSongCacheInstallation != CacheInstallation::Building) {
            currentSongCacheInstallation = CacheInstallation::Rebinding;
            if (!state.pipeline->bindCurrentSongPcmCache(ownership, currentSongCache)) return false;
            currentSongCacheInstallation = CacheInstallation::Installed;
            return true;
        }
        if (state.appliedIntent.firstTimelineFrame != 0U) return false;
        if ((currentSongCache && currentSongCacheKey != key) ||
            (currentSongCacheCreatePending && cacheCreationKey != key)) clearCurrentSongCache();
        if (!currentSongCache) {
            if (!currentSongCacheCreatePending && cacheCreationKey == key && cacheCreationIntentRevision == state.appliedIntent.revision &&
                currentSongCacheStorageStatus != CurrentSongCacheStorageStatus::NotRequested) return false;
            cacheCreationKey = key;
            cacheCreationIntentRevision = state.appliedIntent.revision;
            cacheCreationBank = bank;
            currentSongCacheCreatePending = true;
            const auto upper = state.appliedIntent.cacheFrameUpperBound;
            bool finished = false;
            auto *cache = resources->createCache(key, state.appliedIntent.stemLaneCount + 1U, upper, finished);
            if (!finished) return false;
            currentSongCacheCreatePending = false;
            currentSongCacheStorageStatus = resources->storageStatus();
            if (!cache) {
                static_cast<void>(state.pipeline->retainOriginalTransitionHead(ownership, false));
                return false;
            }
            currentSongCache = cache;
            currentSongCacheKey = key;
            currentSongCacheBank = bank;
        }
        const auto retained = state.pipeline->laneDiagnostics(SongRenderLane::Original);
        if (retained.storedFrames != 0U && retained.firstTimelineFrame != 0U) {
            clearCurrentSongCache();
            static_cast<void>(state.pipeline->retainOriginalTransitionHead(ownership, false));
            return false;
        }
        const auto frame = resources->snapshot().nextExpectedFrames[0U];
        if (frame < retained.endTimelineFrameExclusive) {
            const auto count = static_cast<std::size_t>(std::min<std::uint64_t>(configuration.ingressPullFrames,
                retained.endTimelineFrameExclusive - frame));
            const auto copied = state.pipeline->copyRetainedLanePcm(ownership, SongRenderLane::Original,
                frame, currentSongCacheScratch.get(), configuration.ingressPullFrames, count);
            if (copied.status != TimelineCopyStatus::Complete || copied.framesCopied != count) return false;
            const auto offered = resources->offer(key, 0U, frame, currentSongCacheScratch.get(), count);
            if (offered.status == CurrentSongCacheOfferStatus::Pending) return false;
            if (offered.status != CurrentSongCacheOfferStatus::Accepted || offered.inputFramesConsumed != count) {
                clearCurrentSongCache();
                cacheCreationKey = key;
                currentSongCacheStorageStatus = CurrentSongCacheStorageStatus::IoFailure;
                static_cast<void>(state.pipeline->retainOriginalTransitionHead(ownership, false));
                return false;
            }
            return true;
        }
        const auto originalEnd = state.pipeline->laneEndSnapshot(SongRenderLane::Original);
        if (originalEnd.marked && originalEnd.ingressEpoch == state.pipeline->ingressEpoch()) {
            const auto sealed = resources->seal(key, 0U, originalEnd.endTimelineFrameExclusive);
            if (sealed == CurrentSongCacheSealStatus::Pending) return false;
            if (sealed != CurrentSongCacheSealStatus::Accepted && sealed != CurrentSongCacheSealStatus::AlreadyComplete) return false;
        }
        if (!state.pipeline->bindCurrentSongPcmCache(ownership, currentSongCache)) return false;
        currentSongCacheInstallation = CacheInstallation::Installed;
        if (currentSongCache->availableFrames(0U) >= retained.endTimelineFrameExclusive)
            static_cast<void>(state.pipeline->retainOriginalTransitionHead(ownership, false));
        return true;
    }

    [[nodiscard]] bool installCurrentSongCache(
        std::size_t bank,
        SongRenderOwnership ownership) noexcept {
        try {
            return installCurrentSongCacheImpl(bank, ownership);
        } catch (...) {
            clearCurrentSongCache();
            if (bank < kNativePlaybackBankCount && bankStates[bank]->pipeline)
                static_cast<void>(bankStates[bank]->pipeline->retainOriginalTransitionHead(ownership, false));
            return false;
        }
    }

    [[nodiscard]] bool installSameSongFrameZeroCacheBuilder() noexcept {
        if (currentSongCache && currentSongCacheInstallation == CacheInstallation::Rebinding) {
            if (currentSongCacheBank >= kNativePlaybackBankCount) return false;
            auto &pipeline = *bankStates[currentSongCacheBank]->pipeline;
            if (!sameOwnership(pipeline.ownership(),
                    {currentSongCacheKey.selectionToken, currentSongCacheKey.generation}) ||
                !pipeline.bindCurrentSongPcmCache(pipeline.ownership(), currentSongCache)) return false;
            currentSongCacheInstallation = CacheInstallation::Installed;
            return true;
        }
        if (currentSongCache && currentSongCacheInstallation != CacheInstallation::Installed)
            return installCurrentSongCache(currentSongCacheBank, bankStates[currentSongCacheBank]->pipeline->ownership());
        if (currentSongCacheCreatePending)
            return installCurrentSongCache(cacheCreationBank, bankStates[cacheCreationBank]->pipeline->ownership());
        if (!currentSongCache && selectionAssigned && selectionMatchesDesired() && renderBank < kNativePlaybackBankCount &&
            bankStates[renderBank]->appliedIntent.firstTimelineFrame == 0U)
            return installCurrentSongCache(renderBank, requestedOwnership);
        if (currentSongCache != nullptr) return extendRetainedFrameZeroCachePrefixes();
        if (!selectionAssigned || !hasDesiredSnapshot ||
            !desiredSnapshot.selectionAssigned || renderBank >= kNativePlaybackBankCount ||
            bankIndex(desiredSnapshot.selectedBank) != renderBank ||
            !sameOwnership(desiredSnapshot.selectedOwnership, requestedOwnership)) {
            return false;
        }

        const std::size_t builderBank = 1U - renderBank;
        const BankRuntimeState &consumer = *bankStates[renderBank];
        const BankRuntimeState &builder = *bankStates[builderBank];
        const NativePlaybackBankIntent &desiredBuilder = desiredSnapshot.banks[builderBank];
        if (!consumer.assigned || !builder.assigned || !desiredBuilder.assigned ||
            !sameOwnership(builder.pipeline->ownership(), desiredBuilder.ownership) ||
            builder.appliedIntent.firstTimelineFrame != 0U ||
            builder.appliedIntent.ownership.selectionToken !=
                consumer.appliedIntent.ownership.selectionToken ||
            builder.appliedIntent.songIdentityHash != consumer.appliedIntent.songIdentityHash ||
            builder.appliedIntent.originalContentHash !=
                consumer.appliedIntent.originalContentHash ||
            builder.appliedIntent.stemPackageHash != consumer.appliedIntent.stemPackageHash) {
            return false;
        }
        return installCurrentSongCache(builderBank, builder.pipeline->ownership());
    }

    [[nodiscard]] bool matchingFrameZeroBuilderForCurrentCache(std::size_t bank) const noexcept {
        if (currentSongCache == nullptr || currentSongCacheBuildFailed || !selectionAssigned ||
            !hasDesiredSnapshot || !desiredSnapshot.selectionAssigned || renderBank >= bankStates.size() ||
            currentSongCacheBank != renderBank || bank >= bankStates.size() || bank == renderBank ||
            resources->snapshot().io.admittedLaneCount == 0U) return false;
        const auto &consumer = *bankStates[renderBank];
        const auto &builder = *bankStates[bank];
        const auto &intent = builder.appliedIntent;
        return consumer.assigned && builder.assigned && intent.firstTimelineFrame == 0U &&
            intent.stemLaneCount == consumer.appliedIntent.stemLaneCount &&
            intent.stemPackageHash == currentSongCacheKey.stemPackageHash &&
            intent.songIdentityHash == currentSongCacheKey.songIdentityHash &&
            intent.originalContentHash == currentSongCacheKey.originalContentHash &&
            intent.ownership.selectionToken == currentSongCacheKey.selectionToken &&
            consumer.appliedIntent.songIdentityHash == currentSongCacheKey.songIdentityHash &&
            consumer.appliedIntent.originalContentHash == currentSongCacheKey.originalContentHash &&
            consumer.appliedIntent.stemPackageHash == currentSongCacheKey.stemPackageHash &&
            sameOwnership(consumer.pipeline->ownership(), requestedOwnership) &&
            requestedOwnership.selectionToken == currentSongCacheKey.selectionToken &&
            currentSongCacheKey.generation == requestedOwnership.generation &&
            desiredSnapshot.banks[bank].assigned &&
            desiredSnapshot.banks[bank].stemPackageHash == intent.stemPackageHash &&
            desiredSnapshot.banks[bank].stemLaneCount == intent.stemLaneCount &&
            sameOwnership(builder.pipeline->ownership(), desiredSnapshot.banks[bank].ownership);
    }

    [[nodiscard]] bool cacheOwnsLaneIngress(
        std::size_t bank, std::size_t lane, std::uint64_t generation,
        std::uint64_t firstTimelineFrame) const noexcept {
        if (currentSongCache == nullptr || currentSongCacheInstallation == CacheInstallation::Building || currentSongCacheBuildFailed) return false;
        // Capacity is an accepted contract, not the outcome of a later free-space probe.
        const auto cached = resources->snapshot();
        if (lane >= cached.io.admittedLaneCount) return false;
        const auto owner = bankStates[bank]->pipeline->ownership();
        // A future seek cannot revoke this installed cache's producer before its rebind commits.
        const bool retainedProducer = matchingFrameZeroBuilderForCurrentCache(bank);
        if (retainedProducer) return owner.generation == generation;
        if (currentSongCacheBank != bank || currentSongCacheKey.selectionToken != owner.selectionToken ||
            currentSongCacheKey.generation != generation) return false;
        // Admit each lane at its actual contiguous cache cursor, including late frame-zero parts.
        // A sought suffix beyond that cursor stays ordinary even after its builder retires.
        // Complete lanes still drain through LaneComplete after A resets the ordinary reservoir.
        return firstTimelineFrame <= cached.nextExpectedFrames[lane] ||
            currentSongCache->complete(1U << static_cast<std::uint32_t>(lane));
    }

    [[nodiscard]] bool extendRetainedFrameZeroCachePrefixes() noexcept {
        if (currentSongCacheInstallation != CacheInstallation::Installed || currentSongCacheBuildFailed) return false;
        std::size_t builderBank = currentSongCacheBank;
        if (renderBank < bankStates.size() && matchingFrameZeroBuilderForCurrentCache(1U - renderBank))
            builderBank = 1U - renderBank;
        if (builderBank >= bankStates.size() || !bankStates[builderBank]->assigned) return false;
        if (currentSongCache->complete(requiredBankLaneMask(builderBank))) return false;
        auto &pipeline = *bankStates[builderBank]->pipeline;
        const auto cached = resources->snapshot();
        for (std::size_t lane = 0U; lane <= pipeline.stemLaneCount(); ++lane) {
            if (lane >= cached.io.admittedLaneCount ||
                (cached.completeLaneMask & (1U << lane)) != 0U) continue;
            const auto retained = pipeline.laneDiagnostics(renderLane(lane));
            const auto next = cached.nextExpectedFrames[lane];
            if (retained.firstTimelineFrame <= next && next < retained.endTimelineFrameExclusive) {
                const auto frames = static_cast<std::size_t>(std::min<std::uint64_t>(
                    configuration.ingressPullFrames, retained.endTimelineFrameExclusive - next));
                const auto copied = pipeline.copyRetainedLanePcm(pipeline.ownership(), renderLane(lane),
                    next, currentSongCacheScratch.get(), configuration.ingressPullFrames, frames);
                if (copied.status == TimelineCopyStatus::Complete && copied.framesCopied == frames) {
                    const auto offered = resources->offer(currentSongCacheKey, lane, next,
                        currentSongCacheScratch.get(), frames);
                    if (offered.status == CurrentSongCacheOfferStatus::Pending) return false;
                    if (offered.status == CurrentSongCacheOfferStatus::AllocationFailed) {
                        return abandonCurrentSongCacheBuild();
                    }
                    // One bounded prefix write per cycle leaves the output refill ahead of
                    // further cache I/O; other lanes are resumed from their exact next frame.
                    if (offered.inputFramesConsumed != 0U) return true;
                }
            }
            const auto eos = pipeline.laneEndSnapshot(renderLane(lane));
            const auto requiredEnd = lane == 0U ? eos.endTimelineFrameExclusive :
                std::min(eos.endTimelineFrameExclusive, cached.originalEndFrameExclusive);
            if (eos.marked && eos.ingressEpoch == pipeline.ingressEpoch() &&
                resources->snapshot().nextExpectedFrames[lane] >= requiredEnd) {
                const auto sealed = resources->seal(currentSongCacheKey, lane, eos.endTimelineFrameExclusive);
                if (sealed == CurrentSongCacheSealStatus::Pending) return false;
                if (sealed == CurrentSongCacheSealStatus::AllocationFailed) {
                    return abandonCurrentSongCacheBuild();
                }
                if (sealed == CurrentSongCacheSealStatus::Accepted ||
                    sealed == CurrentSongCacheSealStatus::PaddingPending) return true;
            }
        }
        return false;
    }

    [[nodiscard]] bool promoteCurrentSongCacheToOwner(
        std::size_t consumerBank, SongRenderOwnership consumerOwnership) noexcept {
        // Original readiness cannot wait for an intended but currently inaudible stem package.
        const auto acceptedLaneMask = resources->pendingRebindLaneMask(currentSongCacheKey);
        const std::uint32_t requestedLaneMask = currentSongCacheKey.stemPackageHash == 0U ||
            (hasDesiredSnapshot && desiredSnapshot.controls.mixer.sourceMode == MixerSourceMode::Original)
            ? CurrentSongPcmCache::kOriginalLaneMask
            : requiredBankLaneMask(consumerBank);
        const auto requiredLaneMask = acceptedLaneMask != 0U ? acceptedLaneMask : requestedLaneMask;
        if (currentSongCache == nullptr || currentSongCacheInstallation != CacheInstallation::Installed ||
            currentSongCacheBank >= kNativePlaybackBankCount ||
            !hasDesiredSnapshot || !desiredSnapshot.selectionAssigned ||
            consumerBank >= kNativePlaybackBankCount || currentSongCacheBank == consumerBank ||
            bankIndex(desiredSnapshot.selectedBank) != consumerBank ||
            !sameOwnership(desiredSnapshot.selectedOwnership, consumerOwnership)) {
            return false;
        }
        BankRuntimeState &consumer = *bankStates[consumerBank];
        if (!consumer.assigned || !sameOwnership(consumer.pipeline->ownership(), consumerOwnership)) {
            return false;
        }
        const CurrentSongCacheKey consumerKey{
            .selectionToken = consumerOwnership.selectionToken,
            .generation = consumerOwnership.generation,
            .songIdentityHash = consumer.appliedIntent.songIdentityHash,
            .originalContentHash = consumer.appliedIntent.originalContentHash,
            .stemPackageHash = consumer.appliedIntent.stemPackageHash,
        };
        const bool streamingOriginal = !currentSongCache->complete(requiredLaneMask);
        const auto &builder = *bankStates[currentSongCacheBank];
        const auto &desiredBuilder = desiredSnapshot.banks[currentSongCacheBank];
        if (streamingOriginal && (requiredLaneMask != CurrentSongPcmCache::kOriginalLaneMask ||
            !builder.assigned || !desiredBuilder.assigned || builder.appliedIntent.firstTimelineFrame != 0U ||
            currentSongCacheKey.selectionToken != consumerOwnership.selectionToken ||
            !sameOwnership(builder.pipeline->ownership(), desiredBuilder.ownership) ||
            builder.pipeline->ownership().generation != currentSongCacheKey.generation)) return false;
        if (consumerKey.songIdentityHash != currentSongCacheKey.songIdentityHash ||
            consumerKey.originalContentHash != currentSongCacheKey.originalContentHash ||
            consumerKey.stemPackageHash != currentSongCacheKey.stemPackageHash ||
            !resources->rebind(
                currentSongCacheKey,
                consumerKey,
                requiredLaneMask,
                streamingOriginal)) {
            return false;
        }

        const std::size_t builderBank = currentSongCacheBank;
        const SongRenderOwnership builderOwnership{
            currentSongCacheKey.selectionToken,
            currentSongCacheKey.generation,
        };
        static_cast<void>(bankStates[builderBank]->pipeline->bindCurrentSongPcmCache(
            builderOwnership,
            nullptr));
        currentSongCacheBank = consumerBank;
        currentSongCacheKey = consumerKey;
        currentSongCacheInstallation = CacheInstallation::Rebinding;
        if (consumer.pipeline->bindCurrentSongPcmCache(consumerOwnership, currentSongCache))
            currentSongCacheInstallation = CacheInstallation::Installed;
        return true;
    }

    [[nodiscard]] bool reconcileBanks() noexcept {
        if (!hasDesiredSnapshot) return false;
        bool progressed = false;
        for (std::size_t index = 0; index < kNativePlaybackBankCount; ++index) {
            BankRuntimeState &state = *bankStates[index];
            const NativePlaybackBankIntent &intent = desiredSnapshot.banks[index];
            if (intent.revision <= state.appliedIntent.revision) continue;
            if (!state.pipeline) {
                if (!intent.assigned) {
                    state.appliedIntent = intent;
                    bankDiagnostics[index].publishAppliedIntent(false, intent.revision);
                    progressed = true;
                    continue;
                }
                bool failed = false;
                if (!resources->preparePipeline(index, intent.revision, intent.stemLaneCount, state.pipeline, &failed)) {
                    if (failed && state.rejectedResourceRevision != intent.revision) {
                        state.rejectedResourceRevision = intent.revision;
                        saturatingIncrement(bankDiagnostics[index].rejectedBankIntents);
                    }
                    continue;
                }
            }
            const SongRenderOwnership current = state.pipeline->ownership();
            const bool stemIdentityOnlyUpdate = intent.assigned && state.assigned &&
                sameOwnership(current, intent.ownership) &&
                state.appliedIntent.firstTimelineFrame == intent.firstTimelineFrame &&
                state.appliedIntent.playbackEndFrameExclusive == intent.playbackEndFrameExclusive &&
                state.appliedIntent.outputClockStartFrame == intent.outputClockStartFrame &&
                state.appliedIntent.songIdentityHash == intent.songIdentityHash &&
                state.appliedIntent.originalContentHash == intent.originalContentHash &&
                (state.appliedIntent.stemPackageHash == 0U || intent.stemPackageHash == 0U) &&
                state.appliedIntent.stemPackageHash != intent.stemPackageHash;
            const bool revisionOnlyUpdate = intent.assigned && state.assigned &&
                sameOwnership(current, intent.ownership) &&
                state.appliedIntent.firstTimelineFrame == intent.firstTimelineFrame &&
                state.appliedIntent.playbackEndFrameExclusive == intent.playbackEndFrameExclusive &&
                state.appliedIntent.outputClockStartFrame == intent.outputClockStartFrame &&
                state.appliedIntent.songIdentityHash == intent.songIdentityHash &&
                state.appliedIntent.originalContentHash == intent.originalContentHash &&
                state.appliedIntent.stemPackageHash == intent.stemPackageHash &&
                state.appliedIntent.stemLaneCount == intent.stemLaneCount;
#if defined(STEMWISE_CACHE_IO_PROFILING)
            if (intent.assigned && state.assigned && sameOwnership(current, intent.ownership)) {
                AudioTrace trace("StemWise.audio.bank.reconcile");
                trace.counter("StemWise.audio.bank.index", static_cast<std::int64_t>(index));
                trace.counter("StemWise.audio.bank.generation", static_cast<std::int64_t>(current.generation));
                trace.counter("StemWise.audio.bank.applied_revision", static_cast<std::int64_t>(state.appliedIntent.revision));
                trace.counter("StemWise.audio.bank.requested_revision", static_cast<std::int64_t>(intent.revision));
                trace.counter("StemWise.audio.bank.applied_first", static_cast<std::int64_t>(state.appliedIntent.firstTimelineFrame));
                trace.counter("StemWise.audio.bank.requested_first", static_cast<std::int64_t>(intent.firstTimelineFrame));
                trace.counter("StemWise.audio.bank.applied_output_clock", state.appliedIntent.outputClockStartFrame);
                trace.counter("StemWise.audio.bank.requested_output_clock", intent.outputClockStartFrame);
                trace.counter("StemWise.audio.bank.applied_end", static_cast<std::int64_t>(state.appliedIntent.playbackEndFrameExclusive));
                trace.counter("StemWise.audio.bank.requested_end", static_cast<std::int64_t>(intent.playbackEndFrameExclusive));
                trace.counter("StemWise.audio.bank.same_song_hash", state.appliedIntent.songIdentityHash == intent.songIdentityHash);
                trace.counter("StemWise.audio.bank.same_original_hash", state.appliedIntent.originalContentHash == intent.originalContentHash);
                trace.counter("StemWise.audio.bank.same_stem_hash", state.appliedIntent.stemPackageHash == intent.stemPackageHash);
                trace.counter("StemWise.audio.bank.same_stem_count", state.appliedIntent.stemLaneCount == intent.stemLaneCount);
                trace.counter("StemWise.audio.bank.same_cache_upper_bound", state.appliedIntent.cacheFrameUpperBound == intent.cacheFrameUpperBound);
            }
#endif
            if (intent.assigned && state.assigned && sameOwnership(current, intent.ownership) &&
                !stemIdentityOnlyUpdate && !revisionOnlyUpdate) {
                // Reject transport/content changes before touching this owner's committed cache.
                // Only the supported metadata updates may retain an existing generation.
                saturatingIncrement(bankDiagnostics[index].rejectedBankIntents);
                continue;
            }
            if (currentSongCache && currentSongCacheBank == index) {
                const CurrentSongCacheKey expected{intent.ownership.selectionToken, intent.ownership.generation,
                    intent.songIdentityHash, intent.originalContentHash, intent.stemPackageHash};
                const auto discarded = resources->discardSupersededCacheResult(expected, intent.revision);
                if (discarded == NativePlaybackCacheDiscardStatus::Pending) continue;
                if (discarded == NativePlaybackCacheDiscardStatus::Discarded)
                    currentSongCacheKey = resources->snapshot().key;
            }
            if (intent.assigned && intent.ownership.generation > current.generation &&
                intent.ownership.generation > state.pipeline->ingressEpoch()) {
                if (selectionAssigned && index == renderBank && directLoopCutover.policy.revision != 0U) {
                    const std::uint64_t retiredRevision = directLoopCutover.policy.revision;
                    if (!cancelDirectLoopStage()) continue;
                    directLoopState.retiredRevision = retiredRevision;
                    progressed = true;
                }
                // A reused inactive bank must not carry its retired generation's loop into a seek.
                if (state.pipeline->loopEnabled()) {
                    const SongRenderLoopStatus status = state.pipeline->clearLoop(current);
                    if (status != SongRenderLoopStatus::Accepted &&
                        status != SongRenderLoopStatus::Disabled) {
                        continue;
                    }
                    if (index == renderBank) {
                        directLoopState.replaceOwner();
                    }
                    progressed = true;
                }
            }

            const bool cacheOnBank = currentSongCache != nullptr &&
                currentSongCacheBank == index;
            if (cacheOnBank && stemIdentityOnlyUpdate &&
                directLoopPolicyOwnsSelectedOwner()) {
                // Retire the old package's prepared continuation before changing its cache.
                // Keep the active producer and logical A/B: the next continuation is rebuilt
                // from the updated lanes, without waiting for the user to disable looping.
                if (!cancelDirectLoopStage()) continue;
                reusableLoopHead.discard();
            }
            if (state.assigned && !intent.assigned && loopContinuationPipeline) {
                // An unpublished body copy can pin this exact donor before a cutover exists.
                // Release it before lane retirement; physical stage acknowledgement stays separate.
                loopContinuationPipeline->cancelAtomicLoopContinuationPreparationFrom(*state.pipeline);
            }
            bool preparationFailed = false;
            if (!resources->prepareLanes(index, intent.revision, intent.assigned ? intent.stemLaneCount : 0U,
                    *state.pipeline, &preparationFailed)) {
                if (preparationFailed && state.rejectedResourceRevision != intent.revision) {
                    state.rejectedResourceRevision = intent.revision;
                    saturatingIncrement(bankDiagnostics[index].rejectedBankIntents);
                }
                continue;
            }
            if (cacheOnBank && stemIdentityOnlyUpdate && intent.stemPackageHash != 0U) {
                const auto originalFrames = resources->snapshot().originalEndFrameExclusive;
                const auto upperFrames = originalFrames != 0U ? originalFrames : intent.cacheFrameUpperBound;
                if (resources->admitStorage(
                        CurrentSongCacheKey{intent.ownership.selectionToken, intent.ownership.generation,
                            intent.songIdentityHash, intent.originalContentHash, intent.stemPackageHash},
                        intent.revision, intent.stemLaneCount + 1U, upperFrames) == NativePlaybackResourceStatus::Pending) continue;
                currentSongCacheStorageStatus = resources->storageStatus();
                // This cache is optional: retain its exact Original and stream parts through
                // ordinary reservoirs when a full-song part cache cannot be admitted.
            }
            const auto preservedCacheMask = cacheOnBank ? preservedCurrentSongCacheLaneMask(intent) : 0U;
            const bool preserveSameContentCache = preservedCacheMask != 0U;
            bool adoptedStemPackageIdentity = false;
            if (cacheOnBank && intent.assigned && intent.stemPackageHash == 0U &&
                (currentSongCacheKey.stemPackageHash != 0U || resources->snapshot().io.admittedLaneCount > 1U)) {
                CurrentSongCacheKey retiredKey = currentSongCacheKey;
                retiredKey.stemPackageHash = 0U;
                const auto retired = resources->retireStems(currentSongCacheKey, retiredKey);
                if (retired == NativePlaybackCacheRetirementStatus::Pending) continue;
                adoptedStemPackageIdentity = retired == NativePlaybackCacheRetirementStatus::Retired;
                if (adoptedStemPackageIdentity) {
                    currentSongCacheKey = retiredKey;
                } else {
                    // Permanent rejection is not another pending I/O receipt. An unusable
                    // Original follows the established exact-lane failure/reselection path.
                    reportLaneIngressFailure(index, 0U, state.pipeline->ingressEpoch(),
                        "cache-retire-stems", static_cast<std::int64_t>(retired));
                    bankDiagnostics[index].lanes[0U].failureGeneration.store(
                        state.pipeline->ingressEpoch(), std::memory_order_release);
                    clearCurrentSongCache();
                }
            }
            if (cacheOnBank && stemIdentityOnlyUpdate &&
                currentSongCacheKey.stemPackageHash == 0U && intent.stemPackageHash != 0U) {
                const CurrentSongCacheKey adoptedKey{
                    .selectionToken = intent.ownership.selectionToken,
                    .generation = intent.ownership.generation,
                    .songIdentityHash = intent.songIdentityHash,
                    .originalContentHash = intent.originalContentHash,
                    .stemPackageHash = intent.stemPackageHash,
                };
                const auto adoption = resources->adoptStems(currentSongCacheKey, adoptedKey);
                if (adoption == NativePlaybackResourceStatus::Pending) continue;
                adoptedStemPackageIdentity = adoption == NativePlaybackResourceStatus::Succeeded;
                if (adoptedStemPackageIdentity) {
                    currentSongCacheKey = adoptedKey;
                } else {
                    reportLaneIngressFailure(index, 0U, state.pipeline->ingressEpoch(),
                        "cache-adopt-stems", static_cast<std::int64_t>(adoption));
                    bankDiagnostics[index].lanes[0U].failureGeneration.store(
                        state.pipeline->ingressEpoch(), std::memory_order_release);
                    clearCurrentSongCache();
                }
            }
            if (cacheOnBank && !preserveSameContentCache &&
                !adoptedStemPackageIdentity && !revisionOnlyUpdate) {
                // Without an exact frame-zero producer, an incomplete prefix cannot follow a
                // decoder seek. Retire it so a sought suffix cannot invent the missing prefix.
                clearCurrentSongCache();
            }

            if (stemIdentityOnlyUpdate || revisionOnlyUpdate) {
                // Late stem discovery changes cache identity, not transport ownership. Seeking the
                // audible Original here would discard its ready ring and create an avoidable gap
                // while the independent frame-zero package builder prepares on the spare bank.
                state.appliedIntent = intent;
                if (index == renderBank) publishOwnershipDiagnostics();
                if (intent.stemLaneCount == 0U) {
                    // Original's already-pulled suffix must survive package retirement. It
                    // cannot be pulled again without losing the cache's contiguous cursor.
                    for (std::size_t lane = 1U; lane < state.lanes.size(); ++lane) {
                        state.lanes[lane]->clear();
                        state.lanes[lane]->endOfStreamMarkedGeneration = 0U;
                        bankDiagnostics[index].lanes[lane].failureGeneration.store(0U, std::memory_order_release);
                    }
                }
                bankDiagnostics[index].publishAppliedIntent(true, intent.revision);
                renderReadinessRequested.store(true, std::memory_order_release);
                progressed = true;
                continue;
            }

            if (!intent.assigned) {
                retireStemGainPreviewForBank(index, state.pipeline->ownership());
                state.pipeline->releaseRetiredLoopHeadReaders();
                state.assigned = false;
                state.appliedIntent = intent;
                if (index == renderBank) publishOwnershipDiagnostics();
                state.clearPendingLanes();
                state.pendingLateMixEndpoint = {};
                state.pendingLateMixOwnership = {};
                state.hasPendingLateMixEndpoint = false;
                bankDiagnostics[index].publishAppliedIntent(false, intent.revision);
                progressed = true;
                continue;
            }

            if (preserveSameContentCache) {
                const CurrentSongCacheKey next{intent.ownership.selectionToken, intent.ownership.generation,
                    intent.songIdentityHash, intent.originalContentHash, intent.stemPackageHash};
                if (next != currentSongCacheKey) {
                    if (!resources->rebind(currentSongCacheKey, next,
                            preservedCacheMask, !currentSongCache->complete(preservedCacheMask))) continue;
                    currentSongCacheKey = next;
                }
            }
            SongRenderResetStatus result = SongRenderResetStatus::InvalidOwnership;
            const bool seek = validOwnership(current) &&
                current.selectionToken == intent.ownership.selectionToken;
            if (seek) {
                result = state.pipeline->seek(
                    intent.ownership,
                    intent.ownership.generation,
                    intent.firstTimelineFrame,
                    intent.outputClockStartFrame,
                    intent.playbackEndFrameExclusive);
            } else {
                result = state.pipeline->beginSong(
                    intent.ownership,
                    intent.ownership.generation,
                    intent.firstTimelineFrame,
                    intent.outputClockStartFrame,
                    intent.playbackEndFrameExclusive);
            }
            if (result != SongRenderResetStatus::Ok) {
                saturatingIncrement(bankDiagnostics[index].rejectedBankIntents);
                continue;
            }

            state.assigned = true;
            state.appliedIntent = intent;
            if (index == renderBank) publishOwnershipDiagnostics();
            if (preserveSameContentCache) {
                const CurrentSongCacheKey reboundKey{
                    .selectionToken = intent.ownership.selectionToken,
                    .generation = intent.ownership.generation,
                    .songIdentityHash = intent.songIdentityHash,
                    .originalContentHash = intent.originalContentHash,
                    .stemPackageHash = intent.stemPackageHash,
                };
                currentSongCacheInstallation = CacheInstallation::Rebinding;
                if (state.pipeline->bindCurrentSongPcmCache(
                        intent.ownership,
                        currentSongCache)) {
                    currentSongCacheKey = reboundKey;
                    currentSongCacheInstallation = CacheInstallation::Installed;
                }
            }
            static_cast<void>(state.pipeline->retainOriginalTransitionHead(
                intent.ownership,
                intent.firstTimelineFrame == 0U));
            state.clearPendingLanes();
            state.pendingLateMixEndpoint = {};
            state.pendingLateMixOwnership = {};
            state.hasPendingLateMixEndpoint = false;
            bankDiagnostics[index].publishAppliedIntent(true, intent.revision);
            if (seek) {
                saturatingIncrement(bankDiagnostics[index].seekCount);
            } else {
                saturatingIncrement(bankDiagnostics[index].beginSongCount);
            }
            progressed = true;
        }
        return progressed;
    }

    [[nodiscard]] bool applyControls() noexcept {
        if (!hasDesiredSnapshot) return false;
        bool progressed = false;
        for (std::size_t index = 0; index < kNativePlaybackBankCount; ++index) {
            BankRuntimeState &state = *bankStates[index];
            if (!state.pipeline) continue;
            const bool neutralAutomaticSuccessor =
                automaticSuccessor.phase != AutomaticSuccessorPhase::Idle &&
                automaticSuccessor.successorBank == index &&
                !automaticSuccessor.inheritOutgoingControls;
            const NativePlaybackControlTargetKey targetKey{
                desiredSnapshot.controls.revision,
                neutralAutomaticSuccessor
                    ? NativePlaybackControlPolicyVariant::NeutralAutomaticSuccessor
                    : NativePlaybackControlPolicyVariant::Normal,
                state.pipeline->ownership(),
            };
            if (!state.hasTargetControlKey || state.targetControlKey != targetKey) {
                if (state.controlApplicationEpoch ==
                    std::numeric_limits<std::uint64_t>::max()) {
                    continue;
                }
                ++state.controlApplicationEpoch;
                state.targetControlKey = targetKey;
                state.targetControlApplicationEpoch = state.controlApplicationEpoch;
                state.hasTargetControlKey = true;
            }
            if (state.hasAppliedControlKey &&
                state.appliedControlKey == state.targetControlKey &&
                state.appliedControlApplicationEpoch ==
                    state.targetControlApplicationEpoch) {
                continue;
            }

            SongRenderControlSnapshot controls = desiredSnapshot.controls;
            controls.applicationEpoch = state.targetControlApplicationEpoch;
            if (neutralAutomaticSuccessor) {
                // The reserved owner starts with EngineDesiredState revision 1 when adopted.
                // Outgoing source/package edits cannot raise this owner's mailbox revision floors.
                controls.sourceIntentRevision = 1U;
                controls.stemIntentRevision = 1U;
                controls.mixer.sourceMode = MixerSourceMode::Original;
                controls.mixer.metronome.mode = MetronomeMode::Off;
                controls.tempo = 1.0F;
                controls.pitchSemitones = 0.0F;
            }
            const SongRenderControlStatus result =
                state.pipeline->submitControls(controls);
            if (result == SongRenderControlStatus::Accepted) {
                state.appliedControlKey = state.targetControlKey;
                state.appliedControlApplicationEpoch =
                    state.targetControlApplicationEpoch;
                state.hasAppliedControlKey = true;
                state.appliedControlRevision = desiredSnapshot.controls.revision;
                if (selectionAssigned && renderBank == index &&
                    sameOwnership(requestedOwnership, state.pipeline->ownership())) {
                    const SongRenderLateMixEndpoint endpoint =
                        state.pipeline->prepareLateMixEndpoint(requestedOwnership);
                    if (endpoint.valid()) {
                        state.pendingLateMixEndpoint = endpoint;
                        state.pendingLateMixOwnership = requestedOwnership;
                        state.hasPendingLateMixEndpoint = true;
                    }
                }
                progressed = true;
            }
        }
        return progressed;
    }

    [[nodiscard]] bool applyStemGainPreview() noexcept {
        if (!hasPendingStemGainPreview) return false;
        const NativePlaybackStemGainPreview &preview = pendingStemGainPreview;
        if (!hasDesiredSnapshot || !desiredSnapshot.selectionAssigned ||
            desiredSnapshot.controls.mixer.sourceMode != MixerSourceMode::Stems ||
            !sameOwnership(desiredSnapshot.selectedOwnership, preview.ownership) ||
            desiredSnapshot.controls.sourceIntentRevision != preview.sourceIntentRevision ||
            desiredSnapshot.controls.stemIntentRevision != preview.stemIntentRevision) {
            // Owner/source/package changes make the old overlay ineligible immediately. The
            // pipeline's canonical-control/reset path retires any already-applied overlay.
            hasPendingStemGainPreview = false;
            return true;
        }

        const std::size_t selected = bankIndex(desiredSnapshot.selectedBank);
        if (selected >= kNativePlaybackBankCount) {
            hasPendingStemGainPreview = false;
            return true;
        }
        if (!selectionAssigned || renderBank != selected ||
            !sameOwnership(requestedOwnership, preview.ownership)) {
            // Desired metadata can briefly lag a physically adopted automatic successor. Never
            // apply an overlay to a merely desired or prewarmed bank: only the exact selected
            // physical owner has preview authority.
            hasPendingStemGainPreview = false;
            return true;
        }
        BankRuntimeState &state = *bankStates[selected];
        if (preview.canonicalControlFloorRevision > state.appliedControlRevision) {
            // Cross-mailbox fence: do not install or retire an overlay before command C applies.
            return false;
        }
        const SongRenderStemGainPreviewStatus status = state.pipeline->submitStemGainPreview(
            preview.ownership,
            preview.publicationSequence,
            preview.coversOfferSequence,
            preview.activeMask,
            preview.gains);
        if (status == SongRenderStemGainPreviewStatus::Accepted) {
            const SongRenderLateMixEndpoint endpoint =
                state.pipeline->prepareLateMixEndpoint(preview.ownership);
            if (endpoint.valid()) {
                state.pendingLateMixEndpoint = endpoint;
                state.pendingLateMixOwnership = preview.ownership;
                state.hasPendingLateMixEndpoint = true;
            }
        }
        if (status == SongRenderStemGainPreviewStatus::InvalidOwnership) {
            const SongRenderOwnership current = state.pipeline->ownership();
            if (validOwnership(current) && current.generation < preview.ownership.generation) {
                return false;
            }
        }
        hasPendingStemGainPreview = false;
        return true;
    }

    [[nodiscard]] bool applyBeatGrids() noexcept {
        bool progressed = false;
        for (std::size_t index = 0; index < kNativePlaybackBankCount; ++index) {
            BankRuntimeState &state = *bankStates[index];
            const bool applied = beatGridMailboxes[index]->applyLatest(
                state.consumedBeatGridRevision,
                [&](const NativePlaybackBeatGridUpdate &update,
                    const std::int64_t *frames,
                    const std::uint8_t *accentFlags) noexcept {
                    if (!state.pipeline) return BeatGridApplyResult::Retry;
                    const SongRenderOwnership current = state.pipeline->ownership();
                    if (!validOwnership(current)) return BeatGridApplyResult::Retry;
                    if (!sameOwnership(current, update.ownership)) {
                        return update.ownership.generation < current.generation
                            ? BeatGridApplyResult::Discard
                            : BeatGridApplyResult::Retry;
                    }
                    if (state.pipeline->beatGridMatches(current, frames, accentFlags, update.frameCount)) {
                        return BeatGridApplyResult::Discard;
                    }
                    state.appliedBeatGridRevision = update.revision;
                    if (update.frameCount == 0U) {
                        state.pipeline->clearBeatGrid(update.ownership);
                        saturatingIncrement(bankDiagnostics[index].beatGridClears);
                        return BeatGridApplyResult::Applied;
                    }
                    const bool gridAccepted = state.pipeline->setBeatGrid(
                        update.ownership, frames, accentFlags, update.frameCount);
                    if (!gridAccepted) {
                        return BeatGridApplyResult::Discard;
                    }
                    saturatingIncrement(bankDiagnostics[index].beatGridUpdates);
                    return BeatGridApplyResult::Applied;
                });
            progressed = applied || progressed;
        }
        return progressed;
    }

    void replaySelectedPipelineStateAfterSwap() noexcept {
        if (!selectionAssigned || renderBank >= kNativePlaybackBankCount) return;
        if (managedLoopAdopted && loopCycle.retireRenderer(managedLoopIntent.revision,
                {requestedOwnership.selectionToken, requestedOwnership.generation})) {
            forceLoopPositionObservation = true;
            lastPipelineBoundarySequence = 0U;
        }
        BankRuntimeState &state = *bankStates[renderBank];
        state.pipeline->setCacheReadPreparation(false);
        if (currentSongCache && currentSongCacheBank == renderBank &&
            currentSongCacheInstallation != CacheInstallation::Building &&
            currentSongCacheKey == directCacheIdentity()) {
            // The adopted head may predate canonical installation on the outgoing pipeline.
            currentSongCacheInstallation = CacheInstallation::Rebinding;
            static_cast<void>(installCurrentSongCache(renderBank, state.pipeline->ownership()));
        }
        state.appliedControlRevision = 0U;
        state.hasTargetControlKey = false;
        state.hasAppliedControlKey = false;
        state.consumedBeatGridRevision = 0U;

        hasPendingStemGainPreview = consumedStemGainPreviewMailboxPublication != 0U;
        static_cast<void>(applyControls());
        static_cast<void>(applyStemGainPreview());
        static_cast<void>(applyBeatGrids());
    }

    [[nodiscard]] bool reconcileSelection() noexcept {
        if (!hasDesiredSnapshot) return false;
        const bool desiredOwnsDirectCutover = desiredSnapshot.selectionAssigned &&
            directLoopCutover.policy.selectionToken ==
                desiredSnapshot.selectedOwnership.selectionToken &&
            directLoopCutover.policy.generation ==
                desiredSnapshot.selectedOwnership.generation;
        if ((directLoopCutover.policy.revision != 0U) && !desiredOwnsDirectCutover) {
            const std::uint64_t retiredRevision = directLoopCutover.policy.revision;
            if (!cancelDirectLoopStage()) return false;
            // The command already names a different exact owner. Do not let the later loop phase
            // reopen this just-cancelled old-owner revision before reconcileBanks can apply the
            // seek; the controller republishes retained A/B with a new revision after cache proof.
            directLoopState.retiredRevision = retiredRevision;
        }
        if ((automaticSuccessor.phase ==
                 AutomaticSuccessorPhase::PresentedAwaitingSelection ||
             automaticSuccessor.phase ==
                 AutomaticSuccessorPhase::AppliedPausedAwaitingSelection) &&
            desiredSnapshot.selectionAssigned &&
            bankIndex(desiredSnapshot.selectedBank) == automaticSuccessor.outgoingBank &&
            sameOwnership(
                desiredSnapshot.selectedOwnership,
                automaticSuccessor.outgoingOwnership)) {
            // The callback has already adopted the newer physical role. The old generation cannot
            // be restored, so wait for the controller to install the exact reserved successor (or
            // publish a genuinely newer explicit selection/unload epoch).
            return false;
        }
        if (automaticSuccessor.cancellationDesired) return false;
        if (!desiredSnapshot.selectionAssigned) {
            if (desiredSnapshot.outputGeneration <= appliedOutputGeneration) return false;
            // Deselect/unload is an explicit transport epoch, not merely a UI metadata change.
            // Pause before invalidating the ring so task removal can never leak a ghost song.
            static_cast<void>(output.setPlaying(output.handle, false));
            outputPlaying = false;
            const NativeAudioFlushStatus flushStatus =
                output.flush(output.handle, desiredSnapshot.outputGeneration);
            if (flushStatus != NativeAudioFlushStatus::Accepted) {
                if (flushStatus ==
                    NativeAudioFlushStatus::RetryAfterCallbackAcknowledgement) {
                    // A prior role publication can still be owned by the device callback for one
                    // bounded interval. This is the same expected retry contract used by normal
                    // handoff begin/commit and must not poison the native-fault baseline.
                    saturatingIncrement(outputFlushRetries);
                } else if (failedStopGeneration != desiredSnapshot.outputGeneration) {
                    failedStopGeneration = desiredSnapshot.outputGeneration;
                    saturatingIncrement(outputFlushFailures);
                    saturatingIncrement(gRuntimeOutputFlushFailureCount);
                }
                return false;
            }
            failedStopGeneration = 0;
            appliedOutputGeneration = desiredSnapshot.outputGeneration;
            preparingManualHandoffGeneration = 0U;
            saturatingIncrement(outputFlushes);
            retireStemGainPreviewForBank(renderBank, requestedOwnership);
            selectionAssigned = false;
            handoffStageOpen = false;
            handoffCommitPublished = false;
            pendingPresentationCount = 0;
            pendingOutput.clear();
            requestedOwnership = {};
            acceptedOwnership = {};
            committedOwnership = {};
            acceptedFirstFingerprintSequence = 0;
            directLoopState.replaceOwner();
            pipelineEndObserved = false;
            pipelineEndOwnership = {};
            audibleEndPublishedForOwnership = {};
            forceLoopPositionObservation = true;
            lastPipelineBoundarySequence = 0U;
            publishOwnershipDiagnostics();
            publishPresentationDiagnostics({});
            publishPositionDiagnostics({});
            return true;
        }

        const std::size_t desiredBank = bankIndex(desiredSnapshot.selectedBank);
        if (desiredBank >= kNativePlaybackBankCount) return false;
        BankRuntimeState &bank = *bankStates[desiredBank];
        if (!bank.assigned ||
            !sameOwnership(bank.pipeline->ownership(), desiredSnapshot.selectedOwnership)) {
            return false;
        }
        if (selectionAssigned && renderBank == desiredBank &&
            sameOwnership(requestedOwnership, desiredSnapshot.selectedOwnership)) {
            return bindSelectedRetainedHead();
        }

        const SongRenderOwnership nextOwnership = desiredSnapshot.selectedOwnership;
        if (preparingManualHandoffGeneration != nextOwnership.generation) {
            if (!beginSpareOutputRole(SpareOutputTransition::Manual, nextOwnership.generation)) {
                saturatingIncrement(outputHandoffBeginRetries);
                return false;
            }
            preparingManualHandoffGeneration = nextOwnership.generation;
            saturatingIncrement(outputHandoffBegins);
        }
        // Transfer the candidate's already-drained prefix before frame-zero installation can replace its cache.
        const bool sharesOutgoingCache = currentSongCache != nullptr &&
            bank.appliedIntent.firstTimelineFrame == 0U &&
            currentSongCacheBank != desiredBank && !currentSongCacheBuildFailed &&
            currentSongCacheKey.selectionToken == nextOwnership.selectionToken &&
            currentSongCacheKey.songIdentityHash == bank.appliedIntent.songIdentityHash &&
            currentSongCacheKey.originalContentHash == bank.appliedIntent.originalContentHash &&
            currentSongCacheKey.stemPackageHash == bank.appliedIntent.stemPackageHash;
        if (sharesOutgoingCache && !promoteCurrentSongCacheToOwner(desiredBank, nextOwnership)) return false;
        static_cast<void>(installCurrentSongCache(desiredBank, nextOwnership));

        if (selectionAssigned) {
            retireStemGainPreviewForBank(renderBank, requestedOwnership);
        }
        pendingOutput.clear();
        requestedOwnership = nextOwnership;
        directLoopState.replaceOwner();
        acceptedOwnership = {};
        acceptedFirstFingerprintSequence = 0;
        pipelineEndObserved = false;
        pipelineEndOwnership = {};
        audibleEndPublishedForOwnership = {};
        pendingEvidenceBaseline = lastPresentationEvidence.sequence;
        appliedOutputGeneration = nextOwnership.generation;
        renderBank = desiredBank;
        publishOwnershipDiagnostics();
        static_cast<void>(bindSelectedRetainedHead());
        forceLoopPositionObservation = true;
        lastPipelineBoundarySequence = 0U;
        selectionAssigned = true;
        handoffStageOpen = true;
        handoffCommitPublished = false;
        preparingManualHandoffGeneration = 0U;


        return true;
    }

    [[nodiscard]] bool bindSelectedRetainedHead() noexcept {
        if (renderBank >= kNativePlaybackBankCount) return false;
        auto &bank = *bankStates[renderBank];
        if (!bank.assigned || !sameOwnership(bank.appliedIntent.ownership, requestedOwnership) ||
            !sameOwnership(bank.pipeline->ownership(), requestedOwnership)) return false;
        const auto key = directCacheIdentity();
        auto head = preparedLoopHeadForOwner(key);
        if (!head.valid()) return false;
        const auto &source = head.snapshot().key;
        if (!source.matchesSource(key.songIdentityHash, key.originalContentHash, key.stemPackageHash,
                head.snapshot().completedLaneMask)) return false;
        // Preparation may commit after this owner was selected; attach raw PCM without restarting its DSP.
        return bank.pipeline->bindRetainedLoopHead(requestedOwnership, std::move(head));
    }

    [[nodiscard]] bool directLoopPolicyOwnsSelectedOwner() const noexcept {
        if (!selectionAssigned || !validOwnership(requestedOwnership)) return false;
        const std::uint64_t revision =
            directAtomicPolicyGenerationRevision.load(std::memory_order_acquire);
        return revision != 0U && revision != directLoopState.retiredRevision &&
            directAtomicPolicyGeneration.load(std::memory_order_relaxed) ==
                requestedOwnership.generation &&
            directPolicyOwnerToken.load(std::memory_order_relaxed) ==
                requestedOwnership.selectionToken;
    }

    [[nodiscard]] std::uint64_t selectedOwnerDirectLoopPolicyRevision() const noexcept {
        if (!selectionAssigned || !validOwnership(requestedOwnership)) return 0U;
        const std::uint64_t revision =
            directAtomicPolicyGenerationRevision.load(std::memory_order_acquire);
        return revision != 0U &&
                directAtomicPolicyGeneration.load(std::memory_order_relaxed) ==
                    requestedOwnership.generation &&
                directPolicyOwnerToken.load(std::memory_order_relaxed) ==
                    requestedOwnership.selectionToken
            ? revision
            : 0U;
    }

    [[nodiscard]] bool directLoopDemandEnabledForSelectedOwner() const noexcept {
        if (!directLoopPolicyOwnsSelectedOwner()) return false;
        AtomicLoopPolicySnapshot latest{};
        if (!atomicLoopPolicy.readLatest(latest) || !latest.enabled ||
            latest.revision != selectedOwnerDirectLoopPolicyRevision() ||
            latest.selectionToken != requestedOwnership.selectionToken) {
            return false;
        }
        const NativeAudioPresentedPosition presented = output.presentedPosition(output.handle);
        return latest.boundaryArmedAtPublication || !presented.valid() ||
            presented.selectionToken != requestedOwnership.selectionToken ||
            presented.generation != requestedOwnership.generation ||
            presented.lastTimelineFrame < 0 ||
            static_cast<std::uint64_t>(presented.lastTimelineFrame) <
                latest.bSourceFrameExclusive;
    }

    enum class SpareOutputTransition { Manual, Loop, Automatic };

    [[nodiscard]] SpareOutputTransition spareOutputPriority() const noexcept {
        if (!selectionAssigned || !selectionMatchesDesired() || handoffStageOpen)
            return SpareOutputTransition::Manual;
        // Clear keeps priority until the callback acknowledges the physical linear replacement.
        if (directLoopState.engaged || directLoopCutover.stageOpen() ||
            directLoopCutover.commitPublished() || directLoopDemandEnabledForSelectedOwner())
            return SpareOutputTransition::Loop;
        return SpareOutputTransition::Automatic;
    }

    [[nodiscard]] bool beginSpareOutputRole(SpareOutputTransition transition,
        std::uint64_t generation, std::uint64_t revision = 0U) noexcept {
        if (spareOutputPriority() != transition ||
            automaticSuccessor.phase != AutomaticSuccessorPhase::Idle) return false;
        switch (transition) {
            case SpareOutputTransition::Manual:
                if (directLoopCutover.stageOpen() || directLoopCutover.commitPublished()) return false;
                return output.beginHandoff(output.handle, generation);
            case SpareOutputTransition::Loop:
                return output.beginLoopCutover(output.handle, generation, revision);
            case SpareOutputTransition::Automatic:
                return output.beginDrainHandoff(output.handle,
                    requestedOwnership.generation, generation, revision);
        }
        return false;
    }

    [[nodiscard]] bool automaticIntentMatchesPhysicalPlan() const noexcept {
        if (!hasDesiredSnapshot || !desiredSnapshot.automaticSuccessor.enabled ||
            !desiredSnapshot.selectionAssigned || !desiredSnapshot.playing ||
            spareOutputPriority() != SpareOutputTransition::Automatic) {
            return false;
        }
        const NativePlaybackAutomaticSuccessorIntent &intent =
            desiredSnapshot.automaticSuccessor;
        return bankIndex(desiredSnapshot.selectedBank) == automaticSuccessor.outgoingBank &&
            bankIndex(intent.successorBank) == automaticSuccessor.successorBank &&
            sameOwnership(intent.outgoingOwnership, automaticSuccessor.outgoingOwnership) &&
            sameOwnership(intent.successorOwnership, automaticSuccessor.successorOwnership) &&
            intent.inheritOutgoingControls ==
                automaticSuccessor.inheritOutgoingControls;
    }

    [[nodiscard]] bool desiredSuccessorEndedEmpty() const noexcept {
        const auto &intent = desiredSnapshot.automaticSuccessor;
        return emptySuccessorIntent.enabled && intent.revision == emptySuccessorIntent.revision &&
            intent.successorBank == emptySuccessorIntent.successorBank &&
            sameOwnership(intent.outgoingOwnership, emptySuccessorIntent.outgoingOwnership) &&
            sameOwnership(intent.successorOwnership, emptySuccessorIntent.successorOwnership);
    }

    [[nodiscard]] bool canBeginAutomaticSuccessorPlan() const noexcept {
        if (!hasDesiredSnapshot || !desiredSnapshot.automaticSuccessor.enabled ||
            desiredSuccessorEndedEmpty() ||
            !desiredSnapshot.selectionAssigned || !desiredSnapshot.playing ||
            spareOutputPriority() != SpareOutputTransition::Automatic ||
            !output.supportsDrainHandoff() ||
            !selectionAssigned || !selectionMatchesDesired() || handoffStageOpen ||
            !sameOwnership(committedOwnership, requestedOwnership) ||
            !sameOwnership(acceptedOwnership, requestedOwnership)) {
            return false;
        }
        const NativePlaybackAutomaticSuccessorIntent &intent =
            desiredSnapshot.automaticSuccessor;
        const std::size_t successorBank = bankIndex(intent.successorBank);
        if (successorBank >= kNativePlaybackBankCount || successorBank == renderBank) return false;
        const BankRuntimeState &state = *bankStates[successorBank];
        return state.assigned &&
            sameOwnership(state.pipeline->ownership(), intent.successorOwnership) &&
            state.appliedIntent.outputClockStartFrame == 0;
    }

    [[nodiscard]] bool reconcileAutomaticSuccessorPlan() noexcept {
        if (automaticSuccessor.phase ==
                AutomaticSuccessorPhase::PresentedAwaitingSelection ||
            automaticSuccessor.phase ==
                AutomaticSuccessorPhase::AppliedPausedAwaitingSelection) {
            if (hasDesiredSnapshot && desiredSnapshot.selectionAssigned &&
                bankIndex(desiredSnapshot.selectedBank) == automaticSuccessor.successorBank &&
                sameOwnership(
                    desiredSnapshot.selectedOwnership,
                    automaticSuccessor.successorOwnership)) {
                clearAutomaticSuccessorPlan();
                return true;
            }
            if (automaticIntentMatchesPhysicalPlan()) {
                automaticSuccessor.revision =
                    desiredSnapshot.automaticSuccessor.revision;
                publishAutomaticSuccessorDiagnostics();
                return false;
            }
            const bool explicitNewerEpoch = hasDesiredSnapshot &&
                ((!desiredSnapshot.selectionAssigned &&
                  desiredSnapshot.outputGeneration >
                      automaticSuccessor.successorOwnership.generation) ||
                 (desiredSnapshot.selectionAssigned &&
                  desiredSnapshot.selectedOwnership.generation >
                      automaticSuccessor.successorOwnership.generation &&
                  !sameOwnership(
                      desiredSnapshot.selectedOwnership,
                      automaticSuccessor.outgoingOwnership)));
            if (explicitNewerEpoch) {
                clearAutomaticSuccessorPlan();
                return true;
            }
            // Pause/policy changes can race the callback after it has irrevocably adopted the
            // successor. Preserve the exact physical owner until the controller serializes that
            // reserved owner; never retry the now-stale outgoing generation.
            return false;
        }

        if (automaticSuccessor.phase != AutomaticSuccessorPhase::Idle) {
            if (automaticIntentMatchesPhysicalPlan()) {
                const std::uint64_t nextRevision =
                    desiredSnapshot.automaticSuccessor.revision;
                if (automaticSuccessor.revision == nextRevision) return false;
                automaticSuccessor.revision = nextRevision;
                publishAutomaticSuccessorDiagnostics();
                return true;
            }
            lastAutomaticDrainCancellationStatus = output.cancelDrainHandoff(
                output.handle,
                automaticSuccessor.outgoingOwnership.generation,
                automaticSuccessor.successorOwnership.generation,
                automaticSuccessor.transitionRevision);
            switch (lastAutomaticDrainCancellationStatus) {
                case NativeAudioDrainCancelStatus::CancelledOpenStage:
                case NativeAudioDrainCancelStatus::OutgoingActive:
                    clearAutomaticSuccessorPlan();
                    return true;
                case NativeAudioDrainCancelStatus::CancellationRequested:
                    // A committed drain publication is still owned by the callback until it
                    // acknowledges the exact cancellation sequence. Keep the physical plan and
                    // block selection/unload reconciliation until that acknowledgement is
                    // observable as OutgoingActive. Clearing here lets task removal attempt an
                    // immediate hard flush against the still-published role and misclassify the
                    // coordinator's expected AwaitingAcknowledgement retry as an output failure.
                    automaticSuccessor.cancellationDesired = true;
                    publishAutomaticSuccessorDiagnostics();
                    return false;
                case NativeAudioDrainCancelStatus::SuccessorActive:
                    installAppliedAutomaticSuccessorWithoutPresentation();
                    return true;
                case NativeAudioDrainCancelStatus::AdoptionInFlight:
                case NativeAudioDrainCancelStatus::NoMatchingDrain:
                case NativeAudioDrainCancelStatus::NotRequested:
                    automaticSuccessor.cancellationDesired = true;
                    publishAutomaticSuccessorDiagnostics();
                    return false;
            }
        }

        if (!canBeginAutomaticSuccessorPlan()) return false;
        const NativePlaybackAutomaticSuccessorIntent &intent =
            desiredSnapshot.automaticSuccessor;
        if (!beginSpareOutputRole(SpareOutputTransition::Automatic,
                intent.successorOwnership.generation,
                intent.revision)) {
            return false;
        }
        automaticSuccessorPendingOutput.clear();
        automaticSuccessor.phase = AutomaticSuccessorPhase::Staging;
        automaticSuccessor.revision = intent.revision;
        automaticSuccessor.transitionRevision = intent.revision;
        automaticSuccessor.outgoingBank = renderBank;
        automaticSuccessor.successorBank = bankIndex(intent.successorBank);
        automaticSuccessor.outgoingOwnership = intent.outgoingOwnership;
        automaticSuccessor.successorOwnership = intent.successorOwnership;
        automaticSuccessor.inheritOutgoingControls = intent.inheritOutgoingControls;
        automaticSuccessor.evidenceBaseline = lastPresentationEvidence.sequence;
        lastAutomaticDrainCancellationStatus = NativeAudioDrainCancelStatus::NotRequested;
        publishAutomaticSuccessorDiagnostics();
        return true;
    }

    [[nodiscard]] std::uint64_t expectedLaneIngressGeneration(
        std::size_t bank, std::size_t lane, const LaneIngress &ingress) const noexcept {
        const SongRenderPipeline &pipeline = *bankStates[bank]->pipeline;
        // Repeat advances the render ingress epoch without changing the song owner. Newly
        // attached stems still build that owner's cache from zero; they never enter the live
        // reservoir under this exception. Require the exact bound cache and decoder generation.
        if (lane != 0U && currentSongCache != nullptr && currentSongCacheBank == bank &&
            !currentSongCacheBuildFailed &&
            currentSongCacheKey.selectionToken == pipeline.ownership().selectionToken &&
            currentSongCacheKey.generation == pipeline.ownership().generation &&
            ingress.activeGeneration() == currentSongCacheKey.generation &&
            !currentSongCache->complete(1U << static_cast<std::uint32_t>(lane))) {
            return currentSongCacheKey.generation;
        }
        return pipeline.ingressEpoch();
    }

    [[nodiscard]] bool abandonCurrentSongCacheBuild() noexcept {
        if (currentSongCacheBuildFailed) return true;
        if (currentSongCache == nullptr || currentSongCacheBank >= bankStates.size()) return false;
        auto &pipeline = *bankStates[currentSongCacheBank]->pipeline;
        // Any lane can fail the shared store. Original must leave that store at its published
        // boundary before ordinary ingress resumes; the unpublished tail is replayed once below.
        if (!pipeline.beginCurrentSongPcmCacheFallback(pipeline.ownership(),
                currentSongCache->availableFrames(0U))) return false;
        currentSongCacheBuildFailed = true;
        return true;
    }

    [[nodiscard]] bool replayFailedCacheOriginal(
        std::size_t bank, std::size_t lane, bool &progressed) noexcept {
        if (lane != 0U || !currentSongCacheBuildFailed || currentSongCache == nullptr ||
            bank != currentSongCacheBank) return true;
        auto &pipeline = *bankStates[bank]->pipeline;
        if (pipeline.ownership().selectionToken != currentSongCacheKey.selectionToken ||
            pipeline.ownership().generation != currentSongCacheKey.generation) return true;
        const auto replay = currentSongCache->unpublishedOriginal(currentSongCacheKey);
        if (replay.frameCount == 0U) return true;
        const auto next = pipeline.laneDiagnostics(SongRenderLane::Original).endTimelineFrameExclusive;
        const auto end = replay.firstFrame + replay.frameCount;
        if (next >= end) return true;
        if (next < replay.firstFrame) return false;
        const auto offset = static_cast<std::size_t>(next - replay.firstFrame);
        const auto appended = pipeline.appendLanePcm(SongRenderLane::Original, pipeline.ingressEpoch(),
            next, replay.samples + offset * 2U, replay.frameCount - offset);
        progressed = appended.inputFramesAccepted != 0U;
        return appended.inputFramesAccepted == replay.frameCount - offset;
    }

    [[nodiscard]] SongRenderPipeline *preparedBoundedLaneIngress(
        std::size_t bank, std::size_t lane, std::uint64_t generation) const noexcept {
        if (bank != renderBank || directLoopCutover.policy.revision == 0U || !loopContinuationPipeline ||
            directLoopCutover.policy.generation != generation ||
            directLoopCutover.policy.selectionToken != bankStates[bank]->pipeline->ownership().selectionToken ||
            !sameOwnership(loopContinuationPipeline->ownership(), bankStates[bank]->pipeline->ownership()) ||
            loopContinuationPipeline->ingressEpoch() != generation ||
            loopContinuationPipeline->boundedLoopHeadLaneMask() == 0U ||
            lane > loopContinuationPipeline->stemLaneCount()) return nullptr;
        const auto &policy = directLoopCutover.policy;
        if (policy.enabled && !policy.boundaryArmedAtPublication && !directLoopState.engaged) {
            const auto presented = output.presentedPosition(output.handle);
            if (bankStates[bank]->appliedIntent.firstTimelineFrame >= policy.bTimelineFrameExclusive ||
                (presented.valid() && presented.selectionToken == policy.selectionToken &&
                 presented.generation == generation && presented.lastTimelineFrame >= 0 &&
                 static_cast<std::uint64_t>(presented.lastTimelineFrame) >= policy.bTimelineFrameExclusive))
                return nullptr;
        }
        return loopContinuationPipeline.get();
    }

    [[nodiscard]] static bool sameLoopIngressRequest(
        const LoopCycleIngressRequest &first, const LoopCycleIngressRequest &second) noexcept {
        return first.valid && second.valid && first.sequence == second.sequence &&
            first.loopRevision == second.loopRevision && first.targetCycle == second.targetCycle &&
            first.owner == second.owner && first.ingressEpoch == second.ingressEpoch &&
            first.targetTimelineFrame == second.targetTimelineFrame &&
            first.requiredEndTimelineFrameExclusive == second.requiredEndTimelineFrameExclusive &&
            first.requiredLaneMask == second.requiredLaneMask;
    }

    [[nodiscard]] bool loopIngressRequestOwnsLane(std::size_t bank, std::size_t lane,
        const LoopCycleIngressRequest &request) const noexcept {
        if (!request.valid || !selectionAssigned || bank != renderBank || !managedLoopAdopted ||
            !managedLoopIntent.enabled || directLoopPolicyOwnsSelectedOwner() ||
            request.loopRevision != managedLoopIntent.revision || request.owner != managedLoopIntent.owner ||
            request.owner.selectionToken != requestedOwnership.selectionToken ||
            request.owner.renderGeneration != requestedOwnership.generation ||
            !sameOwnership(bankStates[bank]->pipeline->ownership(), requestedOwnership) ||
            (request.requiredLaneMask & (1U << static_cast<std::uint32_t>(lane))) == 0U) return false;
        const auto state = loopCycle.snapshot();
        return state.mode == LoopCycleMode::Streamed && state.latestRevision == request.loopRevision &&
            state.owner == request.owner;
    }

    [[nodiscard]] LoopCycleIngressRequest futureLoopIngressRequest(
        std::size_t bank, std::size_t lane, const LaneIngress &ingress) const noexcept {
        const auto request = loopCycle.ingressRequest();
        if (!loopIngressRequestOwnsLane(bank, lane, request) ||
            request.ingressEpoch <= bankStates[bank]->pipeline->ingressEpoch() ||
            ingress.activeGeneration() != request.ingressEpoch) return {};
        return request;
    }

    [[nodiscard]] bool retainFutureLoopPending(std::size_t bank, std::size_t lane,
        const PendingLanePcm &pending) const noexcept {
        const auto &request = pending.futureLoopRequest;
        if (pending.generation != request.ingressEpoch || !loopIngressRequestOwnsLane(bank, lane, request)) return false;
        if (sameLoopIngressRequest(request, loopCycle.ingressRequest())) return true;
        // An accepted ACK clears the request before the body epoch's directive is installed.
        // The copied binding keeps its real PCM parked across that exact prepared interval.
        const auto state = loopCycle.snapshot();
        return state.preparedIngressEpoch == request.ingressEpoch;
    }

    [[nodiscard]] bool appendPendingLane(
        std::size_t bank,
        std::size_t laneIndexValue,
        LaneIngress &ingress,
        PendingLanePcm &pending) noexcept {
        bool replayProgressed = false;
        if (!replayFailedCacheOriginal(bank, laneIndexValue, replayProgressed)) return replayProgressed;
        if (pending.remainingFrames == 0U) return replayProgressed;
        AtomicLaneDiagnostics &diagnostic = bankDiagnostics[bank].lanes[laneIndexValue];
        const auto future = futureLoopIngressRequest(bank, laneIndexValue, ingress);
        const bool retainedCurrentSuffix = future.valid &&
            pending.generation == bankStates[bank]->pipeline->ingressEpoch();
        if ((!retainedCurrentSuffix && ingress.activeGeneration() != pending.generation) ||
            expectedLaneIngressGeneration(bank, laneIndexValue, ingress) != pending.generation) {
            pending.clear();
            diagnostic.retainedSuffixFrames.store(0U, std::memory_order_relaxed);
            saturatingIncrement(diagnostic.staleAfterPull);
            return true;
        }

        const bool cacheOwned = cacheOwnsLaneIngress(
            bank, laneIndexValue, pending.generation, pending.firstTimelineFrame);
        if (cacheOwned) {
            // Optional lanes cannot publish ahead of Original. Keep their exact suffix
            // parked instead of occupying the shared I/O slot with a known-unready range.
            // Once Original is complete, the cache must still consume/clip longer stems.
            if (laneIndexValue != 0U &&
                !currentSongCache->complete(CurrentSongPcmCache::kOriginalLaneMask) &&
                pending.firstTimelineFrame >= currentSongCache->availableFrames(0U)) return false;
            const CurrentSongCacheOfferResult offered = resources->offer(
                currentSongCacheKey,
                laneIndexValue,
                pending.firstTimelineFrame,
                pending.samples.get() + pending.offsetFrames * 2U,
                pending.remainingFrames);
            if (offered.status == CurrentSongCacheOfferStatus::Pending ||
                offered.status == CurrentSongCacheOfferStatus::OriginalRangePending) return false;
            if (offered.status == CurrentSongCacheOfferStatus::AllocationFailed) {
                const std::size_t accepted = std::min(
                    offered.inputFramesConsumed,
                    pending.remainingFrames);
                pending.offsetFrames += accepted;
                pending.remainingFrames -= accepted;
                pending.firstTimelineFrame += accepted;
                saturatingAdd(diagnostic.appendedFrames, accepted);
                diagnostic.retainedSuffixFrames.store(
                    pending.remainingFrames,
                    std::memory_order_relaxed);
                if (!abandonCurrentSongCacheBuild()) return false;
                if (!replayFailedCacheOriginal(bank, laneIndexValue, replayProgressed))
                    return accepted != 0U || replayProgressed;
                if (pending.remainingFrames == 0U) return accepted != 0U || replayProgressed;
            } else {
            if (offered.status == CurrentSongCacheOfferStatus::GapRejected &&
                (bank == currentSongCacheBank || matchingFrameZeroBuilderForCurrentCache(bank))) {
                const auto retained = bankStates[bank]->pipeline->laneDiagnostics(renderLane(laneIndexValue));
                if (retained.firstTimelineFrame <= offered.nextExpectedFrame &&
                    retained.endTimelineFrameExclusive >= pending.firstTimelineFrame) {
                    // The exact earlier prefix is retained and being copied in bounded chunks.
                    // Keep this newer suffix parked until its contiguous cache cursor catches up.
                    return false;
                }
            }
            if (offered.status != CurrentSongCacheOfferStatus::Accepted &&
                offered.status != CurrentSongCacheOfferStatus::LaneComplete) {
                reportLaneIngressFailure(bank, laneIndexValue, stateIngressGeneration(bank),
                    "cache-offer", static_cast<std::int64_t>(offered.status));
                pending.clear();
                diagnostic.failureGeneration.store(stateIngressGeneration(bank), std::memory_order_release);
                saturatingIncrement(diagnostic.discontinuityRejects);
                return true;
            }
            const std::size_t accepted = std::min(
                offered.inputFramesConsumed,
                pending.remainingFrames);
            pending.offsetFrames += accepted;
            pending.remainingFrames -= accepted;
            pending.firstTimelineFrame += accepted;
            saturatingAdd(diagnostic.appendedFrames, accepted);
            diagnostic.retainedSuffixFrames.store(
                pending.remainingFrames,
                std::memory_order_relaxed);
            if (laneIndexValue == 0U) {
                const TimelineStereoBufferDiagnostics retained =
                    bankStates[bank]->pipeline->laneDiagnostics(SongRenderLane::Original);
                if (currentSongCache->availableFrames(0U) >=
                    retained.endTimelineFrameExclusive) {
                    static_cast<void>(
                        bankStates[bank]->pipeline->retainOriginalTransitionHead(
                            bankStates[bank]->pipeline->ownership(),
                            false));
                }
            }
            return accepted != 0U;
            }
        }

        auto *continuation = preparedBoundedLaneIngress(bank, laneIndexValue, pending.generation);
        const bool streamedLatePart = laneIndexValue != 0U && currentSongCache != nullptr &&
            currentSongCacheBank == bank && !currentSongCacheBuildFailed &&
            currentSongCache->complete(CurrentSongPcmCache::kOriginalLaneMask) &&
            currentSongCacheStorageStatus != CurrentSongCacheStorageStatus::Admitted &&
            laneIndexValue >= resources->snapshot().io.admittedLaneCount;
        if (streamedLatePart) bankStates[bank]->pipeline->discardConsumedPcm();
        std::size_t appendFrames = pending.remainingFrames;
        if (continuation) {
            const auto retained = continuation->laneDiagnostics(renderLane(laneIndexValue));
            const auto overlap = !(laneIndexValue != 0U && retained.storedFrames == 0U) &&
                pending.firstTimelineFrame < retained.endTimelineFrameExclusive
                ? std::min<std::uint64_t>(appendFrames, retained.endTimelineFrameExclusive - pending.firstTimelineFrame)
                : 0U;
            appendFrames = std::min<std::size_t>(appendFrames,
                retained.capacityFrames - retained.storedFrames + static_cast<std::size_t>(overlap));
            if (appendFrames == 0U) return false;
        }
        const TimelineAppendResult result = bankStates[bank]->pipeline->appendLanePcm(
            renderLane(laneIndexValue),
            pending.generation,
            pending.firstTimelineFrame,
            pending.samples.get() + pending.offsetFrames * 2U,
            appendFrames);
        if (streamedLatePart) bankStates[bank]->pipeline->discardConsumedPcm();

        if (result.status == TimelineAppendStatus::StaleGeneration) {
            pending.clear();
            diagnostic.retainedSuffixFrames.store(0U, std::memory_order_relaxed);
            saturatingIncrement(diagnostic.staleAfterPull);
            return true;
        }
        if (result.status == TimelineAppendStatus::GapRejected ||
            result.status == TimelineAppendStatus::InvalidArgument) {
            reportLaneIngressFailure(bank, laneIndexValue, stateIngressGeneration(bank),
                "reservoir-append", static_cast<std::int64_t>(result.status));
            pending.clear();
            diagnostic.retainedSuffixFrames.store(0U, std::memory_order_relaxed);
            diagnostic.failureGeneration.store(stateIngressGeneration(bank), std::memory_order_release);
            saturatingIncrement(diagnostic.discontinuityRejects);
            return true;
        }

        const std::size_t accepted = std::min(
            result.inputFramesAccepted,
            pending.remainingFrames);
        // The callback may adopt this bounded producer before another decoder pull. Keep every
        // accepted ordinary suffix in both existing reservoirs until that exact role retires.
        if (continuation && accepted != 0U) {
            const auto mirrored = continuation->appendLanePcm(renderLane(laneIndexValue),
                pending.generation, pending.firstTimelineFrame,
                pending.samples.get() + pending.offsetFrames * 2U, accepted);
            if (mirrored.status != TimelineAppendStatus::Accepted || mirrored.inputFramesAccepted != accepted) {
                reportLaneIngressFailure(bank, laneIndexValue, stateIngressGeneration(bank),
                    "continuation-mirror", static_cast<std::int64_t>(mirrored.status));
                pending.clear();
                diagnostic.retainedSuffixFrames.store(0U, std::memory_order_relaxed);
                diagnostic.failureGeneration.store(stateIngressGeneration(bank), std::memory_order_release);
                saturatingIncrement(diagnostic.discontinuityRejects);
                return true;
            }
        }
        pending.offsetFrames += accepted;
        pending.remainingFrames -= accepted;
        pending.firstTimelineFrame += accepted;
        saturatingAdd(diagnostic.appendedFrames, accepted);
        diagnostic.retainedSuffixFrames.store(
            pending.remainingFrames,
            std::memory_order_relaxed);
        if (pending.remainingFrames != 0U) {
            saturatingIncrement(diagnostic.partialAppends);
        }
        if (accepted == 0U) return false;
        return true;
    }

    [[nodiscard]] bool markPendingLaneEndOfStream(
        std::size_t bank,
        std::size_t laneIndexValue,
        PendingLanePcm &pending) noexcept {
        if (!pending.endOfStreamPending || pending.remainingFrames != 0U) return false;
        bool replayProgressed = false;
        if (!replayFailedCacheOriginal(bank, laneIndexValue, replayProgressed)) return replayProgressed;
        AtomicLaneDiagnostics &diagnostic = bankDiagnostics[bank].lanes[laneIndexValue];
        const bool cacheOwned = cacheOwnsLaneIngress(
            bank, laneIndexValue, pending.generation, pending.firstTimelineFrame);
        // A completed exact Original cache already owns its immutable render endpoint. Later
        // sought decoder receipts cannot replace it; their raw endpoint still goes to sealing.
        const auto renderEnd = cacheOwned && laneIndexValue == 0U &&
            currentSongCache->complete(CurrentSongPcmCache::kOriginalLaneMask)
            ? currentSongCache->snapshot().originalEndFrameExclusive : pending.endTimelineFrameExclusive;
        if (cacheOwned && laneIndexValue == 0U) {
            // Before the first seal, decoder EOS establishes the render limit before asynchronous
            // completion can expose the final cache interval to fillOutput.
            const auto marked = bankStates[bank]->pipeline->markLaneEndOfStream(
                SongRenderLane::Original, pending.generation, renderEnd);
            if (marked != SongRenderEosStatus::Accepted) {
                reportLaneIngressFailure(bank, laneIndexValue, stateIngressGeneration(bank),
                    "cache-original-eos", static_cast<std::int64_t>(marked));
                diagnostic.failureGeneration.store(stateIngressGeneration(bank), std::memory_order_release);
                saturatingIncrement(diagnostic.endOfStreamFailures);
                return false;
            }
            if (matchingFrameZeroBuilderForCurrentCache(bank)) {
                auto &consumer = *bankStates[currentSongCacheBank];
                if (consumer.pipeline->markLaneEndOfStream(SongRenderLane::Original,
                        consumer.pipeline->ingressEpoch(), renderEnd) != SongRenderEosStatus::Accepted)
                    return false;
            }
        }
        if (cacheOwned) {
            const CurrentSongCacheSealStatus sealed = resources->seal(
                currentSongCacheKey,
                laneIndexValue,
                pending.endTimelineFrameExclusive);
            if (sealed == CurrentSongCacheSealStatus::Pending) return false;
            if (sealed == CurrentSongCacheSealStatus::OriginalPending) {
                // The observed short-stem end already permits silence inside published Original.
                static_cast<void>(bankStates[bank]->pipeline->markLaneEndOfStream(
                    renderLane(laneIndexValue), pending.generation, renderEnd));
                return false;
            }
            if (sealed == CurrentSongCacheSealStatus::PaddingPending) {
                // Keep this exact EOS pending. Return to the service plan's output refill before
                // padding another chunk; a short stem must not monopolize the render worker.
                return true;
            }
            if (sealed == CurrentSongCacheSealStatus::AllocationFailed) {
                if (!abandonCurrentSongCacheBuild()) return false;
                if (!replayFailedCacheOriginal(bank, laneIndexValue, replayProgressed)) return replayProgressed;
            } else if (sealed != CurrentSongCacheSealStatus::Accepted &&
                sealed != CurrentSongCacheSealStatus::AlreadyComplete) {
                reportLaneIngressFailure(bank, laneIndexValue, stateIngressGeneration(bank),
                    "cache-seal", static_cast<std::int64_t>(sealed));
                diagnostic.failureGeneration.store(stateIngressGeneration(bank), std::memory_order_release);
                saturatingIncrement(diagnostic.endOfStreamFailures);
                return false;
            }
            static_cast<void>(promoteCurrentSongCacheToOwner(renderBank, requestedOwnership));
        }
        if (auto *continuation = preparedBoundedLaneIngress(bank, laneIndexValue, pending.generation)) {
            const auto marked = continuation->markLaneEndOfStream(renderLane(laneIndexValue),
                pending.generation, renderEnd);
            if (marked != SongRenderEosStatus::Accepted) {
                reportLaneIngressFailure(bank, laneIndexValue, stateIngressGeneration(bank),
                    "continuation-eos", static_cast<std::int64_t>(marked));
                diagnostic.failureGeneration.store(stateIngressGeneration(bank), std::memory_order_release);
                saturatingIncrement(diagnostic.endOfStreamFailures);
                return false;
            }
        }
        const SongRenderEosStatus result = bankStates[bank]->pipeline->markLaneEndOfStream(
            renderLane(laneIndexValue),
            pending.generation,
            renderEnd);
        if (result == SongRenderEosStatus::Accepted) {
            pending.endOfStreamPending = false;
            pending.endOfStreamMarkedGeneration = pending.generation;
            saturatingIncrement(diagnostic.endOfStreamMarks);
            return true;
        }
        if (result == SongRenderEosStatus::StaleGeneration) {
            pending.endOfStreamPending = false;
            saturatingIncrement(diagnostic.staleAfterPull);
            return true;
        }
        reportLaneIngressFailure(bank, laneIndexValue, stateIngressGeneration(bank),
            "reservoir-eos", static_cast<std::int64_t>(result));
        diagnostic.failureGeneration.store(stateIngressGeneration(bank), std::memory_order_release);
        saturatingIncrement(diagnostic.endOfStreamFailures);
        return false;
    }

    [[nodiscard]] std::uint64_t stateIngressGeneration(std::size_t bank) const noexcept {
        return bankStates[bank]->pipeline ? bankStates[bank]->pipeline->ingressEpoch() : 0U;
    }

    void reportLaneIngressFailure(std::size_t bank, std::size_t lane, std::uint64_t generation,
        const char *reason, std::int64_t status, const LanePullResult *pulled = nullptr) noexcept {
#if defined(__ANDROID__)
        if (generation == 0U || lastLoggedIngressFailureGeneration[bank][lane] == generation) return;
        lastLoggedIngressFailureGeneration[bank][lane] = generation;
        const auto &state = *bankStates[bank];
        const auto owner = state.pipeline->ownership();
        const auto &pending = *state.lanes[lane];
        const auto retained = state.pipeline->laneDiagnostics(renderLane(lane));
        const auto cache = resources->snapshot();
        const auto pcmGeneration = pulled ? pulled->generation : pending.generation;
        const auto pcmFirst = pulled ? pulled->firstTimelineFrame48k : pending.firstTimelineFrame;
        __android_log_print(ANDROID_LOG_ERROR, "StemWiseIngress",
            "reason=%s status=%lld bank=%zu lane=%zu token=%llu generation=%llu intent=%llu "
            "pcmGeneration=%llu first=%llu frames=%zu eos=%d end=%llu "
            "reservoirGeneration=%llu reservoirFirst=%llu reservoirEnd=%llu cachePresent=%d cacheGeneration=%llu "
            "ownerGeneration=%llu appliedGeneration=%llu appliedFirst=%llu "
            "consumedDesiredToken=%llu consumedDesiredGeneration=%llu consumedDesiredFirst=%llu cutoverGeneration=%llu "
            "commandsPublished=%llu commandsConsumed=%llu commandUnread=%d "
            "pipeline=%p nativeBankIdentity=%llu sourceFrame=%.3f feedFrame=%llu "
            "cacheBank=%zu cacheInstallation=%d cacheBuildFailed=%d cacheCreatePending=%d "
            "cacheAdmitted=%llu cacheNext=%llu cachePublished=%llu cacheCompleteMask=%u "
            "cacheSnapshotToken=%llu cacheSnapshotGeneration=%llu cacheAllocationFailed=%d cacheOwnsIngress=%d "
            "cacheStemHash=%llu cacheSnapshotStemHash=%llu appliedStemHash=%llu consumedDesiredStemHash=%llu",
            reason, static_cast<long long>(status), bank, lane,
            static_cast<unsigned long long>(owner.selectionToken),
            static_cast<unsigned long long>(generation),
            static_cast<unsigned long long>(state.appliedIntent.revision),
            static_cast<unsigned long long>(pulled ? pulled->generation : pending.generation),
            static_cast<unsigned long long>(pulled ? pulled->firstTimelineFrame48k : pending.firstTimelineFrame),
            pulled ? pulled->outputFramesProduced : pending.remainingFrames,
            static_cast<int>(pending.endOfStreamPending),
            static_cast<unsigned long long>(pending.endTimelineFrameExclusive),
            static_cast<unsigned long long>(retained.generation),
            static_cast<unsigned long long>(retained.firstTimelineFrame),
            static_cast<unsigned long long>(retained.endTimelineFrameExclusive),
            static_cast<int>(currentSongCache != nullptr),
            static_cast<unsigned long long>(currentSongCacheKey.generation),
            static_cast<unsigned long long>(owner.generation),
            static_cast<unsigned long long>(state.appliedIntent.ownership.generation),
            static_cast<unsigned long long>(state.appliedIntent.firstTimelineFrame),
            static_cast<unsigned long long>(desiredSnapshot.banks[bank].ownership.selectionToken),
            static_cast<unsigned long long>(desiredSnapshot.banks[bank].ownership.generation),
            static_cast<unsigned long long>(desiredSnapshot.banks[bank].firstTimelineFrame),
            static_cast<unsigned long long>(directLoopCutover.policy.generation),
            static_cast<unsigned long long>(commandSnapshotsPublished.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(commandSnapshotsConsumed.load(std::memory_order_relaxed)),
            static_cast<int>(commandMailbox.hasUnreadPublication()),
            static_cast<void *>(state.pipeline.get()),
            static_cast<unsigned long long>(reinterpret_cast<std::uintptr_t>(ingressBanks[bank]) &
                0x00ff'ffff'ffff'ffffULL),
            state.pipeline->sourceTimelineFrame(),
            static_cast<unsigned long long>(state.pipeline->nextPreDspTimelineFrame()),
            currentSongCacheBank, static_cast<int>(currentSongCacheInstallation),
            static_cast<int>(currentSongCacheBuildFailed), static_cast<int>(currentSongCacheCreatePending),
            static_cast<unsigned long long>(cache.io.admittedLaneCount),
            static_cast<unsigned long long>(cache.nextExpectedFrames[lane]),
            static_cast<unsigned long long>(cache.publishedFrames[lane]),
            static_cast<unsigned int>(cache.completeLaneMask),
            static_cast<unsigned long long>(cache.key.selectionToken),
            static_cast<unsigned long long>(cache.key.generation),
            static_cast<int>(cache.allocationFailed),
            static_cast<int>(cacheOwnsLaneIngress(bank, lane, pcmGeneration, pcmFirst)),
            static_cast<unsigned long long>(currentSongCacheKey.stemPackageHash),
            static_cast<unsigned long long>(cache.key.stemPackageHash),
            static_cast<unsigned long long>(state.appliedIntent.stemPackageHash),
            static_cast<unsigned long long>(desiredSnapshot.banks[bank].stemPackageHash));
#else
        static_cast<void>(bank); static_cast<void>(lane); static_cast<void>(generation);
        static_cast<void>(reason); static_cast<void>(status); static_cast<void>(pulled);
#endif
    }

    [[nodiscard]] bool drainLane(
        std::size_t bank,
        std::size_t laneIndexValue,
        std::uint32_t maximumPulls) noexcept {
        BankRuntimeState &state = *bankStates[bank];
        if (!state.assigned) return false;
        LaneIngress *ingress = ingressBanks[bank]->lane(laneIndexValue);
        if (ingress == nullptr) return false;
        PendingLanePcm &pending = *state.lanes[laneIndexValue];
        AtomicLaneDiagnostics &diagnostic = bankDiagnostics[bank].lanes[laneIndexValue];
        bool progressed = false;

        // Leave new input in its bounded ingress until the asynchronous cache admission and
        // retained-prefix transfer finish. Existing reservoir/output PCM remains renderable;
        // no decoder suffix can overtake the exact prefix accepted by the I/O owner.
        if ((currentSongCacheCreatePending && cacheCreationBank == bank) ||
            (currentSongCache != nullptr && currentSongCacheBank == bank && currentSongCacheInstallation != CacheInstallation::Installed)) return false;

        if (pending.futureLoopRequest.valid) {
            if (retainFutureLoopPending(bank, laneIndexValue, pending)) return false;
            pending.clear();
            diagnostic.retainedSuffixFrames.store(0U, std::memory_order_relaxed);
            progressed = true;
        }
        if (pending.endOfStreamMarkedGeneration ==
            state.pipeline->ingressEpoch() && !futureLoopIngressRequest(bank, laneIndexValue, *ingress).valid) {
            return progressed;
        }
        if (pending.endOfStreamPending && pending.remainingFrames == 0U) {
            progressed = markPendingLaneEndOfStream(bank, laneIndexValue, pending) || progressed;
            if (pending.endOfStreamPending || !futureLoopIngressRequest(bank, laneIndexValue, *ingress).valid) return progressed;
        }

        for (std::uint32_t pullNumber = 0;
             pullNumber < maximumPulls;
             ++pullNumber) {
            if (pending.remainingFrames != 0U) {
                const bool appended = appendPendingLane(
                    bank,
                    laneIndexValue,
                    *ingress,
                    pending);
                progressed = appended || progressed;
                if (pending.remainingFrames != 0U) break;
                progressed = markPendingLaneEndOfStream(
                    bank,
                    laneIndexValue,
                    pending) || progressed;
                if (pending.endOfStreamPending) return progressed;
                if (pending.endOfStreamMarkedGeneration ==
                    state.pipeline->ingressEpoch() && !futureLoopIngressRequest(bank, laneIndexValue, *ingress).valid) {
                    break;
                }
            }

            const auto future = futureLoopIngressRequest(bank, laneIndexValue, *ingress);
            const std::uint64_t expectedGeneration = future.valid ? future.ingressEpoch :
                expectedLaneIngressGeneration(bank, laneIndexValue, *ingress);
            const std::uint64_t ingressGeneration = ingress->activeGeneration();
            if (ingressGeneration < expectedGeneration) {
                break;
            }

            const LaneIngressDiagnostics ingressDiagnostic = ingress->diagnostics();
            const std::size_t pullCapacity = std::min<std::size_t>(
                configuration.ingressPullFrames,
                ingressDiagnostic.maximumOutputQuantumFrames);
            if (pullCapacity == 0U) break;
            const LanePullResult pulled = ingress->pull48k(
                expectedGeneration,
                pending.samples.get(),
                pullCapacity);
            saturatingIncrement(diagnostic.pulls);

            if (pulled.status == LanePullStatus::Empty) break;
            if (pulled.status == LanePullStatus::StaleGeneration) {
                // A newer authorized ingress may sit behind an obsolete current-generation prefix
                // in the bounded ring. LaneIngress consumes that prefix with zero output, then
                // preserves the first newer head. One pull per runtime turn both guarantees bounded
                // work and prevents the old full queue from permanently backpressuring loop-body
                // priming.
                saturatingIncrement(diagnostic.futureGenerationDeferrals);
                break;
            }
            if (pulled.status == LanePullStatus::InvalidArgument ||
                pulled.status == LanePullStatus::ResamplerFailure ||
                pulled.status == LanePullStatus::EndOfStreamFlushFailure) {
                reportLaneIngressFailure(bank, laneIndexValue, expectedGeneration,
                    "ingress-pull", static_cast<std::int64_t>(pulled.status), &pulled);
                diagnostic.failureGeneration.store(expectedGeneration, std::memory_order_release);
                saturatingIncrement(diagnostic.endOfStreamFailures);
                break;
            }

            if (ingress->activeGeneration() != pulled.generation ||
                (future.valid ? !sameLoopIngressRequest(future, futureLoopIngressRequest(bank, laneIndexValue, *ingress)) :
                    expectedLaneIngressGeneration(bank, laneIndexValue, *ingress) != pulled.generation)) {
                saturatingIncrement(diagnostic.staleAfterPull);
                progressed = true;
                continue;
            }
            if (hasDiscontinuity(pulled.discontinuity, LaneDiscontinuity::TimestampGap) ||
                hasDiscontinuity(pulled.discontinuity, LaneDiscontinuity::Explicit)) {
                // A lane-local reset would destroy alignment. Kotlin must publish one newer bank
                // generation and seek all configured lanes together.
                reportLaneIngressFailure(bank, laneIndexValue, expectedGeneration,
                    "ingress-discontinuity", static_cast<std::int64_t>(pulled.discontinuity), &pulled);
                diagnostic.failureGeneration.store(expectedGeneration, std::memory_order_release);
                saturatingIncrement(diagnostic.discontinuityRejects);
                progressed = true;
                continue;
            }

            pending.offsetFrames = 0;
            pending.remainingFrames = pulled.outputFramesProduced;
            pending.generation = pulled.generation;
            pending.firstTimelineFrame = pulled.firstTimelineFrame48k;
            pending.endOfStreamPending = false;
            pending.endTimelineFrameExclusive = 0U;
            pending.futureLoopRequest = future;
            saturatingAdd(diagnostic.pulledFrames, pulled.outputFramesProduced);
            diagnostic.retainedSuffixFrames.store(
                pulled.outputFramesProduced,
                std::memory_order_relaxed);
            if (pulled.status == LanePullStatus::EndOfStream) {
                pending.endOfStreamPending = true;
                pending.endTimelineFrameExclusive = pulled.firstTimelineFrame48k;
                if (pulled.outputFramesProduced != 0U) {
                    pending.endTimelineFrameExclusive =
                        pulled.firstTimelineFrame48k + pulled.outputFramesProduced;
                }
                // LaneIngress reports the authoritative clamped boundary even when its final tail
                // block begins earlier than that boundary.
                if (pulled.firstTimelineFrame48k != 0U &&
                    pulled.outputFramesProduced == 0U) {
                    pending.endTimelineFrameExclusive = pulled.firstTimelineFrame48k;
                }
            }
            progressed = pulled.outputFramesProduced != 0U ||
                pulled.sourceFramesConsumed != 0U || pulled.status == LanePullStatus::EndOfStream || progressed;

            if (future.valid) {
                // Run the exact future SRC through its real history without changing the live
                // reservoir or owner. The first body suffix stays in this lane's bounded slot.
                const auto skipped = pending.firstTimelineFrame < future.targetTimelineFrame
                    ? std::min<std::uint64_t>(pending.remainingFrames,
                        future.targetTimelineFrame - pending.firstTimelineFrame) : 0U;
                pending.offsetFrames += static_cast<std::size_t>(skipped);
                pending.remainingFrames -= static_cast<std::size_t>(skipped);
                pending.firstTimelineFrame += skipped;
                diagnostic.retainedSuffixFrames.store(pending.remainingFrames, std::memory_order_relaxed);
                if (pending.remainingFrames != 0U && pending.firstTimelineFrame != future.targetTimelineFrame) {
                    reportLaneIngressFailure(bank, laneIndexValue, expectedGeneration, "loop-body-gap", 0, &pulled);
                    diagnostic.failureGeneration.store(expectedGeneration, std::memory_order_release);
                    saturatingIncrement(diagnostic.discontinuityRejects);
                    pending.clear();
                    diagnostic.retainedSuffixFrames.store(0U, std::memory_order_relaxed);
                    return true;
                }
                if (pending.remainingFrames != 0U || pending.endOfStreamPending) break;
                pending.futureLoopRequest = {};
                continue;
            }

            if (pending.remainingFrames != 0U) {
                const bool appended = appendPendingLane(
                    bank,
                    laneIndexValue,
                    *ingress,
                    pending);
                progressed = appended || progressed;
                if (pending.remainingFrames != 0U) break;
            }
            progressed = markPendingLaneEndOfStream(
                bank,
                laneIndexValue,
                pending) || progressed;
            if (pending.endOfStreamPending) return progressed;
            if (pending.endOfStreamMarkedGeneration == pulled.generation) break;
        }
        return progressed;
    }

    [[nodiscard]] bool serviceIngressAndCurrentOutput() noexcept {
        const NativePlaybackIngressServicePlan plan(
            renderBank,
            configuration.maximumPullsPerLanePerCycle,
            // Prepared capacity is not admission. Cache storage/identity must commit with the
            // bank intent before newly opened optional decoders can publish into that cache.
            {bankStates[0U]->assigned ? std::min<std::size_t>(bankStates[0U]->appliedIntent.stemLaneCount,
                 bankStates[0U]->pipeline->stemLaneCount()) + 1U : 0U,
             bankStates[1U]->assigned ? std::min<std::size_t>(bankStates[1U]->appliedIntent.stemLaneCount,
                 bankStates[1U]->pipeline->stemLaneCount()) + 1U : 0U});
        std::array<std::array<bool, kSongRenderLaneCount>, kNativePlaybackBankCount>
            laneCanProgress{};
        for (auto &bank : laneCanProgress) bank.fill(true);
        bool progressed = false;
        bool outputServiceRequired = true;
        for (std::size_t actionIndex = 0; actionIndex < plan.actionCount(); ++actionIndex) {
            if (actionIndex != 0U && (commandMailbox.hasUnreadPublication() ||
                runtimeStatus.load(std::memory_order_acquire) != NativePlaybackRuntimeStatus::Running ||
                newerDirectAtomicPolicyPublishedThan(directLoopState.observedRevision))) {
                ++ingressServicePolicyBreaks;
                break;
            }
            const NativePlaybackWorkerServiceAction action = plan.actionAt(actionIndex);
            if (action.kind == NativePlaybackWorkerServiceKind::CurrentOutput) {
                if (outputServiceRequired) {
                    progressed = fillOutput() || progressed;
                    outputServiceRequired = false;
                }
                continue;
            }
            if (!laneCanProgress[action.bank][action.lane]) continue;
            const bool laneProgressed = drainLane(action.bank, action.lane, 1U);
            progressed = laneProgressed || progressed;
            outputServiceRequired = true;
            if (!laneProgressed) {
                // Empty, generation-blocked and backpressured lanes are not polled again during
                // this cycle. A producer notification or the next bounded cycle retries them.
                laneCanProgress[action.bank][action.lane] = false;
            }
        }
        return progressed;
    }



    [[nodiscard]] bool consumeManagedLoopIntent() noexcept {
        LoopCycleIntent received{};
        const bool consumed = loopIntentMailbox.readLatest(
            consumedLoopIntentPublication,
            received);
        if (consumed) {
            pendingManagedLoopIntent = received;
            hasPendingManagedLoopIntent = true;
        }
        if (!hasPendingManagedLoopIntent) return false;

        LoopCycleIntent intent = pendingManagedLoopIntent;
        hasPendingManagedLoopIntent = false;
        const bool firstAdoption = !managedLoopAdopted;
        managedLoopAdopted = true;
        lastLoopSubmitStatus = loopCycle.submit(intent);
        const bool retained = lastLoopSubmitStatus == LoopCycleSubmitStatus::Accepted ||
            lastLoopSubmitStatus == LoopCycleSubmitStatus::AcceptedDisabled ||
            lastLoopSubmitStatus == LoopCycleSubmitStatus::Unchanged ||
            lastLoopSubmitStatus == LoopCycleSubmitStatus::OwnershipRebound;
        if (retained) {
            const bool sameRevision = managedLoopIntent.revision == intent.revision;
            managedLoopIntent = intent;
            if (!sameRevision || !intent.enabled) {
                activeLoopCacheView = {};
                activeLoopCacheSnapshot = {};
                lastLoopOriginalEosStatus = LoopCycleOriginalEosStatus::NotRequired;
            }
            forceLoopPositionObservation = true;
        }
        if (firstAdoption) {

            if (selectionAssigned &&
                intent.owner.selectionToken == requestedOwnership.selectionToken &&
                intent.owner.renderGeneration == requestedOwnership.generation) {
                static_cast<void>(
                    bankStates[renderBank]->pipeline->clearLoop(requestedOwnership));
            }
        }
        return true;
    }

    [[nodiscard]] bool acceptManagedLoopCache() noexcept {
        if (!managedLoopAdopted || !managedLoopIntent.enabled) return false;
        if (!selectionAssigned || managedLoopIntent.owner.selectionToken != requestedOwnership.selectionToken ||
            managedLoopIntent.owner.renderGeneration != requestedOwnership.generation ||
            !sameOwnership(bankStates[renderBank]->appliedIntent.ownership, requestedOwnership)) return false;
        const LoopCycleStateSnapshot state = loopCycle.snapshot();
        if (state.cacheAccepted || state.phase != LoopCyclePhase::WaitingForCache) return false;
        LoopHeadCache::ReadView active =
            repeatLoopPreparation.cache().acquireActive(managedLoopIntent.cacheKey);
        if (!active.valid()) return false;
        activeLoopCacheSnapshot = active.snapshot();
        lastLoopCacheStatus = loopCycle.acceptCache(activeLoopCacheSnapshot,
            bankStates[renderBank]->appliedIntent.playbackEndFrameExclusive);
        if (lastLoopCacheStatus == LoopCycleCacheStatus::Accepted) {
            activeLoopCacheView = std::move(active);
            forceLoopPositionObservation = true;
        }
        return lastLoopCacheStatus == LoopCycleCacheStatus::Accepted;
    }

    [[nodiscard]] bool resolveManagedOriginalEos() noexcept {
        if (!selectionAssigned || !managedLoopAdopted || !managedLoopIntent.enabled ||
            managedLoopIntent.owner.selectionToken != requestedOwnership.selectionToken ||
            managedLoopIntent.owner.renderGeneration != requestedOwnership.generation ||
            managedLoopIntent.boundaryMode != LoopHeadBoundaryMode::OriginalEos) {
            return false;
        }
        const LoopCycleStateSnapshot state = loopCycle.snapshot();
        if (!state.cacheAccepted || state.boundaryResolved) return false;
        SongRenderPipeline &pipeline = *bankStates[renderBank]->pipeline;
        const SongRenderLaneEndSnapshot original =
            pipeline.laneEndSnapshot(SongRenderLane::Original);
        const auto playbackEnd = bankStates[renderBank]->appliedIntent.playbackEndFrameExclusive;
        // An authored playback end resolves repeat without changing the physical lane/cache EOF.
        if ((playbackEnd == 0U && !original.marked) ||
            (original.marked && original.ingressEpoch != pipeline.ingressEpoch())) return false;
        if (!original.marked && pipeline.laneDiagnostics(SongRenderLane::Original).
                endTimelineFrameExclusive < playbackEnd) return false;
        const auto end = playbackEnd == 0U ? original.endTimelineFrameExclusive :
            (original.marked ? std::min(playbackEnd, original.endTimelineFrameExclusive) : playbackEnd);
        lastLoopOriginalEosStatus = loopCycle.resolveOriginalEos({
            .loopRevision = managedLoopIntent.revision,
            .owner = managedLoopIntent.owner,
            .ingressEpoch = pipeline.ingressEpoch(),
            .endTimelineFrameExclusive = end,
        });
        if (lastLoopOriginalEosStatus == LoopCycleOriginalEosStatus::Accepted) {
            forceLoopPositionObservation = true;
        }
        return lastLoopOriginalEosStatus == LoopCycleOriginalEosStatus::Accepted;
    }

    [[nodiscard]] bool consumeManagedLoopIngressReady() noexcept {
        LoopCycleIngressReadyEvidence evidence{};
        if (!loopIngressReadyMailbox.readLatest(
                consumedLoopIngressReadyPublication,
                evidence)) {
            return false;
        }

        // The decoder/controller evidence is still fenced against the actual parked ingress
        // owners. Actual body PCM may be in the source ring or already held after native SRC
        // preroll. A zero-padded stem still needs its exact EOS publication for this same epoch.
        if (selectionAssigned) {
            const LoopCycleIngressRequest request = loopCycle.ingressRequest();
            for (std::size_t lane = 0U; lane < kSongRenderLaneCount; ++lane) {
                const std::uint32_t bit = 1U << static_cast<std::uint32_t>(lane);
                if ((evidence.readyLaneMask & bit) == 0U) continue;
                const LaneIngress *ingress = ingressBanks[renderBank]->lane(lane);
                if (ingress == nullptr) {
                    evidence.readyLaneMask &= ~bit;
                    evidence.eosPaddedLaneMask &= ~bit;
                    continue;
                }
                const LaneIngressDiagnostics laneDiagnostics = ingress->diagnostics();
                const auto &pending = *bankStates[renderBank]->lanes[lane];
                const bool retainedBodyPcm = sameLoopIngressRequest(pending.futureLoopRequest, request) &&
                    pending.generation == evidence.ingressEpoch && pending.remainingFrames != 0U &&
                    pending.firstTimelineFrame == request.targetTimelineFrame;
                const bool eosPadded = (evidence.eosPaddedLaneMask & bit) != 0U;
                const bool exactEosClaim = eosPadded && request.valid && lane != 0U &&
                    laneDiagnostics.endOfStreamGeneration == evidence.ingressEpoch &&
                    laneDiagnostics.finalTimelineFrame48kExclusive <
                        request.requiredEndTimelineFrameExclusive;
                const bool pcmRequired = !eosPadded ||
                    laneDiagnostics.finalTimelineFrame48kExclusive >
                        request.targetTimelineFrame;
                if (laneDiagnostics.activeGeneration != evidence.ingressEpoch ||
                    laneDiagnostics.producerGeneration != evidence.ingressEpoch ||
                    (eosPadded && !exactEosClaim) ||
                    (pcmRequired && laneDiagnostics.readableSourceFrames == 0U && !retainedBodyPcm)) {
                    evidence.readyLaneMask &= ~bit;
                    evidence.eosPaddedLaneMask &= ~bit;
                }
            }
        } else {
            evidence.readyLaneMask = 0U;
            evidence.eosPaddedLaneMask = 0U;
        }
        lastLoopIngressStatus = loopCycle.acknowledgeIngressReady(evidence);
        if (lastLoopIngressStatus == LoopCycleIngressStatus::Accepted) {
            forceLoopPositionObservation = true;
        }
        return true;
    }

    [[nodiscard]] bool applyManagedLoopDirective() noexcept {
        const LoopCycleDirective directive = loopCycle.renderDirective();
        if (!directive.valid || !selectionAssigned ||
            directive.owner.selectionToken != requestedOwnership.selectionToken ||
            directive.owner.renderGeneration != requestedOwnership.generation) {
            return false;
        }
        SongRenderPipeline &pipeline = *bankStates[renderBank]->pipeline;
        const SongRenderLoopDirectiveStatus applied = pipeline.applyLoopDirective(
            directive,
            activeLoopCacheView.retain());
        if (applied != SongRenderLoopDirectiveStatus::Accepted &&
            applied != SongRenderLoopDirectiveStatus::Cleared) {
            return false;
        }
        lastLoopRenderCommitStatus = loopCycle.commitRenderDirective(directive.sequence);
        if (lastLoopRenderCommitStatus == LoopCycleRenderCommitStatus::Accepted) {
            for (auto &lane : bankStates[renderBank]->lanes) {
                auto &request = lane->futureLoopRequest;
                if (request.valid && request.owner == directive.owner && request.loopRevision == directive.loopRevision &&
                    request.targetCycle == directive.cycle && request.ingressEpoch == directive.ingressEpoch &&
                    request.targetTimelineFrame == directive.headEndTimelineFrameExclusive &&
                    request.requiredLaneMask == directive.requiredLaneMask &&
                    lane->generation == pipeline.ingressEpoch()) {
                    // Only this exact installation hands the retained suffix/EOS to ordinary
                    // strict reservoir append. An ACK alone never changes the live body epoch.
                    request = {};
                }
            }
            // A late cache/cold-body activation is an exact reposition for this owner. Natural EOS
            // observed while the loop was still preparing must not permanently gate fillOutput().
            if (applied == SongRenderLoopDirectiveStatus::Accepted && pipelineEndObserved &&
                sameOwnership(pipelineEndOwnership, requestedOwnership)) {
                pipelineEndObserved = false;
                pipelineEndOwnership = {};
                audibleEndPublishedForOwnership = {};
            }
        }
        return lastLoopRenderCommitStatus == LoopCycleRenderCommitStatus::Accepted;
    }

    [[nodiscard]] bool handleManagedLoopBoundary() noexcept {
        if (!selectionAssigned) return false;
        SongRenderPipeline &pipeline = *bankStates[renderBank]->pipeline;
        const LoopCycleBoundaryEvidence evidence = pipeline.pendingLoopBoundary();
        if (evidence.sequence == 0U ||
            evidence.sequence <= lastPipelineBoundarySequence) {
            return false;
        }
        lastPipelineBoundarySequence = evidence.sequence;
        lastLoopBoundaryStatus = loopCycle.onLoopBoundary(evidence);
        return true;
    }

    [[nodiscard]] bool managedTailRetainedThroughEnd(
        const SongRenderPipeline &pipeline,
        std::uint64_t sourceTimelineFrame,
        const LoopCycleStateSnapshot &state) const noexcept {
        if (!managedLoopIntent.enabled) return false;
        if (!state.boundaryResolved) return false;
        const std::uint64_t loopEnd = state.resolvedLoopEndTimelineFrameExclusive;
        const std::uint64_t length = loopEnd - managedLoopIntent.cacheKey.startTimelineFrame;
        const std::uint64_t headEnd = managedLoopIntent.cacheKey.startTimelineFrame +
            std::min<std::uint64_t>(length, LoopHeadCache::kCapacityFrames);
        const std::uint64_t requiredFirst = state.renderInstalled
            ? headEnd
            : sourceTimelineFrame;
        for (std::size_t lane = 0U; lane < kSongRenderLaneCount; ++lane) {
            const std::uint32_t bit = 1U << static_cast<std::uint32_t>(lane);
            if ((managedLoopIntent.requiredLaneMask & bit) == 0U) continue;
            // The renderer can also retain the tail in the sealed song cache. Use its coverage
            // proof before falling back to the reservoir's one-frame decoder-rounding tolerance.
            if (pipeline.laneCoversLogicalRange(pipeline.ownership(), renderLane(lane),
                    requiredFirst, static_cast<std::size_t>(loopEnd - requiredFirst))) continue;
            const TimelineStereoBufferDiagnostics diagnostics =
                pipeline.laneDiagnostics(renderLane(lane));
            const SongRenderLaneEndSnapshot eos = pipeline.laneEndSnapshot(renderLane(lane));
            const bool exactLaneEos = lane != 0U && eos.marked &&
                eos.ingressEpoch == pipeline.ingressEpoch() &&
                eos.endTimelineFrameExclusive <= loopEnd;
            const std::uint64_t requiredPcmEnd = exactLaneEos
                ? eos.endTimelineFrameExclusive
                : loopEnd;
            const bool requiresPcm = requiredPcmEnd > requiredFirst;
            const bool startsTooLate = requiresPcm &&
                diagnostics.firstTimelineFrame > requiredFirst &&
                diagnostics.firstTimelineFrame - requiredFirst > 1U;
            if (diagnostics.generation != pipeline.ingressEpoch() || startsTooLate ||
                (requiresPcm &&
                 diagnostics.endTimelineFrameExclusive < requiredPcmEnd)) {
                return false;
            }
        }
        return true;
    }

    [[nodiscard]] bool observeManagedLoopPosition() noexcept {
        if (!selectionAssigned || !managedLoopIntent.enabled ||
            managedLoopIntent.owner.selectionToken != requestedOwnership.selectionToken ||
            managedLoopIntent.owner.renderGeneration != requestedOwnership.generation ||
            positionEvidenceSequence == std::numeric_limits<std::uint64_t>::max()) {
            return false;
        }
        SongRenderPipeline &pipeline = *bankStates[renderBank]->pipeline;
        const double position = pipeline.sourceTimelineFrame();
        if (!std::isfinite(position) || position < 0.0 ||
            position > static_cast<double>(std::numeric_limits<std::uint64_t>::max())) {
            return false;
        }
        const std::uint64_t sourceTimelineFrame =
            static_cast<std::uint64_t>(position);
        const LoopCycleStateSnapshot state = loopCycle.snapshot();
        const bool tailRetained = managedTailRetainedThroughEnd(
            pipeline,
            sourceTimelineFrame,
            state);
        if (!forceLoopPositionObservation &&
            sourceTimelineFrame == lastManagedObservedSourceFrame &&
            pipeline.ingressEpoch() == lastManagedObservedIngressEpoch &&
            state.renderCycle == lastManagedObservedCycle &&
            managedLoopIntent.revision == lastManagedObservedRevision &&
            tailRetained == lastManagedObservedTailRetained) {
            return false;
        }
        forceLoopPositionObservation = false;
        lastManagedObservedSourceFrame = sourceTimelineFrame;
        lastManagedObservedIngressEpoch = pipeline.ingressEpoch();
        lastManagedObservedCycle = state.renderCycle;
        lastManagedObservedRevision = managedLoopIntent.revision;
        lastManagedObservedTailRetained = tailRetained;
        ++positionEvidenceSequence;
        lastLoopPositionStatus = loopCycle.observePosition({
            .sequence = positionEvidenceSequence,
            .loopRevision = managedLoopIntent.revision,
            .cycle = state.renderCycle,
            .owner = managedLoopIntent.owner,
            .sourceTimelineFrame = sourceTimelineFrame,
            .currentIngressEpoch = pipeline.ingressEpoch(),
            .tailRetainedThroughEnd = tailRetained,
        });
        return true;
    }

    [[nodiscard]] bool consumeManagedLoopCallbackEvidence() noexcept {
        const NativeAudioAudibleLoopEvidence evidence =
            output.audibleLoopEvidence(output.handle);
        if (!evidence.valid() ||
            evidence.sequence <= lastOutputLoopEvidenceSequence) {
            return false;
        }
        // Consume the output-side sequence before semantic filtering so retired callbacks cannot
        // become eligible after a later selection happens to reuse related state. The state
        // machine separately consumes one exact tuple from the physical policy replaced at an
        // atomic loop transition; every other current or stale tuple remains diagnostic.
        lastOutputLoopEvidenceSequence = evidence.sequence;
        lastLoopCallbackStatus = loopCycle.observeCallback(
            evidence.loopRevision,
            evidence.loopCycle,
            {
                .selectionToken = evidence.selectionToken,
                .renderGeneration = evidence.generation,
            },
            evidence.callbackSequence);
        return true;
    }

    void publishManagedLoopSnapshot() noexcept {
        NativePlaybackLoopProtocolSnapshot snapshot{};
        snapshot.adopted = managedLoopAdopted;
        snapshot.bank = static_cast<NativePlaybackBank>(renderBank);
        snapshot.state = loopCycle.snapshot();
        snapshot.ingressRequest = loopCycle.ingressRequest();
        snapshot.renderDirective = loopCycle.renderDirective();
        snapshot.audibleEvidence = loopCycle.audibleEvidence();
        snapshot.activeCache = activeLoopCacheSnapshot;
        snapshot.diagnostics = loopCycle.diagnostics();
        snapshot.lastSubmitStatus = lastLoopSubmitStatus;
        snapshot.lastCacheStatus = lastLoopCacheStatus;
        snapshot.lastOriginalEosStatus = lastLoopOriginalEosStatus;
        snapshot.lastIngressStatus = lastLoopIngressStatus;
        snapshot.lastPositionStatus = lastLoopPositionStatus;
        snapshot.lastBoundaryStatus = lastLoopBoundaryStatus;
        snapshot.lastRenderCommitStatus = lastLoopRenderCommitStatus;
        snapshot.lastCallbackStatus = lastLoopCallbackStatus;
        snapshot.positionEvidenceSequence = positionEvidenceSequence;
        snapshot.lastOutputLoopEvidenceSequence = lastOutputLoopEvidenceSequence;
        loopProtocolSnapshots.publish(snapshot);
    }

    [[nodiscard]] bool newerDirectAtomicPolicyPublishedThan(
        std::uint64_t revision) const noexcept {
        if (!selectionAssigned || !validOwnership(requestedOwnership)) return false;
        const std::uint64_t latestRevision =
            directAtomicPolicyGenerationRevision.load(std::memory_order_acquire);
        if (latestRevision <= revision ||
            directAtomicPolicyGeneration.load(std::memory_order_relaxed) !=
                requestedOwnership.generation) {
            return false;
        }
        AtomicLoopPolicySnapshot latest{};
        if (!atomicLoopPolicy.readLatest(latest)) return true;
        return latest.selectionToken == requestedOwnership.selectionToken &&
            latest.revision >= latestRevision;
    }

    [[nodiscard]] std::uint64_t directAtomicPolicyPublishedAt(
        std::uint64_t revision) const noexcept {
        const std::uint64_t before =
            directAtomicPolicyPublishedRevision.load(std::memory_order_acquire);
        if (before != revision) return 0U;
        const std::uint64_t publishedAt =
            directAtomicPolicyPublishedAtMonotonicMicros.load(std::memory_order_relaxed);
        return directAtomicPolicyPublishedRevision.load(std::memory_order_acquire) == before
            ? publishedAt
            : 0U;
    }

    [[nodiscard]] CurrentSongCacheKey directCacheIdentity() const noexcept {
        if (renderBank >= kNativePlaybackBankCount) return {};
        const auto &intent = bankStates[renderBank]->appliedIntent;
        return {requestedOwnership.selectionToken, requestedOwnership.generation,
            intent.songIdentityHash, intent.originalContentHash, intent.stemPackageHash};
    }

    // Serialized preparation caller; accepted staging cannot replace committed PCM authority.
    void publishCommittedHeadBinding(std::uint64_t revision) noexcept {
        if (revision != pendingPreparedHeadRevision) return;
        const auto head = repeatLoopPreparation.cache().acquireActive();
        if (!head.valid() || head.snapshot().key.loopRevision != revision) return;
        const auto publication = preparedHeadBinding.publish();
        preparedHeadToken.store(pendingPreparedHeadOwnership.selectionToken, std::memory_order_release);
        preparedHeadGeneration.store(pendingPreparedHeadOwnership.generation, std::memory_order_release);
        preparedHeadRevision.store(revision, std::memory_order_release);
    }

    [[nodiscard]] LoopHeadCache::ReadView preparedLoopHeadForOwner(const CurrentSongCacheKey &key) const noexcept {
        const auto binding = preparedHeadBinding.read([&](std::uint64_t) noexcept {
            return std::array<std::uint64_t, 3>{preparedHeadToken.load(std::memory_order_acquire),
                preparedHeadGeneration.load(std::memory_order_acquire), preparedHeadRevision.load(std::memory_order_acquire)};
        });
        if (key.selectionToken != binding[0]) return {};
        if (key.generation != binding[1]) {
            // Transport seeks may reuse raw PCM, but only under the actually applied bank's exact source.
            CurrentSongCacheKey applied{};
            if (key.generation <= binding[1] || !readStableDiagnosticsGroup(ownershipDiagnosticsGuard, [&] {
                    applied = {requestedToken.load(std::memory_order_relaxed), requestedGeneration.load(std::memory_order_relaxed),
                        requestedSongIdentityHash.load(std::memory_order_relaxed), requestedOriginalContentHash.load(std::memory_order_relaxed),
                        requestedStemPackageHash.load(std::memory_order_relaxed)};
                }) || applied != key) return {};
        }
        auto head = repeatLoopPreparation.cache().acquireActive();
        if (!head.valid() || head.snapshot().key.loopRevision != binding[2]) return {};
        const auto &source = head.snapshot().key;
        if (!source.matchesSource(key.songIdentityHash, key.originalContentHash,
                key.stemPackageHash, head.snapshot().completedLaneMask)) return {};
        return head;
    }

    [[nodiscard]] bool boundedLoopRangeReady(const CurrentSongCacheKey &key, std::uint32_t mask,
        std::uint64_t first, std::uint64_t end) const noexcept {
        if (mask == 0U || end <= first) return false;
        const auto head = preparedLoopHeadForOwner(key);
        if (!head.valid()) return false;
        const auto &value = head.snapshot();
        return value.key.matchesSource(key.songIdentityHash, key.originalContentHash, key.stemPackageHash, mask) &&
            (head.readFailureLaneMask() & mask) == 0U &&
            (value.completedLaneMask & mask) == mask && first >= value.key.startTimelineFrame &&
            end <= value.key.startTimelineFrame + value.frameCount;
    }

    [[nodiscard]] bool directCacheCovers(std::uint32_t mask, std::uint64_t first, std::size_t frames) const noexcept {
        return (currentSongCache && currentSongCacheBank == renderBank && currentSongCache->covers(mask, first, frames)) ||
            (first <= std::numeric_limits<std::uint64_t>::max() - frames &&
                selectedLoopRangeReady(mask, first, first + frames));
    }

    [[nodiscard]] bool selectedLoopRangeReady(std::uint32_t mask, std::uint64_t first, std::uint64_t end) const noexcept {
        const auto key = directCacheIdentity();
        if (boundedLoopRangeReady(key, mask, first, end)) return true;
        if (renderBank >= kNativePlaybackBankCount) return false;
        const auto &bank = *bankStates[renderBank];
        return bank.assigned && sameOwnership(bank.appliedIntent.ownership, requestedOwnership) &&
            bank.pipeline->acquireRetainedLoopHead(key, mask, first, end).valid();
    }

    [[nodiscard]] bool directLoopPreparationIsCurrent(
        const DirectLoopCutoverPlan &plan) const noexcept {
        if (renderBank >= kNativePlaybackBankCount || directCacheIdentity() != plan.cacheKey) {
            return false;
        }
        const BankRuntimeState &state = *bankStates[renderBank];
        return state.appliedControlRevision == plan.controlRevision &&
            state.appliedControlApplicationEpoch == plan.controlApplicationEpoch &&
            state.appliedBeatGridRevision == plan.beatGridRevision &&
            hasDesiredSnapshot &&
            desiredSnapshot.controls.sourceIntentRevision == plan.sourceIntentRevision &&
            desiredSnapshot.controls.stemIntentRevision == plan.stemIntentRevision;
    }

    [[nodiscard]] bool directLoopStageRealizes(
        const AtomicLoopPolicySnapshot &policy) const noexcept {
        return (directLoopCutover.policy.revision != 0U) && directLoopCutover.policy.revision == policy.revision &&
            directLoopCutover.policy.generation == requestedOwnership.generation &&
            directLoopCutover.policy.selectionToken == policy.selectionToken &&
            directLoopCutover.policy.enabled == policy.enabled &&
            directLoopCutover.policy.aTimelineFrame == policy.aSourceFrame &&
            directLoopCutover.policy.bTimelineFrameExclusive ==
                policy.bSourceFrameExclusive &&
            directLoopPreparationIsCurrent(directLoopCutover);
    }

    [[nodiscard]] bool retargetOpenDirectLoopStage(
        const AtomicLoopPolicySnapshot &policy, bool capturePending) noexcept {
        if ((directLoopCutover.policy.revision == 0U) ||
            !policy.enabled || !directLoopCutover.policy.enabled ||
            !directLoopPreparationIsCurrent(directLoopCutover) ||
            (directLoopCutover.policy.bTimelineFrameExclusive !=
                 policy.bSourceFrameExclusive &&
             loopContinuationPipeline->diagnostics().loopWraps !=
                 directLoopCutover.preparationWrapBaseline) ||
            directLoopCutover.policy.selectionToken != policy.selectionToken ||
            directLoopCutover.policy.generation != requestedOwnership.generation ||
            directLoopCutover.policy.aTimelineFrame != policy.aSourceFrame) {
            return false;
        }

        const bool installedLoop = directLoopState.engaged &&
            directLoopState.bFrame != 0U;
        const std::uint64_t replacementStart = !capturePending && installedLoop &&
                policy.bSourceFrameExclusive > directLoopState.bFrame
            ? directLoopState.bFrame
            : policy.aSourceFrame;
        if (directLoopCutover.policy.continuationTimelineFrame != replacementStart) {
            return false;
        }

        NativeAudioOutputLoopPolicy retargeted = directLoopCutover.policy;
        if (policy.revision > retargeted.revision) retargeted.nextRevisionOnly = false;
        retargeted.revision = policy.revision;
        retargeted.bTimelineFrameExclusive = policy.bSourceFrameExclusive;
        if (retargeted.cutoverSourceRevision == 0U) {
            retargeted.cutoverTimelineFrameExclusive = policy.bSourceFrameExclusive;
        }
        retargeted.acceptedAtMonotonicMicros =
            policy.acceptedAtMonotonicMicros;
        retargeted.publishedAtMonotonicMicros =
            directAtomicPolicyPublishedAt(policy.revision);
        retargeted.inputEventAtMonotonicMicros =
            policy.inputEventAtMonotonicMicros;
        retargeted.heldBGestureId = policy.heldBGestureId;
        retargeted.boundaryArmedAtPublication =
            policy.boundaryArmedAtPublication;
        if (loopContinuationPipeline->retargetAtomicLoopEnd(
                requestedOwnership,
                policy.aSourceFrame,
                policy.bSourceFrameExclusive) != SongRenderLoopStatus::Accepted) {
            return false;
        }
        if (!reusableLoopHead.fitsBoundary(policy.bSourceFrameExclusive,
                desiredSnapshot.controls.mixer.defaultDeclickFrames)) {
            reusableLoopHead.invalidateReuse();
        }
        if (directLoopCutover.commitPublished()) {
            // B is logical state already owned by the callback demand mailbox. Republishing the
            // same physical role made eligibility depend on a worker retry and caused the B
            // boundary to alternate between overshoot and silence.
            directLoopState.observedRevision = policy.revision;
        }
        directLoopCutover.policy = retargeted;
        return true;
    }

    void cancelPendingRetainedBodyCopy() noexcept {
        if (!loopContinuationPipeline) return;
        for (auto &bank : bankStates) {
            if (bank && bank->pipeline && bank->pipeline.get() != loopContinuationPipeline.get())
                loopContinuationPipeline->cancelAtomicLoopContinuationPreparationFrom(*bank->pipeline);
        }
    }

    [[nodiscard]] bool cancelDirectLoopStage() noexcept {
        cancelPendingRetainedBodyCopy();
        resources->discardSupersededPreparation(kNativePlaybackBankCount, 0U);
        if (!directLoopCutover.stageOpen() && !directLoopCutover.commitPublished()) {
            if (loopContinuationPipeline) {
                loopContinuationPipeline->releaseRetiredLoopHeadReaders();
                loopContinuationPipeline->releaseCacheReadRequirements();
            }
            loopContinuationPendingOutput.clear();
            directLoopCutover.clear();
            return true;
        }
        const NativeAudioOutputLoopPolicy cancelledPolicy = directLoopCutover.policy;
        const NativeAudioDrainCancelStatus cancelled = output.cancelLoopCutover(
            output.handle,
            cancelledPolicy.generation,
            cancelledPolicy.physicalRevision());
        if (cancelled != NativeAudioDrainCancelStatus::CancelledOpenStage &&
            cancelled != NativeAudioDrainCancelStatus::OutgoingActive) {
            return false;
        }
        if (cancelled == NativeAudioDrainCancelStatus::OutgoingActive) {
            if (!selectionAssigned || renderBank >= kNativePlaybackBankCount ||
                !sameOwnership(
                    {cancelledPolicy.selectionToken, cancelledPolicy.generation},
                    bankStates[renderBank]->pipeline->ownership()) ||
                !output.releaseLoopCutoverWrites(
                    output.handle,
                    cancelledPolicy.generation,
                    cancelledPolicy.physicalRevision())) {
                // The callback already owns this continuation. Keep the exact stage retryable
                // until its producer writes are released; clearing it here would let a seek or
                // selection advance with the callback and render worker on different pipelines.
                return false;
            }
            // The callback already swapped this exact role but emitted no position evidence yet.
            // Adopt its producer before invalidating it so source/seek/owner changes can proceed.
            bankStates[renderBank]->pipeline.swap(loopContinuationPipeline);
            pendingOutput.clear();
            reusableLoopHead.discard();
            // Physical ownership alone does not prove adoption of the latest logical B.
            directLoopState.adoptPhysicalReceipt(cancelledPolicy,
                output.presentedPosition(output.handle).loopPolicyAdoptedRevision,
                directLoopCutover.preparationWrapBaseline, directLoopCutover.continuationEngaged);
            replaySelectedPipelineStateAfterSwap();
            if (pipelineEndObserved &&
                sameOwnership(pipelineEndOwnership, requestedOwnership)) {
                pipelineEndObserved = false;
                pipelineEndOwnership = {};
                audibleEndPublishedForOwnership = {};
            }
        }
        loopContinuationPipeline->releaseRetiredLoopHeadReaders();
        loopContinuationPipeline->releaseCacheReadRequirements();
        loopContinuationPendingOutput.clear();
        directLoopCutover.clear();
        return true;
    }

    void clearAppliedDirectLoop() noexcept {
        // Logical retirement preserves the callback's accepted physical role until owner reset.
        directLoopState.retireLogical();
    }

    [[nodiscard]] std::uint32_t directLoopRequiredLaneMask() const noexcept {
        if (!hasDesiredSnapshot || renderBank >= kNativePlaybackBankCount) {
            return 0U;
        }
        const BankRuntimeState &state = *bankStates[renderBank];
        if (state.appliedControlRevision != desiredSnapshot.controls.revision ||
            desiredSnapshot.controls.mixer.sourceMode == MixerSourceMode::Stems) {
            return requiredBankLaneMask(renderBank);
        }
        return CurrentSongPcmCache::kOriginalLaneMask;
    }

    [[nodiscard]] std::uint64_t claimNextDirectLoopPhysicalRevision() noexcept {
        if (nextDirectAtomicLoopPhysicalRevision ==
            std::numeric_limits<std::uint64_t>::max()) {
            return 0U;
        }
        ++nextDirectAtomicLoopPhysicalRevision;
        if (nextDirectAtomicLoopPhysicalRevision == 0U) {
            ++nextDirectAtomicLoopPhysicalRevision;
        }
        return nextDirectAtomicLoopPhysicalRevision;
    }

    [[nodiscard]] bool directLoopRangePermanentlyUnavailable(const AtomicLoopPolicySnapshot &policy) const noexcept {
        if (!policy.enabled) return false;
        // The independent bounded head is prepared by the existing serialized decoder owner.
        if (selectedLoopRangeReady(directLoopRequiredLaneMask(),
                policy.aSourceFrame, policy.bSourceFrameExclusive)) return false;
        if (!currentSongCache || currentSongCacheBank != renderBank) {
            return currentSongCacheStorageStatus != CurrentSongCacheStorageStatus::NotRequested &&
                currentSongCacheStorageStatus != CurrentSongCacheStorageStatus::Admitted;
        }
        const auto required = directLoopRequiredLaneMask();
        if (currentSongCache->covers(required, policy.aSourceFrame,
                policy.bSourceFrameExclusive - policy.aSourceFrame)) return false;
        const auto cache = resources->snapshot();
        const auto admitted = cache.io.admittedLaneCount == 0U ? 0U :
            (1U << cache.io.admittedLaneCount) - 1U;
        return (cache.readFailureLaneMask & required) != 0U || cache.cancelled || currentSongCacheBuildFailed ||
            cache.allocationFailed || (required & ~admitted) != 0U ||
            (cache.completeLaneMask & required) == required;
    }

    [[nodiscard]] bool applyCacheBackedAtomicLoop(bool &ownsFixedPolicy) noexcept {
        ownsFixedPolicy = false;
        if (!selectionAssigned || renderBank >= kNativePlaybackBankCount ||
            directAtomicPolicyGeneration.load(std::memory_order_relaxed) !=
                requestedOwnership.generation) {
            return false;
        }

        // Engagement is inferred by the callback from emitted PCM tags.
        static_cast<void>(atomicLoopPolicy.adoptLatest(requestedOwnership.selectionToken));
        AtomicLoopPolicySnapshot policy = atomicLoopPolicy.adopted();
        if (loopPreparationFailure.revision != 0U &&
            (loopPreparationFailure.revision != policy.revision ||
                !sameOwnership(loopPreparationFailure.ownership, requestedOwnership))) {
            loopPreparationFailure = {};
            renderReadinessRequested.store(true, std::memory_order_release);
        }
        if (policy.selectionToken != requestedOwnership.selectionToken ||
            policy.revision == directLoopState.retiredRevision ||
            directAtomicPolicyGenerationRevision.load(std::memory_order_acquire) !=
                policy.revision ||
            directAtomicPolicyGeneration.load(std::memory_order_relaxed) !=
                requestedOwnership.generation) {
            return false;
        }
        if (directLoopRangePermanentlyUnavailable(policy)) {
            output.retireLoopDemand(output.handle, policy.revision,
                requestedOwnership.generation, requestedOwnership.selectionToken);
            policy.enabled = false;
            policy.aSourceFrame = 0U;
            policy.bSourceFrameExclusive = 0U;
        }
        ownsFixedPolicy = true;
        bool progressed = false;
        if (directLoopState.observedRevision != policy.revision) {
            // Observation is the worker's ingress-service fence, not proof that a physical loop
            // was installed. A frame-zero cache may still belong to the spare bank while the
            // audible owner must keep receiving Original PCM.
            directLoopState.observedRevision = policy.revision;
            progressed = true;
        }
        if ((currentSongCache == nullptr || currentSongCacheBank != renderBank) &&
            !selectedLoopRangeReady(directLoopRequiredLaneMask(),
                policy.enabled ? policy.aSourceFrame : directLoopState.aFrame,
                policy.enabled ? policy.bSourceFrameExclusive : directLoopState.bFrame)) {
            // An installed producer must keep its physical loop until a linear replacement is
            // ready, including the brief cache-commit/owner-publication interval.
            if (!policy.enabled) {
                const auto *beforePipeline = bankStates[renderBank]->pipeline.get();
                const auto beforePhysicalRevision = directLoopState.physicalRevision;
                if (cancelDirectLoopStage()) {
                    // The unused spare cannot pin the head needed by the linear replacement.
                    // Keep the actual active loop until that replacement has its required PCM.
                    if (directLoopState.engaged) return progressed ||
                        bankStates[renderBank]->pipeline.get() != beforePipeline ||
                        directLoopState.physicalRevision != beforePhysicalRevision;
                    directLoopState.retiredRevision = policy.revision;
                    clearAppliedDirectLoop();
                    ownsFixedPolicy = false;
                    return true;
                }
            }
            return progressed;
        }
        const NativeAudioPresentedPosition presented = output.presentedPosition(output.handle);
        if (directLoopState.engaged && presented.valid() &&
            presented.generation == requestedOwnership.generation &&
            presented.selectionToken == requestedOwnership.selectionToken &&
            presented.loopPolicyRevision == directLoopState.physicalRevision &&
            presented.loopPolicyAdoptedEnabled &&
            presented.loopPolicyAdoptedAFrame == directLoopState.aFrame &&
            presented.loopPolicyAdoptedBFrameExclusive == directLoopState.bFrame) {
            const auto provenRevision = std::min(policy.revision, presented.loopPolicyAdoptedRevision);
            if (provenRevision > directLoopState.logicalRevision) {
                directLoopState.logicalRevision = provenRevision;
                progressed = true;
            }
        }

        const auto capture = !policy.enabled ? NativeAudioPendingLoopCapture{true, 0U} :
            output.pendingLoopCapture(output.handle, NativeAudioOutputLoopPolicy{
            .revision = policy.revision, .generation = requestedOwnership.generation,
            .selectionToken = policy.selectionToken, .enabled = policy.enabled, .physicalReady = false,
            .aTimelineFrame = policy.aSourceFrame, .bTimelineFrameExclusive = policy.bSourceFrameExclusive,
            .heldBGestureId = policy.heldBGestureId});
        if (!capture.available) return progressed;
        const bool capturePending = capture.revision != 0U;

        // An expansion needs replacement PCM only when the old B (or its backward jump) is
        // already queued. Otherwise the active producer and every unread frame are still linear,
        // so extending its endpoint in place is both exact and immediately callback-visible.
        const bool installedBExpansion = policy.enabled &&
            directLoopState.engaged &&
            directLoopState.physicalRevision != 0U &&
            directLoopState.aFrame == policy.aSourceFrame &&
            policy.bSourceFrameExclusive > directLoopState.bFrame;
        if (!capturePending && installedBExpansion && presented.valid() &&
            presented.selectionToken == requestedOwnership.selectionToken &&
            presented.generation == requestedOwnership.generation &&
            presented.lastTimelineFrame >= 0) {
            const NativeAudioLoopBoundaryCoverage oldBoundary = output.loopBoundaryCoverage(
                output.handle,
                requestedOwnership.generation,
                requestedOwnership.selectionToken,
                directLoopState.bFrame,
                directLoopState.physicalRevision);
            // Cancellation can adopt the callback's continuation and swap the active pipeline.
            if (!oldBoundary.boundaryQueued && cancelDirectLoopStage()) {
                SongRenderPipeline &active = *bankStates[renderBank]->pipeline;
                const double producerFrame = active.sourceTimelineFrame();
                const bool producerHasNotWrapped = producerFrame + 0.000001 >=
                    static_cast<double>(presented.lastTimelineFrame);
                if (producerHasNotWrapped &&
                    producerFrame < static_cast<double>(policy.bSourceFrameExclusive) &&
                    active.retargetAtomicLoopEnd(
                        requestedOwnership,
                        policy.aSourceFrame,
                        policy.bSourceFrameExclusive) == SongRenderLoopStatus::Accepted) {
                    const NativeAudioOutputLoopPolicy expanded{
                        .revision = policy.revision,
                        .generation = requestedOwnership.generation,
                        .selectionToken = requestedOwnership.selectionToken,
                        .enabled = true,
                        .physicalReady = true,
                        .aTimelineFrame = policy.aSourceFrame,
                        .bTimelineFrameExclusive = policy.bSourceFrameExclusive,
                        .continuationRevision = directLoopState.physicalRevision,
                        .acceptedAtMonotonicMicros = policy.acceptedAtMonotonicMicros,
                        .publishedAtMonotonicMicros =
                            directAtomicPolicyPublishedAt(policy.revision),
                        .inputEventAtMonotonicMicros = policy.inputEventAtMonotonicMicros,
                        .heldBGestureId = policy.heldBGestureId,
                        .boundaryArmedAtPublication = policy.boundaryArmedAtPublication,
                    };
                    if (output.publishLoopPolicy(output.handle, expanded)) {
                        directLoopState.logicalRevision = policy.revision;
                        directLoopState.bFrame = policy.bSourceFrameExclusive;
                        directLoopState.observedRevision = policy.revision;
                        return true;
                    }
                }
            }
        }

        if (directLoopCutover.policy.revision != 0U &&
            (directLoopCutover.allStemBusesPrepared || desiredSnapshot.controls.mixer.sourceMode == MixerSourceMode::Original) &&
            directLoopCutover.cacheKey == directCacheIdentity() &&
            !directLoopPreparationIsCurrent(directLoopCutover)) {
            const auto refreshed = loopContinuationPipeline->refreshAtomicLoopContinuationControlsFrom(
                *bankStates[renderBank]->pipeline, directLoopCutover.allStemBusesPrepared ? 0U :
                    CurrentSongPcmCache::kOriginalLaneMask);
            if (refreshed == SongRenderLoopStatus::Preparing) return true;
            if (refreshed == SongRenderLoopStatus::Accepted) {
                // Source/gain changes belong to the final mixer. Keep the already primed compatible
                // continuation and update its policy, including any not-yet-enqueued block.
                const auto &state = *bankStates[renderBank];
                const auto endpoint = loopContinuationPipeline->prepareLateMixEndpoint(requestedOwnership);
                if (endpoint.valid()) {
                    directLoopCutover.controlRevision = state.appliedControlRevision;
                    directLoopCutover.controlApplicationEpoch = state.appliedControlApplicationEpoch;
                    directLoopCutover.beatGridRevision = state.appliedBeatGridRevision;
                    directLoopCutover.sourceIntentRevision = desiredSnapshot.controls.sourceIntentRevision;
                    directLoopCutover.stemIntentRevision = desiredSnapshot.controls.stemIntentRevision;
                    if (loopContinuationPendingOutput.remainingFrames != 0U) {
                        auto &pending = loopContinuationPendingOutput;
                        pending.controlRevision = endpoint.canonicalControlRevision;
                        pending.sourceIntentRevision = endpoint.sourceIntentRevision;
                        pending.stemIntentRevision = endpoint.stemIntentRevision;
                        pending.lateMixControl = stampLateMixControl(requestedOwnership, endpoint);
                        pending.lateMixControlPublished = false;
                    }
                    progressed = true;
                }
            }
        }

        // Retarget the prepared producer before callback evidence can promote it. The callback
        // reads B directly from the demand mailbox, so promoting the older producer first would
        // briefly install a policy that no longer matches the boundary it just enforced.
        if ((directLoopCutover.policy.revision != 0U) && (!directLoopStageRealizes(policy) ||
            (capturePending && directLoopCutover.policy.continuationTimelineFrame != policy.aSourceFrame))) {
            if (retargetOpenDirectLoopStage(policy, capturePending)) {
                progressed = true;
            } else {
                if (!cancelDirectLoopStage()) return progressed;
                progressed = true;
            }
        }

        // The exact callback role acknowledgement releases producer writes before hardware presentation.
        if (directLoopCutover.commitPublished()) {
            NativeAudioOutputLoopPolicy installed = directLoopCutover.policy;
            const bool exactPresentedRole = presented.valid() &&
                presented.selectionToken == installed.selectionToken && presented.generation == installed.generation &&
                presented.loopPolicyRevision == installed.physicalRevision();
            bool callbackInstalledNewerB =
                exactPresentedRole && presented.loopPolicyAdoptedEnabled &&
                presented.loopPolicyAdoptedRevision >= installed.revision &&
                presented.loopPolicyAdoptedAFrame == installed.aTimelineFrame &&
                presented.loopPolicyAdoptedBFrameExclusive >
                    presented.loopPolicyAdoptedAFrame &&
                installed.continuationTimelineFrame == installed.aTimelineFrame &&
                presented.loopPolicyAdoptedBFrameExclusive -
                        presented.loopPolicyAdoptedAFrame >
                    directLoopCutover.stagedFrames;
            if (callbackInstalledNewerB &&
                loopContinuationPipeline->retargetAtomicLoopEnd(
                    requestedOwnership,
                    installed.aTimelineFrame,
                    presented.loopPolicyAdoptedBFrameExclusive) ==
                    SongRenderLoopStatus::Accepted) {
                installed.revision = presented.loopPolicyAdoptedRevision;
                installed.bTimelineFrameExclusive =
                    presented.loopPolicyAdoptedBFrameExclusive;
            } else {
                callbackInstalledNewerB = false;
            }
            const bool installedEngaged = directLoopCutover.continuationEngaged;
            bankStates[renderBank]->pipeline.swap(loopContinuationPipeline);
            if (!output.releaseLoopCutoverWrites(
                    output.handle,
                    installed.generation,
                    installed.physicalRevision())) {
                bankStates[renderBank]->pipeline.swap(loopContinuationPipeline);
            } else {
                pendingOutput.clear();
                loopContinuationPendingOutput.clear();
                const bool retainCompleteCycle = reusableLoopHead.completeTraversal(
                        desiredSnapshot.controls.tempo, desiredSnapshot.controls.pitchSemitones) &&
                    reusableLoopHead.plan().cacheKey == directCacheIdentity() &&
                    reusableLoopHead.plan().policy.physicalRevision() == installed.physicalRevision() &&
                    reusableLoopHead.plan().policy.aTimelineFrame == installed.aTimelineFrame &&
                    reusableLoopHead.plan().policy.bTimelineFrameExclusive == installed.bTimelineFrameExclusive &&
                    bankStates[renderBank]->pipeline->sourceTimelineFrame() == static_cast<double>(installed.bTimelineFrameExclusive) &&
                    loopContinuationPipeline->sourceTimelineFrame() == static_cast<double>(installed.bTimelineFrameExclusive);
                if (!retainCompleteCycle) {
                    reusableLoopHead.discard();
                    loopContinuationPipeline->releaseRetiredLoopHeadReaders();
                }
                loopContinuationPipeline->releaseCacheReadRequirements();
                // Retargeting the producer before this swap does not acknowledge that logical
                // revision in the callback. Keep its actual acknowledgement so the exact-endpoint
                // path below publishes a B edit that arrived while this role was being adopted.
                directLoopState.adoptPhysicalReceipt(installed,
                    exactPresentedRole ? presented.loopPolicyAdoptedRevision : 0U,
                    directLoopCutover.preparationWrapBaseline, installedEngaged);
                directLoopCutover.clear();
                replaySelectedPipelineStateAfterSwap();
                if (pipelineEndObserved &&
                    sameOwnership(pipelineEndOwnership, requestedOwnership)) {
                    pipelineEndObserved = false;
                    pipelineEndOwnership = {};
                    audibleEndPublishedForOwnership = {};
                }
                progressed = true;
            }
        }

        const bool retireInstalledLoop = !policy.enabled &&
            directLoopState.engaged && directLoopState.bFrame != 0U;
        if (!policy.enabled && !retireInstalledLoop) {
            if (!cancelDirectLoopStage()) return progressed;
            // Cancellation may discover an enabled role already adopted by the callback.
            if (directLoopState.engaged) return true;
            directLoopState.retiredRevision = policy.revision;
            directLoopState.observedRevision = policy.revision;
            clearAppliedDirectLoop();
            ownsFixedPolicy = false;
            return true;
        }

        // A logical revision with identical endpoints reuses the installed physical producer.
        // Rebuilding it would create a race in which the same A/B briefly has two PCM owners.
        const bool installedExactDirectLoop = policy.enabled &&
            directLoopState.engaged &&
            directLoopState.physicalRevision != 0U &&
            directLoopState.aFrame == policy.aSourceFrame &&
            directLoopState.bFrame == policy.bSourceFrameExclusive;
        if (installedExactDirectLoop) {
            if (directLoopState.logicalRevision != policy.revision) {
                const NativeAudioOutputLoopPolicy retargeted{
                    .revision = policy.revision,
                    .generation = requestedOwnership.generation,
                    .selectionToken = requestedOwnership.selectionToken,
                    .enabled = true,
                    .physicalReady = true,
                    .aTimelineFrame = policy.aSourceFrame,
                    .bTimelineFrameExclusive = policy.bSourceFrameExclusive,
                    .continuationRevision = directLoopState.physicalRevision,
                    .acceptedAtMonotonicMicros = policy.acceptedAtMonotonicMicros,
                    .publishedAtMonotonicMicros =
                        directAtomicPolicyPublishedAt(policy.revision),
                    .inputEventAtMonotonicMicros = policy.inputEventAtMonotonicMicros,
                    .heldBGestureId = policy.heldBGestureId,
                    .boundaryArmedAtPublication = policy.boundaryArmedAtPublication,
                };
                if (output.publishLoopPolicy(output.handle, retargeted)) {
                    directLoopState.logicalRevision = policy.revision;
                    progressed = true;
                }
                // A rejected redundant in-place tuple cannot block the next independently owned A head.
            }
            directLoopState.observedRevision = policy.revision;
            // Keep the inactive role primed at A for the next cycle. A B-handle MOVE can then
            // bind its newest boundary to already-ready PCM instead of paying the reset/render
            // cost after the finger arrives. The stage checks below make this one reusable spare,
            // not a queue of speculative loop revisions.
        }

        const bool exactPresentedOwner = presented.valid() &&
            presented.selectionToken == requestedOwnership.selectionToken &&
            presented.generation == requestedOwnership.generation &&
            presented.lastTimelineFrame >= 0;
        const bool dormantPast = policy.enabled &&
            !policy.boundaryArmedAtPublication && exactPresentedOwner &&
            (!directLoopState.engaged ||
                presented.loopPolicyRevision == directLoopState.physicalRevision) &&
            static_cast<std::uint64_t>(presented.lastTimelineFrame) >=
                policy.bSourceFrameExclusive;
        if (policy.enabled && !policy.boundaryArmedAtPublication &&
            !directLoopState.engaged && presented.valid() &&
            presented.selectionToken == requestedOwnership.selectionToken &&
            presented.generation == requestedOwnership.generation &&
            presented.lastTimelineFrame >= 0 &&
            static_cast<std::uint64_t>(presented.lastTimelineFrame) >=
                policy.bSourceFrameExclusive) {
            // A seek's A head can be prepared before its exact callback proves B is behind it.
            // Retire it at exact linear EOS; a nonterminal spare can still serve a pending boundary.
            if (directLoopCutover.policy.revision != 0U && pipelineEndObserved &&
                sameOwnership(pipelineEndOwnership, requestedOwnership))
                return cancelDirectLoopStage() || progressed;
            return progressed;
        }

        const bool installedLoop = directLoopState.engaged &&
            directLoopState.bFrame != 0U;
        const bool transitionAtInstalledB = !capturePending && installedLoop &&
            (!policy.enabled || dormantPast ||
             policy.bSourceFrameExclusive > directLoopState.bFrame);
        SongRenderPipeline &active = *bankStates[renderBank]->pipeline;
        const auto originalEnd = active.laneEndSnapshot(SongRenderLane::Original);
        const std::uint64_t physicalEnd = sameOwnership(active.ownership(), requestedOwnership) &&
                originalEnd.marked && originalEnd.ingressEpoch == active.ingressEpoch()
            ? originalEnd.endTimelineFrameExclusive
            : resources->snapshot().originalEndFrameExclusive;
        const auto playbackEnd = bankStates[renderBank]->appliedIntent.playbackEndFrameExclusive;
        const auto songEnd = playbackEnd == 0U ? physicalEnd :
            (physicalEnd == 0U ? playbackEnd : std::min(playbackEnd, physicalEnd));
        // At EOS, replace the final real frame itself; there is no PCM after B to stage.
        const std::uint64_t replacementStart = transitionAtInstalledB
            ? (!policy.enabled && songEnd != 0U
                ? std::min(directLoopState.bFrame, songEnd - 1U)
                : directLoopState.bFrame)
            : policy.aSourceFrame;
        // Expansion/clear continues from the old B; contraction and A movement prepare exact A.
        const bool continuationEngaged = policy.enabled &&
            replacementStart < policy.bSourceFrameExclusive;

        const std::uint32_t requiredLaneMask = directLoopRequiredLaneMask();
        if (!directCacheCovers(requiredLaneMask, replacementStart, 1U)) {
            if (directLoopState.observedRevision != policy.revision) {
                directLoopState.observedRevision = policy.revision;
                progressed = true;
            }
            return progressed;
        }
        if (directLoopCutover.policy.revision != 0U && !directLoopCutover.stageOpen() &&
            !directLoopCutover.commitPublished()) {
            directLoopCutover.phase = beginSpareOutputRole(SpareOutputTransition::Loop,
                requestedOwnership.generation, directLoopCutover.policy.physicalRevision())
                ? DirectLoopCutoverPlan::Phase::Staged : DirectLoopCutoverPlan::Phase::Preparing;
            return directLoopCutover.stageOpen() || progressed;
        }
        if (directLoopCutover.stageOpen() || directLoopCutover.commitPublished()) {
            return progressed;
        }
        if (automaticSuccessor.phase != AutomaticSuccessorPhase::Idle) return progressed;
        if (!desiredSnapshot.selectionAssigned ||
            bankIndex(desiredSnapshot.selectedBank) != renderBank ||
            !sameOwnership(desiredSnapshot.selectedOwnership, requestedOwnership)) {
            // A newer owner command may be waiting for a callback-owned continuation to release
            // its writes. Adoption above is still required, but do not prime another spare for
            // the superseded owner before the next worker cycle can apply that command.
            return progressed;
        }

        // A new tune must finish its audible prime before allocating/priming the spare.
        if (active.timePitchDiagnostics().timelineState == TimePitchTimelineState::PrimeRequired &&
            queuedFramesForGeneration(requestedOwnership.generation) < outputTargetFrames()) {
            return progressed;
        }
        const auto rejectPreparation = [&](NativePlaybackLoopPreparationFailureReason reason) noexcept {
            loopPreparationFailure = {requestedOwnership, policy.revision, reason};
            directLoopState.retiredRevision = policy.revision;
            output.retireLoopDemand(output.handle, policy.revision,
                requestedOwnership.generation, requestedOwnership.selectionToken);
            renderReadinessRequested.store(true, std::memory_order_release);
            return true;
        };
        bool preparationFailed = false;
        if (!resources->preparePipeline(kNativePlaybackBankCount, policy.revision,
                active.stemLaneCount(), loopContinuationPipeline, &preparationFailed))
            return preparationFailed ? rejectPreparation(NativePlaybackLoopPreparationFailureReason::Pipeline) : progressed;
        loopContinuationPipeline->setCacheReadPreparation(true);
        if (!resources->prepareLanes(kNativePlaybackBankCount, policy.revision,
                active.stemLaneCount(), *loopContinuationPipeline, &preparationFailed))
            return preparationFailed ? rejectPreparation(NativePlaybackLoopPreparationFailureReason::Lanes) : progressed;
        const bool sameOwnerCompleteCycle = reusableLoopHead.ready() &&
            reusableLoopHead.plan().policy.generation == requestedOwnership.generation &&
            reusableLoopHead.plan().cacheKey == directCacheIdentity() &&
            reusableLoopHead.plan().policy.bTimelineFrameExclusive == policy.bSourceFrameExclusive &&
            reusableLoopHead.completeTraversal(desiredSnapshot.controls.tempo, desiredSnapshot.controls.pitchSemitones) &&
            desiredSnapshot.controls.mixer.metronome.mode == MetronomeMode::Off &&
            (desiredSnapshot.controls.tempo != 1.F || desiredSnapshot.controls.pitchSemitones != 0.F) &&
            sameOwnership(loopContinuationPipeline->ownership(), requestedOwnership) &&
            active.sourceTimelineFrame() == static_cast<double>(policy.bSourceFrameExclusive) &&
            loopContinuationPipeline->sourceTimelineFrame() == static_cast<double>(policy.bSourceFrameExclusive);
        const bool mayReuseHead = currentSongCache && reusableLoopHead.ready() && policy.enabled &&
            (reusableLoopHead.plan().allStemBusesPrepared ||
                desiredSnapshot.controls.mixer.sourceMode == MixerSourceMode::Original) &&
            reusableLoopHead.plan().policy.enabled &&
            reusableLoopHead.plan().policy.selectionToken == requestedOwnership.selectionToken &&
            (reusableLoopHead.plan().policy.generation < requestedOwnership.generation || sameOwnerCompleteCycle) &&
            reusableLoopHead.plan().policy.aTimelineFrame == policy.aSourceFrame &&
            reusableLoopHead.fitsBoundary(policy.bSourceFrameExclusive,
                desiredSnapshot.controls.mixer.defaultDeclickFrames) &&
            replacementStart == policy.aSourceFrame &&
            reusableLoopHead.plan().policy.continuationTimelineFrame == replacementStart &&
            reusableLoopHead.plan().cacheKey.songIdentityHash == currentSongCacheKey.songIdentityHash &&
            reusableLoopHead.plan().cacheKey.originalContentHash == currentSongCacheKey.originalContentHash &&
            reusableLoopHead.plan().cacheKey.stemPackageHash == currentSongCacheKey.stemPackageHash &&
            reusableLoopHead.declickFrames() == managedLoopIntent.declickFrames;
        const auto refreshed = mayReuseHead
            ? loopContinuationPipeline->refreshAtomicLoopContinuationControlsFrom(active,
                reusableLoopHead.plan().allStemBusesPrepared ? requiredBankLaneMask(renderBank) :
                    CurrentSongPcmCache::kOriginalLaneMask)
            : SongRenderLoopStatus::InvalidOwnership;
        if (refreshed == SongRenderLoopStatus::Preparing) return true;
        const bool reuseHead = refreshed == SongRenderLoopStatus::Accepted &&
            (reusableLoopHead.plan().policy.bTimelineFrameExclusive == policy.bSourceFrameExclusive ||
             loopContinuationPipeline->retargetAtomicLoopEnd(requestedOwnership,
                policy.aSourceFrame, policy.bSourceFrameExclusive) == SongRenderLoopStatus::Accepted);
#if defined(STEMWISE_CACHE_IO_PROFILING)
        {
            AudioTrace trace("StemWise.audio.loop.reuse");
            trace.counter("StemWise.audio.loop.generation", static_cast<std::int64_t>(requestedOwnership.generation));
            trace.counter("StemWise.audio.loop.logical_revision", static_cast<std::int64_t>(policy.revision));
            trace.counter("StemWise.audio.loop.a", static_cast<std::int64_t>(policy.aSourceFrame));
            trace.counter("StemWise.audio.loop.b", static_cast<std::int64_t>(policy.bSourceFrameExclusive));
            trace.counter("StemWise.audio.loop.active_source", static_cast<std::int64_t>(active.sourceTimelineFrame()));
            trace.counter("StemWise.audio.loop.spare_source", static_cast<std::int64_t>(loopContinuationPipeline->sourceTimelineFrame()));
            trace.counter("StemWise.audio.loop.active_at_b", active.sourceTimelineFrame() == static_cast<double>(policy.bSourceFrameExclusive));
            trace.counter("StemWise.audio.loop.spare_at_b", loopContinuationPipeline->sourceTimelineFrame() == static_cast<double>(policy.bSourceFrameExclusive));
            trace.counter("StemWise.audio.loop.cache_phase", reusableLoopHead.profilingPhase());
            trace.counter("StemWise.audio.loop.cache_frames", reusableLoopHead.profilingFrames());
            trace.counter("StemWise.audio.loop.cached_generation", static_cast<std::int64_t>(reusableLoopHead.plan().policy.generation));
            trace.counter("StemWise.audio.loop.cache_key_matches", reusableLoopHead.plan().cacheKey == directCacheIdentity());
            trace.counter("StemWise.audio.loop.cache_complete", reusableLoopHead.completeTraversal(
                desiredSnapshot.controls.tempo, desiredSnapshot.controls.pitchSemitones));
            trace.counter("StemWise.audio.loop.current_cache", currentSongCache != nullptr);
            trace.counter("StemWise.audio.loop.all_stem_buses", reusableLoopHead.plan().allStemBusesPrepared);
            trace.counter("StemWise.audio.loop.requested_source", static_cast<std::int64_t>(desiredSnapshot.controls.mixer.sourceMode));
            trace.counter("StemWise.audio.loop.tempo_milli", static_cast<std::int64_t>(desiredSnapshot.controls.tempo * 1000.F));
            trace.counter("StemWise.audio.loop.pitch_milli", static_cast<std::int64_t>(desiredSnapshot.controls.pitchSemitones * 1000.F));
            trace.counter("StemWise.audio.loop.metronome", static_cast<std::int64_t>(desiredSnapshot.controls.mixer.metronome.mode));
            trace.counter("StemWise.audio.loop.same_owner_complete", sameOwnerCompleteCycle);
            trace.counter("StemWise.audio.loop.reuse_head", reuseHead);
        }
#endif
        if (!reuseHead) reusableLoopHead.invalidateReuse();
        const bool canonicalRangeReady = currentSongCache && currentSongCacheBank == renderBank &&
            currentSongCache->covers(requiredLaneMask, policy.aSourceFrame, policy.bSourceFrameExclusive - policy.aSourceFrame);
        auto boundedHead = canonicalRangeReady ? LoopHeadCache::ReadView{} :
            active.acquireRetainedLoopHead(directCacheIdentity(), requiredLaneMask,
                continuationEngaged ? policy.aSourceFrame : replacementStart,
                continuationEngaged ? policy.bSourceFrameExclusive : replacementStart + 1U);
        if (!canonicalRangeReady && !boundedHead.valid()) boundedHead = preparedLoopHeadForOwner(directCacheIdentity());
        const SongRenderLoopStatus status = reuseHead ? SongRenderLoopStatus::Accepted :
            loopContinuationPipeline->prepareAtomicLoopContinuationFrom(
                active,
                requestedOwnership,
                continuationEngaged,
                policy.aSourceFrame,
                policy.bSourceFrameExclusive,
                replacementStart,
                managedLoopIntent.declickFrames,
                requiredLaneMask,
                nullptr, std::move(boundedHead));
        if (status == SongRenderLoopStatus::Preparing) return true;
        if (status != SongRenderLoopStatus::Accepted &&
            status != SongRenderLoopStatus::Disabled) {
            return progressed;
        }
        const std::uint64_t physicalRevision = claimNextDirectLoopPhysicalRevision();
        if (physicalRevision == 0U) return progressed;

        const BankRuntimeState &state = *bankStates[renderBank];
        const std::uint64_t cutoverFrame = installedLoop
            ? (!policy.enabled ? replacementStart : directLoopState.bFrame)
            : policy.bSourceFrameExclusive;
        loopContinuationPendingOutput.clear();
        directLoopCutover.policy = {
            .revision = policy.revision,
            .generation = requestedOwnership.generation,
            .selectionToken = requestedOwnership.selectionToken,
            .enabled = policy.enabled,
            .physicalReady = true,
            .aTimelineFrame = policy.aSourceFrame,
            .bTimelineFrameExclusive = policy.bSourceFrameExclusive,
            // An expanded B is outside the currently looping producer. Swap at the first boundary
            // both tuples can reach; the replacement then owns the expanded range.
            .cutoverTimelineFrameExclusive = cutoverFrame,
            .continuationTimelineFrame = replacementStart,
            .cutoverSourceRevision = directLoopState.physicalRevision,
            .continuationRevision = physicalRevision,
            .acceptedAtMonotonicMicros = policy.acceptedAtMonotonicMicros,
            .publishedAtMonotonicMicros =
                directAtomicPolicyPublishedAt(policy.revision),
            .inputEventAtMonotonicMicros = policy.inputEventAtMonotonicMicros,
            .heldBGestureId = policy.heldBGestureId,
            .boundaryArmedAtPublication = policy.boundaryArmedAtPublication,
            // Tuned playback needs the primed A producer on every cycle. Neutral playback
            // wraps cheaply in place and retains this role for the next handle edit.
            .nextRevisionOnly = installedExactDirectLoop &&
                desiredSnapshot.controls.tempo == 1.0F &&
                desiredSnapshot.controls.pitchSemitones == 0.0F,
        };
        directLoopCutover.continuationEngaged = continuationEngaged;
        directLoopCutover.phase = beginSpareOutputRole(
            SpareOutputTransition::Loop, requestedOwnership.generation, physicalRevision)
            ? DirectLoopCutoverPlan::Phase::Staged : DirectLoopCutoverPlan::Phase::Preparing;
        directLoopCutover.stagedFrames = 0U;
        directLoopCutover.preparationWrapBaseline =
            loopContinuationPipeline->diagnostics().loopWraps;
        directLoopCutover.cacheKey = directCacheIdentity();
        directLoopCutover.controlRevision = state.appliedControlRevision;
        directLoopCutover.controlApplicationEpoch =
            state.appliedControlApplicationEpoch;
        directLoopCutover.beatGridRevision = state.appliedBeatGridRevision;
        directLoopCutover.allStemBusesPrepared =
            reuseHead ? reusableLoopHead.plan().allStemBusesPrepared :
                directCacheCovers(requiredBankLaneMask(renderBank),
                    directLoopCutover.policy.aTimelineFrame,
                    active.loopContinuationHeadFrames(directLoopCutover.policy.aTimelineFrame,
                        directLoopCutover.policy.bTimelineFrameExclusive));
        directLoopCutover.sourceIntentRevision =
            desiredSnapshot.controls.sourceIntentRevision;
        directLoopCutover.stemIntentRevision =
            desiredSnapshot.controls.stemIntentRevision;
        reusableLoopHead.begin(reuseHead, policy.enabled && continuationEngaged &&
            replacementStart == policy.aSourceFrame, managedLoopIntent.declickFrames);
        if (!reuseHead && !directLoopCutover.stageOpen() && handoffStageOpen) {
            // The seek occupies the spare output role until its first callback. Prime A now,
            // using the already allocated pending block, so an immediate B capture does not
            // incur a cold DSP prime after that callback. The full handoff reserve is unchanged.
            static_cast<void>(renderOneOutputBlock(*loopContinuationPipeline,
                loopContinuationPendingOutput, OutputRenderTarget::LoopContinuation,
                configuration.pipeline.maximumBlockFrames,
                0U, configuration.minimumOutputReserveFrames(), false, {}));
        }
        return true;
    }
    [[nodiscard]] bool advanceManagedLoopProtocol() noexcept {
        // Attribute any already-published callback evidence to the loop policy that produced it
        // before a newer intent can replace that policy in this worker turn.
        bool progressed = managedLoopAdopted && consumeManagedLoopCallbackEvidence();
        progressed = consumeManagedLoopIntent() || progressed;
        bool cacheBackedFixedPolicy = false;
        progressed = applyCacheBackedAtomicLoop(cacheBackedFixedPolicy) || progressed;
        if (!managedLoopAdopted) {
            publishManagedLoopSnapshot();
            return progressed;
        }
        if (cacheBackedFixedPolicy) {
            publishManagedLoopSnapshot();
            return progressed;
        }
        if (directLoopPolicyOwnsSelectedOwner()) {
            // The direct A/B command remains authoritative even while its current-song cache is
            // temporarily rebuilding or bound to the spare bank. Never let the retained legacy
            // state machine install an older physical loop during that cache gap.
            publishManagedLoopSnapshot();
            return progressed;
        }
        progressed = acceptManagedLoopCache() || progressed;
        progressed = resolveManagedOriginalEos() || progressed;
        progressed = consumeManagedLoopIngressReady() || progressed;
        progressed = applyManagedLoopDirective() || progressed;
        progressed = handleManagedLoopBoundary() || progressed;
        progressed = applyManagedLoopDirective() || progressed;
        progressed = observeManagedLoopPosition() || progressed;
        progressed = applyManagedLoopDirective() || progressed;
        progressed = consumeManagedLoopCallbackEvidence() || progressed;
        publishManagedLoopSnapshot();
        return progressed;
    }





    [[nodiscard]] bool currentOutputMayStopAtNaturalEnd(
        const SongRenderOwnership &ownership) const noexcept {
        if (!validOwnership(ownership) || commandMailbox.hasUnreadPublication()) return false;
        if (spareOutputPriority() == SpareOutputTransition::Loop) {
            const auto &clear = directLoopCutover;
            const bool terminalClear = !clear.policy.enabled &&
                clear.policy.generation == ownership.generation &&
                clear.policy.selectionToken == ownership.selectionToken &&
                clear.policy.revision == selectedOwnerDirectLoopPolicyRevision() &&
                !clear.continuationEngaged && clear.endObserved && clear.stagedFrames != 0U &&
                loopContinuationPendingOutput.remainingFrames == 0U &&
                directLoopPreparationIsCurrent(clear);
            if (!terminalClear) return false;
        }

        // Source EOS is not automatically a transport stop. If the queue expects a native
        // successor, an empty callback is a real continuity failure and must remain visible to the
        // zero-underrun gate. Consult both desired intent and the physical stage so a failed/late
        // plan cannot be mislabeled as intentional silence.
        const bool desiredSuccessor = hasDesiredSnapshot &&
            !desiredSuccessorEndedEmpty() &&
            desiredSnapshot.automaticSuccessor.enabled &&
            desiredSnapshot.automaticSuccessor.revision != 0U &&
            sameOwnership(
                desiredSnapshot.automaticSuccessor.outgoingOwnership,
                ownership);
        const bool physicalSuccessor =
            automaticSuccessor.phase != AutomaticSuccessorPhase::Idle &&
            sameOwnership(automaticSuccessor.outgoingOwnership, ownership);
        if (desiredSuccessor || physicalSuccessor) return false;

        const bool managedLoopPending = !directLoopPolicyOwnsSelectedOwner() &&
            managedLoopAdopted &&
            loopCycle.blocksNaturalEnd(
                managedLoopIntent.revision,
                {
                    .selectionToken = ownership.selectionToken,
                    .renderGeneration = ownership.generation,
                });
        return !managedLoopPending;
    }

    [[nodiscard]] bool reconcileTerminalSilenceAuthorization() noexcept {
        std::uint64_t authorizedGeneration = 0U;
        if (selectionAssigned && validOwnership(requestedOwnership) &&
            currentOutputMayStopAtNaturalEnd(requestedOwnership)) {
            authorizedGeneration = requestedOwnership.generation;
        } else if (selectionAssigned &&
            automaticSuccessor.phase == AutomaticSuccessorPhase::DrainCommitted &&
            automaticSuccessor.successorEndObserved && !automaticSuccessor.cancellationDesired &&
            sameOwnership(requestedOwnership, automaticSuccessor.outgoingOwnership) &&
            automaticIntentMatchesPhysicalPlan() &&
            currentOutputMayStopAtNaturalEnd(automaticSuccessor.successorOwnership)) {
            // A short successor needs its EOS tail before first presentation can promote it.
            // The output still requires that generation's exact terminal PCM/write boundary.
            authorizedGeneration = automaticSuccessor.successorOwnership.generation;
        }
        if (authorizedGeneration == terminalSilenceAuthorizedGeneration) return false;
        output.setTerminalSilenceAuthorization(
            output.handle,
            authorizedGeneration,
            authorizedGeneration != 0U);
        terminalSilenceAuthorizedGeneration = authorizedGeneration;
        return true;
    }

    [[nodiscard]] bool publishSelectedPreparedLateMixEndpoint() noexcept {
        if (!selectionAssigned || renderBank >= kNativePlaybackBankCount ||
            !validOwnership(requestedOwnership)) {
            return false;
        }
        BankRuntimeState &state = *bankStates[renderBank];
        if (!state.hasPendingLateMixEndpoint ||
            !sameOwnership(state.pendingLateMixOwnership, requestedOwnership) ||
            !sameOwnership(state.pipeline->ownership(), requestedOwnership) ||
            !state.pendingLateMixEndpoint.valid()) {
            return false;
        }

        // The endpoint is prepared only after the worker has admitted the exact selected owner,
        // canonical package and preview control-floor fence. Publish it without waiting for queue
        // space: already-prefilled render buses can then react on the next device callback.
        const NativeAudioLateMixControl control = stampLateMixControl(
            requestedOwnership,
            state.pendingLateMixEndpoint);
        if (!publishStampedLateMixControl(control)) return false;
        state.pendingLateMixEndpoint = {};
        state.pendingLateMixOwnership = {};
        state.hasPendingLateMixEndpoint = false;
        return true;
    }

    [[nodiscard]] NativeAudioLateMixControl stampLateMixControl(
        SongRenderOwnership ownership,
        const SongRenderLateMixEndpoint &endpoint) noexcept {
        lateMixControlPublicationSequence =
            saturatingIncremented(lateMixControlPublicationSequence);
        if (lateMixControlPublicationSequence == 0U) {
            lateMixControlPublicationSequence = 1U;
        }
        NativeAudioLateMixControl control{
            .publicationSequence = lateMixControlPublicationSequence,
            .generation = ownership.generation,
            .selectionToken = ownership.selectionToken,
            .canonicalControlRevision = endpoint.canonicalControlRevision,
            .sourceIntentRevision = endpoint.sourceIntentRevision,
            .stemIntentRevision = endpoint.stemIntentRevision,
            .stemGainPreviewPublicationSequence = endpoint.previewPublicationSequence,
            .stemGainPreviewCoversOfferSequence = endpoint.previewCoversOfferSequence,
            .stemGainPreviewActiveMask = endpoint.previewActiveMask,
            .sourceMode = endpoint.sourceMode == MixerSourceMode::Stems
                ? NativeAudioSourceMode::Stems
                : NativeAudioSourceMode::Original,
            .requiredStemMask = endpoint.requiredStemMask,
            .effectiveStemGains = endpoint.effectiveStemGains,
            .musicGain = endpoint.musicGain,
            .mixHeadroom = endpoint.mixHeadroom,
            .sourceRampFrames = endpoint.sourceRampFrames,
            .laneRampFrames = endpoint.laneRampFrames,
            .masterRampFrames = endpoint.masterRampFrames,
        };
        if (endpoint.previewPublicationSequence != 0U) {
            for (std::size_t lane = 0U; lane < control.stemGainPreviewGainBits.size(); ++lane) {
                control.stemGainPreviewGainBits[lane] =
                    std::bit_cast<std::uint32_t>(endpoint.previewGains[lane]);
            }
        }
        return control;
    }

    void recordPublishedLateMixControl(
        const NativeAudioLateMixControl &control) noexcept {
        std::size_t slot = publishedLateMixControls.size();
        for (std::size_t index = 0U; index < publishedLateMixControls.size(); ++index) {
            if (publishedLateMixControlReadable[index] &&
                publishedLateMixControls[index].generation == control.generation &&
                publishedLateMixControls[index].selectionToken == control.selectionToken) {
                slot = index;
                break;
            }
        }
        if (slot == publishedLateMixControls.size()) {
            for (std::size_t index = 0U; index < publishedLateMixControls.size(); ++index) {
                if (!publishedLateMixControlReadable[index]) {
                    slot = index;
                    break;
                }
            }
        }
        if (slot == publishedLateMixControls.size()) {
            slot = publishedLateMixControls[0].generation <=
                    publishedLateMixControls[1].generation
                ? 0U
                : 1U;
        }
        publishedLateMixControls[slot] = control;
        publishedLateMixControlReadable[slot] = true;
    }

    [[nodiscard]] bool publishStampedLateMixControl(
        const NativeAudioLateMixControl &control) noexcept {
        if (!output.publishLateMixControl(output.handle, control)) return false;
        recordPublishedLateMixControl(control);
        return true;
    }

    [[nodiscard]] bool publishedLateMixControlCovers(
        const PendingOutputPcm &pending) const noexcept {
        for (std::size_t index = 0U; index < publishedLateMixControls.size(); ++index) {
            if (!publishedLateMixControlReadable[index]) continue;
            const NativeAudioLateMixControl &published = publishedLateMixControls[index];
            if (published.generation != pending.ownership.generation ||
                published.selectionToken != pending.ownership.selectionToken ||
                published.canonicalControlRevision < pending.controlRevision ||
                published.sourceIntentRevision < pending.sourceIntentRevision ||
                published.stemIntentRevision < pending.stemIntentRevision) {
                continue;
            }
            if (published.canonicalControlRevision > pending.controlRevision ||
                published.sourceIntentRevision > pending.sourceIntentRevision ||
                published.stemIntentRevision > pending.stemIntentRevision ||
                published.stemGainPreviewPublicationSequence >
                    pending.lateMixControl.stemGainPreviewPublicationSequence) {
                return true;
            }
            const NativeAudioLateMixControl &required = pending.lateMixControl;
            if (published.stemGainPreviewPublicationSequence ==
                    required.stemGainPreviewPublicationSequence &&
                published.stemGainPreviewCoversOfferSequence ==
                    required.stemGainPreviewCoversOfferSequence &&
                published.stemGainPreviewActiveMask ==
                    required.stemGainPreviewActiveMask &&
                published.stemGainPreviewGainBits == required.stemGainPreviewGainBits &&
                published.sourceMode == required.sourceMode &&
                published.effectiveStemGains == required.effectiveStemGains &&
                published.requiredStemMask == required.requiredStemMask &&
                published.musicGain == required.musicGain &&
                published.mixHeadroom == required.mixHeadroom &&
                published.sourceRampFrames == required.sourceRampFrames &&
                published.laneRampFrames == required.laneRampFrames &&
                published.masterRampFrames == required.masterRampFrames) {
                return true;
            }
        }
        return false;
    }

    [[nodiscard]] bool stageLateMixControl(
        PendingOutputPcm &pending,
        const SongRenderResult &result,
        SongRenderOwnership ownership) noexcept {
        const SongRenderLateMixEndpoint &endpoint = result.lateMixEndpoint;
        if (!endpoint.valid() ||
            endpoint.canonicalControlRevision != result.controlRevision ||
            !sameOwnership(result.ownership, ownership)) {
            return false;
        }
        pending.sourceIntentRevision = endpoint.sourceIntentRevision;
        pending.stemIntentRevision = endpoint.stemIntentRevision;
        pending.lateMixControl = stampLateMixControl(ownership, endpoint);
        pending.lateMixControlPublished = false;
        return true;
    }

    [[nodiscard]] NativeRenderBusSubmission pendingSubmission(const PendingOutputPcm &pending) const noexcept {
        return NativeRenderBusSubmission{
                .generation = pending.ownership.generation,
                .selectionToken = pending.ownership.selectionToken,
                .payloadSequence = pending.fingerprintSequence,
                .payloadHash = pending.renderPayloadHash,
                .canonicalControlRevision = pending.controlRevision,
                .sourceIntentRevision = pending.sourceIntentRevision,
                .stemIntentRevision = pending.stemIntentRevision,
                .stemBusReadyMask = pending.stemBusReadyMask,
                .baseControl = pending.lateMixControl,
                .actualTempo = pending.actualTempo,
                .actualPitchSemitones = pending.actualPitchSemitones,
                .loopPolicyRevision = pending.loopPolicyRevision,
                .loopRevision = pending.loopRevision,
                .loopCycle = pending.loopCycle,
                .loopEnabled = pending.loopEnabled,
                .managedLoopContext = pending.managedLoopContext,
                .endOfStreamAfterInput = pending.endOfStreamAfterBlock,
                .renderSourceStartFrame = pending.sourceStartFrame,
                .renderFrameCount = static_cast<std::uint32_t>(pending.totalFrames),
                .renderFrameOffset = static_cast<std::uint32_t>(pending.offsetFrames),
                .sourceStartFrame = pending.remainingSourceStart(),
                .sourceEndFrameExclusive = pending.sourceEndFrameExclusive,
                .samples = pending.samples.data() + pending.offsetFrames * kStemwiseMultibusStride,
                .frameCount = pending.remainingFrames,
            };
    }

    [[nodiscard]] bool enqueuePendingOutput(
        PendingOutputPcm &pending,
        bool loopStage) noexcept {
        if (pending.remainingFrames == 0U ||
            runtimeStatus.load(std::memory_order_acquire) !=
                NativePlaybackRuntimeStatus::Running) {
            return false;
        }
        loopStage = loopStage &&
            (directLoopCutover.stageOpen() || directLoopCutover.commitPublished()) &&
            pending.loopPolicyRevision ==
                directLoopCutover.policy.physicalRevision() &&
            sameOwnership(pending.ownership, requestedOwnership);
        if (!pending.lateMixControlPublished) {
            if (((loopStage && !directLoopCutover.lateMixControlBound) ||
                 !publishedLateMixControlCovers(pending)) &&
                !publishStampedLateMixControl(pending.lateMixControl)) {
                return false;
            }
            if (loopStage) directLoopCutover.lateMixControlBound = true;
            pending.lateMixControlPublished = true;
        }
        const std::size_t accepted = (loopStage
            ? output.enqueueLoopCutover
            : output.enqueue)(
            output.handle,
            pendingSubmission(pending));
        const std::size_t boundedAccepted = std::min(accepted, pending.remainingFrames);
        if (boundedAccepted == 0U) {
            saturatingIncrement(outputBackpressureEvents);
            return false;
        }
        if (boundedAccepted < pending.remainingFrames) {
            saturatingIncrement(outputPartialAccepts);
        }
        pending.offsetFrames += boundedAccepted;
        pending.remainingFrames -= boundedAccepted;
        if (loopStage) directLoopCutover.stagedFrames += boundedAccepted;
        saturatingAdd(outputFramesAccepted, boundedAccepted);

        if (!validOwnership(acceptedOwnership)) {
            acceptedOwnership = pending.ownership;
            acceptedFirstFingerprintSequence = pending.fingerprintSequence;
            publishOwnershipDiagnostics();
        }
        if (pending.remainingFrames == 0U) {
            lastOutputFingerprintSequence.store(
                pending.fingerprintSequence,
                std::memory_order_relaxed);
            lastRenderPayloadHash.store(
                pending.renderPayloadHash,
                std::memory_order_relaxed);
            pending.clear();
            // A final rendered block carries authoritative EOS. Publish the already-prefilled
            // drain handoff immediately after that block enters the outgoing ring; waiting for a
            // later worker-cycle render probe leaves a callback-sized race at natural end.
            if (!loopStage && pipelineEndObserved &&
                sameOwnership(pipelineEndOwnership, requestedOwnership)) {
                static_cast<void>(commitAutomaticSuccessorIfReady());
            }
        }
        return true;
    }

#if defined(STEMWISE_CACHE_IO_PROFILING)
    void traceRejectedRender(const PendingOutputPcm &pending, const SongRenderResult &result,
        SongRenderOwnership expected, std::uint32_t requestedFrames, std::int64_t reason) const noexcept {
        AudioTrace trace("StemWise.audio.render.rejected");
        if (!trace.enabled()) return;
        // Reasons: 1 invalid block, 2 late-control staging, 3 unexpected successor status, 4 managed context.
        trace.counter("StemWise.audio.rejected.reason", reason);
        trace.counter("StemWise.audio.rejected.target", &pending == &loopContinuationPendingOutput ? 1 :
            &pending == &automaticSuccessorPendingOutput ? 2 : 0);
        trace.counter("StemWise.audio.rejected.generation", static_cast<std::int64_t>(expected.generation));
        trace.counter("StemWise.audio.rejected.token", static_cast<std::int64_t>(expected.selectionToken));
        trace.counter("StemWise.audio.rejected.status", static_cast<std::int64_t>(result.status));
        trace.counter("StemWise.audio.rejected.result_generation", static_cast<std::int64_t>(result.ownership.generation));
        trace.counter("StemWise.audio.rejected.result_token", static_cast<std::int64_t>(result.ownership.selectionToken));
        trace.counter("StemWise.audio.rejected.requested_frames", requestedFrames);
        trace.counter("StemWise.audio.rejected.frames", result.framesProduced);
        trace.counter("StemWise.audio.rejected.fingerprint_frames", result.payloadFingerprint.frameCount);
        trace.counter("StemWise.audio.rejected.fingerprint_sequence", static_cast<std::int64_t>(result.payloadFingerprint.sequence));
        trace.counter("StemWise.audio.rejected.fingerprint_generation", static_cast<std::int64_t>(result.payloadFingerprint.generation));
        trace.counter("StemWise.audio.rejected.fingerprint_token", static_cast<std::int64_t>(result.payloadFingerprint.selectionToken));
        trace.counter("StemWise.audio.rejected.control", static_cast<std::int64_t>(result.controlRevision));
        trace.counter("StemWise.audio.rejected.endpoint_control", static_cast<std::int64_t>(result.lateMixEndpoint.canonicalControlRevision));
        trace.counter("StemWise.audio.rejected.endpoint_source", static_cast<std::int64_t>(result.lateMixEndpoint.sourceIntentRevision));
        trace.counter("StemWise.audio.rejected.endpoint_stem", static_cast<std::int64_t>(result.lateMixEndpoint.stemIntentRevision));
        trace.counter("StemWise.audio.rejected.endpoint_valid", result.lateMixEndpoint.valid() ? 1 : 0);
        const auto frame = [](double value) noexcept -> std::int64_t {
            return std::isfinite(value) && value > static_cast<double>(std::numeric_limits<std::int64_t>::min()) &&
                value < static_cast<double>(std::numeric_limits<std::int64_t>::max()) ? static_cast<std::int64_t>(value) : -1;
        };
        trace.counter("StemWise.audio.rejected.source_start", frame(result.sourceTimeline.startFrame));
        trace.counter("StemWise.audio.rejected.source_end", frame(result.sourceTimeline.endFrameExclusive));
        trace.counter("StemWise.audio.rejected.source_finite", std::isfinite(result.sourceTimeline.startFrame) &&
            std::isfinite(result.sourceTimeline.endFrameExclusive) ? 1 : 0);
        trace.counter("StemWise.audio.rejected.source_ordered",
            result.sourceTimeline.endFrameExclusive >= result.sourceTimeline.startFrame ? 1 : 0);
    }
#endif

    [[nodiscard]] bool validRenderedBlock(const SongRenderResult &result,
        SongRenderOwnership ownership, std::uint32_t requestedFrames) const noexcept {
        return !(result.framesProduced == 0U || result.framesProduced > requestedFrames ||
            result.framesProduced > configuration.pipeline.maximumBlockFrames ||
            !sameOwnership(result.ownership, ownership) ||
            !sameOwnership(
                {result.payloadFingerprint.selectionToken, result.payloadFingerprint.generation},
                ownership) ||
            result.payloadFingerprint.sequence == 0U ||
            result.controlRevision == 0U ||
            !result.lateMixEndpoint.valid() ||
            result.lateMixEndpoint.canonicalControlRevision != result.controlRevision ||
            result.payloadFingerprint.frameCount != result.framesProduced ||
            !std::isfinite(result.sourceTimeline.startFrame) ||
            !std::isfinite(result.sourceTimeline.endFrameExclusive) ||
            result.sourceTimeline.endFrameExclusive < result.sourceTimeline.startFrame);
    }

    [[nodiscard]] bool stageRenderedBlock(PendingOutputPcm &pending,
        const SongRenderResult &result, SongRenderOwnership ownership) noexcept {
        outputFingerprintSequence = saturatingIncremented(outputFingerprintSequence);
        if (outputFingerprintSequence == 0U) outputFingerprintSequence = 1U;
        pending.offsetFrames = 0;
        pending.remainingFrames = result.framesProduced;
        pending.totalFrames = result.framesProduced;
        pending.ownership = ownership;
        pending.fingerprintSequence = outputFingerprintSequence;
        pending.renderPayloadHash = result.payloadFingerprint.hash;
        pending.controlRevision = result.controlRevision;
        pending.stemBusReadyMask = result.stemBusReadyMask;
        pending.actualTempo = result.actualTempo;
        pending.actualPitchSemitones = result.actualPitchSemitones;
        if (!stageLateMixControl(pending, result, ownership)) {
#if defined(STEMWISE_CACHE_IO_PROFILING)
            traceRejectedRender(pending, result, ownership, result.framesRequested, 2);
#endif
            pending.clear();
            saturatingIncrement(outputInvalidRenderResults);
            saturatingIncrement(gRuntimeOutputInvalidRenderResultCount);
            return false;
        }
        pending.endOfStreamAfterBlock = result.endOfStreamAfterBlock;
        pending.sourceStartFrame = result.sourceTimeline.startFrame;
        pending.sourceEndFrameExclusive = result.sourceTimeline.endFrameExclusive;
        return true;
    }

    enum class OutputRenderTarget { ActiveOutput, LoopContinuation };

    [[nodiscard]] bool renderOneOutputBlock(
        SongRenderPipeline &pipeline,
        PendingOutputPcm &pending,
        OutputRenderTarget target,
        std::uint32_t requestedFrames,
        std::size_t queuedFrames,
        std::size_t targetFrames,
        bool committedCurrentOutput,
        DiagnosticLowWaterPublication currentOutputLowWater) noexcept {
        if (!selectionAssigned || requestedFrames == 0U) return false;
        const bool loopStage = target == OutputRenderTarget::LoopContinuation;
        committedCurrentOutput = committedCurrentOutput && !loopStage;
        pipeline.setCacheReadPreparation(loopStage);
        pipeline.setDiagnosticPayloadFingerprints(diagnosticPayloadFingerprints.load(std::memory_order_relaxed));
        const SongRenderResult result = pipeline.renderMultibus(
            requestedOwnership,
            pending.samples.data(),
            configuration.pipeline.maximumBlockFrames,
            requestedFrames,
            static_cast<std::uint32_t>(outputRenderQuantumFrames()));
#if defined(STEMWISE_CACHE_IO_PROFILING)
        if (result.status != SongRenderStatus::Rendered && result.status != SongRenderStatus::RenderedTuneDeferred) {
            AudioTrace trace("StemWise.audio.render.wait");
            trace.counter("StemWise.audio.render.generation", static_cast<std::int64_t>(requestedOwnership.generation));
            trace.counter("StemWise.audio.render.status", static_cast<std::int64_t>(result.status));
            trace.counter("StemWise.audio.render.target", loopStage ? 1 : 0);
            trace.counter("StemWise.audio.render.input_frame", static_cast<std::int64_t>(pipeline.nextPreDspTimelineFrame()));
            trace.counter("StemWise.audio.render.source_frame", static_cast<std::int64_t>(pipeline.sourceTimelineFrame()));
            trace.counter("StemWise.audio.render.cache_read_pending", pipeline.awaitingCacheRead() ? 1 : 0);
            trace.counter("StemWise.audio.render.queue_frames", static_cast<std::int64_t>(queuedFrames));
        }
#endif
        if (currentSongCache != nullptr && currentSongCacheBank == renderBank &&
            (resources->snapshot().readFailureLaneMask & CurrentSongPcmCache::kOriginalLaneMask) != 0U) {
            return false;
        }
        if (result.status == SongRenderStatus::PreparingTune) return true;
        if (result.status == SongRenderStatus::NeedOriginalData) {
            needOriginalDataDiagnostics.record(
                requestedOwnership.generation,
                queuedFrames,
                targetFrames,
                committedCurrentOutput,
                currentOutputLowWater);
            saturatingIncrement(outputNeedDataEvents);
            return false;
        }
        if (result.status == SongRenderStatus::NeedStemData) {
            needStemDataDiagnostics.record(
                requestedOwnership.generation,
                queuedFrames,
                targetFrames,
                committedCurrentOutput,
                currentOutputLowWater);
            saturatingIncrement(outputNeedDataEvents);
            return false;
        }
        if (result.status == SongRenderStatus::LoopBoundaryPending) {
            loopBoundaryPendingDiagnostics.record(
                requestedOwnership.generation,
                queuedFrames,
                targetFrames,
                committedCurrentOutput,
                currentOutputLowWater);
            return false;
        }
        if (result.status == SongRenderStatus::EndOfStream) {
            static_cast<void>(output.publishTerminalBoundary(output.handle,
                requestedOwnership.generation, requestedOwnership.selectionToken,
                loopStage ? directLoopCutover.policy.physicalRevision() : directLoopState.physicalRevision,
                result.sourceTimeline.endFrameExclusive));
            if (loopStage) directLoopCutover.endObserved = true;
            if (!loopStage && (!pipelineEndObserved ||
                !sameOwnership(pipelineEndOwnership, requestedOwnership))) {
                pipelineEndObserved = true;
                pipelineEndOwnership = requestedOwnership;
                saturatingIncrement(outputEndOfStreamEvents);
                recordWorkerEndOrderingEvent(
                    endOrderingPipelineEndObserved,
                    {},
                    queuedFramesForGeneration(requestedOwnership.generation));
            }
            return false;
        }
        if (result.status != SongRenderStatus::Rendered &&
            result.status != SongRenderStatus::RenderedTuneDeferred) {
            saturatingIncrement(outputInvalidRenderResults);
            saturatingIncrement(gRuntimeOutputInvalidRenderResultCount);
            return false;
        }
        if (!validRenderedBlock(result, requestedOwnership, requestedFrames)) {
#if defined(STEMWISE_CACHE_IO_PROFILING)
            traceRejectedRender(pending, result, requestedOwnership, requestedFrames, 1);
#endif
            saturatingIncrement(outputInvalidRenderResults);
            saturatingIncrement(gRuntimeOutputInvalidRenderResultCount);
            return false;
        }

        if (!stageRenderedBlock(pending, result, requestedOwnership)) return false;
        // The staged A producer needs its following body before the callback adopts it.
        pipeline.requestUpcomingCacheInput();
        if (loopStage && result.stemBusReadyMask != (requiredBankLaneMask(renderBank) >> 1U)) {
#if defined(STEMWISE_CACHE_IO_PROFILING)
            if (directLoopCutover.allStemBusesPrepared) {
                AudioTrace trace("StemWise.audio.loop.stem_invalidation");
                trace.counter("StemWise.audio.loop.generation", static_cast<std::int64_t>(requestedOwnership.generation));
                trace.counter("StemWise.audio.loop.logical_revision", static_cast<std::int64_t>(directLoopCutover.policy.revision));
                trace.counter("StemWise.audio.loop.physical_revision", static_cast<std::int64_t>(directLoopCutover.policy.physicalRevision()));
                trace.counter("StemWise.audio.loop.rendered_stem_mask", result.stemBusReadyMask);
                trace.counter("StemWise.audio.loop.required_stem_mask", requiredBankLaneMask(renderBank) >> 1U);
            }
#endif
            directLoopCutover.allStemBusesPrepared = false;
        }
        pending.loopPolicyRevision = loopStage
            ? directLoopCutover.policy.physicalRevision()
            : directLoopState.physicalRevision;
        pending.managedLoopContext = {};
        if (loopStage && directLoopCutover.continuationEngaged) {
            pending.loopRevision = directLoopCutover.policy.physicalRevision();
            pending.loopCycle = saturatingIncremented(
                pipeline.diagnostics().loopWraps - directLoopCutover.preparationWrapBaseline);
            pending.loopEnabled = true;
        } else if (!loopStage && directLoopState.physicalRevision != 0U &&
                   directLoopState.engaged) {
            pending.loopRevision = directLoopState.physicalRevision;
            // Natural wraps keep their physical role, but advance the cycle within it.
            // Preserve the preparation baseline when that role becomes the active producer.
            pending.loopCycle = saturatingIncremented(
                pipeline.diagnostics().loopWraps - directLoopState.wrapBaseline);
            pending.loopEnabled = true;
        } else {
            pending.loopRevision = result.loopRevision;
            pending.loopCycle = result.loopCycle;
            pending.loopEnabled = result.loopEnabled;
            if (pending.loopEnabled) {
                const auto installed = pipeline.loopCycleSnapshot();
                if (!installed.installed || installed.loopRevision != pending.loopRevision ||
                    installed.ingressEpoch != pipeline.ingressEpoch() ||
                    installed.startTimelineFrame >= installed.endTimelineFrameExclusive) {
#if defined(STEMWISE_CACHE_IO_PROFILING)
                    traceRejectedRender(pending, result, requestedOwnership, requestedFrames, 4);
#endif
                    pending.clear();
                    saturatingIncrement(outputInvalidRenderResults);
                    saturatingIncrement(gRuntimeOutputInvalidRenderResultCount);
                    return false;
                }
                pending.managedLoopContext = {installed.loopRevision, installed.startTimelineFrame,
                    installed.endTimelineFrameExclusive};
            }
        }
        if (loopStage && result.endOfStreamAfterBlock) directLoopCutover.endObserved = true;
        if (!loopStage && result.endOfStreamAfterBlock &&
            (!pipelineEndObserved ||
             !sameOwnership(pipelineEndOwnership, requestedOwnership))) {
            pipelineEndObserved = true;
            pipelineEndOwnership = requestedOwnership;
            saturatingIncrement(outputEndOfStreamEvents);
            recordWorkerEndOrderingEvent(
                endOrderingPipelineEndObserved,
                {},
                queuedFramesForGeneration(requestedOwnership.generation));
        }
        saturatingIncrement(outputBlocksRendered);
        saturatingAdd(outputFramesRendered, result.framesProduced);
        if (loopStage) reusableLoopHead.append(pending);
        return true;
    }

    [[nodiscard]] bool enqueueAutomaticSuccessorPendingOutput() noexcept {
        PendingOutputPcm &pending = automaticSuccessorPendingOutput;
        if (pending.remainingFrames == 0U ||
            runtimeStatus.load(std::memory_order_acquire) !=
                NativePlaybackRuntimeStatus::Running) {
            return false;
        }
        if (!pending.lateMixControlPublished) {
            if (!publishedLateMixControlCovers(pending) &&
                !publishStampedLateMixControl(pending.lateMixControl)) {
                return false;
            }
            pending.lateMixControlPublished = true;
        }
        const std::size_t accepted = output.enqueue(
            output.handle,
            pendingSubmission(pending));
        const std::size_t boundedAccepted = std::min(accepted, pending.remainingFrames);
        if (boundedAccepted == 0U) {
            saturatingIncrement(outputBackpressureEvents);
            return false;
        }
        if (automaticSuccessor.firstFingerprintSequence == 0U) {
            automaticSuccessor.firstFingerprintSequence = pending.fingerprintSequence;
        }
        if (boundedAccepted < pending.remainingFrames) {
            saturatingIncrement(outputPartialAccepts);
        }
        pending.offsetFrames += boundedAccepted;
        pending.remainingFrames -= boundedAccepted;
        saturatingAdd(outputFramesAccepted, boundedAccepted);
        if (pending.remainingFrames == 0U) pending.clear();
        return true;
    }

    [[nodiscard]] bool renderOneAutomaticSuccessorBlock(
        std::uint32_t requestedFrames, bool &preparingTune) noexcept {
        preparingTune = false;
        if (automaticSuccessor.phase == AutomaticSuccessorPhase::Idle ||
            requestedFrames == 0U) {
            return false;
        }
        SongRenderPipeline &pipeline =
            *bankStates[automaticSuccessor.successorBank]->pipeline;
        pipeline.setCacheReadPreparation(true);
        pipeline.setDiagnosticPayloadFingerprints(diagnosticPayloadFingerprints.load(std::memory_order_relaxed));
        const SongRenderResult result = pipeline.renderMultibus(
            automaticSuccessor.successorOwnership,
            automaticSuccessorPendingOutput.samples.data(),
            configuration.pipeline.maximumBlockFrames,
            requestedFrames,
            static_cast<std::uint32_t>(outputRenderQuantumFrames()));
        if (result.status == SongRenderStatus::PreparingTune) {
            preparingTune = true;
            return true;
        }
        if (result.status == SongRenderStatus::NeedOriginalData ||
            result.status == SongRenderStatus::NeedStemData ||
            result.status == SongRenderStatus::LoopBoundaryPending) {
            saturatingIncrement(outputNeedDataEvents);
            return false;
        }
        if (result.status == SongRenderStatus::EndOfStream) {
            static_cast<void>(output.publishTerminalBoundary(output.handle,
                automaticSuccessor.successorOwnership.generation,
                automaticSuccessor.successorOwnership.selectionToken, 0U,
                result.sourceTimeline.endFrameExclusive));
            if (!automaticSuccessor.successorEndObserved) {
                automaticSuccessor.successorEndObserved = true;
                saturatingIncrement(outputEndOfStreamEvents);
            }
            return false;
        }
        if ((result.status != SongRenderStatus::Rendered &&
             result.status != SongRenderStatus::RenderedTuneDeferred) ||
            !validRenderedBlock(result, automaticSuccessor.successorOwnership, requestedFrames)) {
#if defined(STEMWISE_CACHE_IO_PROFILING)
            traceRejectedRender(automaticSuccessorPendingOutput, result, automaticSuccessor.successorOwnership,
                requestedFrames, (result.status == SongRenderStatus::Rendered ||
                    result.status == SongRenderStatus::RenderedTuneDeferred) ? 1 : 3);
#endif
            saturatingIncrement(outputInvalidRenderResults);
            saturatingIncrement(gRuntimeOutputInvalidRenderResultCount);
            return false;
        }

        PendingOutputPcm &pending = automaticSuccessorPendingOutput;
        if (!stageRenderedBlock(pending, result, automaticSuccessor.successorOwnership)) return false;
        pending.loopRevision = result.loopRevision;
        pending.loopCycle = result.loopCycle;
        pending.loopEnabled = result.loopEnabled;
        if (result.endOfStreamAfterBlock && !automaticSuccessor.successorEndObserved) {
            automaticSuccessor.successorEndObserved = true;
            saturatingIncrement(outputEndOfStreamEvents);
        }
        saturatingIncrement(outputBlocksRendered);
        saturatingAdd(outputFramesRendered, result.framesProduced);
        return true;
    }

    [[nodiscard]] bool fillAutomaticSuccessorOutput() noexcept {
        if (automaticSuccessor.phase == AutomaticSuccessorPhase::Idle ||
            automaticSuccessor.cancellationDesired ||
            !output.supportsDrainHandoff()) {
            return false;
        }
        if (!pipelineEndObserved &&
            queuedFramesForGeneration(automaticSuccessor.outgoingOwnership.generation) <
                outputTargetFrames(automaticSuccessor.outgoingOwnership.generation)) {
            return false;
        }
        const std::uint64_t generation = automaticSuccessor.successorOwnership.generation;
        const std::size_t target = outputTargetFrames(
            generation,
            kAutomaticSuccessorReserveFrames);
        bool progressed = false;
        for (std::uint32_t block = 0U;
             block < configuration.maximumOutputBlocksPerCycle;
             ++block) {
            if (runtimeStatus.load(std::memory_order_acquire) !=
                NativePlaybackRuntimeStatus::Running) {
                break;
            }
            const std::size_t queued = queuedFramesForGeneration(generation);
            if (queued >= target) break;
            const std::size_t writable = writableFramesForGeneration(generation);
            if (writable == 0U) break;
            if (automaticSuccessorPendingOutput.remainingFrames != 0U) {
                const bool accepted = enqueueAutomaticSuccessorPendingOutput();
                progressed = accepted || progressed;
                if (!accepted) break;
                continue;
            }
            if (automaticSuccessor.successorEndObserved) break;
            const std::size_t requested = std::min<std::size_t>({
                outputRenderQuantumFrames(),
                writable,
                target - queued});
            bool preparingTune = false;
            if (requested == 0U ||
                !renderOneAutomaticSuccessorBlock(
                    static_cast<std::uint32_t>(requested), preparingTune)) {
                break;
            }
            progressed = true;
            // Return to audible-owner refill after each bounded spectral-prime step.
            if (preparingTune) break;
        }
        publishAutomaticSuccessorDiagnostics();
        return progressed;
    }

    [[nodiscard]] bool commitAutomaticSuccessorIfReady() noexcept {
        if (automaticSuccessor.phase != AutomaticSuccessorPhase::Staging ||
            !pipelineEndObserved || pendingOutput.remainingFrames != 0U ||
            !sameOwnership(
                pipelineEndOwnership,
                automaticSuccessor.outgoingOwnership) ||
            automaticSuccessorPendingOutput.remainingFrames != 0U) {
            return false;
        }
        const std::size_t successorQueued = queuedFramesForGeneration(
            automaticSuccessor.successorOwnership.generation);
        const std::size_t outgoingQueued = queuedFramesForGeneration(
            automaticSuccessor.outgoingOwnership.generation);
        if (successorQueued == 0U) {
            if (outgoingQueued != 0U) return false;
            lastAutomaticDrainCancellationStatus = output.cancelDrainHandoff(
                output.handle,
                automaticSuccessor.outgoingOwnership.generation,
                automaticSuccessor.successorOwnership.generation,
                automaticSuccessor.transitionRevision);
            if (lastAutomaticDrainCancellationStatus ==
                    NativeAudioDrainCancelStatus::CancelledOpenStage ||
                lastAutomaticDrainCancellationStatus ==
                    NativeAudioDrainCancelStatus::CancellationRequested ||
                lastAutomaticDrainCancellationStatus ==
                    NativeAudioDrainCancelStatus::OutgoingActive) {
                if (automaticSuccessor.successorEndObserved &&
                    automaticSuccessor.firstFingerprintSequence == 0U) {
                    // Exact terminal emptiness is authoritative; merely late PCM is not.
                    emptySuccessorIntent = desiredSnapshot.automaticSuccessor;
                }
                clearAutomaticSuccessorPlan();
                return true;
            }
            automaticSuccessor.cancellationDesired = true;
            publishAutomaticSuccessorDiagnostics();
            return false;
        }
        const std::size_t minimumReady = outputTargetFrames(
            automaticSuccessor.successorOwnership.generation,
            kAutomaticSuccessorReserveFrames);
        if (successorQueued < minimumReady &&
            !automaticSuccessor.successorEndObserved && outgoingQueued != 0U) {
            return false;
        }
        // Publish the producer-side phase before making the drain publication visible to the
        // callback. Otherwise the render worker can be preempted after commitDrainHandoff accepts,
        // allowing successor PCM to be presented while every runtime observer still reports
        // Staging. A rejected output commit is still retriable and restores the exact prior phase.
        automaticSuccessor.phase = AutomaticSuccessorPhase::DrainCommitted;
        publishAutomaticSuccessorDiagnostics();
        if (!output.commitDrainHandoff(
                output.handle,
                automaticSuccessor.outgoingOwnership.generation,
                automaticSuccessor.successorOwnership.generation,
                automaticSuccessor.transitionRevision)) {
            automaticSuccessor.phase = AutomaticSuccessorPhase::Staging;
            publishAutomaticSuccessorDiagnostics();
            return false;
        }
        return true;
    }
    [[nodiscard]] bool fillDirectLoopCutover(bool currentOutputReserved = true) noexcept {
        if ((!directLoopCutover.stageOpen() && !directLoopCutover.commitPublished()) ||
            !selectionAssigned ||
            !sameOwnership(
                {directLoopCutover.policy.selectionToken,
                 directLoopCutover.policy.generation},
                requestedOwnership)) {
            return false;
        }
        if (!directLoopPreparationIsCurrent(directLoopCutover)) {
            return cancelDirectLoopStage();
        }

        // Keep the ordinary starvation reserve in the continuation as well as the audible
        // producer, so a delayed worker immediately after the callback swap cannot drain it.
        const std::size_t commitTarget = output.loopCutoverRequiredFrames(output.handle);
        const std::size_t reserveTarget = std::max(
            commitTarget,
            std::min<std::size_t>(configuration.minimumOutputReserveFrames(),
                outputTargetFrames(directLoopCutover.policy.generation)));
        if (commitTarget == 0U || reserveTarget == 0U) return false;
        bool progressed = false;
        // Finish the bounded continuation while the audible reserve is full. Re-entering the
        // entire worker cycle between every render/enqueue pair delays immediate post-seek B.
        const bool deadlineReplay = tunedDirectLoopNeedsPreparedSuccessor() && reusableLoopHead.replaying() &&
            reusableLoopHead.completeTraversal(desiredSnapshot.controls.tempo, desiredSnapshot.controls.pitchSemitones) &&
            reusableLoopHead.plan().cacheKey == directLoopCutover.cacheKey &&
            reusableLoopHead.plan().policy.aTimelineFrame == directLoopCutover.policy.aTimelineFrame &&
            reusableLoopHead.plan().policy.bTimelineFrameExclusive == directLoopCutover.policy.bTimelineFrameExclusive &&
            desiredSnapshot.controls.mixer.metronome.mode == MetronomeMode::Off;
#if defined(STEMWISE_CACHE_IO_PROFILING)
        if (!currentOutputReserved) {
            AudioTrace trace("StemWise.audio.loop.service");
            trace.counter("StemWise.audio.loop.generation", static_cast<std::int64_t>(requestedOwnership.generation));
            trace.counter("StemWise.audio.loop.logical_revision", static_cast<std::int64_t>(directLoopCutover.policy.revision));
            trace.counter("StemWise.audio.loop.physical_revision", static_cast<std::int64_t>(directLoopCutover.policy.physicalRevision()));
            trace.counter("StemWise.audio.loop.a", static_cast<std::int64_t>(directLoopCutover.policy.aTimelineFrame));
            trace.counter("StemWise.audio.loop.b", static_cast<std::int64_t>(directLoopCutover.policy.bTimelineFrameExclusive));
            trace.counter("StemWise.audio.loop.stage_phase", static_cast<std::int64_t>(directLoopCutover.phase));
            trace.counter("StemWise.audio.loop.active_queue", static_cast<std::int64_t>(queuedFramesForGeneration(requestedOwnership.generation)));
            trace.counter("StemWise.audio.loop.staged_frames", static_cast<std::int64_t>(directLoopCutover.stagedFrames));
            trace.counter("StemWise.audio.loop.pending_frames", static_cast<std::int64_t>(loopContinuationPendingOutput.remainingFrames));
            trace.counter("StemWise.audio.loop.reserve_frames", static_cast<std::int64_t>(reserveTarget));
            trace.counter("StemWise.audio.loop.active_source", static_cast<std::int64_t>(bankStates[renderBank]->pipeline->sourceTimelineFrame()));
            trace.counter("StemWise.audio.loop.spare_source", static_cast<std::int64_t>(loopContinuationPipeline->sourceTimelineFrame()));
            trace.counter("StemWise.audio.loop.active_at_b", bankStates[renderBank]->pipeline->sourceTimelineFrame() ==
                static_cast<double>(directLoopCutover.policy.bTimelineFrameExclusive));
            trace.counter("StemWise.audio.loop.spare_at_b", loopContinuationPipeline->sourceTimelineFrame() ==
                static_cast<double>(directLoopCutover.policy.bTimelineFrameExclusive));
            trace.counter("StemWise.audio.loop.cache_phase", reusableLoopHead.profilingPhase());
            trace.counter("StemWise.audio.loop.cache_frames", reusableLoopHead.profilingFrames());
            trace.counter("StemWise.audio.loop.cache_unloaded_frames", reusableLoopHead.profilingUnloadedFrames());
            trace.counter("StemWise.audio.loop.cache_complete", reusableLoopHead.completeTraversal(
                desiredSnapshot.controls.tempo, desiredSnapshot.controls.pitchSemitones));
            trace.counter("StemWise.audio.loop.deadline_replay", deadlineReplay);
        }
#endif
        const auto commitReadyStage = [&]() noexcept {
            if (directLoopCutover.stageOpen() &&
                loopContinuationPendingOutput.remainingFrames == 0U &&
                (directLoopCutover.stagedFrames >= reserveTarget ||
                 (directLoopCutover.endObserved && directLoopCutover.stagedFrames != 0U))) {
                // The callback can consume this terminal Clear role as soon as commit publishes it.
                static_cast<void>(reconcileTerminalSilenceAuthorization());
                const NativeAudioLoopCutoverCommitStatus commitStatus =
                    output.commitLoopCutover(output.handle, directLoopCutover.policy);
#if defined(STEMWISE_CACHE_IO_PROFILING)
                {
                    AudioTrace trace("StemWise.audio.loop.commit");
                    trace.counter("StemWise.audio.loop.logical_revision", static_cast<std::int64_t>(directLoopCutover.policy.revision));
                    trace.counter("StemWise.audio.loop.physical_revision", static_cast<std::int64_t>(directLoopCutover.policy.physicalRevision()));
                    trace.counter("StemWise.audio.loop.staged_frames", static_cast<std::int64_t>(directLoopCutover.stagedFrames));
                    trace.counter("StemWise.audio.loop.reserve_frames", static_cast<std::int64_t>(reserveTarget));
                    trace.counter("StemWise.audio.loop.commit_status", static_cast<std::int64_t>(commitStatus));
                }
#endif
                if (commitStatus == NativeAudioLoopCutoverCommitStatus::Retired) {
                    static_cast<void>(output.cancelLoopCutover(
                        output.handle,
                        directLoopCutover.policy.generation,
                        directLoopCutover.policy.physicalRevision()));
                    loopContinuationPendingOutput.clear();
                    directLoopCutover.clear();
                    return true;
                }
                if (commitStatus == NativeAudioLoopCutoverCommitStatus::Committed) {
                    reusableLoopHead.finishStage(directLoopCutover,
                        loopContinuationPipeline->nextPreDspTimelineFrame(),
                        loopContinuationPipeline->diagnostics().loopWraps ==
                            directLoopCutover.preparationWrapBaseline);
                    directLoopState.observedRevision = directLoopCutover.policy.revision;
                    directLoopCutover.phase = DirectLoopCutoverPlan::Phase::Published;
                    progressed = true;
                }
            }

            return false;
        };
        const auto maximumActions = currentOutputReserved || deadlineReplay
            ? configuration.maximumOutputBlocksPerCycle : 1U;
        for (std::uint32_t block = 0U; block < maximumActions; ++block) {
            if (block != 0U && (commandMailbox.hasUnreadPublication() ||
                (!deadlineReplay && queuedFramesForGeneration(requestedOwnership.generation) < outputTargetFrames()))) {
                break;
            }
            if (reusableLoopHead.hasReplayBlock() && loopContinuationPendingOutput.remainingFrames == 0U) {
                const auto endpoint = loopContinuationPipeline->prepareLateMixEndpoint(requestedOwnership);
                if (!endpoint.valid()) return progressed;
                auto &pending = loopContinuationPendingOutput;
                reusableLoopHead.takeNext(pending);
                pending.ownership = requestedOwnership;
                outputFingerprintSequence = saturatingIncremented(outputFingerprintSequence);
                pending.fingerprintSequence = outputFingerprintSequence;
                pending.loopPolicyRevision = directLoopCutover.policy.physicalRevision();
                pending.loopRevision = pending.loopPolicyRevision;
                pending.controlRevision = endpoint.canonicalControlRevision;
                pending.sourceIntentRevision = endpoint.sourceIntentRevision;
                pending.stemIntentRevision = endpoint.stemIntentRevision;
                pending.lateMixControl = stampLateMixControl(requestedOwnership, endpoint);
                pending.lateMixControlPublished = false;
            }
            if (commitReadyStage()) return true;

            const bool newerPolicy =
                newerDirectAtomicPolicyPublishedThan(directLoopCutover.policy.revision);
            AtomicLoopPolicySnapshot latestPolicy{};
            const bool finishReusableAHead = newerPolicy && directLoopCutover.stageOpen() &&
                directLoopCutover.stagedFrames < commitTarget &&
                directLoopCutover.policy.enabled &&
                directLoopCutover.policy.continuationTimelineFrame ==
                    directLoopCutover.policy.aTimelineFrame &&
                atomicLoopPolicy.readLatest(latestPolicy) && latestPolicy.enabled &&
                latestPolicy.selectionToken == directLoopCutover.policy.selectionToken &&
                latestPolicy.aSourceFrame == directLoopCutover.policy.aTimelineFrame;
            if ((newerPolicy && !finishReusableAHead) ||
                !directLoopPreparationIsCurrent(directLoopCutover) ||
                runtimeStatus.load(std::memory_order_acquire) !=
                    NativePlaybackRuntimeStatus::Running) {
                break;
            }
            if (loopContinuationPendingOutput.remainingFrames != 0U) {
                const bool accepted = enqueuePendingOutput(
                    loopContinuationPendingOutput,
                    true);
                progressed = accepted || progressed;
                if (!accepted) break;
                // A final fragment can consume the last bounded action. Publish its
                // ready role now rather than leaving the next callback behind another turn.
                if (!commandMailbox.hasUnreadPublication() &&
                    !newerDirectAtomicPolicyPublishedThan(directLoopCutover.policy.revision) &&
                    directLoopPreparationIsCurrent(directLoopCutover) && commitReadyStage()) return true;
                continue;
            }
            if (directLoopCutover.stagedFrames >= reserveTarget) break;
            const std::size_t targetFrames = finishReusableAHead
                ? commitTarget
                : reserveTarget;
            const std::size_t renderQuantum = outputRenderQuantumFrames();
            const std::size_t requested = std::min<std::size_t>(
                renderQuantum,
                targetFrames - directLoopCutover.stagedFrames);
            if (requested == 0U ||
                !renderOneOutputBlock(
                    *loopContinuationPipeline,
                    loopContinuationPendingOutput,
                    OutputRenderTarget::LoopContinuation,
                    static_cast<std::uint32_t>(requested),
                    directLoopCutover.stagedFrames,
                    reserveTarget,
                    outputPlaying,
                    {})) {
                break;
            }
            progressed = true;
        }

        if (!directLoopPreparationIsCurrent(directLoopCutover) ||
            runtimeStatus.load(std::memory_order_acquire) !=
                NativePlaybackRuntimeStatus::Running) {
            return cancelDirectLoopStage() || progressed;
        }
        // Keep the prepared continuation for the next retarget/cancel attempt. A newer
        // demand alone is not progress: a callback-owned role may still block cancellation.
        // Claiming progress here busy-spins until that callback releases the role.
        return progressed;
    }
    [[nodiscard]] bool tunedDirectLoopNeedsPreparedSuccessor() const noexcept {
        if (!selectionMatchesDesired() || !directLoopPolicyOwnsSelectedOwner() ||
            !directLoopState.engaged || directLoopState.physicalRevision == 0U ||
            directLoopState.aFrame >= directLoopState.bFrame) return false;
        const auto &pipeline = *bankStates[renderBank]->pipeline;
        if (!sameOwnership(pipeline.ownership(), requestedOwnership) || !pipeline.loopEnabled() ||
            !pipeline.processedTimelineActive() ||
            pipeline.sourceTimelineFrame() < static_cast<double>(directLoopState.bFrame)) return false;
        const auto tune = pipeline.timePitchDiagnostics();
        if ((desiredSnapshot.controls.tempo == 1.0F && desiredSnapshot.controls.pitchSemitones == 0.0F) ||
            tune.tempo != desiredSnapshot.controls.tempo ||
            tune.pitchSemitones != desiredSnapshot.controls.pitchSemitones) return false;
        AtomicLoopPolicySnapshot policy{};
        // Physical-role acknowledgement already installed this producer and range.
        // Its first presentation can still be pending; waiting for that logical
        // evidence here would cold-wrap the acknowledged producer past queued B.
        return atomicLoopPolicy.readLatest(policy) && policy.enabled &&
            policy.revision == selectedOwnerDirectLoopPolicyRevision() &&
            policy.selectionToken == requestedOwnership.selectionToken &&
            policy.aSourceFrame == directLoopState.aFrame &&
            policy.bSourceFrameExclusive == directLoopState.bFrame;
    }

    [[nodiscard]] bool fillOutput(bool prepareContinuation = true,
        std::size_t setupReserveFrames = 0U) noexcept {
        if (!selectionAssigned || !selectionMatchesDesired()) return false;
        bool progressed = false;
        if (pipelineEndObserved &&
            sameOwnership(pipelineEndOwnership, requestedOwnership) &&
            pendingOutput.remainingFrames == 0U) {
            return prepareContinuation &&
                (directLoopCutover.stageOpen() || directLoopCutover.commitPublished()) &&
                fillDirectLoopCutover();
        }
        const std::size_t target = outputTargetFrames(requestedOwnership.generation, setupReserveFrames);
        targetQueuedFrames.store(target, std::memory_order_relaxed);

        for (std::uint32_t block = 0;
             block < configuration.maximumOutputBlocksPerCycle;
             ++block) {
            if (newerDirectAtomicPolicyPublishedThan(
                    directLoopState.observedRevision) &&
                !directLoopCutover.stageOpen()) {
                break;
            }
            if (runtimeStatus.load(std::memory_order_acquire) !=
                NativePlaybackRuntimeStatus::Running) {
                break;
            }
            const std::size_t queued = queuedFramesForGeneration(
                requestedOwnership.generation);
            const bool committedCurrentOutput =
                outputPlaying && validOwnership(committedOwnership) &&
                sameOwnership(committedOwnership, requestedOwnership);
            DiagnosticLowWaterPublication currentOutputLowWater{};
            if (committedCurrentOutput) {
                currentOutputLowWater = currentOutputQueueLowWater.observe(
                    requestedOwnership.generation,
                    queued);
            }
            if (queued >= target) {
                if (committedCurrentOutput) {
                    needOriginalDataDiagnostics.recovered();
                    needStemDataDiagnostics.recovered();
                    loopBoundaryPendingDiagnostics.recovered();
                }
                break;
            }
            const std::size_t writable = writableFramesForGeneration(
                requestedOwnership.generation);
            if (writable == 0U) {
                saturatingIncrement(outputBackpressureEvents);
                break;
            }
            if (pendingOutput.remainingFrames != 0U) {
                const bool accepted = enqueuePendingOutput(pendingOutput, false);
                progressed = accepted || progressed;
                if (!accepted) break;
                continue;
            }
            if (directLoopCutover.commitPublished() &&
                !directLoopCutover.policy.nextRevisionOnly &&
                (directLoopCutover.policy.boundaryArmedAtPublication ||
                    directLoopState.engaged) &&
                directLoopPreparationIsCurrent(directLoopCutover) &&
                bankStates[renderBank]->pipeline->sourceTimelineFrame() >=
                    static_cast<double>(directLoopCutover.policy.cutoverTimelineFrameExclusive)) {
                // B is already queued and the callback owns a complete continuation. Do not
                // cold-prime/render a second A on the outgoing role which that callback discards.
                break;
            }
            // The adopted short-loop producer may already have queued its complete B tail.
            // Its next A belongs to the prepared role, not another cold prime on this role.
            if (tunedDirectLoopNeedsPreparedSuccessor()) break;
            const std::size_t requested = std::min(outputRenderQuantumFrames(), writable);
            if (requested == 0U ||
                !renderOneOutputBlock(
                    *bankStates[renderBank]->pipeline,
                    pendingOutput,
                    OutputRenderTarget::ActiveOutput,
                    static_cast<std::uint32_t>(requested),
                    queued,
                    target,
                    committedCurrentOutput,
                    currentOutputLowWater)) {
                break;
            }
            progressed = true;
        }
        const std::size_t queued = queuedFramesForGeneration(requestedOwnership.generation);
        if (prepareContinuation &&
            (directLoopCutover.stageOpen() || directLoopCutover.commitPublished())) {
            const bool reserveGrew = queued > directLoopCutover.currentOutputRefillHighWater;
            directLoopCutover.currentOutputRefillHighWater = std::max(
                directLoopCutover.currentOutputRefillHighWater, queued);
            const bool waitingForPreparedSuccessor = tunedDirectLoopNeedsPreparedSuccessor();
            if (!waitingForPreparedSuccessor && queued < target && (queued == 0U || reserveGrew)) return progressed;
            // Give reserve growth priority, then prepare the continuation within that reserve.
            // A pending active cache read cannot starve ready continuation PCM while a reserve remains.
            // Track the high water so callback drains between calls cannot starve a change.
            progressed = fillDirectLoopCutover(queued >= target) || progressed;
        }
        return progressed;
    }

    [[nodiscard]] bool selectionMatchesDesired() const noexcept {
        return hasDesiredSnapshot && desiredSnapshot.selectionAssigned &&
            bankIndex(desiredSnapshot.selectedBank) == renderBank &&
            sameOwnership(desiredSnapshot.selectedOwnership, requestedOwnership);
    }

    [[nodiscard]] std::size_t outputTargetFrames() const noexcept {
        return outputTargetFrames(requestedOwnership.generation);
    }

    [[nodiscard]] std::size_t outputRenderQuantumFrames() const noexcept {
        const bool tuned = hasDesiredSnapshot &&
            (desiredSnapshot.controls.tempo != 1.0F || desiredSnapshot.controls.pitchSemitones != 0.0F ||
             (selectionAssigned && bankStates[renderBank]->pipeline->processedTimelineActive()));
        if (!tuned) return configuration.pipeline.maximumBlockFrames;
        // Split spectral work at the hardware cadence. A 10 ms/36-channel block can occupy
        // the producer longer than the ready reserve, especially immediately after a prime.
        const auto reportedBurst = output.framesPerBurst(output.handle);
        const auto burst = reportedBurst > 0
            ? static_cast<std::size_t>(reportedBurst) : configuration.fallbackFramesPerBurst;
        return std::min<std::size_t>(configuration.pipeline.maximumBlockFrames, burst);
    }

    [[nodiscard]] std::size_t queuedFramesForGeneration(
        std::uint64_t generation) const noexcept {
        return output.supportsDrainHandoff()
            ? output.queuedFramesForGeneration(output.handle, generation)
            : output.queuedFrames(output.handle);
    }

    [[nodiscard]] std::size_t writableFramesForGeneration(
        std::uint64_t generation) const noexcept {
        return output.supportsDrainHandoff()
            ? output.writableFramesForGeneration(output.handle, generation)
            : output.writableFrames(output.handle);
    }

    [[nodiscard]] std::size_t outputTargetFrames(
        std::uint64_t generation,
        std::uint64_t minimumFrames = 0U) const noexcept {
        const std::int32_t reportedBurst = output.framesPerBurst(output.handle);
        const std::uint64_t burstFrames = reportedBurst > 0
            ? static_cast<std::uint64_t>(reportedBurst)
            : configuration.fallbackFramesPerBurst;
        const std::uint64_t desired = std::min<std::uint64_t>(
            std::max(configuration.desiredOutputFrames(burstFrames), minimumFrames),
            std::numeric_limits<std::size_t>::max());
        const std::size_t queued = queuedFramesForGeneration(generation);
        const std::size_t writable = writableFramesForGeneration(generation);
        const std::size_t capacity = writable > std::numeric_limits<std::size_t>::max() - queued
            ? std::numeric_limits<std::size_t>::max()
            : queued + writable;
        const auto quantum = configuration.pipeline.maximumBlockFrames;
        const auto rounded = ((desired + quantum - 1U) / quantum) * quantum;
        return static_cast<std::size_t>(std::min<std::uint64_t>(rounded, capacity));
    }

    [[nodiscard]] bool commitStagedHandoffIfReady() noexcept {
        if (!handoffStageOpen || !selectionAssigned || !selectionMatchesDesired() ||
            pendingOutput.remainingFrames != 0U) {
            return false;
        }
        const std::size_t queued = queuedFramesForGeneration(
            requestedOwnership.generation);
        const std::size_t target = outputTargetFrames();
        const bool completeShortSong = pipelineEndObserved && queued != 0U;
        if (queued == 0U || (queued < target && !completeShortSong)) return false;
        if (!output.commitHandoff(output.handle, requestedOwnership.generation)) {
            saturatingIncrement(outputHandoffCommitRetries);
            return false;
        }
        handoffStageOpen = false;
        handoffCommitPublished = true;
        if (hasDesiredSnapshot && !desiredSnapshot.playing) {
            // The device is globally paused and its callback applies the published role swap before
            // it can observe a later Play flag. Therefore a non-empty committed ring is already the
            // safely installed paused owner; waiting for impossible audible evidence would leave
            // metadata stuck on the old song forever.
            committedOwnership = requestedOwnership;
            pendingPresentationCount = 0;
            publishOwnershipDiagnostics();
            publishPresentationDiagnostics({});
            publishPositionDiagnostics({});
        } else {
            recordPendingPresentationCommit();
        }
        saturatingIncrement(outputHandoffCommits);
        return true;
    }

    void recordPendingPresentationCommit() noexcept {
        PendingPresentationCommit candidate{
            requestedOwnership,
            acceptedFirstFingerprintSequence,
            pendingEvidenceBaseline,
        };
        if (pendingPresentationCount < pendingPresentationCommits.size()) {
            pendingPresentationCommits[pendingPresentationCount++] = candidate;
            return;
        }
        pendingPresentationCommits[0] = pendingPresentationCommits[1];
        pendingPresentationCommits[1] = candidate;
    }

    [[nodiscard]] bool installCommittedHandoffWhilePaused() noexcept {
        if (!hasDesiredSnapshot || desiredSnapshot.playing ||
            !desiredSnapshot.selectionAssigned || handoffStageOpen ||
            !handoffCommitPublished || !selectionMatchesDesired() ||
            !sameOwnership(acceptedOwnership, requestedOwnership)) {
            return false;
        }

        bool hasExactPendingCommit = false;
        for (std::size_t index = 0; index < pendingPresentationCount; ++index) {
            if (sameOwnership(
                    pendingPresentationCommits[index].ownership,
                    requestedOwnership)) {
                hasExactPendingCommit = true;
                break;
            }
        }
        if (!hasExactPendingCommit) return false;

        // Commit may have been published while Play was true, then Pause can win before the
        // callback presents the first staged frame. Once globally paused, that presentation
        // evidence cannot arrive. The output role swap is already published and will be observed
        // before any later Play flag, so install the exact accepted owner without manufacturing
        // audible evidence or leaving metadata pinned to the previous owner.
        committedOwnership = requestedOwnership;
        pendingPresentationCount = 0;
        publishOwnershipDiagnostics();
        publishPresentationDiagnostics({});
        publishPositionDiagnostics({});
        return true;
    }

    [[nodiscard]] bool reconcileOutputPlaying() noexcept {
        bool desiredPlaying = false;
        if (hasDesiredSnapshot && desiredSnapshot.selectionAssigned && desiredSnapshot.playing) {
            // Keep an already audible owner running while its replacement is staged. For the first
            // owner, publish play only after commit so the callback swaps before reading PCM.
            desiredPlaying = validOwnership(committedOwnership) || handoffCommitPublished;
        }
        if (desiredPlaying == outputPlaying) { outputPlayingRetryAt = 0U; return false; }
        const auto now = diagnosticMonotonicMicros();
        if (outputPlayingRetryCommandRevision == desiredSnapshot.revision && now < outputPlayingRetryAt)
            return false;
        const bool accepted = output.setPlaying(output.handle, desiredPlaying);
        if (!accepted) {
            // requestStart can briefly race an asynchronous platform pause. Keep retrying on the
            // existing stream; never report the transport applied or rebuild output ownership.
            outputPlayingRetryCommandRevision = desiredSnapshot.revision;
            outputPlayingRetryAt = now + configuration.idleSleepMicros;
            return false;
        }
        outputPlayingRetryAt = 0U;
        outputPlaying = desiredPlaying;
        const std::size_t queued = validOwnership(requestedOwnership)
            ? queuedFramesForGeneration(requestedOwnership.generation)
            : 0U;
        recordWorkerEndOrderingEvent(endOrderingOutputPlayingApplied, {}, queued);
        return true;
    }

    [[nodiscard]] bool publishAudibleEndIfDrained() noexcept {
        const std::uint64_t directPolicyRevision =
            selectedOwnerDirectLoopPolicyRevision();
        if (audibleEndPublicationFenceRevision != directPolicyRevision) {
            audibleEndPublicationFenceRevision = directPolicyRevision;
            if (sameOwnership(audibleEndPublishedForOwnership, requestedOwnership)) {
                audibleEndPublishedForOwnership = {};
            }
        }
        const NativeAudioPresentedPosition terminalPosition =
            output.presentedPosition(output.handle);
        const std::size_t queued = validOwnership(requestedOwnership)
            ? queuedFramesForGeneration(requestedOwnership.generation)
            : 0U;
        const bool physicalDrainObserved = pipelineEndObserved && selectionAssigned &&
            pendingOutput.remainingFrames == 0U && queued == 0U &&
            terminalPosition.valid() && terminalPosition.terminalAfterFrame &&
            terminalPosition.selectionToken == requestedOwnership.selectionToken &&
            terminalPosition.generation == requestedOwnership.generation &&
            sameOwnership(pipelineEndOwnership, requestedOwnership) &&
            sameOwnership(committedOwnership, requestedOwnership);
        if (physicalDrainObserved &&
            !sameOwnership(endOrderingPhysicalDrainOwnership, requestedOwnership)) {
            endOrderingPhysicalDrainOwnership = requestedOwnership;
            recordWorkerEndOrderingEvent(
                endOrderingPhysicalDrainObserved, terminalPosition, queued);
        }
        const bool ownedManagedLoopPending = !directLoopPolicyOwnsSelectedOwner() &&
            managedLoopAdopted &&
            loopCycle.blocksNaturalEnd(
                managedLoopIntent.revision,
                {
                    .selectionToken = requestedOwnership.selectionToken,
                    .renderGeneration = requestedOwnership.generation,
                });
        if (commandMailbox.hasUnreadPublication() || !pipelineEndObserved ||
            !selectionAssigned || !hasDesiredSnapshot ||
            !desiredSnapshot.playing || pendingOutput.remainingFrames != 0U || queued != 0U ||
            !terminalPosition.valid() || !terminalPosition.terminalAfterFrame ||
            terminalPosition.selectionToken != requestedOwnership.selectionToken ||
            terminalPosition.generation != requestedOwnership.generation ||
            !sameOwnership(pipelineEndOwnership, requestedOwnership) ||
            !sameOwnership(committedOwnership, requestedOwnership) ||
            !currentOutputMayStopAtNaturalEnd(requestedOwnership) ||
             sameOwnership(audibleEndPublishedForOwnership, requestedOwnership) ||
            ownedManagedLoopPending) {
            return false;
        }
        const std::uint64_t verifiedDirectPolicyRevision =
            selectedOwnerDirectLoopPolicyRevision();
        if (verifiedDirectPolicyRevision != directPolicyRevision) return false;
        audibleEndPublishedForOwnership = requestedOwnership;
        audibleEndSequenceValue = saturatingIncremented(audibleEndSequenceValue);
        beginGuardedPublication(audibleEndDiagnosticsGuard);
        audibleEndToken.store(requestedOwnership.selectionToken, std::memory_order_relaxed);
        audibleEndGeneration.store(requestedOwnership.generation, std::memory_order_relaxed);
        audibleEndDirectLoopPolicyRevision.store(
            directPolicyRevision,
            std::memory_order_relaxed);
        audibleEndSequence.store(audibleEndSequenceValue, std::memory_order_relaxed);
        audibleEndDiagnosticsGuard.fetch_add(1U, std::memory_order_release);
        recordWorkerEndOrderingEvent(
            endOrderingAudibleEndPublished, terminalPosition, queued);
        return true;
    }

    [[nodiscard]] bool consumePresentationEvidence() noexcept {
        const NativeAudioPresentationEvidence evidence =
            output.presentationEvidence(output.handle);
        if (!evidence.valid() || evidence.sequence <= lastPresentationEvidence.sequence) {
            return false;
        }
        lastPresentationEvidence = evidence;
        publishPresentationDiagnostics(evidence);
        if ((automaticSuccessor.phase == AutomaticSuccessorPhase::DrainCommitted ||
             automaticSuccessor.phase ==
                 AutomaticSuccessorPhase::AppliedPausedAwaitingSelection) &&
            evidence.sequence > automaticSuccessor.evidenceBaseline &&
            evidence.selectionToken ==
                automaticSuccessor.successorOwnership.selectionToken &&
            evidence.generation == automaticSuccessor.successorOwnership.generation &&
            evidence.fingerprintSequence ==
                automaticSuccessor.firstFingerprintSequence) {
            automaticSuccessor.phase =
                AutomaticSuccessorPhase::PresentedAwaitingSelection;
            installAutomaticSuccessorOwnership();
            publishOwnershipDiagnostics();
            publishAutomaticSuccessorDiagnostics();
            return true;
        }
        for (std::size_t index = 0; index < pendingPresentationCount; ++index) {
            const PendingPresentationCommit &candidate = pendingPresentationCommits[index];
            if (evidence.sequence <= candidate.evidenceBaseline ||
                evidence.selectionToken != candidate.ownership.selectionToken ||
                evidence.generation != candidate.ownership.generation ||
                evidence.fingerprintSequence != candidate.fingerprintSequence) {
                continue;
            }
            committedOwnership = candidate.ownership;
            const std::size_t remaining = pendingPresentationCount - index - 1U;
            for (std::size_t offset = 0; offset < remaining; ++offset) {
                pendingPresentationCommits[offset] =
                    pendingPresentationCommits[index + 1U + offset];
            }
            pendingPresentationCount = remaining;
            publishOwnershipDiagnostics();
            break;
        }
        return true;
    }

    [[nodiscard]] bool consumePresentedPosition() noexcept {
        const NativeAudioPresentedPosition position = output.presentedPosition(output.handle);
        if (!position.valid() || position.sequence <= lastObservedPosition.sequence) return false;
        // While a replacement is only staging, the callback is still audibly consuming the
        // previously committed ring. Keep that exact owner's position live until first-presentation
        // evidence atomically commits the replacement. Do not consume a future owner's position:
        // the callback publishes position and first-presentation evidence as separate coherent
        // tuples, so this worker can observe the position in the narrow interval before the matching
        // presentation commit. Retaining that sequence lets the next worker cycle accept the same
        // exact tuple after ownership commits, even if playback pauses before another callback.
        const SongRenderOwnership audibleOwner = committedOwnership;
        if (!validOwnership(audibleOwner) ||
            position.selectionToken != audibleOwner.selectionToken ||
            position.generation != audibleOwner.generation) {
            return false;
        }
        lastObservedPosition = position;
        publishPositionDiagnostics(position);
        return true;
    }

    [[nodiscard]] bool consumeAppliedControlEvidence() noexcept {
        const NativeAudioAppliedControlEvidence evidence =
            output.appliedControlEvidence(output.handle);
        if (!evidence.valid() ||
            evidence.sequence <= lastObservedAppliedControlSequence) {
            return false;
        }
        const SongRenderOwnership audibleOwner = committedOwnership;
        // Like stem-gain evidence, this tuple can arrive after this cycle's presentation read.
        // Retain future-owner evidence for the next cycle; consume retired tuples only once.
        if (!validOwnership(audibleOwner) || evidence.generation > audibleOwner.generation) {
            return false;
        }
        lastObservedAppliedControlSequence = evidence.sequence;
        if (evidence.selectionToken != audibleOwner.selectionToken ||
            evidence.generation != audibleOwner.generation ||
            (evidence.actualSourceMode != NativeAudioSourceMode::Original &&
             evidence.actualSourceMode != NativeAudioSourceMode::Stems)) {
            return true;
        }
        publishAudibleControlDiagnostics(evidence);
        return true;
    }

    [[nodiscard]] bool consumeStemGainCallbackEvidence() noexcept {
        const NativeAudioStemGainCallbackEvidence evidence =
            output.stemGainCallbackEvidence(output.handle);
        if (!evidence.valid() ||
            evidence.sequence <= lastObservedStemGainCallbackSequence) {
            return false;
        }
        const SongRenderOwnership audibleOwner = committedOwnership;
        // The callback can publish this tuple after the worker's presentation read but before this
        // evidence read. Preserve a future generation's sequence so the next worker cycle can
        // consume the same latest-only tuple after first-presentation commits its owner. Retired or
        // malformed same-generation tuples are consumed once and can never reappear as current.
        if (!validOwnership(audibleOwner) || evidence.generation > audibleOwner.generation) {
            return false;
        }
        lastObservedStemGainCallbackSequence = evidence.sequence;
        if (evidence.selectionToken != audibleOwner.selectionToken ||
            evidence.generation != audibleOwner.generation) {
            return true;
        }
        stemGainCallbackDiagnostics.publish(evidence);
        return true;
    }

    [[nodiscard]] NativeAudioStemGainCallbackEvidence
    stemGainCallbackSettlementDiagnostics() const noexcept {
        NativeAudioStemGainCallbackEvidence evidence{};
        if (!stemGainCallbackDiagnostics.read(evidence)) { evidence.sequence = {}; evidence.generation = {}; evidence.selectionToken = {}; evidence.publishedSequence = {}; evidence.offeredSequence = {}; evidence.activeMask = {}; evidence.callbackSequence = {}; evidence.firstTaggedFrame = {}; evidence.lastTaggedFrame = {}; evidence.taggedFrameCount = {}; evidence.nonZeroPcmFrameCount = {}; evidence.callbackObservedAtNs = {}; }
        return evidence;
    }

    void clearAutomaticSuccessorPlan() noexcept {
        automaticSuccessor.clear();
        automaticSuccessorPendingOutput.clear();
        publishAutomaticSuccessorDiagnostics();
    }

    void installAutomaticSuccessorOwnership() noexcept {
        const SongRenderOwnership outgoing = automaticSuccessor.outgoingOwnership;
        retireStemGainPreviewForBank(
            automaticSuccessor.outgoingBank,
            outgoing);
        static_cast<void>(installCurrentSongCache(
            automaticSuccessor.successorBank,
            automaticSuccessor.successorOwnership));
        renderBank = automaticSuccessor.successorBank;
        requestedOwnership = automaticSuccessor.successorOwnership;
        directLoopState.replaceOwner();
        acceptedOwnership = automaticSuccessor.successorOwnership;
        committedOwnership = automaticSuccessor.successorOwnership;
        acceptedFirstFingerprintSequence = automaticSuccessor.firstFingerprintSequence;
        appliedOutputGeneration = automaticSuccessor.successorOwnership.generation;
        pipelineEndObserved = automaticSuccessor.successorEndObserved;
        pipelineEndOwnership = pipelineEndObserved
            ? automaticSuccessor.successorOwnership
            : SongRenderOwnership{};
        audibleEndPublishedForOwnership = outgoing;
        handoffStageOpen = false;
        handoffCommitPublished = false;
        pendingPresentationCount = 0U;
        forceLoopPositionObservation = true;
        lastPipelineBoundarySequence = 0U;
    }

    void installAppliedAutomaticSuccessorWithoutPresentation() noexcept {
        automaticSuccessor.phase =
            AutomaticSuccessorPhase::AppliedPausedAwaitingSelection;
        automaticSuccessor.cancellationDesired = false;
        installAutomaticSuccessorOwnership();
        publishOwnershipDiagnostics();
        // This is physical callback-role evidence, not audible PCM evidence. Clear the old owner's
        // presentation/position tuple rather than manufacturing a successor callback proof.
        publishPresentationDiagnostics({});
        publishPositionDiagnostics({});
        publishAutomaticSuccessorDiagnostics();
    }

    void publishAutomaticSuccessorDiagnostics() noexcept {
        const std::uint64_t queued = validOwnership(automaticSuccessor.successorOwnership)
            ? static_cast<std::uint64_t>(queuedFramesForGeneration(
                  automaticSuccessor.successorOwnership.generation))
            : 0U;
        beginGuardedPublication(automaticSuccessorDiagnosticsGuard);
        automaticSuccessorPhase.store(
            static_cast<std::uint8_t>(automaticSuccessor.phase),
            std::memory_order_relaxed);
        automaticSuccessorCancellationStatus.store(
            static_cast<std::uint8_t>(lastAutomaticDrainCancellationStatus),
            std::memory_order_relaxed);
        automaticSuccessorRevision.store(
            automaticSuccessor.revision,
            std::memory_order_relaxed);
        automaticSuccessorTransitionRevision.store(
            automaticSuccessor.transitionRevision,
            std::memory_order_relaxed);
        automaticSuccessorOutgoingToken.store(
            automaticSuccessor.outgoingOwnership.selectionToken,
            std::memory_order_relaxed);
        automaticSuccessorOutgoingGeneration.store(
            automaticSuccessor.outgoingOwnership.generation,
            std::memory_order_relaxed);
        automaticSuccessorToken.store(
            automaticSuccessor.successorOwnership.selectionToken,
            std::memory_order_relaxed);
        automaticSuccessorGeneration.store(
            automaticSuccessor.successorOwnership.generation,
            std::memory_order_relaxed);
        automaticSuccessorQueuedFrames.store(queued, std::memory_order_relaxed);
        automaticSuccessorFirstFingerprint.store(
            automaticSuccessor.firstFingerprintSequence,
            std::memory_order_relaxed);
        automaticSuccessorCancellationDesired.store(
            automaticSuccessor.cancellationDesired,
            std::memory_order_relaxed);
        automaticSuccessorEndObserved.store(
            automaticSuccessor.successorEndObserved,
            std::memory_order_relaxed);
        automaticSuccessorDiagnosticsGuard.fetch_add(1U, std::memory_order_release);
    }

    void publishOwnershipDiagnostics() noexcept {
        CurrentSongCacheKey source{};
        if (renderBank < kNativePlaybackBankCount) {
            const auto &bank = *bankStates[renderBank];
            if (bank.assigned && sameOwnership(bank.appliedIntent.ownership, requestedOwnership) &&
                sameOwnership(bank.pipeline->ownership(), requestedOwnership)) source = directCacheIdentity();
        }
        beginGuardedPublication(ownershipDiagnosticsGuard);
        requestedToken.store(requestedOwnership.selectionToken, std::memory_order_relaxed);
        requestedGeneration.store(requestedOwnership.generation, std::memory_order_relaxed);
        requestedSongIdentityHash.store(source.songIdentityHash, std::memory_order_relaxed);
        requestedOriginalContentHash.store(source.originalContentHash, std::memory_order_relaxed);
        requestedStemPackageHash.store(source.stemPackageHash, std::memory_order_relaxed);
        acceptedToken.store(acceptedOwnership.selectionToken, std::memory_order_relaxed);
        acceptedGeneration.store(acceptedOwnership.generation, std::memory_order_relaxed);
        committedToken.store(committedOwnership.selectionToken, std::memory_order_relaxed);
        committedGeneration.store(committedOwnership.generation, std::memory_order_relaxed);
        acceptedFingerprint.store(
            acceptedFirstFingerprintSequence,
            std::memory_order_relaxed);
        ownershipDiagnosticsGuard.fetch_add(1U, std::memory_order_release);
    }

    void publishPresentationDiagnostics(
        const NativeAudioPresentationEvidence &evidence) noexcept {
        beginGuardedPublication(presentationDiagnosticsGuard);
        presentationGeneration.store(evidence.generation, std::memory_order_relaxed);
        presentationToken.store(evidence.selectionToken, std::memory_order_relaxed);
        presentationFingerprint.store(evidence.fingerprintSequence, std::memory_order_relaxed);
        presentationFirstTimeline.store(evidence.firstTimelineFrame, std::memory_order_relaxed);
        presentationFirstOutput.store(evidence.firstOutputFrame, std::memory_order_relaxed);
        presentationCallbackSequence.store(evidence.callbackSequence, std::memory_order_relaxed);
        presentationPcmFrames.store(evidence.pcmFrames, std::memory_order_relaxed);
        presentationSequence.store(evidence.sequence, std::memory_order_relaxed);
        presentationDiagnosticsGuard.fetch_add(1U, std::memory_order_release);
    }

    void publishPositionDiagnostics(const NativeAudioPresentedPosition &position) noexcept {
        beginGuardedPublication(positionDiagnosticsGuard);
        positionGeneration.store(position.generation, std::memory_order_relaxed);
        positionToken.store(position.selectionToken, std::memory_order_relaxed);
        positionFingerprint.store(position.fingerprintSequence, std::memory_order_relaxed);
        positionLastTimeline.store(position.lastTimelineFrame, std::memory_order_relaxed);
        positionLastOutput.store(position.lastOutputFrame, std::memory_order_relaxed);
        positionCallbackSequence.store(position.callbackSequence, std::memory_order_relaxed);
        positionPcmFrames.store(position.pcmFrames, std::memory_order_relaxed);
        positionLoopPolicyRevision.store(
            position.loopPolicyRevision,
            std::memory_order_relaxed);
        positionLoopPolicyAdoptedRevision.store(
            position.loopPolicyAdoptedRevision,
            std::memory_order_relaxed);
        positionLoopPolicyAdoptedEnabled.store(
            position.loopPolicyAdoptedEnabled,
            std::memory_order_relaxed);
        positionLoopPolicyAdoptedAFrame.store(
            position.loopPolicyAdoptedAFrame,
            std::memory_order_relaxed);
        positionLoopPolicyAdoptedBFrameExclusive.store(
            position.loopPolicyAdoptedBFrameExclusive,
            std::memory_order_relaxed);
        positionLoopPolicyAdoptionLatencyMicros.store(
            position.loopPolicyAdoptionLatencyMicros,
            std::memory_order_relaxed);
        positionLoopPolicyInputToAtomicPublicationLatencyMicros.store(
            position.loopPolicyInputToAtomicPublicationLatencyMicros,
            std::memory_order_relaxed);
        positionLoopPolicyInputToCallbackAdoptionLatencyMicros.store(
            position.loopPolicyInputToCallbackAdoptionLatencyMicros,
            std::memory_order_relaxed);
        positionSequence.store(position.sequence, std::memory_order_relaxed);
        positionDiagnosticsGuard.fetch_add(1U, std::memory_order_release);
    }

    void publishAudibleControlDiagnostics(
        const NativeAudioAppliedControlEvidence &evidence) noexcept {
        beginGuardedPublication(audibleControlDiagnosticsGuard);
        audibleControlGeneration.store(evidence.generation, std::memory_order_relaxed);
        audibleControlToken.store(evidence.selectionToken, std::memory_order_relaxed);
        audibleControlRevision.store(evidence.controlRevision, std::memory_order_relaxed);
        audibleControlActualTempo.store(evidence.actualTempo, std::memory_order_relaxed);
        audibleControlActualPitch.store(evidence.actualPitchSemitones, std::memory_order_relaxed);
        audibleControlSourceMode.store(
            static_cast<std::uint8_t>(evidence.actualSourceMode),
            std::memory_order_relaxed);
        audibleControlFirstFingerprint.store(
            evidence.firstFingerprintSequence,
            std::memory_order_relaxed);
        audibleControlLastFingerprint.store(
            evidence.lastFingerprintSequence,
            std::memory_order_relaxed);
        audibleControlCallbackSequence.store(
            evidence.callbackSequence,
            std::memory_order_relaxed);
        audibleControlPcmFrames.store(evidence.pcmFrames, std::memory_order_relaxed);
        audibleControlSequence.store(evidence.sequence, std::memory_order_relaxed);
        audibleControlDiagnosticsGuard.fetch_add(1U, std::memory_order_release);
        // This runs on the non-real-time render worker after the callback's atomic evidence has
        // been consumed. Wake only the blocking control observer; the output consumer never touches
        // a condition, JNI, Java, or controller scheduler.
        audibleControlProgressEpoch.fetch_add(1U, std::memory_order_release);
        audibleControlProgressEpoch.notify_one();
    }

    [[nodiscard]] std::uint64_t waitForAudibleControlProgress(
        std::uint64_t afterSequence) noexcept {
        for (;;) {
            const std::uint64_t sequence =
                audibleControlSequence.load(std::memory_order_acquire);
            if (sequence > afterSequence) return sequence;
            if (runtimeStatus.load(std::memory_order_acquire) !=
                NativePlaybackRuntimeStatus::Running) {
                return 0U;
            }
            const std::uint64_t observedEpoch =
                audibleControlProgressEpoch.load(std::memory_order_acquire);
            // Close the sequence/status-to-wait race before sleeping. A publication or STOP that
            // landed after observedEpoch necessarily changed the epoch and makes wait return.
            if (audibleControlSequence.load(std::memory_order_acquire) > afterSequence ||
                runtimeStatus.load(std::memory_order_acquire) !=
                    NativePlaybackRuntimeStatus::Running) {
                continue;
            }
            audibleControlProgressEpoch.wait(observedEpoch, std::memory_order_acquire);
        }
    }

    [[nodiscard]] NativePlaybackAudibleControlEvidence audibleControlEvidence() const noexcept {
        NativePlaybackAudibleControlEvidence result{};
        if (!readStableDiagnosticsGroup(audibleControlDiagnosticsGuard, [&] {
            result.sequence =
                audibleControlSequence.load(std::memory_order_relaxed);
            result.ownership.generation =
                audibleControlGeneration.load(std::memory_order_relaxed);
            result.ownership.selectionToken =
                audibleControlToken.load(std::memory_order_relaxed);
            result.controlRevision =
                audibleControlRevision.load(std::memory_order_relaxed);
            result.actualSourceMode =
                audibleControlSourceMode.load(std::memory_order_relaxed) ==
                    static_cast<std::uint8_t>(NativeAudioSourceMode::Stems)
                ? MixerSourceMode::Stems
                : MixerSourceMode::Original;
            result.actualTempo = audibleControlActualTempo.load(std::memory_order_relaxed);
            result.actualPitchSemitones = audibleControlActualPitch.load(std::memory_order_relaxed);
            result.firstFingerprintSequence =
                audibleControlFirstFingerprint.load(std::memory_order_relaxed);
            result.lastFingerprintSequence =
                audibleControlLastFingerprint.load(std::memory_order_relaxed);
            result.callbackSequence =
                audibleControlCallbackSequence.load(std::memory_order_relaxed);
            result.pcmFrames =
                audibleControlPcmFrames.load(std::memory_order_relaxed);
        })) { result = {}; }
        return result;
    }

    template<typename Progress>
    void fillControlProgress(Progress &result) const noexcept {
        result.status = runtimeStatus.load(std::memory_order_acquire);
        if (!readStableDiagnosticsGroup(ownershipDiagnosticsGuard, [&] {
            result.requestedOwnership.generation =
                requestedGeneration.load(std::memory_order_relaxed);
            result.requestedOwnership.selectionToken =
                requestedToken.load(std::memory_order_relaxed);
            result.acceptedOwnership.generation =
                acceptedGeneration.load(std::memory_order_relaxed);
            result.acceptedOwnership.selectionToken =
                acceptedToken.load(std::memory_order_relaxed);
            result.committedOwnership.generation =
                committedGeneration.load(std::memory_order_relaxed);
            result.committedOwnership.selectionToken =
                committedToken.load(std::memory_order_relaxed);
            result.acceptedFirstFingerprintSequence =
                acceptedFingerprint.load(std::memory_order_relaxed);
        })) { result.requestedOwnership.generation = {}; result.requestedOwnership.selectionToken = {}; result.acceptedOwnership.generation = {}; result.acceptedOwnership.selectionToken = {}; result.committedOwnership.generation = {}; result.committedOwnership.selectionToken = {}; result.acceptedFirstFingerprintSequence = {}; }

        if (!readStableDiagnosticsGroup(presentationDiagnosticsGuard, [&] {
            result.presentationEvidence.sequence =
                presentationSequence.load(std::memory_order_relaxed);
            result.presentationEvidence.generation =
                presentationGeneration.load(std::memory_order_relaxed);
            result.presentationEvidence.selectionToken =
                presentationToken.load(std::memory_order_relaxed);
            result.presentationEvidence.fingerprintSequence =
                presentationFingerprint.load(std::memory_order_relaxed);
            result.presentationEvidence.firstTimelineFrame =
                presentationFirstTimeline.load(std::memory_order_relaxed);
            result.presentationEvidence.firstOutputFrame =
                presentationFirstOutput.load(std::memory_order_relaxed);
            result.presentationEvidence.callbackSequence =
                presentationCallbackSequence.load(std::memory_order_relaxed);
            result.presentationEvidence.pcmFrames =
                presentationPcmFrames.load(std::memory_order_relaxed);
        })) { result.presentationEvidence.sequence = {}; result.presentationEvidence.generation = {}; result.presentationEvidence.selectionToken = {}; result.presentationEvidence.fingerprintSequence = {}; result.presentationEvidence.firstTimelineFrame = {}; result.presentationEvidence.firstOutputFrame = {}; result.presentationEvidence.callbackSequence = {}; result.presentationEvidence.pcmFrames = {}; }

        if (!readStableDiagnosticsGroup(positionDiagnosticsGuard, [&] {
            result.presentedPosition.sequence =
                positionSequence.load(std::memory_order_relaxed);
            result.presentedPosition.generation =
                positionGeneration.load(std::memory_order_relaxed);
            result.presentedPosition.selectionToken =
                positionToken.load(std::memory_order_relaxed);
            result.presentedPosition.fingerprintSequence =
                positionFingerprint.load(std::memory_order_relaxed);
            result.presentedPosition.lastTimelineFrame =
                positionLastTimeline.load(std::memory_order_relaxed);
            result.presentedPosition.lastOutputFrame =
                positionLastOutput.load(std::memory_order_relaxed);
            result.presentedPosition.callbackSequence =
                positionCallbackSequence.load(std::memory_order_relaxed);
            result.presentedPosition.pcmFrames =
                positionPcmFrames.load(std::memory_order_relaxed);
            result.presentedPosition.loopPolicyRevision =
                positionLoopPolicyRevision.load(std::memory_order_relaxed);
            result.presentedPosition.loopPolicyAdoptedRevision =
                positionLoopPolicyAdoptedRevision.load(std::memory_order_relaxed);
            result.presentedPosition.loopPolicyAdoptedEnabled =
                positionLoopPolicyAdoptedEnabled.load(std::memory_order_relaxed);
            result.presentedPosition.loopPolicyAdoptedAFrame =
                positionLoopPolicyAdoptedAFrame.load(std::memory_order_relaxed);
            result.presentedPosition.loopPolicyAdoptedBFrameExclusive =
                positionLoopPolicyAdoptedBFrameExclusive.load(std::memory_order_relaxed);
            result.presentedPosition.loopPolicyAdoptionLatencyMicros =
                positionLoopPolicyAdoptionLatencyMicros.load(std::memory_order_relaxed);
            result.presentedPosition.loopPolicyInputToAtomicPublicationLatencyMicros =
                positionLoopPolicyInputToAtomicPublicationLatencyMicros.load(
                    std::memory_order_relaxed);
            result.presentedPosition.loopPolicyInputToCallbackAdoptionLatencyMicros =
                positionLoopPolicyInputToCallbackAdoptionLatencyMicros.load(
                    std::memory_order_relaxed);
        })) { result.presentedPosition.sequence = {}; result.presentedPosition.generation = {}; result.presentedPosition.selectionToken = {}; result.presentedPosition.fingerprintSequence = {}; result.presentedPosition.lastTimelineFrame = {}; result.presentedPosition.lastOutputFrame = {}; result.presentedPosition.callbackSequence = {}; result.presentedPosition.pcmFrames = {}; result.presentedPosition.loopPolicyRevision = {}; result.presentedPosition.loopPolicyAdoptedRevision = {}; result.presentedPosition.loopPolicyAdoptedEnabled = {}; result.presentedPosition.loopPolicyAdoptedAFrame = {}; result.presentedPosition.loopPolicyAdoptedBFrameExclusive = {}; result.presentedPosition.loopPolicyAdoptionLatencyMicros = {}; result.presentedPosition.loopPolicyInputToAtomicPublicationLatencyMicros = {}; result.presentedPosition.loopPolicyInputToCallbackAdoptionLatencyMicros = {}; }



        if (!readStableDiagnosticsGroup(audibleEndDiagnosticsGuard, [&] {
            result.audibleEnd.sequence =
                audibleEndSequence.load(std::memory_order_relaxed);
            result.audibleEnd.ownership.generation =
                audibleEndGeneration.load(std::memory_order_relaxed);
            result.audibleEnd.ownership.selectionToken =
                audibleEndToken.load(std::memory_order_relaxed);
            result.audibleEnd.directLoopPolicyRevision =
                audibleEndDirectLoopPolicyRevision.load(std::memory_order_relaxed);
        })) { result.audibleEnd.sequence = {}; result.audibleEnd.ownership.generation = {}; result.audibleEnd.ownership.selectionToken = {}; result.audibleEnd.directLoopPolicyRevision = {}; }
        result.audibleControl = audibleControlEvidence();

        if (!readStableDiagnosticsGroup(automaticSuccessorDiagnosticsGuard, [&] {
            result.automaticSuccessor.phase =
                static_cast<NativePlaybackAutomaticSuccessorPhase>(
                    automaticSuccessorPhase.load(std::memory_order_relaxed));
            result.automaticSuccessor.cancellationStatus =
                static_cast<NativeAudioDrainCancelStatus>(
                    automaticSuccessorCancellationStatus.load(std::memory_order_relaxed));
            result.automaticSuccessor.revision =
                automaticSuccessorRevision.load(std::memory_order_relaxed);
            result.automaticSuccessor.transitionRevision =
                automaticSuccessorTransitionRevision.load(std::memory_order_relaxed);
            result.automaticSuccessor.outgoingOwnership.selectionToken =
                automaticSuccessorOutgoingToken.load(std::memory_order_relaxed);
            result.automaticSuccessor.outgoingOwnership.generation =
                automaticSuccessorOutgoingGeneration.load(std::memory_order_relaxed);
            result.automaticSuccessor.successorOwnership.selectionToken =
                automaticSuccessorToken.load(std::memory_order_relaxed);
            result.automaticSuccessor.successorOwnership.generation =
                automaticSuccessorGeneration.load(std::memory_order_relaxed);
            result.automaticSuccessor.successorQueuedFrames =
                automaticSuccessorQueuedFrames.load(std::memory_order_relaxed);
            result.automaticSuccessor.firstFingerprintSequence =
                automaticSuccessorFirstFingerprint.load(std::memory_order_relaxed);
            result.automaticSuccessor.cancellationDesired =
                automaticSuccessorCancellationDesired.load(std::memory_order_relaxed);
            result.automaticSuccessor.successorEndObserved =
                automaticSuccessorEndObserved.load(std::memory_order_relaxed);
        })) { result.automaticSuccessor.phase = {}; result.automaticSuccessor.cancellationStatus = {}; result.automaticSuccessor.revision = {}; result.automaticSuccessor.transitionRevision = {}; result.automaticSuccessor.outgoingOwnership.selectionToken = {}; result.automaticSuccessor.outgoingOwnership.generation = {}; result.automaticSuccessor.successorOwnership.selectionToken = {}; result.automaticSuccessor.successorOwnership.generation = {}; result.automaticSuccessor.successorQueuedFrames = {}; result.automaticSuccessor.firstFingerprintSequence = {}; result.automaticSuccessor.cancellationDesired = {}; result.automaticSuccessor.successorEndObserved = {}; }

        for (std::size_t bank = 0U; bank < kNativePlaybackBankCount; ++bank) {
            if constexpr (std::is_same_v<Progress, NativePlaybackControlProgress>)
                result.banks[bank] = bankDiagnostics[bank].controlProgress();
            else result.banks[bank] = bankDiagnostics[bank].snapshot();
        }
        // Rendering/priming can outlast several callbacks. Read the callback's position directly
        // for the committed owner instead of reporting the worker's older observation as current.
        const NativeAudioPresentedPosition latest = output.presentedPosition(output.handle);
        if (latest.valid() && latest.generation == result.committedOwnership.generation &&
            latest.selectionToken == result.committedOwnership.selectionToken &&
            latest.callbackSequence > result.presentedPosition.callbackSequence) {
            result.presentedPosition = latest;
        }
    }

    [[nodiscard]] NativePlaybackControlProgress controlProgress() const noexcept {
        NativePlaybackControlProgress result{};
        fillControlProgress(result);
        result.readiness = renderReadinessDiagnosticsPublisher.controlProgress();
        return result;
    }

    [[nodiscard]] NativePlaybackRuntimeDiagnostics diagnostics() const noexcept {
        NativePlaybackRuntimeDiagnostics result{};
        result.status = runtimeStatus.load(std::memory_order_acquire);
        result.commandSnapshotsPublished =
            commandSnapshotsPublished.load(std::memory_order_relaxed);
        result.commandSnapshotsConsumed =
            commandSnapshotsConsumed.load(std::memory_order_relaxed);
        result.commandSnapshotsSuperseded =
            commandSnapshotsSuperseded.load(std::memory_order_relaxed);
        result.rejectedCommandSnapshots =
            rejectedCommandSnapshots.load(std::memory_order_relaxed);
        result.beatGridUpdatesPublished =
            beatGridUpdatesPublished.load(std::memory_order_relaxed);
        result.rejectedBeatGridUpdates =
            rejectedBeatGridUpdates.load(std::memory_order_relaxed);
        result.renderCycles = renderCycles.load(std::memory_order_relaxed);
        result.idleSleeps = idleSleeps.load(std::memory_order_relaxed);
        const auto resource = resources->diagnostics();
        result.canonicalCacheKey = resource.cacheKey;
        result.canonicalPcmIo = resource.pcmIo;
        result.resourceOperationsCompleted = resource.operationsCompleted;
        result.resourceLastOperationMicros = resource.lastOperationMicros;
        result.resourceMaximumOperationMicros = resource.maximumOperationMicros;
        result.resourceMaximumQueueAgeMicros = resource.maximumQueueAgeMicros;
        result.refillDeadlineMisses = refillDeadlineMisses.load(std::memory_order_relaxed);
        result.outputFlushes = outputFlushes.load(std::memory_order_relaxed);
        result.outputFlushFailures = outputFlushFailures.load(std::memory_order_relaxed);
        result.outputFlushRetries = outputFlushRetries.load(std::memory_order_relaxed);
        result.outputHandoffBegins = outputHandoffBegins.load(std::memory_order_relaxed);
        result.outputHandoffBeginRetries =
            outputHandoffBeginRetries.load(std::memory_order_relaxed);
        result.outputHandoffCommits = outputHandoffCommits.load(std::memory_order_relaxed);
        result.outputHandoffCommitRetries =
            outputHandoffCommitRetries.load(std::memory_order_relaxed);
        result.outputBlocksRendered = outputBlocksRendered.load(std::memory_order_relaxed);
        result.outputFramesRendered = outputFramesRendered.load(std::memory_order_relaxed);
        result.outputFramesAccepted = outputFramesAccepted.load(std::memory_order_relaxed);
        result.outputPartialAccepts = outputPartialAccepts.load(std::memory_order_relaxed);
        result.outputBackpressureEvents =
            outputBackpressureEvents.load(std::memory_order_relaxed);
        result.outputInvalidRenderResults =
            outputInvalidRenderResults.load(std::memory_order_relaxed);
        result.outputNeedDataEvents = outputNeedDataEvents.load(std::memory_order_relaxed);
        result.outputEndOfStreamEvents = outputEndOfStreamEvents.load(std::memory_order_relaxed);
        result.targetQueuedFrames = targetQueuedFrames.load(std::memory_order_relaxed);
        result.currentOutputQueueLowWater = currentOutputQueueLowWater.snapshot();
        result.needOriginalData = needOriginalDataDiagnostics.snapshot();
        result.needStemData = needStemDataDiagnostics.snapshot();
        result.loopBoundaryPending = loopBoundaryPendingDiagnostics.snapshot();
        result.lastOutputFingerprintSequence =
            lastOutputFingerprintSequence.load(std::memory_order_relaxed);
        result.lastRenderPayloadHash =
            lastRenderPayloadHash.load(std::memory_order_relaxed);
        if (renderWorkerStarted.load(std::memory_order_acquire)) {
            result.renderWorkerAudioPriorityRequested =
                renderWorkerAudioPriorityRequested.load(std::memory_order_relaxed);
            result.renderWorkerAudioPriorityApplied =
                renderWorkerAudioPriorityApplied.load(std::memory_order_relaxed);
            result.renderWorkerNiceValue =
                renderWorkerNiceValue.load(std::memory_order_relaxed);
            result.renderWorkerPriorityErrorCode =
                renderWorkerPriorityErrorCode.load(std::memory_order_relaxed);
            result.renderWorkerStarted = true;
        }

        result.endOrdering.commandPublished = endOrderingCommandPublished.snapshot();
        result.endOrdering.commandConsumed = endOrderingCommandConsumed.snapshot();
        result.endOrdering.pipelineEndObserved = endOrderingPipelineEndObserved.snapshot();
        result.endOrdering.outputPlayingApplied = endOrderingOutputPlayingApplied.snapshot();
        result.endOrdering.physicalDrainObserved =
            endOrderingPhysicalDrainObserved.snapshot();
        result.endOrdering.audibleEndPublished = endOrderingAudibleEndPublished.snapshot();

        fillControlProgress(result);
        return result;
    }

    NativePlaybackRuntimeConfiguration configuration;
    std::atomic<bool> diagnosticPayloadFingerprints{configuration.pipeline.diagnosticPayloadFingerprints};
    std::array<SongLaneIngressBank *, kNativePlaybackBankCount> ingressBanks;
    NativePlaybackOutputPort output;
    LatestCommandMailbox commandMailbox;
    LatestLoopValueMailbox<NativePlaybackStemGainPreview> stemGainPreviewMailbox;
    AtomicLoopPolicy atomicLoopPolicy;
    LatestLoopValueMailbox<LoopCycleIntent> loopIntentMailbox;
    LatestLoopValueMailbox<LoopCycleIngressReadyEvidence> loopIngressReadyMailbox;
    /** Repeat-One remains resident independently from explicit fixed A-B loop preparation. */
    LoopHeadPreparationIngress repeatLoopPreparation;
    GuardedAtomicSnapshot preparedHeadBinding{};
    std::atomic<std::uint64_t> preparedHeadToken{}, preparedHeadGeneration{}, preparedHeadRevision{};
    SongRenderOwnership pendingPreparedHeadOwnership{};
    std::uint64_t pendingPreparedHeadRevision{};
    LoopCycleStateMachine loopCycle;
    LoopProtocolSnapshotPublisher loopProtocolSnapshots;
    std::array<std::unique_ptr<LatestBeatGridMailbox>, kNativePlaybackBankCount>
        beatGridMailboxes;
    std::array<std::unique_ptr<BankRuntimeState>, kNativePlaybackBankCount> bankStates;
    CurrentSongPcmCache *currentSongCache{};
    enum class CacheInstallation { Building, Rebinding, Installed };
    CacheInstallation currentSongCacheInstallation{CacheInstallation::Building};
    bool currentSongCacheCreatePending{};
    CurrentSongCacheKey cacheCreationKey{};
    std::uint64_t cacheCreationIntentRevision{};
    std::size_t cacheCreationBank{kNativePlaybackBankCount};
    CurrentSongCacheKey currentSongCacheKey{};
    std::size_t currentSongCacheBank{kNativePlaybackBankCount};
    bool currentSongCacheBuildFailed{false};
    CurrentSongCacheStorageStatus currentSongCacheStorageStatus{CurrentSongCacheStorageStatus::NotRequested};
    std::unique_ptr<float[]> currentSongCacheScratch;
    std::unique_ptr<SongRenderPipeline> loopContinuationPipeline;
    PendingOutputPcm pendingOutput;
    PendingOutputPcm loopContinuationPendingOutput;
    ReusableLoopContinuation reusableLoopHead;
    PendingOutputPcm automaticSuccessorPendingOutput;
    DirectLoopCutoverPlan directLoopCutover{};
    AutomaticSuccessorPlan automaticSuccessor{};
    NativePlaybackAutomaticSuccessorIntent emptySuccessorIntent{};
    NativeAudioDrainCancelStatus lastAutomaticDrainCancellationStatus{
        NativeAudioDrainCancelStatus::NotRequested};
    std::thread worker;
    std::atomic<NativePlaybackRuntimeStatus> runtimeStatus{
        NativePlaybackRuntimeStatus::Constructed};
    std::mutex wakeMutex;
    std::condition_variable wakeCondition;
    std::atomic<std::uint64_t> wakeEpoch{0};

    NativePlaybackCommandSnapshot desiredSnapshot{};
    bool hasDesiredSnapshot{false};
    std::uint64_t consumedCommandRevision{0};
    std::uint64_t consumedStemGainPreviewMailboxPublication{0};
    NativePlaybackStemGainPreview pendingStemGainPreview{};
    bool hasPendingStemGainPreview{false};
    std::uint64_t producerLastStemGainPreviewPublication{0};
    std::uint64_t producerLastStemGainPreviewOffer{0};
    std::uint64_t lateMixControlPublicationSequence{0};
    std::array<NativeAudioLateMixControl, kNativePlaybackBankCount>
        publishedLateMixControls{};
    std::array<bool, kNativePlaybackBankCount> publishedLateMixControlReadable{};
    std::uint64_t producerLastOutputGeneration{0};
    std::array<NativePlaybackBankIntent, kNativePlaybackBankCount> producerLastBankIntents{};
#if defined(__ANDROID__)
    std::array<std::array<std::uint64_t, kSongRenderLaneCount>, kNativePlaybackBankCount>
        lastLoggedIngressFailureGeneration{};
#endif
    bool producerLastSelectionAssigned{false};
    SongRenderOwnership producerLastSelectedOwnership{};
    std::atomic<std::uint64_t> directPolicyOwnerToken{0U};
    std::atomic<std::uint64_t> directPolicyOwnerGeneration{0U};
    std::atomic<std::uint64_t> directAtomicPolicyGeneration{0U};
    std::atomic<std::uint64_t> directAtomicPolicyGenerationRevision{0U};
    std::atomic<std::uint64_t> directAtomicPolicyPublishedRevision{0U};
    std::atomic<std::uint64_t> directAtomicPolicyPublishedAtMonotonicMicros{0U};
    LoopCycleIntent producerLastLoopIntent{};
    bool hasProducerLastLoopIntent{false};
    std::uint64_t consumedLoopIntentPublication{0U};
    std::uint64_t consumedLoopIngressReadyPublication{0U};
    std::uint64_t appliedOutputGeneration{0};
    std::uint64_t preparingManualHandoffGeneration{0};
    std::uint64_t failedStopGeneration{0};
    bool selectionAssigned{false};
    std::size_t renderBank{0};
    SongRenderOwnership requestedOwnership{};
    SongRenderOwnership acceptedOwnership{};
    SongRenderOwnership committedOwnership{};
    DirectLoopState directLoopState{};
    NativePlaybackLoopPreparationFailure loopPreparationFailure{};
    std::uint64_t acceptedFirstFingerprintSequence{0};
    std::uint64_t pendingEvidenceBaseline{0};
    std::uint64_t outputFingerprintSequence{0};
    /** Process-local identity of each callback-claimable prepared continuation. */
    std::uint64_t nextDirectAtomicLoopPhysicalRevision{0U};
    std::uint64_t ingressServicePolicyBreaks{0U};
    bool managedLoopAdopted{false};
    bool forceLoopPositionObservation{false};
    LoopCycleIntent managedLoopIntent{};
    LoopCycleIntent pendingManagedLoopIntent{};
    bool hasPendingManagedLoopIntent{false};
    LoopHeadCacheSnapshot activeLoopCacheSnapshot{};
    LoopHeadCache::ReadView activeLoopCacheView{};
    LoopCycleSubmitStatus lastLoopSubmitStatus{LoopCycleSubmitStatus::InvalidIntent};
    LoopCycleCacheStatus lastLoopCacheStatus{LoopCycleCacheStatus::NotWaiting};
    LoopCycleOriginalEosStatus lastLoopOriginalEosStatus{
        LoopCycleOriginalEosStatus::NotRequired};
    LoopCycleIngressStatus lastLoopIngressStatus{LoopCycleIngressStatus::NoRequest};
    LoopCyclePositionStatus lastLoopPositionStatus{LoopCyclePositionStatus::Disabled};
    LoopCycleBoundaryStatus lastLoopBoundaryStatus{LoopCycleBoundaryStatus::NotActive};
    LoopCycleRenderCommitStatus lastLoopRenderCommitStatus{
        LoopCycleRenderCommitStatus::NoDirective};
    LoopCycleCallbackStatus lastLoopCallbackStatus{LoopCycleCallbackStatus::NoActiveLoop};
    std::uint64_t positionEvidenceSequence{0U};
    std::uint64_t lastOutputLoopEvidenceSequence{0U};
    std::uint64_t lastPipelineBoundarySequence{0U};
    std::uint64_t lastManagedObservedSourceFrame{0U};
    std::uint64_t lastManagedObservedIngressEpoch{0U};
    std::uint64_t lastManagedObservedCycle{0U};
    std::uint64_t lastManagedObservedRevision{0U};
    bool lastManagedObservedTailRetained{false};
    NativeAudioPresentationEvidence lastPresentationEvidence{};
    NativeAudioPresentedPosition lastObservedPosition{};
    std::uint64_t lastObservedAppliedControlSequence{0};
    std::uint64_t lastObservedStemGainCallbackSequence{0};
    bool handoffStageOpen{false};
    bool handoffCommitPublished{false};
    bool outputPlaying{false};
    std::uint64_t terminalSilenceAuthorizedGeneration{0U};
    std::array<PendingPresentationCommit, 2> pendingPresentationCommits{};
    std::size_t pendingPresentationCount{0};
    bool pipelineEndObserved{false};
    SongRenderOwnership pipelineEndOwnership{};
    SongRenderOwnership audibleEndPublishedForOwnership{};
    std::uint64_t audibleEndPublicationFenceRevision{0};
    SongRenderOwnership endOrderingPhysicalDrainOwnership{};
    std::uint64_t audibleEndSequenceValue{0};


    std::atomic<std::uint64_t> commandSnapshotsPublished{0};
    std::atomic<std::uint64_t> commandSnapshotsConsumed{0};
    std::atomic<std::uint64_t> commandSnapshotsSuperseded{0};
    std::atomic<std::uint64_t> rejectedCommandSnapshots{0};
    std::atomic<std::uint64_t> beatGridUpdatesPublished{0};
    std::atomic<std::uint64_t> rejectedBeatGridUpdates{0};
    std::atomic<std::uint64_t> renderCycles{0};
    std::uint64_t outputPlayingRetryAt{0U};
    std::uint64_t outputPlayingRetryCommandRevision{0U};
    std::atomic<std::uint64_t> refillDeadlineMisses{0};
    std::atomic<std::uint64_t> idleSleeps{0};
    std::atomic<std::uint64_t> outputFlushes{0};
    std::atomic<std::uint64_t> outputFlushFailures{0};
    std::atomic<std::uint64_t> outputFlushRetries{0};
    std::atomic<std::uint64_t> outputHandoffBegins{0};
    std::atomic<std::uint64_t> outputHandoffBeginRetries{0};
    std::atomic<std::uint64_t> outputHandoffCommits{0};
    std::atomic<std::uint64_t> outputHandoffCommitRetries{0};
    std::atomic<std::uint64_t> outputBlocksRendered{0};
    std::atomic<std::uint64_t> outputFramesRendered{0};
    std::atomic<std::uint64_t> outputFramesAccepted{0};
    std::atomic<std::uint64_t> outputPartialAccepts{0};
    std::atomic<std::uint64_t> outputBackpressureEvents{0};
    std::atomic<std::uint64_t> outputInvalidRenderResults{0};
    std::atomic<std::uint64_t> outputNeedDataEvents{0};
    std::atomic<std::uint64_t> outputEndOfStreamEvents{0};
    std::atomic<std::uint64_t> targetQueuedFrames{0};
    AtomicCurrentOutputQueueLowWater currentOutputQueueLowWater{};
    AtomicOutputWaitReasonDiagnostics needOriginalDataDiagnostics{};
    AtomicOutputWaitReasonDiagnostics needStemDataDiagnostics{};
    AtomicOutputWaitReasonDiagnostics loopBoundaryPendingDiagnostics{};
    std::atomic<std::uint64_t> lastOutputFingerprintSequence{0};
    std::atomic<std::uint64_t> lastRenderPayloadHash{0};
    std::atomic<bool> renderWorkerStarted{false};
    std::atomic<bool> renderWorkerAudioPriorityRequested{false};
    std::atomic<bool> renderWorkerAudioPriorityApplied{false};
    std::atomic<std::int32_t> renderWorkerNiceValue{0};
    std::atomic<std::int32_t> renderWorkerPriorityErrorCode{0};
    std::array<AtomicBankDiagnostics, kNativePlaybackBankCount> bankDiagnostics{};
    std::atomic<bool> renderReadinessRequested{false};
    std::uint64_t renderReadinessSequenceValue{0};
    AtomicRenderReadinessDiagnostics renderReadinessDiagnosticsPublisher{};

    std::atomic<std::uint64_t> ownershipDiagnosticsGuard{0};
    std::atomic<std::uint64_t> requestedToken{0};
    std::atomic<std::uint64_t> requestedGeneration{0};
    std::atomic<std::uint64_t> requestedSongIdentityHash{0};
    std::atomic<std::uint64_t> requestedOriginalContentHash{0};
    std::atomic<std::uint64_t> requestedStemPackageHash{0};
    std::atomic<std::uint64_t> acceptedToken{0};
    std::atomic<std::uint64_t> acceptedGeneration{0};
    std::atomic<std::uint64_t> committedToken{0};
    std::atomic<std::uint64_t> committedGeneration{0};
    std::atomic<std::uint64_t> acceptedFingerprint{0};

    std::atomic<std::uint64_t> automaticSuccessorDiagnosticsGuard{0};
    std::atomic<std::uint8_t> automaticSuccessorPhase{
        static_cast<std::uint8_t>(NativePlaybackAutomaticSuccessorPhase::Idle)};
    std::atomic<std::uint8_t> automaticSuccessorCancellationStatus{
        static_cast<std::uint8_t>(NativeAudioDrainCancelStatus::NotRequested)};
    std::atomic<std::uint64_t> automaticSuccessorRevision{0};
    std::atomic<std::uint64_t> automaticSuccessorTransitionRevision{0};
    std::atomic<std::uint64_t> automaticSuccessorOutgoingToken{0};
    std::atomic<std::uint64_t> automaticSuccessorOutgoingGeneration{0};
    std::atomic<std::uint64_t> automaticSuccessorToken{0};
    std::atomic<std::uint64_t> automaticSuccessorGeneration{0};
    std::atomic<std::uint64_t> automaticSuccessorQueuedFrames{0};
    std::atomic<std::uint64_t> automaticSuccessorFirstFingerprint{0};
    std::atomic<bool> automaticSuccessorCancellationDesired{false};
    std::atomic<bool> automaticSuccessorEndObserved{false};

    std::atomic<std::uint64_t> presentationDiagnosticsGuard{0};
    std::atomic<std::uint64_t> presentationSequence{0};
    std::atomic<std::uint64_t> presentationGeneration{0};
    std::atomic<std::uint64_t> presentationToken{0};
    std::atomic<std::uint64_t> presentationFingerprint{0};
    std::atomic<std::int64_t> presentationFirstTimeline{0};
    std::atomic<std::uint64_t> presentationFirstOutput{0};
    std::atomic<std::uint64_t> presentationCallbackSequence{0};
    std::atomic<std::uint64_t> presentationPcmFrames{0};

    std::atomic<std::uint64_t> positionDiagnosticsGuard{0};
    std::atomic<std::uint64_t> positionSequence{0};
    std::atomic<std::uint64_t> positionGeneration{0};
    std::atomic<std::uint64_t> positionToken{0};
    std::atomic<std::uint64_t> positionFingerprint{0};
    std::atomic<std::int64_t> positionLastTimeline{0};
    std::atomic<std::uint64_t> positionLastOutput{0};
    std::atomic<std::uint64_t> positionCallbackSequence{0};
    std::atomic<std::uint64_t> positionPcmFrames{0};
    std::atomic<std::uint64_t> positionLoopPolicyRevision{0};
    std::atomic<std::uint64_t> positionLoopPolicyAdoptedRevision{0};
    std::atomic<bool> positionLoopPolicyAdoptedEnabled{false};
    std::atomic<std::uint64_t> positionLoopPolicyAdoptedAFrame{0};
    std::atomic<std::uint64_t> positionLoopPolicyAdoptedBFrameExclusive{0};
    std::atomic<std::uint64_t> positionLoopPolicyAdoptionLatencyMicros{0};
    std::atomic<std::uint64_t> positionLoopPolicyInputToAtomicPublicationLatencyMicros{0};
    std::atomic<std::uint64_t> positionLoopPolicyInputToCallbackAdoptionLatencyMicros{0};



    std::atomic<std::uint64_t> audibleEndDiagnosticsGuard{0};
    std::atomic<std::uint64_t> audibleEndSequence{0};
    std::atomic<std::uint64_t> audibleEndToken{0};
    std::atomic<std::uint64_t> audibleEndGeneration{0};
    std::atomic<std::uint64_t> audibleEndDirectLoopPolicyRevision{0};
    std::atomic<std::uint64_t> endOrderingSequence{0};
    /** Latest complete worker checkpoint sampled by producer-side publication diagnostics. */
    AtomicEndOrderingEvent endOrderingWorkerState{};
    AtomicEndOrderingEvent endOrderingCommandPublished{};
    AtomicEndOrderingEvent endOrderingCommandConsumed{};
    AtomicEndOrderingEvent endOrderingPipelineEndObserved{};
    AtomicEndOrderingEvent endOrderingOutputPlayingApplied{};
    AtomicEndOrderingEvent endOrderingPhysicalDrainObserved{};
    AtomicEndOrderingEvent endOrderingAudibleEndPublished{};

    std::atomic<std::uint64_t> audibleControlDiagnosticsGuard{0};
    std::atomic<std::uint64_t> audibleControlProgressEpoch{0};
    std::atomic<std::uint64_t> audibleControlSequence{0};
    std::atomic<std::uint64_t> audibleControlToken{0};
    std::atomic<std::uint64_t> audibleControlGeneration{0};
    std::atomic<std::uint64_t> audibleControlRevision{0};
    std::atomic<float> audibleControlActualTempo{0.0F}, audibleControlActualPitch{0.0F};
    std::atomic<std::uint8_t> audibleControlSourceMode{0};
    std::atomic<std::uint64_t> audibleControlFirstFingerprint{0};
    std::atomic<std::uint64_t> audibleControlLastFingerprint{0};
    std::atomic<std::uint64_t> audibleControlCallbackSequence{0};
    std::atomic<std::uint64_t> audibleControlPcmFrames{0};

    StemGainCallbackPublication stemGainCallbackDiagnostics{};
    std::unique_ptr<NativePlaybackResources> resources;

};

NativePlaybackRuntime::NativePlaybackRuntime(
    const NativePlaybackRuntimeConfiguration &configuration,
    SongLaneIngressBank &firstBank,
    SongLaneIngressBank &secondBank,
    NativePlaybackOutputPort output)
    : mImpl(std::make_unique<Impl>(configuration, firstBank, secondBank, output)) {
    saturatingIncrement(gRuntimeConstructionCount);
    saturatingIncrement(gRuntimeLiveInstanceCount);
}

NativePlaybackRuntime::~NativePlaybackRuntime() {
    // Finish the worker lifetime barrier before declaring this process-global instance gone.
    mImpl.reset();
    gRuntimeLiveInstanceCount.fetch_sub(1U, std::memory_order_acq_rel);
}

bool NativePlaybackRuntime::inheritCompletedSongCache(
    NativePlaybackRuntime &previous) noexcept {
    auto &from = *previous.mImpl;
    auto &to = *mImpl;
    if (&previous == this || status() != NativePlaybackRuntimeStatus::Constructed ||
        previous.status() != NativePlaybackRuntimeStatus::Stopped || from.worker.joinable() ||
        to.ingressBanks != from.ingressBanks || to.output.handle != from.output.handle) return false;
    // The surviving output retains revision-keyed mailboxes across route recreation.
    // Never reuse a physical continuation identity already published to that output.
    to.nextDirectAtomicLoopPhysicalRevision = from.nextDirectAtomicLoopPhysicalRevision;
    const auto required = from.currentSongCacheKey.stemPackageHash == 0U
        ? CurrentSongPcmCache::kOriginalLaneMask : from.requiredBankLaneMask(from.currentSongCacheBank);
    if (from.currentSongCache != nullptr && from.currentSongCache->complete(required)) {
        // PCM belongs to the song, not the output route. The first bank command checks content
        // identity and rebinds this sealed cache to the replacement generation normally.
        if (!to.resources->inheritCacheFrom(*from.resources)) return false;
        to.currentSongCache = from.currentSongCache;
        to.currentSongCacheInstallation = from.currentSongCacheInstallation;
        from.currentSongCache = nullptr;
        from.currentSongCacheInstallation = Impl::CacheInstallation::Building;
        to.currentSongCacheKey = from.currentSongCacheKey;
        to.currentSongCacheBank = from.currentSongCacheBank;
        from.currentSongCacheKey = {};
        from.currentSongCacheBank = kNativePlaybackBankCount;
    }
    return true;
}

bool NativePlaybackRuntime::start() noexcept {
    return mImpl->start();
}

void NativePlaybackRuntime::requestStop() noexcept {
    mImpl->requestStop();
}

void NativePlaybackRuntime::join() noexcept {
    mImpl->join();
}

NativePlaybackPublishStatus NativePlaybackRuntime::publishCommand(
    const NativePlaybackCommandSnapshot &snapshot) noexcept {
    return mImpl->publishCommand(snapshot);
}

NativePlaybackPublishStatus NativePlaybackRuntime::publishStemGainPreview(
    const NativePlaybackStemGainPreview &preview) noexcept {
    return mImpl->publishStemGainPreview(preview);
}

NativePlaybackPublishStatus NativePlaybackRuntime::publishBeatGrid(
    const NativePlaybackBeatGridUpdate &update,
    const std::int64_t *beatFrames,
    const std::uint8_t *accentFlags) noexcept {
    return mImpl->publishBeatGrid(update, beatFrames, accentFlags);
}

NativePlaybackPublishStatus NativePlaybackRuntime::publishLoopCycleIntent(
    const LoopCycleIntent &intent) noexcept {
    return mImpl->publishLoopCycleIntent(intent);
}

NativePlaybackPublishStatus NativePlaybackRuntime::publishDirectLoopPolicy(
    std::uint64_t revision,
    std::uint64_t selectionToken,
    std::uint64_t generation,
    bool enabled,
    std::uint64_t aTimelineFrame,
    std::uint64_t bTimelineFrameExclusive,
    std::uint64_t inputEventAtMonotonicMicros,
    bool boundaryArmedAtPublication,
    bool boundaryCrossedAtPublication,
    std::uint64_t heldBGestureId,
    std::uint64_t capturedBTimelineFrameExclusive,
    std::int64_t captureReferenceTimelineFrame) noexcept {
    return mImpl->publishDirectLoopPolicy(
        revision,
        selectionToken,
        generation,
        enabled,
        aTimelineFrame,
        bTimelineFrameExclusive,
        inputEventAtMonotonicMicros,
        boundaryArmedAtPublication,
        boundaryCrossedAtPublication,
        heldBGestureId,
        capturedBTimelineFrameExclusive,
        captureReferenceTimelineFrame);
}

NativePlaybackPublishStatus NativePlaybackRuntime::publishLoopIngressReady(
    const LoopCycleIngressReadyEvidence &evidence) noexcept {
    return mImpl->publishLoopIngressReady(evidence);
}

LoopHeadPreparationBeginStatus NativePlaybackRuntime::beginLoopHeadPreparation(
    const LoopHeadContentKey &key,
    std::uint32_t requiredLaneMask,
    LoopHeadBoundaryMode boundaryMode, SongRenderOwnership ownership) noexcept {
    return mImpl->beginLoopHeadPreparation(key, requiredLaneMask, boundaryMode, ownership);
}

LoopHeadPreparationConfigureStatus
NativePlaybackRuntime::configureLoopHeadPreparationLane(
    std::uint64_t loopRevision,
    std::size_t laneIndex,
    std::uint32_t sourceRateHz,
    std::uint64_t preparationGeneration) noexcept {
    return mImpl->configureLoopHeadPreparationLane(
        loopRevision,
        laneIndex,
        sourceRateHz,
        preparationGeneration);
}

LoopHeadPreparationOfferResult NativePlaybackRuntime::offerLoopHeadPreparationPcm(
    std::uint64_t loopRevision,
    std::size_t laneIndex,
    const LaneInputBlock &block) noexcept {
    return mImpl->offerLoopHeadPreparationPcm(loopRevision, laneIndex, block);
}

LoopHeadPreparationEndStatus
NativePlaybackRuntime::markLoopHeadPreparationEndOfStream(
    std::uint64_t loopRevision,
    std::size_t laneIndex,
    std::uint64_t preparationGeneration,
    std::uint64_t finalSourceFrameExclusive) noexcept {
    return mImpl->markLoopHeadPreparationEndOfStream(
        loopRevision,
        laneIndex,
        preparationGeneration,
        finalSourceFrameExclusive);
}

LoopHeadPreparationAbortStatus NativePlaybackRuntime::abortLoopHeadPreparation(
    std::uint64_t loopRevision) noexcept {
    return mImpl->abortLoopHeadPreparation(loopRevision);
}

NativePlaybackLoopProtocolSnapshot
NativePlaybackRuntime::loopProtocolSnapshot() const noexcept {
    return mImpl->loopProtocolSnapshot();
}

void NativePlaybackRuntime::notifyIngressAvailable() noexcept {
    mImpl->notifyIngressAvailable();
}

std::uint64_t NativePlaybackRuntime::waitForAudibleControlProgress(
    std::uint64_t afterSequence) noexcept {
    return mImpl->waitForAudibleControlProgress(afterSequence);
}

NativeAudioStemGainCallbackEvidence
NativePlaybackRuntime::stemGainCallbackSettlementDiagnostics() const noexcept {
    return mImpl->stemGainCallbackSettlementDiagnostics();
}

void NativePlaybackRuntime::setDiagnosticPayloadFingerprints(bool enabled) noexcept {
    mImpl->diagnosticPayloadFingerprints.store(enabled, std::memory_order_relaxed);
    if (mImpl->output.setDiagnosticPayloadFingerprints != nullptr) {
        mImpl->output.setDiagnosticPayloadFingerprints(mImpl->output.handle, enabled);
    }
}

NativePlaybackRuntimeStatus NativePlaybackRuntime::status() const noexcept {
    return mImpl->runtimeStatus.load(std::memory_order_acquire);
}

NativePlaybackAudibleControlEvidence NativePlaybackRuntime::audibleControlEvidence() const noexcept {
    return mImpl->audibleControlEvidence();
}

NativePlaybackControlProgress NativePlaybackRuntime::controlProgress() const noexcept {
    return mImpl->controlProgress();
}

NativePlaybackRuntimeDiagnostics NativePlaybackRuntime::diagnostics() const noexcept {
    NativePlaybackRuntimeDiagnostics result = mImpl->diagnostics();
    // Creation publishes construction before live. Reading live first preserves
    // construction >= live even if another runtime is being created concurrently.
    const std::uint64_t liveInstances =
        gRuntimeLiveInstanceCount.load(std::memory_order_acquire);
    result.processConstructionCount =
        gRuntimeConstructionCount.load(std::memory_order_acquire);
    result.processLiveInstanceCount = liveInstances;
    result.processOutputInvalidRenderResults =
        gRuntimeOutputInvalidRenderResultCount.load(std::memory_order_acquire);
    result.processOutputFlushFailures =
        gRuntimeOutputFlushFailureCount.load(std::memory_order_acquire);
    return result;
}

NativePlaybackRenderReadinessDiagnostics
NativePlaybackRuntime::renderReadinessDiagnostics() noexcept {
    return mImpl->requestRenderReadinessDiagnostics();
}

static_assert(std::atomic<std::uint64_t>::is_always_lock_free);
std::uint64_t NativePlaybackRuntime::waitPcmCacheProgress(std::uint64_t after) noexcept {
    return mImpl->resources->waitPcmCacheProgress(after);
}
std::uint64_t NativePlaybackRuntime::beginPcmWindowRead(const CurrentSongCacheKey &key, std::size_t lane,
    std::uint64_t first, std::size_t frames) noexcept {
    return status() == NativePlaybackRuntimeStatus::Running ? mImpl->resources->beginPcmWindowRead(key, lane, first, frames) : 0U;
}
int NativePlaybackRuntime::pollPcmWindowRead(std::uint64_t ticket, float *samples, std::size_t frames) noexcept {
    return mImpl->resources->pollPcmWindowRead(ticket, samples, frames);
}
void NativePlaybackRuntime::cancelPcmWindowRead(std::uint64_t ticket) noexcept { mImpl->resources->cancelPcmWindowRead(ticket); }
bool NativePlaybackRuntime::readPcmCacheForAudit(const CurrentSongCacheKey &key, std::size_t lane,
    std::uint64_t first, float *samples, std::size_t frames) noexcept {
    if (status() != NativePlaybackRuntimeStatus::Stopped && status() != NativePlaybackRuntimeStatus::Running) return false;
    return mImpl->resources->readPcmCacheForAudit(key, lane, first, samples, frames);
}
bool NativePlaybackRuntime::pcmCacheRangeReady(const CurrentSongCacheKey &key, std::uint32_t mask,
    std::uint64_t first, std::uint64_t end) noexcept {
    return status() == NativePlaybackRuntimeStatus::Running &&
        (mImpl->resources->pcmCacheRangeReady(key, mask, first, end) || mImpl->boundedLoopRangeReady(key, mask, first, end));
}
static_assert(std::atomic<std::size_t>::is_always_lock_free);
static_assert(std::atomic<NativePlaybackRuntimeStatus>::is_always_lock_free);

}  // namespace stemwise::audio::engine
