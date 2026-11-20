#include "LaneIngress.h"

#include <jni.h>

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <new>
#include <stdexcept>

namespace {

using stemwise::audio::engine::LaneInputBlock;
using stemwise::audio::engine::LaneInputFormat;
using stemwise::audio::engine::LaneEndOfStreamStatus;
using stemwise::audio::engine::LaneOfferStatus;
using stemwise::audio::engine::LanePcmEncoding;
using stemwise::audio::engine::SongLaneIngressBank;

constexpr std::uint32_t kStatusAccepted = 0;
constexpr std::uint32_t kStatusPartial = 1;
constexpr std::uint32_t kStatusBackpressured = 2;
constexpr std::uint32_t kStatusStaleGeneration = 3;
constexpr std::uint32_t kStatusInvalidArgument = 4;
constexpr std::uint32_t kStatusAfterEndOfStream = 5;

SongLaneIngressBank *bankFromHandle(jlong handle) noexcept {
    return reinterpret_cast<SongLaneIngressBank *>(static_cast<std::uintptr_t>(handle));
}

jlong handleFromBank(SongLaneIngressBank *bank) noexcept {
    return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(bank));
}

void throwJava(JNIEnv *environment, const char *className, const char *message) noexcept {
    if (environment->ExceptionCheck() == JNI_TRUE) return;
    jclass exceptionClass = environment->FindClass(className);
    if (exceptionClass != nullptr) environment->ThrowNew(exceptionClass, message);
}

std::uint32_t statusCode(LaneOfferStatus status) noexcept {
    switch (status) {
        case LaneOfferStatus::Accepted:
            return kStatusAccepted;
        case LaneOfferStatus::Partial:
            return kStatusPartial;
        case LaneOfferStatus::Backpressured:
            return kStatusBackpressured;
        case LaneOfferStatus::StaleGeneration:
            return kStatusStaleGeneration;
        case LaneOfferStatus::AfterEndOfStream:
            return kStatusAfterEndOfStream;
        case LaneOfferStatus::InvalidArgument:
            return kStatusInvalidArgument;
    }
    return kStatusInvalidArgument;
}

jlong packOffer(std::uint32_t status, std::size_t acceptedFrames) noexcept {
    if (acceptedFrames > std::numeric_limits<std::uint32_t>::max()) {
        status = kStatusInvalidArgument;
        acceptedFrames = 0;
    }
    const std::uint64_t packed =
        (static_cast<std::uint64_t>(status) << 32U) |
        static_cast<std::uint32_t>(acceptedFrames);
    return static_cast<jlong>(packed);
}

LanePcmEncoding encodingFromCode(jint code, bool &valid) noexcept {
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

std::size_t bytesPerSample(LanePcmEncoding encoding) noexcept {
    return encoding == LanePcmEncoding::Signed16 ? sizeof(std::int16_t) : sizeof(float);
}

bool validLaneIndex(jint laneIndex) noexcept {
    return laneIndex >= 0 &&
        static_cast<std::size_t>(laneIndex) < SongLaneIngressBank::kLaneCount;
}

jint endOfStreamStatusCode(LaneEndOfStreamStatus status) noexcept {
    switch (status) {
        case LaneEndOfStreamStatus::Accepted:
            return 0;
        case LaneEndOfStreamStatus::AlreadyMarked:
            return 1;
        case LaneEndOfStreamStatus::StaleGeneration:
            return 2;
        case LaneEndOfStreamStatus::BoundaryMismatch:
            return 3;
        case LaneEndOfStreamStatus::InvalidArgument:
            return 4;
    }
    return 4;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_app_stemwise_playback_engine_NativeLaneIngressJniBackend_createSongBank(
    JNIEnv *environment,
    jobject,
    jint queueCapacityFrames,
    jint maximumInputQuantumFrames) {
    if (queueCapacityFrames <= 0 || maximumInputQuantumFrames <= 0) {
        throwJava(environment, "java/lang/IllegalArgumentException", "Invalid lane queue bounds");
        return 0;
    }
    try {
        return handleFromBank(new SongLaneIngressBank(
            static_cast<std::size_t>(queueCapacityFrames),
            static_cast<std::size_t>(maximumInputQuantumFrames)));
    } catch (const std::invalid_argument &) {
        throwJava(environment, "java/lang/IllegalArgumentException", "Invalid lane queue bounds");
    } catch (const std::bad_alloc &) {
        throwJava(environment, "java/lang/OutOfMemoryError", "Unable to allocate lane buffers");
    } catch (...) {
        throwJava(environment, "java/lang/IllegalStateException", "Unable to create lane bank");
    }
    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_stemwise_playback_engine_NativeLaneIngressJniBackend_configureLane(
    JNIEnv *environment,
    jobject,
    jlong bankHandle,
    jint laneIndex,
    jint sourceRateHz,
    jlong initialGeneration) {
    SongLaneIngressBank *bank = bankFromHandle(bankHandle);
    if (bank == nullptr || !validLaneIndex(laneIndex) || sourceRateHz <= 0 ||
        initialGeneration <= 0) {
        throwJava(environment, "java/lang/IllegalArgumentException", "Invalid lane configuration");
        return JNI_FALSE;
    }
    try {
        return bank->configureLane(
            static_cast<std::size_t>(laneIndex),
            static_cast<std::uint32_t>(sourceRateHz),
            static_cast<std::uint64_t>(initialGeneration))
            ? JNI_TRUE
            : JNI_FALSE;
    } catch (const std::bad_alloc &) {
        throwJava(environment, "java/lang/OutOfMemoryError", "Unable to allocate a lane");
    } catch (...) {
        throwJava(environment, "java/lang/IllegalStateException", "Unable to configure a lane");
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_stemwise_playback_engine_NativeLaneIngressJniBackend_offer(
    JNIEnv *environment,
    jobject,
    jlong bankHandle,
    jint laneIndex,
    jlong generation,
    jlong presentationTimeUs,
    jint encodingCode,
    jint channelCount,
    jobject directBuffer,
    jint byteOffset,
    jint frameCount) {
    SongLaneIngressBank *bank = bankFromHandle(bankHandle);
    if (bank == nullptr || !validLaneIndex(laneIndex) || generation <= 0 ||
        presentationTimeUs < 0 || channelCount < 1 || channelCount > 2 ||
        directBuffer == nullptr || byteOffset < 0 || frameCount <= 0) {
        return packOffer(kStatusInvalidArgument, 0);
    }
    auto *lane = bank->lane(static_cast<std::size_t>(laneIndex));
    if (lane == nullptr) return packOffer(kStatusInvalidArgument, 0);

    bool validEncoding = false;
    const LanePcmEncoding encoding = encodingFromCode(encodingCode, validEncoding);
    if (!validEncoding) return packOffer(kStatusInvalidArgument, 0);
    void *bufferAddress = environment->GetDirectBufferAddress(directBuffer);
    const jlong bufferCapacity = environment->GetDirectBufferCapacity(directBuffer);
    if (bufferAddress == nullptr || bufferCapacity < 0) {
        return packOffer(kStatusInvalidArgument, 0);
    }
    const std::uint64_t frameBytes =
        bytesPerSample(encoding) * static_cast<std::uint64_t>(channelCount);
    const std::uint64_t requiredBytes =
        static_cast<std::uint64_t>(frameCount) * frameBytes;
    const std::uint64_t unsignedOffset = static_cast<std::uint64_t>(byteOffset);
    if (unsignedOffset > static_cast<std::uint64_t>(bufferCapacity) ||
        requiredBytes > static_cast<std::uint64_t>(bufferCapacity) - unsignedOffset) {
        return packOffer(kStatusInvalidArgument, 0);
    }

    const auto *source = static_cast<const std::byte *>(bufferAddress) + byteOffset;
    const auto result = lane->offer(LaneInputBlock{
        source,
        static_cast<std::size_t>(frameCount),
        LaneInputFormat{
            lane->sourceRateHz(),
            static_cast<std::uint8_t>(channelCount),
            encoding,
        },
        static_cast<std::uint64_t>(generation),
        static_cast<std::uint64_t>(presentationTimeUs),
    });
    return packOffer(statusCode(result.status), result.inputFramesAccepted);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_stemwise_playback_engine_NativeLaneIngressJniBackend_advanceGeneration(
    JNIEnv *,
    jobject,
    jlong bankHandle,
    jint laneIndex,
    jlong generation) {
    SongLaneIngressBank *bank = bankFromHandle(bankHandle);
    if (bank == nullptr || !validLaneIndex(laneIndex) || generation <= 0) return JNI_FALSE;
    auto *lane = bank->lane(static_cast<std::size_t>(laneIndex));
    return lane != nullptr && lane->advanceGeneration(static_cast<std::uint64_t>(generation))
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_stemwise_playback_engine_NativeLaneIngressJniBackend_markDiscontinuity(
    JNIEnv *,
    jobject,
    jlong bankHandle,
    jint laneIndex,
    jlong generation) {
    SongLaneIngressBank *bank = bankFromHandle(bankHandle);
    if (bank == nullptr || !validLaneIndex(laneIndex) || generation <= 0) return JNI_FALSE;
    auto *lane = bank->lane(static_cast<std::size_t>(laneIndex));
    return lane != nullptr && lane->markDiscontinuity(static_cast<std::uint64_t>(generation))
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_stemwise_playback_engine_NativeLaneIngressJniBackend_markEndOfStream(
    JNIEnv *,
    jobject,
    jlong bankHandle,
    jint laneIndex,
    jlong generation,
    jlong finalSourceFrameExclusive) {
    SongLaneIngressBank *bank = bankFromHandle(bankHandle);
    if (bank == nullptr || !validLaneIndex(laneIndex) || generation <= 0 ||
        finalSourceFrameExclusive < 0) {
        return 4;
    }
    auto *lane = bank->lane(static_cast<std::size_t>(laneIndex));
    if (lane == nullptr) return 4;
    return endOfStreamStatusCode(lane->markEndOfStream(
        static_cast<std::uint64_t>(generation),
        static_cast<std::uint64_t>(finalSourceFrameExclusive)).status);
}

extern "C" JNIEXPORT jint JNICALL
Java_app_stemwise_playback_engine_NativeLaneIngressJniBackend_readableSourceFrames(
    JNIEnv *,
    jobject,
    jlong bankHandle,
    jint laneIndex) {
    SongLaneIngressBank *bank = bankFromHandle(bankHandle);
    if (bank == nullptr || !validLaneIndex(laneIndex)) return -1;
    const auto *lane = bank->lane(static_cast<std::size_t>(laneIndex));
    if (lane == nullptr) return -1;
    const std::size_t readable = lane->diagnostics().readableSourceFrames;
    return readable > static_cast<std::size_t>(std::numeric_limits<jint>::max())
        ? std::numeric_limits<jint>::max()
        : static_cast<jint>(readable);
}

extern "C" JNIEXPORT jint JNICALL
Java_app_stemwise_playback_engine_NativeLaneIngressJniBackend_pullOriginal48kForPreparation(
    JNIEnv *environment,
    jobject,
    jlong bankHandle,
    jlong generation,
    jobject output,
    jlongArray metadata) {
    auto *bank = bankFromHandle(bankHandle);
    void *address = output == nullptr ? nullptr : environment->GetDirectBufferAddress(output);
    const jlong capacityBytes = output == nullptr ? -1 : environment->GetDirectBufferCapacity(output);
    constexpr jlong bytesPerFrame = sizeof(float) * 2U;
    if (bank == nullptr || generation <= 0 || address == nullptr || capacityBytes < bytesPerFrame ||
        capacityBytes % bytesPerFrame != 0 || metadata == nullptr ||
        environment->GetArrayLength(metadata) != 3 ||
        reinterpret_cast<std::uintptr_t>(address) % alignof(float) != 0) {
        throwJava(environment, "java/lang/IllegalArgumentException", "Invalid preparation readback storage");
        return -1;
    }
    auto *lane = bank->lane(0U);
    if (lane == nullptr) {
        throwJava(environment, "java/lang/IllegalStateException", "Original preparation lane is not configured");
        return -1;
    }
    const auto capacityFrames = std::min(
        static_cast<std::size_t>(capacityBytes / bytesPerFrame),
        lane->diagnostics().maximumOutputQuantumFrames);
    const auto pulled = lane->pull48k(
        static_cast<std::uint64_t>(generation), static_cast<float *>(address),
        capacityFrames);
    const jlong values[3]{
        static_cast<jlong>(pulled.firstTimelineFrame48k),
        static_cast<jlong>(pulled.status),
        static_cast<jlong>(pulled.sourceFramesConsumed),
    };
    environment->SetLongArrayRegion(metadata, 0, 3, values);
    return static_cast<jint>(pulled.outputFramesProduced);
}

extern "C" JNIEXPORT void JNICALL
Java_app_stemwise_playback_engine_NativeLaneIngressJniBackend_releaseLane(
    JNIEnv *,
    jobject,
    jlong bankHandle,
    jint laneIndex) {
    SongLaneIngressBank *bank = bankFromHandle(bankHandle);
    if (bank != nullptr && validLaneIndex(laneIndex)) {
        bank->releaseLane(static_cast<std::size_t>(laneIndex));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_app_stemwise_playback_engine_NativeLaneIngressJniBackend_closeSongBank(
    JNIEnv *,
    jobject,
    jlong bankHandle) {
    delete bankFromHandle(bankHandle);
}
