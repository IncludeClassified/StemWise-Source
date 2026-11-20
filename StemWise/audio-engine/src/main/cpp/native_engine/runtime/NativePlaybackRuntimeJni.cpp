#include "NativePacketSchema.h"
#include "NativePacketEnumChecks.h"
#include "NativePlaybackRuntime.h"
#include "NativePlaybackCommandDecoder.h"

#include <jni.h>

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <stdexcept>
#include <tuple>
#include <unordered_map>
#include <vector>

namespace {

using stemwise::audio::MetronomeMode;
using stemwise::audio::MixerSourceMode;
using stemwise::audio::NativeAudioOutputHandle;
using stemwise::audio::engine::NativePlaybackBank;
using stemwise::audio::engine::NativePlaybackAutomaticSuccessorIntent;
using stemwise::audio::engine::NativePlaybackBeatGridUpdate;
using stemwise::audio::engine::NativePlaybackCommandSnapshot;
using stemwise::audio::engine::NativePlaybackPublishStatus;
using stemwise::audio::engine::NativePlaybackRuntime;
using stemwise::audio::engine::NativePlaybackRuntimeConfiguration;
using stemwise::audio::engine::NativePlaybackRuntimeDiagnostics;
using stemwise::audio::engine::NativePlaybackRenderReadinessDiagnostics;
using stemwise::audio::engine::NativePlaybackStemGainPreview;
using stemwise::audio::engine::SongLaneIngressBank;
using stemwise::audio::engine::LaneInputBlock;
using stemwise::audio::engine::LaneInputFormat;
using stemwise::audio::engine::LanePcmEncoding;
using stemwise::audio::engine::LoopCycleIngressReadyEvidence;
using stemwise::audio::engine::LoopCycleIntent;
using stemwise::audio::engine::LoopHeadContentKey;
using stemwise::audio::engine::LoopHeadPreparationOfferResult;
using stemwise::audio::engine::NativePlaybackLoopProtocolSnapshot;

using namespace stemwise::audio::engine::command_codec;

constexpr jlong kBeatGridSchemaVersion = 3;
constexpr jlong kDiagnosticsSchemaVersion = stemwise::audio::packet::RUNTIME_DIAGNOSTICS_VERSION;
constexpr std::size_t kDiagnosticsValueCount = stemwise::audio::packet::RUNTIME_DIAGNOSTICS_COUNT;
constexpr jlong kRenderReadinessDiagnosticsSchemaVersion = stemwise::audio::packet::RENDER_READINESS_VERSION;
constexpr std::size_t kRenderReadinessDiagnosticsValueCount = stemwise::audio::packet::RENDER_READINESS_COUNT;
constexpr std::size_t kMaximumBeatGridPoints = stemwise::audio::engine::kMaximumNativeBeatGridPoints;
constexpr jlong kLoopIntentSchemaVersion = stemwise::audio::packet::LOOP_INTENT_VERSION;
constexpr std::size_t kLoopIntentValueCount = stemwise::audio::packet::LOOP_INTENT_COUNT;
constexpr jlong kLoopIngressReadySchemaVersion = stemwise::audio::packet::LOOP_INGRESS_READY_VERSION;
constexpr std::size_t kLoopIngressReadyValueCount = stemwise::audio::packet::LOOP_INGRESS_READY_COUNT;
constexpr jlong kLoopProtocolSchemaVersion = stemwise::audio::packet::LOOP_PROTOCOL_VERSION;
constexpr std::size_t kLoopProtocolValueCount = stemwise::audio::packet::LOOP_PROTOCOL_COUNT;
constexpr jlong kStemGainCallbackDiagnosticsSchemaVersion = stemwise::audio::packet::STEM_GAIN_CALLBACK_VERSION;
constexpr std::size_t kStemGainCallbackDiagnosticsValueCount = stemwise::audio::packet::STEM_GAIN_CALLBACK_COUNT;
constexpr std::size_t kDiagnosticsBankBase = stemwise::audio::packet::RUNTIME_DIAGNOSTICS_BANK_BASE;
constexpr std::size_t kDiagnosticsBankStride = stemwise::audio::packet::RUNTIME_DIAGNOSTICS_BANK_STRIDE;
constexpr std::size_t kReadinessLaneBase = stemwise::audio::packet::RENDER_READINESS_LANE_BASE;
constexpr std::size_t kReadinessLaneStride = stemwise::audio::packet::RENDER_READINESS_LANE_STRIDE;
constexpr std::size_t kStemGainBitsBase = stemwise::audio::packet::STEM_GAIN_CALLBACK_GAIN_BITS_BASE;
constexpr std::size_t kLoopCacheChecksumsBase = stemwise::audio::packet::LOOP_PROTOCOL_LANE_CHECKSUM_BASE;
static_assert(kDiagnosticsBankBase + kCommandBankCount * kDiagnosticsBankStride <= kDiagnosticsValueCount);
static_assert(kReadinessLaneBase + stemwise::audio::engine::SongLaneIngressBank::kLaneCount * kReadinessLaneStride <= kRenderReadinessDiagnosticsValueCount);
static_assert(kStemGainBitsBase + stemwise::audio::kStemLaneCount <= kStemGainCallbackDiagnosticsValueCount);
static_assert(kLoopCacheChecksumsBase + stemwise::audio::engine::SongLaneIngressBank::kLaneCount <= kLoopProtocolValueCount);


static_assert(sizeof(jlong) == sizeof(std::int64_t));
static_assert(sizeof(jfloat) == sizeof(float));

struct RuntimeHolder final {
    RuntimeHolder(
        const NativePlaybackRuntimeConfiguration &configuration,
        SongLaneIngressBank &firstBank,
        SongLaneIngressBank &secondBank,
        NativeAudioOutputHandle outputHandle)
        : runtime(
              configuration,
              firstBank,
              secondBank,
              stemwise::audio::engine::borrowNativeAudioOutputPort(outputHandle)) {}

    NativePlaybackRuntime runtime;
};

std::mutex gRuntimeRegistryMutex;
std::unordered_map<jlong, std::shared_ptr<RuntimeHolder>> gRuntimeRegistry;
std::uint64_t gNextRuntimeHandle = 1U;

void throwJava(JNIEnv *environment, const char *className, const char *message) noexcept {
    if (environment->ExceptionCheck() == JNI_TRUE) return;
    jclass exceptionClass = environment->FindClass(className);
    if (exceptionClass != nullptr) environment->ThrowNew(exceptionClass, message);
}

class ScopedUtfChars final {
public:
    ScopedUtfChars(JNIEnv *environment, jstring string) noexcept
        : environment_(environment), string_(string), chars_(environment->GetStringUTFChars(string, nullptr)) {}
    ~ScopedUtfChars() { if (chars_) environment_->ReleaseStringUTFChars(string_, chars_); }
    ScopedUtfChars(const ScopedUtfChars &) = delete;
    ScopedUtfChars &operator=(const ScopedUtfChars &) = delete;
    const char *get() const noexcept { return chars_; }
private:
    JNIEnv *environment_;
    jstring string_;
    const char *chars_;
};

SongLaneIngressBank *bankFromHandle(jlong handle) noexcept {
    return reinterpret_cast<SongLaneIngressBank *>(
        static_cast<std::uintptr_t>(static_cast<std::uint64_t>(handle)));
}

NativeAudioOutputHandle outputFromHandle(jlong handle) noexcept {
    return reinterpret_cast<NativeAudioOutputHandle>(
        static_cast<std::uintptr_t>(static_cast<std::uint64_t>(handle)));
}

std::shared_ptr<RuntimeHolder> runtimeFromHandle(
    JNIEnv *environment,
    jlong handle,
    bool allowRetired = false) noexcept {
    if (handle <= 0) {
        throwJava(environment, "java/lang/IllegalStateException", "Invalid native runtime handle");
        return {};
    }
    try {
        const std::scoped_lock lock(gRuntimeRegistryMutex);
        const auto found = gRuntimeRegistry.find(handle);
        if (found != gRuntimeRegistry.end()) return found->second;
        if (allowRetired && static_cast<std::uint64_t>(handle) < gNextRuntimeHandle) return {};
    } catch (...) {
        throwJava(environment, "java/lang/IllegalStateException", "Runtime registry lookup failed");
        return {};
    }
    throwJava(environment, "java/lang/IllegalStateException", "Native runtime is closed");
    return {};
}

bool copyCommandArrays(
    JNIEnv *environment,
    jlongArray encodedLongs,
    jfloatArray encodedFloats,
    std::array<jlong, kCommandLongCount> &longs,
    std::array<jfloat, kCommandFloatCount> &floats) noexcept {
    if (encodedLongs == nullptr || encodedFloats == nullptr ||
        environment->GetArrayLength(encodedLongs) !=
            static_cast<jsize>(kCommandLongCount) ||
        environment->GetArrayLength(encodedFloats) !=
            static_cast<jsize>(kCommandFloatCount)) {
        throwJava(
            environment,
            "java/lang/IllegalArgumentException",
            "Invalid native runtime command schema size");
        return false;
    }
    environment->GetLongArrayRegion(
        encodedLongs,
        0,
        static_cast<jsize>(longs.size()),
        longs.data());
    if (environment->ExceptionCheck() == JNI_TRUE) return false;
    environment->GetFloatArrayRegion(
        encodedFloats,
        0,
        static_cast<jsize>(floats.size()),
        floats.data());
    return environment->ExceptionCheck() != JNI_TRUE;
}

template <std::size_t Count>
bool copyExactLongArray(
    JNIEnv *environment,
    jlongArray encoded,
    std::array<jlong, Count> &values,
    const char *message) noexcept {
    if (encoded == nullptr ||
        environment->GetArrayLength(encoded) != static_cast<jsize>(Count)) {
        throwJava(environment, "java/lang/IllegalArgumentException", message);
        return false;
    }
    environment->GetLongArrayRegion(
        encoded,
        0,
        static_cast<jsize>(values.size()),
        values.data());
    return environment->ExceptionCheck() != JNI_TRUE;
}

bool decodeLoopIntent(
    const std::array<jlong, kLoopIntentValueCount> &values,
    LoopCycleIntent &intent) noexcept {
    if (values[stemwise::audio::packet::LOOP_INTENT_SCHEMA] != kLoopIntentSchemaVersion ||
        !positiveUnsigned(values[stemwise::audio::packet::LOOP_INTENT_REVISION], intent.revision) ||
        !strictBoolean(values[stemwise::audio::packet::LOOP_INTENT_ENABLED], intent.enabled) ||
        !positiveUnsigned(values[stemwise::audio::packet::LOOP_INTENT_TOKEN], intent.owner.selectionToken) ||
        !positiveUnsigned(values[stemwise::audio::packet::LOOP_INTENT_GENERATION], intent.owner.renderGeneration)) {
        return false;
    }
    if (!intent.enabled) {
        for (std::size_t index = 5U; index < values.size(); ++index) {
            if (values[index] != 0) return false;
        }
        return true;
    }

    intent.cacheKey.songIdentityHash = rawUnsignedFromLong(values[stemwise::audio::packet::LOOP_INTENT_SONG_HASH]);
    intent.cacheKey.originalContentHash = rawUnsignedFromLong(values[stemwise::audio::packet::LOOP_INTENT_ORIGINAL_HASH]);
    intent.cacheKey.stemPackageHash = rawUnsignedFromLong(values[stemwise::audio::packet::LOOP_INTENT_STEM_HASH]);
    if (intent.cacheKey.songIdentityHash == 0U ||
        intent.cacheKey.originalContentHash == 0U ||
        !positiveUnsigned(values[stemwise::audio::packet::LOOP_INTENT_CONTENT_REVISION], intent.cacheKey.contentRevision) ||
        !positiveUnsigned(values[stemwise::audio::packet::LOOP_INTENT_LOOP_REVISION], intent.cacheKey.loopRevision) ||
        !nonNegativeUnsigned(values[stemwise::audio::packet::LOOP_INTENT_START_FRAME], intent.cacheKey.startTimelineFrame) ||
        !nonNegativeUnsigned(values[stemwise::audio::packet::LOOP_INTENT_END_FRAME], intent.cacheKey.endTimelineFrameExclusive) ||
        !positiveUint32(values[stemwise::audio::packet::LOOP_INTENT_REQUIRED_LANE_MASK], intent.requiredLaneMask) ||
        !nonNegativeUint32(values[stemwise::audio::packet::LOOP_INTENT_DECLICK_FRAMES], intent.declickFrames) ||
        (values[stemwise::audio::packet::LOOP_INTENT_BOUNDARY_MODE] != static_cast<jlong>(stemwise::audio::engine::LoopHeadBoundaryMode::OriginalEos) &&
         values[stemwise::audio::packet::LOOP_INTENT_BOUNDARY_MODE] != static_cast<jlong>(stemwise::audio::engine::LoopHeadBoundaryMode::RetainedWindow)) ||
        intent.cacheKey.endTimelineFrameExclusive <= intent.cacheKey.startTimelineFrame ||
        (intent.requiredLaneMask & 1U) == 0U ||
        (intent.requiredLaneMask &
         ~stemwise::audio::engine::kLoopHeadAllLanesMask) != 0U ||
        ((intent.requiredLaneMask & ~1U) != 0U &&
         intent.cacheKey.stemPackageHash == 0U) ||
        intent.declickFrames > 48'000U) {
        return false;
    }
    intent.boundaryMode =
        static_cast<stemwise::audio::engine::LoopHeadBoundaryMode>(values[stemwise::audio::packet::LOOP_INTENT_BOUNDARY_MODE]);
    return intent.boundaryMode == stemwise::audio::engine::LoopHeadBoundaryMode::RetainedWindow ||
        intent.cacheKey.endTimelineFrameExclusive - intent.cacheKey.startTimelineFrame ==
        stemwise::audio::engine::LoopHeadCache::kCapacityFrames;
}

bool decodeLoopIngressReady(
    const std::array<jlong, kLoopIngressReadyValueCount> &values,
    LoopCycleIngressReadyEvidence &evidence) noexcept {
    return values[stemwise::audio::packet::LOOP_INGRESS_READY_SCHEMA] == kLoopIngressReadySchemaVersion &&
        positiveUnsigned(values[stemwise::audio::packet::LOOP_INGRESS_READY_REQUEST_SEQUENCE], evidence.requestSequence) &&
        positiveUnsigned(values[stemwise::audio::packet::LOOP_INGRESS_READY_LOOP_REVISION], evidence.loopRevision) &&
        positiveUnsigned(values[stemwise::audio::packet::LOOP_INGRESS_READY_TARGET_CYCLE], evidence.targetCycle) &&
        positiveUnsigned(values[stemwise::audio::packet::LOOP_INGRESS_READY_TOKEN], evidence.owner.selectionToken) &&
        positiveUnsigned(values[stemwise::audio::packet::LOOP_INGRESS_READY_GENERATION], evidence.owner.renderGeneration) &&
        positiveUnsigned(values[stemwise::audio::packet::LOOP_INGRESS_READY_INGRESS_EPOCH], evidence.ingressEpoch) &&
        positiveUint32(values[stemwise::audio::packet::LOOP_INGRESS_READY_READY_LANE_MASK], evidence.readyLaneMask) &&
        (evidence.readyLaneMask & 1U) != 0U &&
        (evidence.readyLaneMask &
         ~stemwise::audio::engine::kLoopHeadAllLanesMask) == 0U &&
        nonNegativeUint32(values[stemwise::audio::packet::LOOP_INGRESS_READY_EOS_PADDED_LANE_MASK], evidence.eosPaddedLaneMask) &&
        (evidence.eosPaddedLaneMask &
         ~stemwise::audio::engine::kLoopHeadAllLanesMask) == 0U &&
        (evidence.eosPaddedLaneMask & ~evidence.readyLaneMask) == 0U &&
        (evidence.eosPaddedLaneMask & 1U) == 0U &&
        nonNegativeUnsigned(values[stemwise::audio::packet::LOOP_INGRESS_READY_FIRST_FRAME], evidence.firstTimelineFrame) &&
        nonNegativeUnsigned(values[stemwise::audio::packet::LOOP_INGRESS_READY_END_FRAME], evidence.endTimelineFrameExclusive) &&
        evidence.endTimelineFrameExclusive > evidence.firstTimelineFrame;
}

LanePcmEncoding loopPreparationEncoding(jint code, bool &valid) noexcept {
    switch (code) {
    case 1:
        valid = true;
        return LanePcmEncoding::Signed16;
    case 2:
        valid = true;
        return LanePcmEncoding::Float32;
    default:
        valid = false;
        return LanePcmEncoding::Signed16;
    }
}

std::size_t loopPreparationBytesPerSample(LanePcmEncoding encoding) noexcept {
    return encoding == LanePcmEncoding::Signed16 ? sizeof(std::int16_t) : sizeof(float);
}

jlong packLoopPreparationOffer(const LoopHeadPreparationOfferResult &result) noexcept {
    const std::uint64_t accepted = std::min<std::uint64_t>(
        result.inputFramesAccepted,
        std::numeric_limits<std::uint32_t>::max());
    const std::uint64_t packed =
        (static_cast<std::uint64_t>(result.status) << 32U) |
        static_cast<std::uint32_t>(accepted);
    return static_cast<jlong>(packed);
}

jlong saturatedCounter(std::uint64_t value) noexcept {
    const auto maximum = static_cast<std::uint64_t>(std::numeric_limits<jlong>::max());
    return value > maximum ? std::numeric_limits<jlong>::max() : static_cast<jlong>(value);
}

jlong rawUnsignedBits(std::uint64_t value) noexcept {
    return static_cast<jlong>(std::bit_cast<std::int64_t>(value));
}

bool exactLong(
    JNIEnv *environment,
    std::uint64_t value,
    jlong &destination,
    const char *label) noexcept {
    if (value > static_cast<std::uint64_t>(std::numeric_limits<jlong>::max())) {
        throwJava(environment, "java/lang/ArithmeticException", label);
        return false;
    }
    destination = static_cast<jlong>(value);
    return true;
}

template<typename Progress, typename Fields>
bool encodeControlFields(JNIEnv *environment, const Progress &diagnostics, Fields &fields) noexcept {
    fields.STATUS = static_cast<jlong>(diagnostics.status);
    if (!(exactLong(environment, diagnostics.requestedOwnership.selectionToken, fields.REQUESTED_TOKEN, "Native token exceeds Kotlin Long") && exactLong(environment, diagnostics.requestedOwnership.generation, fields.REQUESTED_GENERATION, "Native generation exceeds Kotlin Long")) ||
        !(exactLong(environment, diagnostics.acceptedOwnership.selectionToken, fields.ACCEPTED_TOKEN, "Native token exceeds Kotlin Long") && exactLong(environment, diagnostics.acceptedOwnership.generation, fields.ACCEPTED_GENERATION, "Native generation exceeds Kotlin Long")) ||
        !(exactLong(environment, diagnostics.committedOwnership.selectionToken, fields.COMMITTED_TOKEN, "Native token exceeds Kotlin Long") && exactLong(environment, diagnostics.committedOwnership.generation, fields.COMMITTED_GENERATION, "Native generation exceeds Kotlin Long"))) {
        return false;
    }
    fields.ACCEPTED_FIRST_FINGERPRINT_SEQUENCE = saturatedCounter(diagnostics.acceptedFirstFingerprintSequence);

    const auto &presentation = diagnostics.presentationEvidence;
    fields.PRESENTATION_SEQUENCE = saturatedCounter(presentation.sequence);
    if (!exactLong(
            environment,
            presentation.generation,
            fields.PRESENTATION_GENERATION,
            "Presented generation exceeds Kotlin Long") ||
        !exactLong(
            environment,
            presentation.selectionToken,
            fields.PRESENTATION_SELECTION_TOKEN,
            "Presented selection token exceeds Kotlin Long")) {
        return false;
    }
    fields.PRESENTATION_FINGERPRINT_SEQUENCE = saturatedCounter(presentation.fingerprintSequence);
    fields.PRESENTATION_FIRST_TIMELINE_FRAME = static_cast<jlong>(presentation.firstTimelineFrame);
    fields.PRESENTATION_FIRST_OUTPUT_FRAME = saturatedCounter(presentation.firstOutputFrame);
    fields.PRESENTATION_CALLBACK_SEQUENCE = saturatedCounter(presentation.callbackSequence);
    fields.PRESENTATION_PCM_FRAMES = saturatedCounter(presentation.pcmFrames);

    const auto &position = diagnostics.presentedPosition;
    fields.POSITION_SEQUENCE = saturatedCounter(position.sequence);
    if (!exactLong(
            environment,
            position.generation,
            fields.POSITION_GENERATION,
            "Position generation exceeds Kotlin Long") ||
        !exactLong(
            environment,
            position.selectionToken,
            fields.POSITION_SELECTION_TOKEN,
            "Position selection token exceeds Kotlin Long")) {
        return false;
    }
    fields.POSITION_FINGERPRINT_SEQUENCE = saturatedCounter(position.fingerprintSequence);
    fields.POSITION_LAST_TIMELINE_FRAME = static_cast<jlong>(position.lastTimelineFrame);
    fields.POSITION_LAST_OUTPUT_FRAME = saturatedCounter(position.lastOutputFrame);
    fields.POSITION_CALLBACK_SEQUENCE = saturatedCounter(position.callbackSequence);
    fields.POSITION_PCM_FRAMES = saturatedCounter(position.pcmFrames);

    fields.AUDIBLE_END_SEQUENCE = saturatedCounter(diagnostics.audibleEnd.sequence);
    if (!(exactLong(environment, diagnostics.audibleEnd.ownership.selectionToken, fields.AUDIBLE_END_TOKEN, "Native token exceeds Kotlin Long") && exactLong(environment, diagnostics.audibleEnd.ownership.generation, fields.AUDIBLE_END_GENERATION, "Native generation exceeds Kotlin Long"))) {
        return false;
    }
    for (std::size_t bank = 0; bank < diagnostics.banks.size(); ++bank) {
        const auto &source = diagnostics.banks[bank];
        if (!exactLong(
                environment,
                source.appliedIntentRevision,
                fields.BANK[bank].APPLIED_INTENT_REVISION,
                "Bank revision exceeds Kotlin Long")) {
            return false;
        }
        fields.BANK[bank].ASSIGNED = source.assigned ? 1 : 0;
        if constexpr (requires { fields.BANK[bank].BEGIN_SONG_COUNT; }) {
        fields.BANK[bank].BEGIN_SONG_COUNT = saturatedCounter(source.beginSongCount);
        fields.BANK[bank].SEEK_COUNT = saturatedCounter(source.seekCount);
        fields.BANK[bank].REJECTED_BANK_INTENTS = saturatedCounter(source.rejectedBankIntents);
        fields.BANK[bank].BEAT_GRID_UPDATES = saturatedCounter(source.beatGridUpdates);
        fields.BANK[bank].BEAT_GRID_CLEARS = saturatedCounter(source.beatGridClears);
        }
    }

    const auto &audibleControl = diagnostics.audibleControl;
    fields.AUDIBLE_CONTROL_SEQUENCE = saturatedCounter(audibleControl.sequence);
    if (!(exactLong(environment, audibleControl.ownership.selectionToken, fields.AUDIBLE_CONTROL_TOKEN, "Native token exceeds Kotlin Long") && exactLong(environment, audibleControl.ownership.generation, fields.AUDIBLE_CONTROL_GENERATION, "Native generation exceeds Kotlin Long")) ||
        !exactLong(
            environment,
            audibleControl.controlRevision,
            fields.AUDIBLE_CONTROL_CONTROL_REVISION,
            "Audible control revision exceeds Kotlin Long")) {
        return false;
    }
    fields.AUDIBLE_CONTROL_ACTUAL_SOURCE_MODE = static_cast<jlong>(audibleControl.actualSourceMode);
    fields.AUDIBLE_CONTROL_FIRST_FINGERPRINT_SEQUENCE = saturatedCounter(audibleControl.firstFingerprintSequence);
    fields.AUDIBLE_CONTROL_LAST_FINGERPRINT_SEQUENCE = saturatedCounter(audibleControl.lastFingerprintSequence);
    fields.AUDIBLE_CONTROL_CALLBACK_SEQUENCE = saturatedCounter(audibleControl.callbackSequence);
    fields.AUDIBLE_CONTROL_PCM_FRAMES = saturatedCounter(audibleControl.pcmFrames);
    fields.AUDIBLE_CONTROL_ACTUAL_TEMPO_BITS = std::bit_cast<std::uint32_t>(audibleControl.actualTempo);
    fields.AUDIBLE_CONTROL_ACTUAL_PITCH_SEMITONES_BITS = std::bit_cast<std::uint32_t>(audibleControl.actualPitchSemitones);

    const auto &automatic = diagnostics.automaticSuccessor;
    fields.AUTOMATIC_PHASE = static_cast<jlong>(automatic.phase);
    fields.AUTOMATIC_CANCELLATION_STATUS = static_cast<jlong>(automatic.cancellationStatus);
    if (!exactLong(
            environment,
            automatic.revision,
            fields.AUTOMATIC_REVISION,
            "Automatic successor revision exceeds Kotlin Long") ||
        !exactLong(
            environment,
            automatic.transitionRevision,
            fields.AUTOMATIC_TRANSITION_REVISION,
            "Automatic transition revision exceeds Kotlin Long") ||
        !(exactLong(environment, automatic.outgoingOwnership.selectionToken, fields.AUTOMATIC_OUTGOING_TOKEN, "Native token exceeds Kotlin Long") && exactLong(environment, automatic.outgoingOwnership.generation, fields.AUTOMATIC_OUTGOING_GENERATION, "Native generation exceeds Kotlin Long")) ||
        !(exactLong(environment, automatic.successorOwnership.selectionToken, fields.AUTOMATIC_SUCCESSOR_TOKEN, "Native token exceeds Kotlin Long") && exactLong(environment, automatic.successorOwnership.generation, fields.AUTOMATIC_SUCCESSOR_GENERATION, "Native generation exceeds Kotlin Long"))) {
        return false;
    }
    fields.AUTOMATIC_SUCCESSOR_QUEUED_FRAMES = saturatedCounter(automatic.successorQueuedFrames);
    fields.AUTOMATIC_FIRST_FINGERPRINT_SEQUENCE = saturatedCounter(automatic.firstFingerprintSequence);
    fields.AUTOMATIC_CANCELLATION_DESIRED = automatic.cancellationDesired ? 1 : 0;
    fields.AUTOMATIC_SUCCESSOR_END_OBSERVED = automatic.successorEndObserved ? 1 : 0;
    fields.POSITION_LOOP_POLICY_ADOPTED_REVISION = saturatedCounter(position.loopPolicyAdoptedRevision);
    fields.POSITION_LOOP_POLICY_ADOPTION_LATENCY_MICROS = saturatedCounter(position.loopPolicyAdoptionLatencyMicros);
    fields.POSITION_LOOP_POLICY_REVISION = saturatedCounter(position.loopPolicyRevision);
    fields.POSITION_LOOP_POLICY_INPUT_TO_ATOMIC_PUBLICATION_LATENCY_MICROS = saturatedCounter(
        position.loopPolicyInputToAtomicPublicationLatencyMicros);
    fields.POSITION_LOOP_POLICY_INPUT_TO_CALLBACK_ADOPTION_LATENCY_MICROS = saturatedCounter(
        position.loopPolicyInputToCallbackAdoptionLatencyMicros);
    fields.AUDIBLE_END_DIRECT_LOOP_POLICY_REVISION = saturatedCounter(diagnostics.audibleEnd.directLoopPolicyRevision);
    return true;
}

jlongArray encodeControlProgress(JNIEnv *environment,
    const stemwise::audio::engine::NativePlaybackControlProgress &progress) noexcept {
    using namespace stemwise::audio::packet;
    ControlProgressFields<jlong> fields{};
    fields.SCHEMA = CONTROL_PROGRESS_VERSION;
    if (!encodeControlFields(environment, progress, fields)) return nullptr;
    const auto &readiness = progress.readiness;
    fields.READINESS_SEQUENCE = saturatedCounter(readiness.sequence);
    fields.READINESS_ASSIGNED = readiness.assigned ? 1 : 0;
    if (!exactLong(environment, readiness.ownership.selectionToken, fields.READINESS_TOKEN, "Readiness token exceeds Kotlin Long") ||
        !exactLong(environment, readiness.ownership.generation, fields.READINESS_GENERATION, "Readiness generation exceeds Kotlin Long")) return nullptr;
    fields.READINESS_CONTROL_REVISION = saturatedCounter(readiness.controlRevision);
    fields.READINESS_CACHE_BOUND = readiness.cacheBound ? 1 : 0;
    fields.READINESS_CACHE_CAN_GROW = readiness.cacheCanGrow ? 1 : 0;
    fields.READINESS_COMPLETE_LANE_MASK = readiness.completeLaneMask;
    fields.READINESS_REQUIRED_LANE_MASK = readiness.requiredLaneMask;
    fields.READINESS_STORAGE_STATUS = static_cast<jlong>(readiness.storageStatus);
    fields.READINESS_FIXED_LOOP_HEAD_PREPARED = readiness.fixedLoopHeadPrepared ? 1 : 0;
    fields.READINESS_DIRECT_ATOMIC_POLICY_GENERATION = saturatedCounter(readiness.directAtomicPolicyGeneration);
    fields.READINESS_FAILURE_LANE = readiness.failureLane;
    fields.READINESS_FAILURE_GENERATION = saturatedCounter(readiness.failureGeneration);
    if (!exactLong(environment, readiness.loopPreparationFailure.ownership.selectionToken, fields.READINESS_LOOP_FAILURE_TOKEN, "Loop failure token exceeds Kotlin Long") ||
        !exactLong(environment, readiness.loopPreparationFailure.ownership.generation, fields.READINESS_LOOP_FAILURE_GENERATION, "Loop failure generation exceeds Kotlin Long") ||
        !exactLong(environment, readiness.loopPreparationFailure.revision, fields.READINESS_LOOP_FAILURE_REVISION, "Loop failure revision exceeds Kotlin Long")) return nullptr;
    fields.READINESS_LOOP_FAILURE_REASON = static_cast<jlong>(readiness.loopPreparationFailure.reason);
    const auto values = packControlProgress(fields);
    const auto result = environment->NewLongArray(static_cast<jsize>(values.size()));
    if (!result) return nullptr;
    environment->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    return environment->ExceptionCheck() == JNI_TRUE ? nullptr : result;
}

jlongArray encodeDiagnostics(
    JNIEnv *environment,
    const NativePlaybackRuntimeDiagnostics &diagnostics) noexcept {
    stemwise::audio::packet::RuntimeDiagnosticsFields<jlong> fields{};
    fields.SCHEMA = kDiagnosticsSchemaVersion;
    fields.STATUS = static_cast<jlong>(diagnostics.status);
    fields.COMMAND_SNAPSHOTS_PUBLISHED = saturatedCounter(diagnostics.commandSnapshotsPublished);
    fields.COMMAND_SNAPSHOTS_CONSUMED = saturatedCounter(diagnostics.commandSnapshotsConsumed);
    fields.COMMAND_SNAPSHOTS_SUPERSEDED = saturatedCounter(diagnostics.commandSnapshotsSuperseded);
    fields.REJECTED_COMMAND_SNAPSHOTS = saturatedCounter(diagnostics.rejectedCommandSnapshots);
    fields.BEAT_GRID_UPDATES_PUBLISHED = saturatedCounter(diagnostics.beatGridUpdatesPublished);
    fields.REJECTED_BEAT_GRID_UPDATES = saturatedCounter(diagnostics.rejectedBeatGridUpdates);
    fields.RENDER_CYCLES = saturatedCounter(diagnostics.renderCycles);
    fields.IDLE_SLEEPS = saturatedCounter(diagnostics.idleSleeps);
    fields.OUTPUT_FLUSHES = saturatedCounter(diagnostics.outputFlushes);
    fields.OUTPUT_FLUSH_FAILURES = saturatedCounter(diagnostics.outputFlushFailures);
    fields.OUTPUT_HANDOFF_BEGINS = saturatedCounter(diagnostics.outputHandoffBegins);
    fields.OUTPUT_HANDOFF_BEGIN_RETRIES = saturatedCounter(diagnostics.outputHandoffBeginRetries);
    fields.OUTPUT_HANDOFF_COMMITS = saturatedCounter(diagnostics.outputHandoffCommits);
    fields.OUTPUT_HANDOFF_COMMIT_RETRIES = saturatedCounter(diagnostics.outputHandoffCommitRetries);
    fields.OUTPUT_BLOCKS_RENDERED = saturatedCounter(diagnostics.outputBlocksRendered);
    fields.OUTPUT_FRAMES_RENDERED = saturatedCounter(diagnostics.outputFramesRendered);
    fields.OUTPUT_FRAMES_ACCEPTED = saturatedCounter(diagnostics.outputFramesAccepted);
    fields.OUTPUT_PARTIAL_ACCEPTS = saturatedCounter(diagnostics.outputPartialAccepts);
    fields.OUTPUT_BACKPRESSURE_EVENTS = saturatedCounter(diagnostics.outputBackpressureEvents);
    fields.OUTPUT_INVALID_RENDER_RESULTS = saturatedCounter(diagnostics.outputInvalidRenderResults);
    fields.OUTPUT_NEED_DATA_EVENTS = saturatedCounter(diagnostics.outputNeedDataEvents);
    fields.OUTPUT_END_OF_STREAM_EVENTS = saturatedCounter(diagnostics.outputEndOfStreamEvents);
    fields.TARGET_QUEUED_FRAMES = saturatedCounter(diagnostics.targetQueuedFrames);
    fields.LAST_OUTPUT_FINGERPRINT_SEQUENCE = saturatedCounter(diagnostics.lastOutputFingerprintSequence);
    fields.LAST_RENDER_PAYLOAD_HASH = rawUnsignedBits(diagnostics.lastRenderPayloadHash);
    if (!encodeControlFields(environment, diagnostics, fields)) return nullptr;
    fields.PROCESS_CONSTRUCTION_COUNT = saturatedCounter(diagnostics.processConstructionCount);
    fields.PROCESS_LIVE_INSTANCE_COUNT = saturatedCounter(diagnostics.processLiveInstanceCount);
    fields.RENDER_WORKER_STARTED = diagnostics.renderWorkerStarted ? 1 : 0;
    fields.RENDER_WORKER_AUDIO_PRIORITY_REQUESTED = diagnostics.renderWorkerAudioPriorityRequested ? 1 : 0;
    fields.RENDER_WORKER_AUDIO_PRIORITY_APPLIED = diagnostics.renderWorkerAudioPriorityApplied ? 1 : 0;
    fields.RENDER_WORKER_NICE_VALUE = static_cast<jlong>(diagnostics.renderWorkerNiceValue);
    fields.RENDER_WORKER_PRIORITY_ERROR_CODE = static_cast<jlong>(diagnostics.renderWorkerPriorityErrorCode);
    fields.OUTPUT_FLUSH_RETRIES = saturatedCounter(diagnostics.outputFlushRetries);
    fields.PROCESS_OUTPUT_INVALID_RENDER_RESULTS = saturatedCounter(diagnostics.processOutputInvalidRenderResults);
    fields.PROCESS_OUTPUT_FLUSH_FAILURES = saturatedCounter(diagnostics.processOutputFlushFailures);
    const auto &lowWater = diagnostics.currentOutputQueueLowWater;
    const auto &needOriginal = diagnostics.needOriginalData;
    const auto &needStem = diagnostics.needStemData;
    const auto &loopBoundary = diagnostics.loopBoundaryPending;
    fields.LOW_WATER_SEQUENCE = saturatedCounter(lowWater.sequence);
    if (!exactLong(
            environment,
            lowWater.generation,
            fields.LOW_WATER_GENERATION,
            "Current output low-water generation exceeds Kotlin Long") ||
        !exactLong(
            environment,
            needOriginal.lastGeneration,
            fields.NEED_ORIGINAL_LAST_GENERATION,
            "NeedOriginal generation exceeds Kotlin Long") ||
        !exactLong(
            environment,
            needStem.lastGeneration,
            fields.NEED_STEM_LAST_GENERATION,
            "NeedStem generation exceeds Kotlin Long") ||
        !exactLong(
            environment,
            loopBoundary.lastGeneration,
            fields.LOOP_BOUNDARY_LAST_GENERATION,
            "LoopBoundary generation exceeds Kotlin Long")) {
        return nullptr;
    }
    fields.LOW_WATER_FRAMES = saturatedCounter(lowWater.frames);
    fields.LOW_WATER_MONOTONIC_MICROS = saturatedCounter(lowWater.monotonicMicros);
    fields.NEED_ORIGINAL_LOW_WATER_EVIDENCE_SEQUENCE = saturatedCounter(needOriginal.lowWaterEvidenceSequence);
    fields.NEED_ORIGINAL_LAST_MONOTONIC_MICROS = saturatedCounter(needOriginal.lastMonotonicMicros);
    fields.NEED_STEM_LOW_WATER_EVIDENCE_SEQUENCE = saturatedCounter(needStem.lowWaterEvidenceSequence);
    fields.NEED_STEM_LAST_MONOTONIC_MICROS = saturatedCounter(needStem.lastMonotonicMicros);
    fields.LOOP_BOUNDARY_LOW_WATER_EVIDENCE_SEQUENCE = saturatedCounter(loopBoundary.lowWaterEvidenceSequence);
    fields.LOOP_BOUNDARY_LAST_MONOTONIC_MICROS = saturatedCounter(loopBoundary.lastMonotonicMicros);


    fields.RESOURCE_OPERATIONS_COMPLETED = saturatedCounter(diagnostics.resourceOperationsCompleted);
    fields.RESOURCE_LAST_OPERATION_MICROS = saturatedCounter(diagnostics.resourceLastOperationMicros);
    fields.RESOURCE_MAXIMUM_OPERATION_MICROS = saturatedCounter(diagnostics.resourceMaximumOperationMicros);
    fields.RESOURCE_MAXIMUM_QUEUE_AGE_MICROS = saturatedCounter(diagnostics.resourceMaximumQueueAgeMicros);
    fields.REFILL_DEADLINE_MISSES = saturatedCounter(diagnostics.refillDeadlineMisses);
    fields.CANONICAL_CACHE_TOKEN = saturatedCounter(diagnostics.canonicalCacheKey.selectionToken);
    fields.CANONICAL_CACHE_GENERATION = saturatedCounter(diagnostics.canonicalCacheKey.generation);
    fields.CANONICAL_CACHE_SONG_HASH = rawUnsignedBits(diagnostics.canonicalCacheKey.songIdentityHash);
    fields.CANONICAL_CACHE_ORIGINAL_HASH = rawUnsignedBits(diagnostics.canonicalCacheKey.originalContentHash);
    fields.CANONICAL_CACHE_STEM_HASH = rawUnsignedBits(diagnostics.canonicalCacheKey.stemPackageHash);
    fields.CANONICAL_READ_OPERATIONS = saturatedCounter(diagnostics.canonicalPcmIo.readOperations);
    fields.CANONICAL_READ_BYTES = saturatedCounter(diagnostics.canonicalPcmIo.readBytes);
    fields.CANONICAL_LAST_READ_BYTE_OFFSET = saturatedCounter(diagnostics.canonicalPcmIo.lastReadByteOffset);
    fields.CANONICAL_LAST_READ_BYTES = saturatedCounter(diagnostics.canonicalPcmIo.lastReadBytes);
    fields.CANONICAL_FILE_BYTES = saturatedCounter(diagnostics.canonicalPcmIo.fileBytes);
    fields.CANONICAL_RESERVED_BYTES = saturatedCounter(diagnostics.canonicalPcmIo.reservedBytes);
    fields.CANONICAL_PEAK_FILE_BYTES = saturatedCounter(diagnostics.canonicalPcmIo.peakFileBytes);
    fields.CANONICAL_ADMITTED_LANES = saturatedCounter(diagnostics.canonicalPcmIo.admittedLaneCount);
    fields.CANONICAL_ADMITTED_FRAME_BOUND = saturatedCounter(diagnostics.canonicalPcmIo.admittedFrameUpperBound);
    fields.CANONICAL_LAST_ADMISSION_STATUS = static_cast<jlong>(diagnostics.canonicalPcmIo.lastAdmissionStatus);
    const std::array ordered{diagnostics.endOrdering.commandPublished, diagnostics.endOrdering.commandConsumed,
        diagnostics.endOrdering.pipelineEndObserved, diagnostics.endOrdering.outputPlayingApplied,
        diagnostics.endOrdering.physicalDrainObserved, diagnostics.endOrdering.audibleEndPublished};
    for (std::size_t index = 0U; index < ordered.size(); ++index) {
        const auto &event = ordered[index];
        auto &row = fields.END_ORDERING[index];
        row.SEQUENCE = saturatedCounter(event.sequence);
        row.WORKER_STATE_SEQUENCE = saturatedCounter(event.workerStateSequence);
        row.COMMAND_REVISION = saturatedCounter(event.commandRevision);
        row.TOKEN = saturatedCounter(event.ownership.selectionToken);
        row.GENERATION = saturatedCounter(event.ownership.generation);
        row.DESIRED_PLAYING = event.desiredPlaying;
        row.OUTPUT_PLAYING = event.outputPlaying;
        row.PIPELINE_END_OBSERVED = event.pipelineEndObserved;
        row.COMMAND_UNREAD = event.commandUnread;
        row.PENDING_OUTPUT_FRAMES = saturatedCounter(event.pendingOutputFrames);
        row.QUEUED_OUTPUT_FRAMES = saturatedCounter(event.queuedOutputFrames);
        row.TERMINAL_POSITION_VALID = event.terminalPositionValid;
        row.TERMINAL_AFTER_FRAME = event.terminalAfterFrame;
    }

    const auto values = stemwise::audio::packet::packRuntimeDiagnostics(fields);
    jlongArray encoded = environment->NewLongArray(static_cast<jsize>(values.size()));
    if (encoded == nullptr) return nullptr;
    environment->SetLongArrayRegion(
        encoded,
        0,
        static_cast<jsize>(values.size()),
        values.data());
    return environment->ExceptionCheck() == JNI_TRUE ? nullptr : encoded;
}

jlongArray encodeRenderReadinessDiagnostics(
    JNIEnv *environment,
    const NativePlaybackRenderReadinessDiagnostics &diagnostics) noexcept {
    stemwise::audio::packet::RenderReadinessFields<jlong> fields{};
    const auto putExact = [&](jlong &destination, std::uint64_t value) noexcept {
        return exactLong(
            environment,
            value,
            destination,
            "Native render-readiness value exceeds Kotlin Long");
    };
    fields.SCHEMA = kRenderReadinessDiagnosticsSchemaVersion;
    if (!putExact(fields.SEQUENCE, diagnostics.sequence)) return nullptr;
    fields.ASSIGNED = diagnostics.assigned ? 1 : 0;
    fields.BANK = static_cast<jlong>(diagnostics.bank);
    if (!putExact(fields.OWNERSHIP_SELECTION_TOKEN, diagnostics.ownership.selectionToken) ||
        !putExact(fields.OWNERSHIP_GENERATION, diagnostics.ownership.generation) ||
        !putExact(fields.CONTROL_REVISION, diagnostics.controlRevision)) {
        return nullptr;
    }
    fields.DESIRED_SOURCE_MODE = static_cast<jlong>(diagnostics.desiredSourceMode);
    if (!putExact(fields.RENDER_START_TIMELINE_FRAME, diagnostics.renderStartTimelineFrame) ||
        !putExact(fields.PENDING_CONTROL_REVISION, diagnostics.pendingControlRevision)) {
        return nullptr;
    }
    fields.PENDING_ACTUAL_SOURCE_MODE = static_cast<jlong>(diagnostics.pendingActualSourceMode);
    if (!putExact(fields.PENDING_SOURCE_START_TIMELINE_FRAME, diagnostics.pendingSourceStartTimelineFrame) ||
        !putExact(fields.PENDING_SOURCE_END_TIMELINE_FRAME_EXCLUSIVE, diagnostics.pendingSourceEndTimelineFrameExclusive) ||
        !putExact(fields.PENDING_FRAMES, diagnostics.pendingFrames) ||
        !putExact(fields.OUTPUT_QUEUED_FRAMES, diagnostics.outputQueuedFrames) ||
        !putExact(fields.STEM_ACTIVATION_DEFERRALS, diagnostics.stemActivationDeferrals) ||
        !putExact(fields.STEM_SAFETY_FALLBACKS, diagnostics.stemSafetyFallbacks) ||
        !putExact(fields.NEED_STEM_DATA_COUNT, diagnostics.needStemDataCount)) {
        return nullptr;
    }
    for (std::size_t lane = 0U; lane < diagnostics.lanes.size(); ++lane) {
        const auto &source = diagnostics.lanes[lane];
        if (!putExact(fields.LANE[lane].GENERATION, source.generation) ||
            !putExact(fields.LANE[lane].FIRST_TIMELINE_FRAME, source.firstTimelineFrame) ||
            !putExact(fields.LANE[lane].END_TIMELINE_FRAME_EXCLUSIVE, source.endTimelineFrameExclusive) ||
            !putExact(fields.LANE[lane].AVAILABLE_FROM_RENDER_START, source.availableFromRenderStart) ||
            !putExact(fields.LANE[lane].RESERVOIR_GAP_REJECTION_COUNT, source.reservoirGapRejectionCount) ||
            !putExact(fields.LANE[lane].INGRESS_FAILURE_GENERATION, source.ingressFailureGeneration) ||
            !putExact(fields.LANE[lane].INGRESS_ACTIVE_GENERATION, source.ingressActiveGeneration) ||
            !putExact(fields.LANE[lane].INGRESS_PRODUCER_GENERATION, source.ingressProducerGeneration) ||
            !putExact(fields.LANE[lane].INGRESS_CONSUMER_GENERATION, source.ingressConsumerGeneration) ||
            !putExact(fields.LANE[lane].INGRESS_READABLE_SOURCE_FRAMES, source.ingressReadableSourceFrames) ||
            !putExact(fields.LANE[lane].INGRESS_PULLS, source.ingressPulls) ||
            !putExact(fields.LANE[lane].INGRESS_STALE_AFTER_PULL, source.ingressStaleAfterPull) ||
            !putExact(fields.LANE[lane].INGRESS_FUTURE_GENERATION_DEFERRALS, source.ingressFutureGenerationDeferrals)) {
            return nullptr;
        }
    }
    fields.CURRENT_SONG_CACHE_BOUND_TO_SELECTED_OWNER = diagnostics.currentSongCacheBoundToSelectedOwner ? 1 : 0;
    fields.CURRENT_SONG_CACHE_COMPLETE_LANE_MASK = static_cast<jlong>(diagnostics.currentSongCacheCompleteLaneMask);
    fields.CURRENT_SONG_CACHE_CAN_GROW = diagnostics.currentSongCacheCanGrow ? 1 : 0;
    if (!putExact(fields.INGRESS_BANK_CONFIGURED_LANE_COUNT, diagnostics.ingressBankConfiguredLaneCount) ||
        !putExact(fields.DIRECT_ATOMIC_POLICY_GENERATION, diagnostics.directAtomicPolicyGeneration) ||
        !putExact(fields.DIRECT_ATOMIC_POLICY_PUBLISHED_REVISION, diagnostics.directAtomicPolicyPublishedRevision) ||
        !putExact(fields.DIRECT_ATOMIC_POLICY_ADOPTED_REVISION, diagnostics.directAtomicPolicyAdoptedRevision) ||
        !putExact(fields.DIRECT_ATOMIC_POLICY_OBSERVED_REVISION, diagnostics.directAtomicPolicyObservedRevision) ||
        !putExact(fields.INGRESS_SERVICE_POLICY_BREAKS, diagnostics.ingressServicePolicyBreaks) ||
        !putExact(fields.INGRESS_BANK_IDENTITY, diagnostics.ingressBankIdentity)) {
        return nullptr;
    }
    fields.REQUIRED_SONG_LANE_MASK = static_cast<jlong>(diagnostics.requiredSongLaneMask);
    fields.CACHE_STORAGE_STATUS = static_cast<jlong>(diagnostics.cacheStorageStatus);
    fields.FIXED_LOOP_HEAD_PREPARED = diagnostics.fixedLoopHeadPrepared ? 1 : 0;
    if (!putExact(fields.LOOP_FAILURE_TOKEN, diagnostics.loopPreparationFailure.ownership.selectionToken) ||
        !putExact(fields.LOOP_FAILURE_GENERATION, diagnostics.loopPreparationFailure.ownership.generation) ||
        !putExact(fields.LOOP_FAILURE_REVISION, diagnostics.loopPreparationFailure.revision)) return nullptr;
    fields.LOOP_FAILURE_REASON = static_cast<jlong>(diagnostics.loopPreparationFailure.reason);
    const auto values = stemwise::audio::packet::packRenderReadiness(fields);
    jlongArray encoded = environment->NewLongArray(static_cast<jsize>(values.size()));
    if (encoded == nullptr) return nullptr;
    environment->SetLongArrayRegion(
        encoded,
        0,
        static_cast<jsize>(values.size()),
        values.data());
    return environment->ExceptionCheck() == JNI_TRUE ? nullptr : encoded;
}

jlongArray encodeStemGainCallbackDiagnostics(
    JNIEnv *environment,
    const stemwise::audio::NativeAudioStemGainCallbackEvidence &evidence) noexcept {
    stemwise::audio::packet::StemGainCallbackFields<jlong> fields{};
    fields.SCHEMA = kStemGainCallbackDiagnosticsSchemaVersion;
    fields.VALID = evidence.valid() ? 1 : 0;
    fields.SEQUENCE = saturatedCounter(evidence.sequence);
    if (!exactLong(
            environment,
            evidence.generation,
            fields.GENERATION,
            "Stem-gain callback generation exceeds Kotlin Long") ||
        !exactLong(
            environment,
            evidence.selectionToken,
            fields.SELECTION_TOKEN,
            "Stem-gain callback token exceeds Kotlin Long")) {
        return nullptr;
    }
    fields.OFFERED_SEQUENCE = saturatedCounter(evidence.offeredSequence);
    fields.PUBLISHED_SEQUENCE = saturatedCounter(evidence.publishedSequence);
    fields.ACTIVE_MASK = static_cast<jlong>(evidence.activeMask);
    for (std::size_t lane = 0U; lane < evidence.gainBits.size(); ++lane) {
        fields.GAIN_BITS[lane] = static_cast<jlong>(evidence.gainBits[lane]);
    }
    fields.CALLBACK_SEQUENCE = saturatedCounter(evidence.callbackSequence);
    fields.FIRST_TAGGED_FRAME = static_cast<jlong>(evidence.firstTaggedFrame);
    fields.LAST_TAGGED_FRAME = static_cast<jlong>(evidence.lastTaggedFrame);
    fields.TAGGED_FRAME_COUNT = saturatedCounter(evidence.taggedFrameCount);
    fields.NON_ZERO_PCM_FRAME_COUNT = saturatedCounter(evidence.nonZeroPcmFrameCount);
    fields.CALLBACK_OBSERVED_AT_NS = saturatedCounter(evidence.callbackObservedAtNs);
    const auto values = stemwise::audio::packet::packStemGainCallback(fields);
    jlongArray encoded = environment->NewLongArray(
        static_cast<jsize>(values.size()));
    if (encoded == nullptr) return nullptr;
    environment->SetLongArrayRegion(
        encoded,
        0,
        static_cast<jsize>(values.size()),
        values.data());
    return environment->ExceptionCheck() == JNI_TRUE ? nullptr : encoded;
}

jlongArray encodeLoopProtocolSnapshot(
    JNIEnv *environment,
    const NativePlaybackLoopProtocolSnapshot &snapshot) noexcept {
    stemwise::audio::packet::LoopProtocolFields<jlong> fields{};
    const auto putExact = [&](jlong &destination, std::uint64_t value) noexcept {
        return exactLong(
            environment,
            value,
            destination,
            "Native loop protocol value exceeds Kotlin Long");
    };

    fields.SCHEMA = kLoopProtocolSchemaVersion;
    fields.ADOPTED = snapshot.adopted ? 1 : 0;
    fields.BANK = static_cast<jlong>(snapshot.bank);
    fields.STATE_MODE = static_cast<jlong>(snapshot.state.mode);
    fields.STATE_PHASE = static_cast<jlong>(snapshot.state.phase);
    fields.STATE_ACTIVATION = static_cast<jlong>(snapshot.state.activation);
    if (!putExact(fields.STATE_LATEST_REVISION, snapshot.state.latestRevision) ||
        !putExact(fields.STATE_OWNER_SELECTION_TOKEN, snapshot.state.owner.selectionToken) ||
        !putExact(fields.STATE_OWNER_RENDER_GENERATION, snapshot.state.owner.renderGeneration) ||
        !putExact(fields.STATE_RENDER_CYCLE, snapshot.state.renderCycle) ||
        !putExact(fields.STATE_ACTIVE_INGRESS_EPOCH, snapshot.state.activeIngressEpoch) ||
        !putExact(fields.STATE_PREPARED_INGRESS_EPOCH, snapshot.state.preparedIngressEpoch)) {
        return nullptr;
    }
    fields.STATE_CACHE_CHECKSUM = rawUnsignedBits(snapshot.state.cacheChecksum);
    fields.STATE_CACHE_ACCEPTED = snapshot.state.cacheAccepted ? 1 : 0;
    fields.STATE_RENDER_INSTALLED = snapshot.state.renderInstalled ? 1 : 0;
    fields.STATE_RENDER_ACTIVE = snapshot.state.renderActive ? 1 : 0;
    fields.STATE_AUDIBLY_PROVEN = snapshot.state.audiblyProven ? 1 : 0;
    fields.STATE_INGRESS_REQUEST_PENDING = snapshot.state.ingressRequestPending ? 1 : 0;
    fields.STATE_RENDER_DIRECTIVE_PENDING = snapshot.state.renderDirectivePending ? 1 : 0;

    const auto &request = snapshot.ingressRequest;
    fields.REQUEST_VALID = request.valid ? 1 : 0;
    if (!putExact(fields.REQUEST_SEQUENCE, request.sequence) || !putExact(fields.REQUEST_LOOP_REVISION, request.loopRevision) ||
        !putExact(fields.REQUEST_TARGET_CYCLE, request.targetCycle) ||
        !putExact(fields.REQUEST_OWNER_SELECTION_TOKEN, request.owner.selectionToken) ||
        !putExact(fields.REQUEST_OWNER_RENDER_GENERATION, request.owner.renderGeneration) ||
        !putExact(fields.REQUEST_INGRESS_EPOCH, request.ingressEpoch) ||
        !putExact(fields.REQUEST_TARGET_TIMELINE_FRAME, request.targetTimelineFrame)) {
        return nullptr;
    }
    fields.REQUEST_REQUIRED_LANE_MASK = static_cast<jlong>(request.requiredLaneMask);
    fields.REQUEST_ACTIVATION = static_cast<jlong>(request.activation);

    const auto &directive = snapshot.renderDirective;
    fields.DIRECTIVE_VALID = directive.valid ? 1 : 0;
    if (!putExact(fields.DIRECTIVE_SEQUENCE, directive.sequence)) return nullptr;
    fields.DIRECTIVE_ACTION = static_cast<jlong>(directive.action);
    if (!putExact(fields.DIRECTIVE_LOOP_REVISION, directive.loopRevision) || !putExact(fields.DIRECTIVE_CYCLE, directive.cycle) ||
        !putExact(fields.DIRECTIVE_OWNER_SELECTION_TOKEN, directive.owner.selectionToken) ||
        !putExact(fields.DIRECTIVE_OWNER_RENDER_GENERATION, directive.owner.renderGeneration) ||
        !putExact(fields.DIRECTIVE_INGRESS_EPOCH, directive.ingressEpoch) ||
        !putExact(fields.DIRECTIVE_START_TIMELINE_FRAME, directive.startTimelineFrame) ||
        !putExact(fields.DIRECTIVE_END_TIMELINE_FRAME_EXCLUSIVE, directive.endTimelineFrameExclusive) ||
        !putExact(fields.DIRECTIVE_HEAD_END_TIMELINE_FRAME_EXCLUSIVE, directive.headEndTimelineFrameExclusive)) {
        return nullptr;
    }
    fields.DIRECTIVE_CACHE_CHECKSUM = rawUnsignedBits(directive.cacheChecksum);
    fields.DIRECTIVE_REQUIRED_LANE_MASK = static_cast<jlong>(directive.requiredLaneMask);
    fields.DIRECTIVE_DECLICK_FRAMES = static_cast<jlong>(directive.declickFrames);

    const auto &audible = snapshot.audibleEvidence;
    fields.AUDIBLE_VALID = audible.valid ? 1 : 0;
    if (!putExact(fields.AUDIBLE_SEQUENCE, audible.sequence) || !putExact(fields.AUDIBLE_LOOP_REVISION, audible.loopRevision) ||
        !putExact(fields.AUDIBLE_CYCLE, audible.cycle) ||
        !putExact(fields.AUDIBLE_OWNER_SELECTION_TOKEN, audible.owner.selectionToken) ||
        !putExact(fields.AUDIBLE_OWNER_RENDER_GENERATION, audible.owner.renderGeneration) ||
        !putExact(fields.AUDIBLE_CALLBACK_SEQUENCE, audible.callbackSequence)) {
        return nullptr;
    }

    const auto &cache = snapshot.activeCache;
    fields.CACHE_VALID = cache.valid ? 1 : 0;
    fields.CACHE_SLOT_INDEX = static_cast<jlong>(cache.slotIndex);
    fields.CACHE_KEY_SONG_IDENTITY_HASH = rawUnsignedBits(cache.key.songIdentityHash);
    fields.CACHE_KEY_ORIGINAL_CONTENT_HASH = rawUnsignedBits(cache.key.originalContentHash);
    fields.CACHE_KEY_STEM_PACKAGE_HASH = rawUnsignedBits(cache.key.stemPackageHash);
    if (!putExact(fields.CACHE_KEY_CONTENT_REVISION, cache.key.contentRevision) ||
        !putExact(fields.CACHE_KEY_LOOP_REVISION, cache.key.loopRevision) ||
        !putExact(fields.CACHE_KEY_START_TIMELINE_FRAME, cache.key.startTimelineFrame) ||
        !putExact(fields.CACHE_KEY_END_TIMELINE_FRAME_EXCLUSIVE, cache.key.endTimelineFrameExclusive)) {
        return nullptr;
    }
    fields.CACHE_REQUIRED_LANE_MASK = static_cast<jlong>(cache.requiredLaneMask);
    fields.CACHE_COMPLETED_LANE_MASK = static_cast<jlong>(cache.completedLaneMask);
    fields.CACHE_FRAME_COUNT = static_cast<jlong>(cache.frameCount);
    fields.CACHE_CACHE_ONLY = cache.cacheOnly ? 1 : 0;
    fields.CACHE_COMBINED_CHECKSUM = rawUnsignedBits(cache.combinedChecksum);
    for (std::size_t lane = 0U; lane < cache.laneChecksums.size(); ++lane) {
        fields.LANE_CHECKSUM[lane] = rawUnsignedBits(cache.laneChecksums[lane]);
    }

    const auto &preparation = snapshot.preparation;
    fields.PREPARATION_PHASE = static_cast<jlong>(preparation.phase);
    if (!putExact(fields.PREPARATION_KEY_LOOP_REVISION, preparation.key.loopRevision)) return nullptr;
    fields.PREPARATION_REQUIRED_LANE_MASK = static_cast<jlong>(preparation.requiredLaneMask);
    fields.PREPARATION_COMPLETED_LANE_MASK = static_cast<jlong>(preparation.completedLaneMask);
    fields.PREPARATION_ACTIVE_LANE = saturatedCounter(preparation.activeLane);
    fields.PREPARATION_ACTIVE_SOURCE_RATE_HZ = static_cast<jlong>(preparation.activeSourceRateHz);
    if (!putExact(fields.PREPARATION_ACTIVE_PREPARATION_GENERATION, preparation.activePreparationGeneration) ||
        !putExact(fields.PREPARATION_NEXT_EXPECTED_TIMELINE_FRAME, preparation.nextExpectedTimelineFrame)) {
        return nullptr;
    }
    fields.PREPARATION_BEGIN_CALLS = saturatedCounter(preparation.beginCalls);
    fields.PREPARATION_CONFIGURED_LANES = saturatedCounter(preparation.configuredLanes);
    fields.PREPARATION_OFFER_CALLS = saturatedCounter(preparation.offerCalls);
    fields.PREPARATION_INPUT_FRAMES_ACCEPTED = saturatedCounter(preparation.inputFramesAccepted);
    fields.PREPARATION_CACHE_FRAMES_WRITTEN = saturatedCounter(preparation.cacheFramesWritten);
    fields.PREPARATION_LANES_COMPLETED = saturatedCounter(preparation.lanesCompleted);
    fields.PREPARATION_COMMITS = saturatedCounter(preparation.commits);
    fields.PREPARATION_FAILURES = saturatedCounter(preparation.failures);
    fields.PREPARATION_ABORTS = saturatedCounter(preparation.aborts);

    const auto &diagnostics = snapshot.diagnostics;
    fields.ACCEPTED_INTENTS = saturatedCounter(diagnostics.acceptedIntents);
    fields.UNCHANGED_INTENTS = saturatedCounter(diagnostics.unchangedIntents);
    fields.OWNERSHIP_REBINDS = saturatedCounter(diagnostics.ownershipRebinds);
    fields.STALE_INTENTS = saturatedCounter(diagnostics.staleIntents);
    fields.REVISION_COLLISIONS = saturatedCounter(diagnostics.revisionCollisions);
    fields.CACHES_ACCEPTED = saturatedCounter(diagnostics.cachesAccepted);
    fields.CACHE_REJECTIONS = saturatedCounter(diagnostics.cacheRejections);
    fields.INGRESS_REQUESTS = saturatedCounter(diagnostics.ingressRequests);
    fields.INGRESS_ACKS = saturatedCounter(diagnostics.ingressAcks);
    fields.INGRESS_ACK_REJECTIONS = saturatedCounter(diagnostics.ingressAckRejections);
    fields.POSITION_OBSERVATIONS = saturatedCounter(diagnostics.positionObservations);
    fields.REJECTED_POSITION_OBSERVATIONS = saturatedCounter(diagnostics.rejectedPositionObservations);
    fields.BOUNDARY_EVENTS = saturatedCounter(diagnostics.boundaryEvents);
    fields.REJECTED_BOUNDARY_EVENTS = saturatedCounter(diagnostics.rejectedBoundaryEvents);
    fields.RENDER_DIRECTIVES = saturatedCounter(diagnostics.renderDirectives);
    fields.RENDER_COMMITS = saturatedCounter(diagnostics.renderCommits);
    fields.CACHE_ONLY_WRAPS = saturatedCounter(diagnostics.cacheOnlyWraps);
    fields.STREAMED_WRAPS = saturatedCounter(diagnostics.streamedWraps);
    fields.DEADLINE_FALLBACKS = saturatedCounter(diagnostics.deadlineFallbacks);
    fields.CALLBACK_PROOFS = saturatedCounter(diagnostics.callbackProofs);
    fields.REJECTED_CALLBACK_PROOFS = saturatedCounter(diagnostics.rejectedCallbackProofs);
    fields.LAST_SUBMIT_STATUS = static_cast<jlong>(snapshot.lastSubmitStatus);
    fields.LAST_CACHE_STATUS = static_cast<jlong>(snapshot.lastCacheStatus);
    fields.LAST_INGRESS_STATUS = static_cast<jlong>(snapshot.lastIngressStatus);
    fields.LAST_POSITION_STATUS = static_cast<jlong>(snapshot.lastPositionStatus);
    fields.LAST_BOUNDARY_STATUS = static_cast<jlong>(snapshot.lastBoundaryStatus);
    fields.LAST_RENDER_COMMIT_STATUS = static_cast<jlong>(snapshot.lastRenderCommitStatus);
    fields.LAST_CALLBACK_STATUS = static_cast<jlong>(snapshot.lastCallbackStatus);
    if (!putExact(fields.POSITION_EVIDENCE_SEQUENCE, snapshot.positionEvidenceSequence) ||
        !putExact(fields.LAST_OUTPUT_LOOP_EVIDENCE_SEQUENCE, snapshot.lastOutputLoopEvidenceSequence)) {
        return nullptr;
    }
    fields.STATE_BOUNDARY_MODE = static_cast<jlong>(snapshot.state.boundaryMode);
    fields.STATE_BOUNDARY_RESOLVED = snapshot.state.boundaryResolved ? 1 : 0;
    if (!putExact(fields.STATE_RESOLVED_LOOP_END_TIMELINE_FRAME_EXCLUSIVE, snapshot.state.resolvedLoopEndTimelineFrameExclusive) ||
        !putExact(fields.REQUEST_REQUIRED_END_TIMELINE_FRAME_EXCLUSIVE, request.requiredEndTimelineFrameExclusive)) {
        return nullptr;
    }
    fields.DIRECTIVE_BOUNDARY_MODE = static_cast<jlong>(directive.boundaryMode);
    fields.CACHE_BOUNDARY_MODE = static_cast<jlong>(cache.boundaryMode);
    fields.CACHE_ORIGINAL_END_RESOLVED = cache.originalEndResolved ? 1 : 0;
    if (!putExact(fields.CACHE_RESOLVED_ORIGINAL_END_TIMELINE_FRAME_EXCLUSIVE, cache.resolvedOriginalEndTimelineFrameExclusive)) return nullptr;
    fields.LAST_ORIGINAL_EOS_STATUS = static_cast<jlong>(snapshot.lastOriginalEosStatus);
    fields.ORIGINAL_EOS_ACCEPTED = saturatedCounter(diagnostics.originalEosAccepted);
    fields.ORIGINAL_EOS_REJECTED = saturatedCounter(diagnostics.originalEosRejected);

    const auto values = stemwise::audio::packet::packLoopProtocol(fields);
    jlongArray encoded = environment->NewLongArray(static_cast<jsize>(values.size()));
    if (encoded == nullptr) return nullptr;
    environment->SetLongArrayRegion(
        encoded,
        0,
        static_cast<jsize>(values.size()),
        values.data());
    return environment->ExceptionCheck() == JNI_TRUE ? nullptr : encoded;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_app_stemwise_playback_engine_NativePlaybackRuntimeJniBackend_nativeCreate(
    JNIEnv *environment,
    jobject,
    jlong outputHandle,
    jlong firstBankHandle,
    jlong secondBankHandle,
    jint targetBursts,
    jint minimumOutputReserveMs,
    jint fallbackFramesPerBurst,
    jint ingressPullFrames,
    jint maximumPullsPerLanePerCycle,
    jint maximumOutputBlocksPerCycle,
    jint idleSleepMicros,
    jstring currentSongCacheDirectory) {
    // These three values are borrowed pointer bit patterns, not registry sequence numbers.
    // Tagged ARM64 pointers may be negative when transported through signed JNI jlong.
    if (outputHandle == 0 || firstBankHandle == 0 || secondBankHandle == 0 ||
        firstBankHandle == secondBankHandle || targetBursts < 4 || targetBursts > 8 ||
        minimumOutputReserveMs < 4 || minimumOutputReserveMs > 64 ||
        fallbackFramesPerBurst <= 0 || ingressPullFrames <= 0 || ingressPullFrames > 4'096 ||
        maximumPullsPerLanePerCycle <= 0 || maximumPullsPerLanePerCycle > 8 ||
        maximumOutputBlocksPerCycle <= 0 ||
        idleSleepMicros <= 0) {
        throwJava(environment, "java/lang/IllegalArgumentException", "Invalid runtime creation data");
        return 0;
    }

    if (currentSongCacheDirectory == nullptr) {
        throwJava(environment, "java/lang/IllegalArgumentException", "Missing cache directory");
        return 0;
    }
    try {
        const ScopedUtfChars cacheDirectoryChars(environment, currentSongCacheDirectory);
        if (!cacheDirectoryChars.get() || environment->ExceptionCheck() == JNI_TRUE) return 0;

        NativePlaybackRuntimeConfiguration configuration{};
        configuration.targetBursts = static_cast<std::uint32_t>(targetBursts);
        configuration.minimumOutputReserveMicros =
            static_cast<std::uint32_t>(minimumOutputReserveMs) * 1'000U;
        configuration.fallbackFramesPerBurst =
            static_cast<std::uint32_t>(fallbackFramesPerBurst);
        configuration.ingressPullFrames = static_cast<std::uint32_t>(ingressPullFrames);
        configuration.maximumPullsPerLanePerCycle =
            static_cast<std::uint32_t>(maximumPullsPerLanePerCycle);
        configuration.maximumOutputBlocksPerCycle =
            static_cast<std::uint32_t>(maximumOutputBlocksPerCycle);
        configuration.idleSleepMicros = static_cast<std::uint32_t>(idleSleepMicros);
        configuration.currentSongCacheDirectory = cacheDirectoryChars.get();

        auto runtime = std::make_shared<RuntimeHolder>(
            configuration,
            *bankFromHandle(firstBankHandle),
            *bankFromHandle(secondBankHandle),
            outputFromHandle(outputHandle));
        const std::scoped_lock lock(gRuntimeRegistryMutex);
        if (gNextRuntimeHandle >
            static_cast<std::uint64_t>(std::numeric_limits<jlong>::max())) {
            throw std::overflow_error("Native runtime handle space exhausted");
        }
        const jlong handle = static_cast<jlong>(gNextRuntimeHandle++);
        gRuntimeRegistry.emplace(handle, std::move(runtime));
        return handle;
    } catch (const std::bad_alloc &) {
        throwJava(environment, "java/lang/OutOfMemoryError", "Unable to allocate playback runtime");
    } catch (const std::invalid_argument &) {
        throwJava(environment, "java/lang/IllegalArgumentException", "Invalid playback runtime dependency");
    } catch (const std::overflow_error &) {
        throwJava(environment, "java/lang/IllegalStateException", "Playback runtime handle space exhausted");
    } catch (...) {
        throwJava(environment, "java/lang/IllegalStateException", "Unable to create playback runtime");
    }
    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_stemwise_playback_engine_NativePlaybackRuntimeJniBackend_inheritCompletedSongCache(
    JNIEnv *environment, jobject, jlong handle, jlong previousHandle) {
    const auto runtime = runtimeFromHandle(environment, handle);
    const auto previous = runtimeFromHandle(environment, previousHandle);
    return runtime != nullptr && previous != nullptr &&
        runtime->runtime.inheritCompletedSongCache(previous->runtime) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_stemwise_playback_engine_NativePlaybackRuntimeJniBackend_start(
    JNIEnv *environment,
    jobject,
    jlong handle) {
    const auto runtime = runtimeFromHandle(environment, handle);
    return runtime != nullptr && runtime->runtime.start() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_app_stemwise_playback_engine_NativePlaybackRuntimeJniBackend_requestStop(
    JNIEnv *environment,
    jobject,
    jlong handle) {
    const auto runtime = runtimeFromHandle(environment, handle);
    if (runtime != nullptr) runtime->runtime.requestStop();
}

extern "C" JNIEXPORT void JNICALL
Java_app_stemwise_playback_engine_NativePlaybackRuntimeJniBackend_join(
    JNIEnv *environment,
    jobject,
    jlong handle) {
    const auto runtime = runtimeFromHandle(environment, handle);
    if (runtime != nullptr) runtime->runtime.join();
}

extern "C" JNIEXPORT jint JNICALL
Java_app_stemwise_playback_engine_NativePlaybackRuntimeJniBackend_publishCommand(
    JNIEnv *environment,
    jobject,
    jlong handle,
    jlongArray encodedLongs,
    jfloatArray encodedFloats) {
    const auto runtime = runtimeFromHandle(environment, handle);
    if (runtime == nullptr) return static_cast<jint>(NativePlaybackPublishStatus::Stopped);

    std::array<jlong, kCommandLongCount> longs{};
    std::array<jfloat, kCommandFloatCount> floats{};
    NativePlaybackCommandSnapshot snapshot{};
    if (!copyCommandArrays(environment, encodedLongs, encodedFloats, longs, floats) ||
        !decodeCommand(longs, floats, snapshot)) {
        if (environment->ExceptionCheck() != JNI_TRUE) {
            throwJava(
                environment,
                "java/lang/IllegalArgumentException",
                "Invalid native runtime command data");
        }
        return static_cast<jint>(NativePlaybackPublishStatus::InvalidSnapshot);
    }
    return static_cast<jint>(runtime->runtime.publishCommand(snapshot));
}

extern "C" JNIEXPORT jint JNICALL
Java_app_stemwise_playback_engine_NativePlaybackRuntimeJniBackend_publishStemGainPreview(
    JNIEnv *environment,
    jobject,
    jlong handle,
    jlong publicationSequence,
    jlong coversOfferSequence,
    jlong selectionToken,
    jlong generation,
    jlong sourceIntentRevision,
    jlong stemIntentRevision,
    jlong canonicalControlFloorRevision,
    jint activeMask,
    jfloatArray encodedGains) {
    const auto runtime = runtimeFromHandle(environment, handle);
    if (runtime == nullptr) return static_cast<jint>(NativePlaybackPublishStatus::Stopped);
    NativePlaybackStemGainPreview preview{};
    if (!positiveUnsigned(publicationSequence, preview.publicationSequence) ||
        !positiveUnsigned(coversOfferSequence, preview.coversOfferSequence) ||
        !positiveUnsigned(selectionToken, preview.ownership.selectionToken) ||
        !positiveUnsigned(generation, preview.ownership.generation) ||
        !positiveUnsigned(sourceIntentRevision, preview.sourceIntentRevision) ||
        !positiveUnsigned(stemIntentRevision, preview.stemIntentRevision) ||
        !positiveUnsigned(
            canonicalControlFloorRevision,
            preview.canonicalControlFloorRevision) ||
        activeMask < 0 || encodedGains == nullptr ||
        environment->GetArrayLength(encodedGains) !=
            static_cast<jsize>(preview.gains.size())) {
        throwJava(environment, "java/lang/IllegalArgumentException", "Invalid stem-gain preview");
        return static_cast<jint>(NativePlaybackPublishStatus::InvalidSnapshot);
    }
    preview.activeMask = static_cast<std::uint32_t>(activeMask);
    environment->GetFloatArrayRegion(
        encodedGains,
        0,
        static_cast<jsize>(preview.gains.size()),
        preview.gains.data());
    if (environment->ExceptionCheck() == JNI_TRUE) {
        return static_cast<jint>(NativePlaybackPublishStatus::InvalidSnapshot);
    }
    return static_cast<jint>(runtime->runtime.publishStemGainPreview(preview));
}

extern "C" JNIEXPORT jint JNICALL
Java_app_stemwise_playback_engine_NativePlaybackRuntimeJniBackend_publishBeatGrid(
    JNIEnv *environment,
    jobject,
    jlong handle,
    jlong schemaVersion,
    jlong revision,
    jint bank,
    jlong selectionToken,
    jlong generation,
    jlongArray encodedFrames,
    jbyteArray encodedAccentFlags) {
    const auto runtime = runtimeFromHandle(environment, handle);
    if (runtime == nullptr) return static_cast<jint>(NativePlaybackPublishStatus::Stopped);
    if (schemaVersion != kBeatGridSchemaVersion || revision <= 0 || bank < 0 || bank > 1 ||
        selectionToken <= 0 || generation <= 0 || encodedFrames == nullptr ||
        encodedAccentFlags == nullptr) {
        throwJava(environment, "java/lang/IllegalArgumentException", "Invalid beat-grid owner");
        return static_cast<jint>(NativePlaybackPublishStatus::InvalidSnapshot);
    }
    const jsize frameCount = environment->GetArrayLength(encodedFrames);
    const jsize accentCount = environment->GetArrayLength(encodedAccentFlags);
    if (frameCount < 0 || (frameCount == 1) ||
        static_cast<std::size_t>(frameCount) > kMaximumBeatGridPoints ||
        accentCount != frameCount) {
        throwJava(environment, "java/lang/IllegalArgumentException", "Invalid beat-grid size");
        return static_cast<jint>(NativePlaybackPublishStatus::InvalidSnapshot);
    }

    try {
        std::vector<jlong> javaFrames(static_cast<std::size_t>(frameCount));
        std::vector<std::int64_t> nativeFrames(static_cast<std::size_t>(frameCount));
        std::vector<jbyte> javaAccentFlags(
            static_cast<std::size_t>(frameCount));
        std::vector<std::uint8_t> nativeAccentFlags(
            static_cast<std::size_t>(frameCount));
        if (frameCount != 0) {
            environment->GetLongArrayRegion(encodedFrames, 0, frameCount, javaFrames.data());
            if (environment->ExceptionCheck() == JNI_TRUE) {
                return static_cast<jint>(NativePlaybackPublishStatus::InvalidSnapshot);
            }
            for (std::size_t index = 0; index < javaFrames.size(); ++index) {
                const jlong frame = javaFrames[index];
                if (frame < 0 || (index != 0U && frame <= javaFrames[index - 1U])) {
                    throwJava(
                        environment,
                        "java/lang/IllegalArgumentException",
                        "Beat-grid frames must be non-negative and strictly increasing");
                    return static_cast<jint>(NativePlaybackPublishStatus::InvalidSnapshot);
                }
                nativeFrames[index] = static_cast<std::int64_t>(frame);
            }
            {
                environment->GetByteArrayRegion(
                    encodedAccentFlags,
                    0,
                    frameCount,
                    javaAccentFlags.data());
                if (environment->ExceptionCheck() == JNI_TRUE) {
                    return static_cast<jint>(NativePlaybackPublishStatus::InvalidSnapshot);
                }
                for (std::size_t index = 0; index < javaAccentFlags.size(); ++index) {
                    if (javaAccentFlags[index] != 0 && javaAccentFlags[index] != 1) {
                        throwJava(
                            environment,
                            "java/lang/IllegalArgumentException",
                            "Beat-grid accent flags must be exactly zero or one");
                        return static_cast<jint>(NativePlaybackPublishStatus::InvalidSnapshot);
                    }
                    nativeAccentFlags[index] =
                        static_cast<std::uint8_t>(javaAccentFlags[index]);
                }
            }
        }
        const NativePlaybackBeatGridUpdate update{
            static_cast<std::uint64_t>(revision),
            static_cast<NativePlaybackBank>(bank),
            {
                static_cast<std::uint64_t>(selectionToken),
                static_cast<std::uint64_t>(generation),
            },
            static_cast<std::size_t>(frameCount),
        };
        return static_cast<jint>(runtime->runtime.publishBeatGrid(
            update,
            nativeFrames.empty() ? nullptr : nativeFrames.data(),
            nativeAccentFlags.empty() ? nullptr : nativeAccentFlags.data()));
    } catch (const std::bad_alloc &) {
        throwJava(environment, "java/lang/OutOfMemoryError", "Unable to copy beat grid");
    } catch (...) {
        throwJava(environment, "java/lang/IllegalStateException", "Unable to publish beat grid");
    }
    return static_cast<jint>(NativePlaybackPublishStatus::InvalidSnapshot);
}

extern "C" JNIEXPORT jint JNICALL
Java_app_stemwise_playback_engine_NativePlaybackRuntimeJniBackend_publishLoopCycleIntent(
    JNIEnv *environment,
    jobject,
    jlong handle,
    jlongArray encodedIntent) {
    const auto runtime = runtimeFromHandle(environment, handle);
    if (runtime == nullptr) return static_cast<jint>(NativePlaybackPublishStatus::Stopped);
    std::array<jlong, kLoopIntentValueCount> values{};
    LoopCycleIntent intent{};
    if (!copyExactLongArray(
            environment,
            encodedIntent,
            values,
            "Invalid native loop-intent schema size") ||
        !decodeLoopIntent(values, intent)) {
        if (environment->ExceptionCheck() != JNI_TRUE) {
            throwJava(
                environment,
                "java/lang/IllegalArgumentException",
                "Invalid native loop intent");
        }
        return static_cast<jint>(NativePlaybackPublishStatus::InvalidSnapshot);
    }
    return static_cast<jint>(runtime->runtime.publishLoopCycleIntent(intent));
}

extern "C" JNIEXPORT jint JNICALL
Java_app_stemwise_playback_engine_NativePlaybackRuntimeJniBackend_publishDirectLoopPolicy(
    JNIEnv *environment,
    jobject,
    jlong handle,
    jlong revision,
    jlong selectionToken,
    jlong generation,
    jboolean enabled,
    jlong aTimelineFrame,
    jlong bTimelineFrameExclusive,
    jlong capturedBTimelineFrameExclusive,
    jlong inputEventAtMonotonicMicros,
    jlong heldBGestureId,
    jboolean boundaryArmedAtPublication,
    jboolean boundaryCrossedAtPublication,
    jlong captureReferenceTimelineFrame) {
    // Gesture publication may race retirement; a successful lookup retains its shared lifetime.
    const auto runtime = runtimeFromHandle(environment, handle, true);
    if (runtime == nullptr) return static_cast<jint>(NativePlaybackPublishStatus::Stopped);
    if (revision <= 0 || selectionToken <= 0 || generation <= 0 || aTimelineFrame < 0 ||
        bTimelineFrameExclusive < 0 || capturedBTimelineFrameExclusive < 0 ||
        inputEventAtMonotonicMicros < 0 || heldBGestureId < 0 ||
        (capturedBTimelineFrameExclusive != 0 &&
         (boundaryCrossedAtPublication != JNI_TRUE ||
          aTimelineFrame >= capturedBTimelineFrameExclusive)) ||
        (enabled == JNI_TRUE && aTimelineFrame >= bTimelineFrameExclusive)) {
        return static_cast<jint>(NativePlaybackPublishStatus::InvalidSnapshot);
    }
    return static_cast<jint>(runtime->runtime.publishDirectLoopPolicy(
        static_cast<std::uint64_t>(revision),
        static_cast<std::uint64_t>(selectionToken),
        static_cast<std::uint64_t>(generation),
        enabled == JNI_TRUE,
        static_cast<std::uint64_t>(aTimelineFrame),
        static_cast<std::uint64_t>(bTimelineFrameExclusive),
        static_cast<std::uint64_t>(inputEventAtMonotonicMicros),
        boundaryArmedAtPublication == JNI_TRUE,
        boundaryCrossedAtPublication == JNI_TRUE,
        static_cast<std::uint64_t>(heldBGestureId),
        static_cast<std::uint64_t>(capturedBTimelineFrameExclusive),
        static_cast<std::int64_t>(captureReferenceTimelineFrame)));
}

extern "C" JNIEXPORT jint JNICALL
Java_app_stemwise_playback_engine_NativePlaybackRuntimeJniBackend_publishLoopIngressReady(
    JNIEnv *environment,
    jobject,
    jlong handle,
    jlongArray encodedEvidence) {
    const auto runtime = runtimeFromHandle(environment, handle);
    if (runtime == nullptr) return static_cast<jint>(NativePlaybackPublishStatus::Stopped);
    std::array<jlong, kLoopIngressReadyValueCount> values{};
    LoopCycleIngressReadyEvidence evidence{};
    if (!copyExactLongArray(
            environment,
            encodedEvidence,
            values,
            "Invalid loop-ingress-ready schema size") ||
        !decodeLoopIngressReady(values, evidence)) {
        if (environment->ExceptionCheck() != JNI_TRUE) {
            throwJava(
                environment,
                "java/lang/IllegalArgumentException",
                "Invalid loop-ingress-ready evidence");
        }
        return static_cast<jint>(NativePlaybackPublishStatus::InvalidSnapshot);
    }
    return static_cast<jint>(runtime->runtime.publishLoopIngressReady(evidence));
}

extern "C" JNIEXPORT jint JNICALL
Java_app_stemwise_playback_engine_NativePlaybackRuntimeJniBackend_beginLoopHeadPreparation(
    JNIEnv *environment,
    jobject,
    jlong handle,
    jlongArray encodedIntent) {
    const auto runtime = runtimeFromHandle(environment, handle);
    if (runtime == nullptr) {
        return static_cast<jint>(
            stemwise::audio::engine::LoopHeadPreparationBeginStatus::Stopped);
    }
    std::array<jlong, kLoopIntentValueCount> values{};
    LoopCycleIntent intent{};
    if (!copyExactLongArray(
            environment,
            encodedIntent,
            values,
            "Invalid loop-head preparation schema size") ||
        !decodeLoopIntent(values, intent) || !intent.enabled) {
        if (environment->ExceptionCheck() != JNI_TRUE) {
            throwJava(
                environment,
                "java/lang/IllegalArgumentException",
                "Loop-head preparation requires one enabled loop intent");
        }
        return static_cast<jint>(
            stemwise::audio::engine::LoopHeadPreparationBeginStatus::InvalidKey);
    }
    return static_cast<jint>(runtime->runtime.beginLoopHeadPreparation(
        intent.cacheKey,
        intent.requiredLaneMask,
        intent.boundaryMode, {intent.owner.selectionToken, intent.owner.renderGeneration}));
}

extern "C" JNIEXPORT jint JNICALL
Java_app_stemwise_playback_engine_NativePlaybackRuntimeJniBackend_configureLoopHeadPreparationLane(
    JNIEnv *environment,
    jobject,
    jlong handle,
    jlong schemaVersion,
    jlong loopRevision,
    jint laneIndex,
    jint sourceRateHz,
    jlong preparationGeneration) {
    const auto runtime = runtimeFromHandle(environment, handle);
    if (runtime == nullptr) {
        return static_cast<jint>(
            stemwise::audio::engine::LoopHeadPreparationConfigureStatus::Stopped);
    }
    if (schemaVersion != kLoopIntentSchemaVersion || loopRevision <= 0 ||
        laneIndex < 0 ||
        static_cast<std::size_t>(laneIndex) >=
            stemwise::audio::engine::kLoopHeadLaneCount ||
        sourceRateHz <= 0 || preparationGeneration <= 0) {
        throwJava(
            environment,
            "java/lang/IllegalArgumentException",
            "Invalid loop-head lane configuration");
        return static_cast<jint>(
            stemwise::audio::engine::LoopHeadPreparationConfigureStatus::InvalidConfiguration);
    }
    return static_cast<jint>(runtime->runtime.configureLoopHeadPreparationLane(
        static_cast<std::uint64_t>(loopRevision),
        static_cast<std::size_t>(laneIndex),
        static_cast<std::uint32_t>(sourceRateHz),
        static_cast<std::uint64_t>(preparationGeneration)));
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_stemwise_playback_engine_NativePlaybackRuntimeJniBackend_offerLoopHeadPreparationPcm(
    JNIEnv *environment,
    jobject,
    jlong handle,
    jlong schemaVersion,
    jlong loopRevision,
    jint laneIndex,
    jlong preparationGeneration,
    jlong presentationTimeUs,
    jint sourceRateHz,
    jint encodingCode,
    jint channelCount,
    jobject directBuffer,
    jint byteOffset,
    jint frameCount) {
    const auto invalid = []() noexcept {
        return packLoopPreparationOffer({
            stemwise::audio::engine::LoopHeadPreparationOfferStatus::InvalidArgument});
    };
    const auto runtime = runtimeFromHandle(environment, handle);
    if (runtime == nullptr) {
        return packLoopPreparationOffer({
            stemwise::audio::engine::LoopHeadPreparationOfferStatus::Stopped});
    }
    if (schemaVersion != kLoopIntentSchemaVersion || loopRevision <= 0 ||
        laneIndex < 0 ||
        static_cast<std::size_t>(laneIndex) >=
            stemwise::audio::engine::kLoopHeadLaneCount ||
        preparationGeneration <= 0 || presentationTimeUs < 0 || sourceRateHz <= 0 ||
        channelCount < 1 || channelCount > 2 || directBuffer == nullptr ||
        byteOffset < 0 || frameCount <= 0) {
        return invalid();
    }
    bool validEncoding = false;
    const LanePcmEncoding encoding = loopPreparationEncoding(encodingCode, validEncoding);
    if (!validEncoding) return invalid();
    void *bufferAddress = environment->GetDirectBufferAddress(directBuffer);
    const jlong bufferCapacity = environment->GetDirectBufferCapacity(directBuffer);
    if (bufferAddress == nullptr || bufferCapacity < 0) return invalid();
    const std::uint64_t frameBytes = loopPreparationBytesPerSample(encoding) *
        static_cast<std::uint64_t>(channelCount);
    const std::uint64_t requiredBytes =
        static_cast<std::uint64_t>(frameCount) * frameBytes;
    const std::uint64_t offset = static_cast<std::uint64_t>(byteOffset);
    if (offset > static_cast<std::uint64_t>(bufferCapacity) ||
        requiredBytes > static_cast<std::uint64_t>(bufferCapacity) - offset) {
        return invalid();
    }
    const auto *source = static_cast<const std::byte *>(bufferAddress) + byteOffset;
    return packLoopPreparationOffer(runtime->runtime.offerLoopHeadPreparationPcm(
        static_cast<std::uint64_t>(loopRevision),
        static_cast<std::size_t>(laneIndex),
        LaneInputBlock{
            .data = source,
            .frameCount = static_cast<std::size_t>(frameCount),
            .format = {
                .sampleRateHz = static_cast<std::uint32_t>(sourceRateHz),
                .channelCount = static_cast<std::uint8_t>(channelCount),
                .encoding = encoding,
            },
            .generation = static_cast<std::uint64_t>(preparationGeneration),
            .presentationTimeUs = static_cast<std::uint64_t>(presentationTimeUs),
        }));
}

extern "C" JNIEXPORT jint JNICALL
Java_app_stemwise_playback_engine_NativePlaybackRuntimeJniBackend_markLoopHeadPreparationEndOfStream(
    JNIEnv *environment,
    jobject,
    jlong handle,
    jlong schemaVersion,
    jlong loopRevision,
    jint laneIndex,
    jlong preparationGeneration,
    jlong finalSourceFrameExclusive) {
    const auto runtime = runtimeFromHandle(environment, handle);
    if (runtime == nullptr) {
        return static_cast<jint>(
            stemwise::audio::engine::LoopHeadPreparationEndStatus::Stopped);
    }
    if (schemaVersion != kLoopIntentSchemaVersion || loopRevision <= 0 ||
        laneIndex < 0 ||
        static_cast<std::size_t>(laneIndex) >=
            stemwise::audio::engine::kLoopHeadLaneCount ||
        preparationGeneration <= 0 || finalSourceFrameExclusive < 0) {
        return static_cast<jint>(
            stemwise::audio::engine::LoopHeadPreparationEndStatus::InvalidArgument);
    }
    return static_cast<jint>(runtime->runtime.markLoopHeadPreparationEndOfStream(
        static_cast<std::uint64_t>(loopRevision),
        static_cast<std::size_t>(laneIndex),
        static_cast<std::uint64_t>(preparationGeneration),
        static_cast<std::uint64_t>(finalSourceFrameExclusive)));
}

extern "C" JNIEXPORT jint JNICALL
Java_app_stemwise_playback_engine_NativePlaybackRuntimeJniBackend_abortLoopHeadPreparation(
    JNIEnv *environment,
    jobject,
    jlong handle,
    jlong schemaVersion,
    jlong loopRevision) {
    const auto runtime = runtimeFromHandle(environment, handle);
    if (runtime == nullptr) {
        return static_cast<jint>(
            stemwise::audio::engine::LoopHeadPreparationAbortStatus::Stopped);
    }
    if (schemaVersion != kLoopIntentSchemaVersion || loopRevision <= 0) {
        return static_cast<jint>(
            stemwise::audio::engine::LoopHeadPreparationAbortStatus::StaleRevision);
    }
    return static_cast<jint>(runtime->runtime.abortLoopHeadPreparation(
        static_cast<std::uint64_t>(loopRevision)));
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_app_stemwise_playback_engine_NativePlaybackRuntimeJniBackend_loopProtocolSnapshot(
    JNIEnv *environment,
    jobject,
    jlong handle) {
    const auto runtime = runtimeFromHandle(environment, handle);
    return runtime == nullptr
        ? nullptr
        : encodeLoopProtocolSnapshot(
            environment,
            runtime->runtime.loopProtocolSnapshot());
}

extern "C" JNIEXPORT void JNICALL
Java_app_stemwise_playback_engine_NativePlaybackRuntimeJniBackend_notifyIngressAvailable(
    JNIEnv *environment,
    jobject,
    jlong handle) {
    const auto runtime = runtimeFromHandle(environment, handle);
    if (runtime != nullptr) runtime->runtime.notifyIngressAvailable();
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_stemwise_playback_engine_NativePlaybackRuntimeJniBackend_awaitAudibleControlProgress(
    JNIEnv *environment,
    jobject,
    jlong handle,
    jlong afterSequence) {
    if (afterSequence < 0) return static_cast<jlong>(-1);
    const auto runtime = runtimeFromHandle(environment, handle);
    if (runtime == nullptr) return static_cast<jlong>(-1);
    const std::uint64_t sequence = runtime->runtime.waitForAudibleControlProgress(
        static_cast<std::uint64_t>(afterSequence));
    return sequence == 0U
        ? static_cast<jlong>(-1)
        : static_cast<jlong>(saturatedCounter(sequence));
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_app_stemwise_playback_engine_NativePlaybackRuntimeJniBackend_stemGainCallbackSettlementDiagnostics(
    JNIEnv *environment,
    jobject,
    jlong handle) {
    const auto runtime = runtimeFromHandle(environment, handle);
    return runtime == nullptr
        ? nullptr
        : encodeStemGainCallbackDiagnostics(
            environment,
            runtime->runtime.stemGainCallbackSettlementDiagnostics());
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_app_stemwise_playback_engine_NativePlaybackRuntimeJniBackend_audibleControlEvidence(
    JNIEnv *environment, jobject, jlong handle) {
    const auto runtime = runtimeFromHandle(environment, handle);
    if (runtime == nullptr) return nullptr;
    const auto evidence = runtime->runtime.audibleControlEvidence();
    stemwise::audio::packet::AudibleControlFields<jlong> fields{};
    fields.SCHEMA = stemwise::audio::packet::AUDIBLE_CONTROL_VERSION;
    fields.SEQUENCE = saturatedCounter(evidence.sequence);
    if (!exactLong(environment, evidence.ownership.selectionToken, fields.TOKEN, "Control token exceeds Long") ||
        !exactLong(environment, evidence.ownership.generation, fields.GENERATION, "Control generation exceeds Long") ||
        !exactLong(environment, evidence.controlRevision, fields.CONTROL_REVISION, "Control revision exceeds Long")) {
        return nullptr;
    }
    fields.ACTUAL_SOURCE_MODE = static_cast<jlong>(evidence.actualSourceMode);
    fields.FIRST_FINGERPRINT = saturatedCounter(evidence.firstFingerprintSequence);
    fields.LAST_FINGERPRINT = saturatedCounter(evidence.lastFingerprintSequence);
    fields.CALLBACK_SEQUENCE = saturatedCounter(evidence.callbackSequence);
    fields.PCM_FRAMES = saturatedCounter(evidence.pcmFrames);
    fields.ACTUAL_TEMPO_BITS = std::bit_cast<std::uint32_t>(evidence.actualTempo);
    fields.ACTUAL_PITCH_SEMITONES_BITS = std::bit_cast<std::uint32_t>(evidence.actualPitchSemitones);
    const auto values = stemwise::audio::packet::packAudibleControl(fields);
    const auto encoded = environment->NewLongArray(static_cast<jsize>(values.size()));
    if (encoded == nullptr) return nullptr;
    environment->SetLongArrayRegion(encoded, 0, static_cast<jsize>(values.size()), values.data());
    return environment->ExceptionCheck() == JNI_TRUE ? nullptr : encoded;
}

extern "C" JNIEXPORT void JNICALL
Java_app_stemwise_playback_engine_NativePlaybackRuntimeJniBackend_setDiagnosticPayloadFingerprints(
    JNIEnv *environment, jobject, jlong handle, jboolean enabled) {
    const auto runtime = runtimeFromHandle(environment, handle);
    if (runtime) runtime->runtime.setDiagnosticPayloadFingerprints(enabled == JNI_TRUE);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_app_stemwise_playback_engine_NativePlaybackRuntimeJniBackend_controlProgress(
    JNIEnv *environment, jobject, jlong handle) {
    const auto runtime = runtimeFromHandle(environment, handle);
    return runtime ? encodeControlProgress(environment, runtime->runtime.controlProgress()) : nullptr;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_app_stemwise_playback_engine_NativePlaybackRuntimeJniBackend_diagnostics(
    JNIEnv *environment,
    jobject,
    jlong handle) {
    const auto runtime = runtimeFromHandle(environment, handle);
    return runtime == nullptr
        ? nullptr
        : encodeDiagnostics(environment, runtime->runtime.diagnostics());
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_app_stemwise_playback_engine_NativePlaybackRuntimeJniBackend_renderReadinessDiagnostics(
    JNIEnv *environment,
    jobject,
    jlong handle) {
    const auto runtime = runtimeFromHandle(environment, handle);
    return runtime == nullptr
        ? nullptr
        : encodeRenderReadinessDiagnostics(
            environment,
            runtime->runtime.renderReadinessDiagnostics());
}

extern "C" JNIEXPORT void JNICALL
Java_app_stemwise_playback_engine_NativePlaybackRuntimeJniBackend_close(
    JNIEnv *environment,
    jobject,
    jlong handle) {
    std::shared_ptr<RuntimeHolder> runtime;
    try {
        const std::scoped_lock lock(gRuntimeRegistryMutex);
        const auto found = gRuntimeRegistry.find(handle);
        if (handle <= 0 || found == gRuntimeRegistry.end()) {
            throwJava(environment, "java/lang/IllegalStateException", "Native runtime is closed");
            return;
        }
        runtime = std::move(found->second);
        gRuntimeRegistry.erase(found);
    } catch (...) {
        throwJava(environment, "java/lang/IllegalStateException", "Unable to close playback runtime");
        return;
    }

    // No registry lock is held while joining. This is the definitive borrowed-owner lifetime fence.
    runtime->runtime.requestStop();
    runtime->runtime.join();
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_stemwise_playback_engine_NativePlaybackRuntimeJniBackend_awaitPcmCacheProgress(
    JNIEnv *environment, jobject, jlong handle, jlong after) {
    const auto owner = runtimeFromHandle(environment, handle);
    return !owner || after < 0 ? 0 : static_cast<jlong>(owner->runtime.waitPcmCacheProgress(static_cast<std::uint64_t>(after)));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_stemwise_playback_engine_NativePlaybackRuntimeJniBackend_pcmCacheRangeReady(
    JNIEnv *environment, jobject, jlong handle, jlongArray owner, jint mask, jlong first, jlong end) {
    const auto runtime = runtimeFromHandle(environment, handle);
    std::array<jlong, 5U> values{};
    if (!runtime || mask <= 0 || first < 0 || end <= first ||
        !copyExactLongArray(environment, owner, values, "Invalid PCM range owner")) return JNI_FALSE;
    const stemwise::audio::engine::CurrentSongCacheKey key{static_cast<std::uint64_t>(values[0]),
        static_cast<std::uint64_t>(values[1]), rawUnsignedFromLong(values[2]), rawUnsignedFromLong(values[3]), rawUnsignedFromLong(values[4])};
    return runtime->runtime.pcmCacheRangeReady(key, static_cast<std::uint32_t>(mask),
        static_cast<std::uint64_t>(first), static_cast<std::uint64_t>(end)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_stemwise_playback_engine_NativePlaybackRuntimeJniBackend_beginPcmWindowRead(
    JNIEnv *environment, jobject, jlong handle, jlongArray owner, jint lane, jlong first, jint frames) {
    const auto runtime = runtimeFromHandle(environment, handle);
    std::array<jlong, 5U> values{};
    if (!runtime || lane < 0 || first < 0 || frames <= 0 || frames > 480'000 ||
        !copyExactLongArray(environment, owner, values, "Invalid waveform PCM owner")) return 0;
    const stemwise::audio::engine::CurrentSongCacheKey key{static_cast<std::uint64_t>(values[0]),
        static_cast<std::uint64_t>(values[1]), rawUnsignedFromLong(values[2]), rawUnsignedFromLong(values[3]), rawUnsignedFromLong(values[4])};
    return static_cast<jlong>(runtime->runtime.beginPcmWindowRead(key, static_cast<std::size_t>(lane),
        static_cast<std::uint64_t>(first), static_cast<std::size_t>(frames)));
}
extern "C" JNIEXPORT jint JNICALL
Java_app_stemwise_playback_engine_NativePlaybackRuntimeJniBackend_pollPcmWindowRead(
    JNIEnv *environment, jobject, jlong handle, jlong ticket, jobject pcm, jint frames) {
    const auto runtime = runtimeFromHandle(environment, handle);
    auto *samples = pcm ? static_cast<float *>(environment->GetDirectBufferAddress(pcm)) : nullptr;
    if (!runtime || !samples || ticket <= 0 || frames <= 0 || frames > 480'000 ||
        environment->GetDirectBufferCapacity(pcm) < static_cast<jlong>(frames) * 2 * static_cast<jlong>(sizeof(float))) return -1;
    return runtime->runtime.pollPcmWindowRead(static_cast<std::uint64_t>(ticket), samples, static_cast<std::size_t>(frames));
}
extern "C" JNIEXPORT void JNICALL
Java_app_stemwise_playback_engine_NativePlaybackRuntimeJniBackend_cancelPcmWindowRead(
    JNIEnv *environment, jobject, jlong handle, jlong ticket) {
    if (const auto runtime = runtimeFromHandle(environment, handle); runtime && ticket > 0)
        runtime->runtime.cancelPcmWindowRead(static_cast<std::uint64_t>(ticket));
}

#if defined(STEMWISE_AUDIT_OPTIMIZED)
extern "C" JNIEXPORT jfloatArray JNICALL
Java_app_stemwise_playback_engine_NativeCanonicalPcmInstrumentationBackend_read(
    JNIEnv *environment, jobject, jlong handle, jlongArray owner, jint lane, jlong first, jint frames) {
    const auto runtime = runtimeFromHandle(environment, handle);
    std::array<jlong, 5U> values{};
    if (!runtime || lane < 0 || lane >= static_cast<jint>(stemwise::audio::engine::kCurrentSongCacheLaneCount) ||
        first < 0 || frames <= 0 || frames > 4'096 ||
        !copyExactLongArray(environment, owner, values, "Invalid audit PCM owner")) return environment->NewFloatArray(0);
    const stemwise::audio::engine::CurrentSongCacheKey key{static_cast<std::uint64_t>(values[0]),
        static_cast<std::uint64_t>(values[1]), rawUnsignedFromLong(values[2]), rawUnsignedFromLong(values[3]), rawUnsignedFromLong(values[4])};
    std::array<float, 4'096U * 2U> samples{};
    if (!key.valid() || !runtime->runtime.readPcmCacheForAudit(key, static_cast<std::size_t>(lane),
            static_cast<std::uint64_t>(first), samples.data(), static_cast<std::size_t>(frames))) return environment->NewFloatArray(0);
    const auto result = environment->NewFloatArray(frames * 2);
    if (result) environment->SetFloatArrayRegion(result, 0, frames * 2, samples.data());
    return result;
}
#endif
