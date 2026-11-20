#include "NativePacketSchema.h"
#include "NativePacketEnumChecks.h"
#include "NativeAudioOutputCallbackCore.h"
#include "NativeAudioBackend.h"
#include <algorithm>
#include <jni.h>
#include <memory>

namespace stemwise::audio {
namespace {
#if defined(STEMWISE_CACHE_IO_PROFILING)
std::atomic<std::uint64_t> gTraceStreamEpoch{0U};
#endif
void throwJavaException(JNIEnv *env, const char *className, const char *message) {
    if (env->ExceptionCheck() == JNI_TRUE) return;
    jclass exceptionClass = env->FindClass(className);
    if (exceptionClass != nullptr) env->ThrowNew(exceptionClass, message);
}
}
class NativeAudioOutputEngine final : public NativeAudioOutputCallbackCore {
public:
    NativeAudioOutputEngine(std::size_t ringCapacityFrames, std::int32_t requestedSampleRate,
                            std::uint64_t initialGeneration)
        : NativeAudioOutputCallbackCore(ringCapacityFrames, requestedSampleRate, initialGeneration) {}
    ~NativeAudioOutputEngine() override { close(); }
    void trackWake() noexcept {
        mTrackWakeEpoch.fetch_add(1U, std::memory_order_release);
        mTrackWakeEpoch.notify_all();
    }
    void trackAwaitControl(std::uint64_t after) noexcept {
        mTrackWakeEpoch.wait(after, std::memory_order_acquire);
    }
    struct TrackControlWake final {
        NativeAudioOutputEngine &owner;
        ~TrackControlWake() { owner.trackWake(); }
    };
    bool open() {
        if (mRequestedSampleRate != 48'000 || !configurePushConsumer(packet::TRACK_BLOCK_FRAMES)) return false;
        mAudioApi.store(static_cast<std::int32_t>(NativeAudioBackend::AudioTrack));
        mFramesPerBurst.store(-1);
        return true;
    }
    void close() noexcept {
        TrackControlWake notify{*this};
        if (mClosed.exchange(true, std::memory_order_acq_rel)) return;
        mPlaying.store(false, std::memory_order_release);
        mEffectiveRenderPlaying.store(false, std::memory_order_release);
        auto lock = mStreamLifecycle.acquire();
        mClosing.store(true, std::memory_order_release);
        mObservedStreamState.store(static_cast<std::int32_t>(NativeAudioStreamState::Closed), std::memory_order_release);
    }
    [[nodiscard]] bool setPlaying(bool playing) noexcept override {
        TrackControlWake notify{*this};
        auto lock = mStreamLifecycle.acquire();
        if (mClosed.load(std::memory_order_acquire)) return false;
        mPlaying.store(playing, std::memory_order_release);
        if (playing && !mTrackSessionSuspended) mTrackSessionActive = true;
        return true;
    }
    [[nodiscard]] bool quiesceIfPaused() noexcept override {
        auto lock = mStreamLifecycle.acquire();
        if (mClosed.load(std::memory_order_acquire) || mPlaying.load(std::memory_order_acquire)) return false;
        mTrackSessionActive = false;
        if (mTrackConsumerActive.load(std::memory_order_acquire)) return false;
        adoptHandoffAfterPhysicalPause();
        return true;
    }
    bool needsRecovery() noexcept { return mRecoveryRequired.load(std::memory_order_acquire); }
    bool reopenForRouteChange(std::uint64_t generation) {
        if (mClosed.load(std::memory_order_acquire) || generation <= mRoles.producerGeneration()) return false;
        {
            auto lock = mStreamLifecycle.acquire();
            // A pending reset has already spent its generation; recovery must finish at least it.
            generation = std::max(generation, mTrackResetGeneration);
            // Kotlin may already have replaced a failed feeder before this generation reset.
            const bool replacementInstalled = mRecoveryRequired.load(std::memory_order_acquire) &&
                mTrackInstance > mTrackFailureInstance;
            mTrackRecreate.store(!replacementInstalled, std::memory_order_release);
        }
        if (!preparePushGenerationChange(generation)) return false;
        struct Completion {
            NativeAudioOutputEngine &owner;
            std::uint64_t generation;
            bool accepted{false};
            ~Completion() { owner.completePushGenerationChange(generation, accepted); }
        } completion{*this, generation};
        auto lock = mStreamLifecycle.acquire();
        if (mClosed.load(std::memory_order_acquire) ||
            generation <= mRoles.producerGeneration()) {
            return false;
        }

        if (!resetOutputAfterConsumerStopped(generation)) return false;
        mRecoveryRequired.store(false, std::memory_order_release);
        mTrackSessionSuspended = false;
        completion.accepted = true;
        return true;
    }
    // Kotlin has joined the feeder and verified both sink and native-consumer retirement.
    bool retireForStop(std::uint64_t generation) noexcept {
        TrackControlWake notify{*this};
        auto lock = mStreamLifecycle.acquire();
        if (mTrackConsumerActive.load(std::memory_order_acquire) ||
            !resetOutputAfterConsumerStopped(generation)) return false;
        mTrackSessionActive = false;
        mTrackSessionSuspended = true;
        mTrackResetGeneration = 0U;
        mTrackRecreate.store(false, std::memory_order_release);
        mTrackResetRecreate = mTrackResetSourceOnly = false;
        mObservedStreamState.store(static_cast<std::int32_t>(NativeAudioStreamState::Closed),
            std::memory_order_release);
        mTrackSinkRetired = true;
        return true;
    }

    NativeAudioAudiblePosition audiblePosition() const noexcept override {
        auto lock = mStreamLifecycle.acquire();
        return mTrackAudiblePosition;
    }
    std::array<jlong, kDiagnosticsFieldCount> diagnostics() const noexcept {
        return NativeAudioOutputCallbackCore::diagnostics<jlong>(mTrackRawUnderruns.load(std::memory_order_acquire),
            mObservedStreamState.load(std::memory_order_acquire), mActualBufferSize.load(std::memory_order_acquire));
    }
    void trackControl(jlong *values) noexcept {
#if defined(STEMWISE_CACHE_IO_PROFILING)
        AudioTrace trace("StemWise.audio.track.control");
#endif
        auto lock = mStreamLifecycle.acquire();
        if (mTrackResetCancelGeneration != 0U) {
            if (mTrackResetCancelGeneration != mTrackResetGeneration) mTrackResetCancelGeneration = 0U;
            else if (mTrackResetSourceOnly && cancelPushSourceTransition(mTrackResetCancelGeneration)) {
                mTrackResetGeneration = mTrackResetCancelGeneration = 0U;
                mTrackResetSourceOnly = false;
                mTrackResetAcknowledged = mTrackResetSerial;
            }
        }
        values[packet::TRACK_CONTROL_PLAYING] = mPlaying.load(std::memory_order_acquire) && mTrackResetGeneration == 0U &&
            !mRecoveryRequired.load(std::memory_order_acquire) && !mClosed.load(std::memory_order_acquire);
        values[packet::TRACK_CONTROL_RESET_SERIAL] = static_cast<jlong>(mTrackResetSerial);
        values[packet::TRACK_CONTROL_RESET_ACTION] = mTrackResetRecreate ? 1 : mTrackResetSourceOnly ? 2 : 0;
        values[packet::TRACK_CONTROL_GENERATION] = static_cast<jlong>(producerGeneration());
        values[packet::TRACK_CONTROL_SESSION_ACTIVE] = mTrackSessionActive && !mTrackSessionSuspended &&
            !mRecoveryRequired.load(std::memory_order_acquire) && !mClosed.load(std::memory_order_acquire);
        values[packet::TRACK_CONTROL_SOURCE_TARGET] = static_cast<jlong>(mTrackResetGeneration);
        values[packet::TRACK_CONTROL_WAKE_SEQUENCE] = static_cast<jlong>(mTrackWakeEpoch.load(std::memory_order_acquire));
#if defined(STEMWISE_CACHE_IO_PROFILING)
        trace.counter("StemWise.audio.track.reset_requested", saturatedCounter(mTrackResetSerial));
        trace.counter("StemWise.audio.track.reset_acknowledged", saturatedCounter(mTrackResetAcknowledged));
        trace.counter("StemWise.audio.track.reset_generation", saturatedCounter(mTrackResetGeneration));
        trace.counter("StemWise.audio.track.play_intent", values[packet::TRACK_CONTROL_PLAYING]);
#endif
    }
    void setSessionSuspended(bool suspended) noexcept {
        TrackControlWake notify{*this};
        auto lock = mStreamLifecycle.acquire();
        mTrackSessionSuspended = suspended;
        mTrackSessionActive = !suspended && mPlaying.load(std::memory_order_acquire);
    }
    bool trackBeginSourceTransition(std::uint64_t serial, std::uint64_t generation) noexcept {
        auto lock = mStreamLifecycle.acquire();
        if (!mTrackResetSourceOnly || generation == 0U ||
            serial != mTrackResetSerial || generation != mTrackResetGeneration) return false;
        if (!beginPushSourceTransition(generation)) return false;
        mTrackResetAcknowledged = serial;
        return true;
    }
    void cancelSourceTransition(std::uint64_t generation) noexcept {
        TrackControlWake notify{*this};
        auto lock = mStreamLifecycle.acquire();
        if (mTrackResetGeneration == generation && mTrackResetSourceOnly)
            mTrackResetCancelGeneration = generation;
    }
    bool trackConsumerActive(bool active) noexcept {
        const bool accepted = active ? beginPushConsumer() : quiescePushConsumer();
        if (accepted) {
            const bool wasActive = mTrackConsumerActive.exchange(active, std::memory_order_acq_rel);
            if (active && !wasActive)
                mPhysicalRunRestartPending.store(true, std::memory_order_release);
            // A parked feeder cannot refresh this flag by running another render callback.
            if (!active) mEffectiveRenderPlaying.store(false, std::memory_order_release);
        }
        return accepted;
    }
    void trackConfigure(std::uint64_t instance, const jint *actual) noexcept {
        auto lock = mStreamLifecycle.acquire();
        // A newly presented sink revokes retirement authority even if its format is rejected.
        if (instance > mTrackInstance) mTrackSinkRetired = false;
        if (instance <= mTrackInstance || actual[packet::TRACK_CONFIGURATION_RATE] != 48'000 ||
            actual[packet::TRACK_CONFIGURATION_CHANNELS] != 2 || actual[packet::TRACK_CONFIGURATION_ENCODING] != 4 || actual[packet::TRACK_CONFIGURATION_BUFFER] < 512 || actual[packet::TRACK_CONFIGURATION_BUFFER] > static_cast<jint>(packet::TRACK_MAXIMUM_BUFFER_FRAMES) || actual[packet::TRACK_CONFIGURATION_BUFFER] > actual[packet::TRACK_CONFIGURATION_CAPACITY] ||
            actual[packet::TRACK_CONFIGURATION_THRESHOLD] <= 0 || actual[packet::TRACK_CONFIGURATION_THRESHOLD] > actual[packet::TRACK_CONFIGURATION_BUFFER] || actual[packet::TRACK_CONFIGURATION_PERFORMANCE] < 0 || actual[packet::TRACK_CONFIGURATION_PERFORMANCE] > 1) {
            recordOpenFailure(NativeAudioOutputError::ErrorInvalidFormat);
            return;
        }
        if (mTrackInstance != 0U) saturatingIncrement(mReopenCount);
        mTrackInstance = instance;
        if (mRecoveryRequired.load(std::memory_order_acquire) && instance > mTrackFailureInstance &&
            mTrackResetRecreate) {
            // A restarted feeder has completed the physical replacement. Its own source/consumer
            // acknowledgement must still retire the pending reset without opening a second sink.
            mTrackRecreate.store(false, std::memory_order_release);
            mTrackResetRecreate = false;
            mTrackResetSourceOnly = true;
            ++mTrackResetSerial;
        }
#if defined(STEMWISE_CACHE_IO_PROFILING)
        mTraceStreamEpoch = gTraceStreamEpoch.fetch_add(1U, std::memory_order_relaxed) + 1U;
#endif
        mStreamOutputFrameBase = pushSubmittedFrames();
        mTrackEpochNanos = monotonicNanos();
        mTrackLastHead = 0U;
        mTrackHeadResetObserved = false;
        mTrackAudiblePosition = {};
        mAccountedStreamXRunCount = 0;
        mTrackRawUnderruns.store(-1);
        mTrackIncompleteObservationRecorded = false;
        mActualSampleRate.store(actual[packet::TRACK_CONFIGURATION_RATE]);
        mActualChannelCount.store(actual[packet::TRACK_CONFIGURATION_CHANNELS]);
        mActualBufferSize.store(actual[packet::TRACK_CONFIGURATION_BUFFER]);
        mOutputBufferCapacity.store(actual[packet::TRACK_CONFIGURATION_CAPACITY]);
        mPerformanceMode.store(actual[packet::TRACK_CONFIGURATION_PERFORMANCE]);
        mObservedStreamState.store(static_cast<std::int32_t>(NativeAudioStreamState::Open));
    }
    void trackResetComplete(std::uint64_t serial) noexcept {
        auto lock = mStreamLifecycle.acquire();
        if (serial != mTrackResetSerial) return; // A superseded reset cannot release its replacement.
        if (mTrackConsumerActive.load(std::memory_order_acquire) ||
            !mTrackHeadResetObserved) {
            recordOpenFailure(NativeAudioOutputError::ErrorInvalidState);
            return;
        }
        mStreamOutputFrameBase = pushSubmittedFrames();
        mTrackEpochNanos = monotonicNanos();
        mTrackLastHead = 0U;
        mTrackAudiblePosition = {};
        mTrackResetAcknowledged = serial;
    }
    void trackObserve(std::uint64_t instance, const jint *actual, const jlong *position, bool final) noexcept {
#if defined(STEMWISE_CACHE_IO_PROFILING)
        AudioTrace trace("StemWise.audio.track.presentation_observation");
        trace.counter("StemWise.audio.track.instance", saturatedCounter(instance));
        trace.counter("StemWise.audio.track.head", position[packet::TRACK_POSITION_HEAD]);
        trace.counter("StemWise.audio.track.submitted", position[packet::TRACK_POSITION_SUBMITTED]);
#endif
        auto lock = mStreamLifecycle.acquire();
        if (instance != mTrackInstance) return;
        mTrackRawUnderruns.store(actual[packet::TRACK_ACTUAL_UNDERRUNS], std::memory_order_release);
        if (actual[packet::TRACK_ACTUAL_UNDERRUNS] >= 0) accountStreamXRunsLocked(actual[packet::TRACK_ACTUAL_UNDERRUNS]);
        else if (final) {
            if (!mTrackIncompleteObservationRecorded) {
                saturatingIncrement(gOutputIncompleteStreamObservationCount);
                mTrackIncompleteObservationRecorded = true;
            }
            recordOpenFailure(NativeAudioOutputError::ErrorUnavailable);
        }
        mObservedStreamState.store(actual[packet::TRACK_ACTUAL_STATE], std::memory_order_release);
        mActualBufferSize.store(actual[packet::TRACK_ACTUAL_BUFFER], std::memory_order_release);
        if (position[packet::TRACK_POSITION_HEAD] == 0 && actual[packet::TRACK_ACTUAL_STATE] != static_cast<std::int32_t>(NativeAudioStreamState::Started))
            mTrackHeadResetObserved = true;
        // Head progress is a presentation estimate; timestamps and acoustic capture remain separate.
        if (actual[packet::TRACK_ACTUAL_STATE] != static_cast<std::int32_t>(NativeAudioStreamState::Started) ||
            !mTrackHeadResetObserved || position[packet::TRACK_POSITION_HEAD] <= 0 || position[packet::TRACK_POSITION_SUBMITTED] <= 0 ||
            position[packet::TRACK_POSITION_HEAD] > position[packet::TRACK_POSITION_SUBMITTED] ||
            static_cast<std::uint64_t>(position[packet::TRACK_POSITION_HEAD]) < mTrackLastHead) return;
        const auto observed = static_cast<std::uint64_t>(position[packet::TRACK_POSITION_HEAD] - 1);
        if (mStreamOutputFrameBase > std::numeric_limits<std::uint64_t>::max() - observed) return;
        const auto absolute = mStreamOutputFrameBase + observed;
        if (observePushPresentation(absolute)) {
            mTrackLastHead = static_cast<std::uint64_t>(position[packet::TRACK_POSITION_HEAD]);
        }
        if (position[packet::TRACK_POSITION_TIMESTAMP_FRAME] > 0 && position[packet::TRACK_POSITION_TIMESTAMP_FRAME] <= position[packet::TRACK_POSITION_HEAD] && position[packet::TRACK_POSITION_TIMESTAMP_NANOS] > 0 &&
            static_cast<std::uint64_t>(position[packet::TRACK_POSITION_TIMESTAMP_NANOS]) >= mTrackEpochNanos)
            mTrackAudiblePosition = mAudibleTimelineHistory.position(
                mStreamOutputFrameBase + static_cast<std::uint64_t>(position[packet::TRACK_POSITION_TIMESTAMP_FRAME] - 1));
    }
    void trackFailed() noexcept {
        auto lock = mStreamLifecycle.acquire();
        recordOpenFailure(NativeAudioOutputError::ErrorInternal);
    }

protected:
    bool preparePushGenerationChange(std::uint64_t generation) noexcept override {
        TrackControlWake notify{*this};
        auto lock = mStreamLifecycle.acquire();
        // A never-opened or explicitly retired sink owns no Java suffix or device frames.
        if (mTrackSinkRetired && !mTrackConsumerActive.load(std::memory_order_acquire)) return true;
        if (mTrackResetGeneration == 0U) {
            mTrackResetGeneration = generation;
            ++mTrackResetSerial;
            if (mTrackRecreate.load(std::memory_order_acquire))
                mTrackHeadResetObserved = false;
            mTrackResetRecreate = mTrackRecreate.load(std::memory_order_acquire);
            mTrackResetSourceOnly = !mTrackResetRecreate;
        } else {
            // Newer intent may supersede a cancelled handoff while the same writer is parked.
            if (generation > mTrackResetGeneration) {
                mTrackResetGeneration = generation;
                ++mTrackResetSerial;
            }
            if (mTrackRecreate.load(std::memory_order_acquire) && !mTrackResetRecreate) {
                mTrackResetRecreate = true;
                mTrackResetSourceOnly = false;
                ++mTrackResetSerial;
                mTrackHeadResetObserved = false;
            }
        }
        return mTrackResetGeneration == generation && mTrackResetAcknowledged == mTrackResetSerial &&
            (mTrackResetSourceOnly ||
                !mTrackConsumerActive.load(std::memory_order_acquire));
    }
    void completePushGenerationChange(std::uint64_t generation, bool accepted) noexcept override {
        TrackControlWake notify{*this};
        auto lock = mStreamLifecycle.acquire();
        if (!accepted) return;
        if (mTrackResetGeneration == generation) {
            if (!mPlaying.load(std::memory_order_acquire) &&
                mRoles.activeGeneration() == generation && outputRingQueuedFrames(mCallbackRing) == 0U)
                mTrackSessionActive = false;
            if (mTrackResetRecreate) mTrackSessionSuspended = false;
            mTrackResetGeneration = 0U;
            mTrackRecreate.store(false, std::memory_order_release);
            mTrackResetRecreate = false;
            mTrackResetSourceOnly = false;
        }
    }
private:
    void accountStreamXRunsLocked(std::int32_t rawCount) const noexcept {
        const std::int32_t currentCount = rawCount;
        if (currentCount < 0) return;
        if (currentCount > mAccountedStreamXRunCount) {
            saturatingAdd(
                gOutputXRunCount,
                static_cast<std::uint64_t>(currentCount - mAccountedStreamXRunCount));
#if defined(STEMWISE_CACHE_IO_PROFILING)
            AudioTrace trace("StemWise.audio.stream.xrun");
            trace.counter("StemWise.audio.stream.epoch", saturatedCounter(mTraceStreamEpoch));
            trace.counter("StemWise.audio.stream.raw_xruns", currentCount);
            trace.counter("StemWise.audio.stream.previous_raw_xruns", mAccountedStreamXRunCount);
            trace.counter("StemWise.audio.stream.xrun_delta", currentCount - mAccountedStreamXRunCount);
            trace.counter("StemWise.audio.stream.process_xruns", saturatedCounter(gOutputXRunCount.load(std::memory_order_relaxed)));
#endif
        }
        // Rebase if a platform stream unexpectedly resets its own counter. A later increase is
        // still counted once, while a route reopen starts at zero on the next track configuration.
        mAccountedStreamXRunCount = currentCount;
    }
    void recordOpenFailure(NativeAudioOutputError result) noexcept {
        TrackControlWake notify{*this};
        mLastError.store(static_cast<std::int32_t>(result), std::memory_order_release);
        mLastErrorRealtimeMillis.store(
            monotonicMicros() / 1'000LL,
            std::memory_order_release);
        mTrackFailureInstance = mTrackInstance;
        mRecoveryRequired.store(true, std::memory_order_release);
        mObservedStreamState.store(
            static_cast<std::int32_t>(NativeAudioStreamState::Closed),
            std::memory_order_release);
    }
    std::atomic<bool> mTrackConsumerActive{false}, mTrackRecreate{false};
    std::atomic<std::uint64_t> mTrackWakeEpoch{0U};
    bool mTrackSessionActive{false}, mTrackSessionSuspended{false};
    bool mTrackSinkRetired{true};
    std::atomic<std::int32_t> mTrackRawUnderruns{-1};
    std::uint64_t mTrackInstance{0U}, mTrackFailureInstance{0U}, mTrackEpochNanos{0U}, mTrackLastHead{0U};
    std::uint64_t mTrackResetGeneration{0U}, mTrackResetSerial{0U}, mTrackResetAcknowledged{0U};
    std::uint64_t mTrackResetCancelGeneration{0U};
    NativeAudioAudiblePosition mTrackAudiblePosition{};
    bool mTrackHeadResetObserved{false}, mTrackIncompleteObservationRecorded{false};
    bool mTrackResetRecreate{false}, mTrackResetSourceOnly{false};
#if defined(STEMWISE_CACHE_IO_PROFILING)
    std::uint64_t mTraceStreamEpoch{0U};
#endif
};

static_assert(std::atomic<float>::is_always_lock_free);
static_assert(std::atomic<bool>::is_always_lock_free);
static_assert(std::atomic<std::uint32_t>::is_always_lock_free);
static_assert(std::atomic<std::uint8_t>::is_always_lock_free);

}  // namespace stemwise::audio

namespace {

using stemwise::audio::NativeAudioOutputEngine;
using stemwise::audio::kPresentationJournalCapacity;
using stemwise::audio::kPresentationJournalEntryFieldCount;
using stemwise::audio::kPresentationJournalHeaderFieldCount;
using stemwise::audio::kPresentationJournalSchemaVersion;
using stemwise::audio::saturatedCounter;
using stemwise::audio::throwJavaException;

NativeAudioOutputEngine *engineFromHandle(jlong handle) noexcept {
    return static_cast<NativeAudioOutputEngine *>(reinterpret_cast<stemwise::audio::NativeAudioOutputCallbackCore *>(static_cast<std::uintptr_t>(handle)));
}

jlong handleFromEngine(NativeAudioOutputEngine *engine) noexcept {
    return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(static_cast<stemwise::audio::NativeAudioOutputCallbackCore *>(engine)));
}

}  // namespace

#include "NativeAudioOutputJni.inc"
