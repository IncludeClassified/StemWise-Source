#include "WavPackReader.h"
#include <jni.h>
#include <memory>
#include <stdexcept>
#include <array>
#include <cstdio>

using stemwise::audio::WavPackReader;
namespace {
WavPackReader *reader(jlong handle) {
    if (handle == 0) throw std::runtime_error("WavPack reader is closed");
    return reinterpret_cast<WavPackReader *>(static_cast<std::uintptr_t>(handle));
}
void fail(JNIEnv *env, const char *operation, const char *reason) {
    if (env->ExceptionCheck()) return;
    std::array<char, 512U> message{};
    std::snprintf(message.data(), message.size(), "WavPack %s: %.440s", operation, reason);
    env->ThrowNew(env->FindClass("java/io/IOException"), message.data());
}
}
extern "C" JNIEXPORT jlong JNICALL
Java_app_stemwise_playback_engine_WavPackReader_nativeOpen(JNIEnv *env, jobject, jobject fd, jlong offset, jlong length) {
    try {
        jclass type = env->GetObjectClass(fd);
        jfieldID field = env->GetFieldID(type, "descriptor", "I");
        if (!field || env->ExceptionCheck()) return 0;
        auto result = std::make_unique<WavPackReader>(env->GetIntField(fd, field), offset, length);
        return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(result.release()));
    } catch (const std::exception &error) { fail(env, "open", error.what()); return 0; }
      catch (...) { fail(env, "open", "unknown native exception"); return 0; }
}
extern "C" JNIEXPORT jlong JNICALL
Java_app_stemwise_playback_engine_WavPackReader_nativeFrames(JNIEnv *env, jobject, jlong handle) {
    try { return reader(handle)->frames(); }
    catch (const std::exception &error) { fail(env, "frames", error.what()); return 0; }
    catch (...) { fail(env, "frames", "unknown native exception"); return 0; }
}
extern "C" JNIEXPORT jint JNICALL
Java_app_stemwise_playback_engine_WavPackReader_nativeRead(JNIEnv *env, jobject, jlong handle, jlong frame, jobject buffer, jint count) {
    try {
        auto *destination = static_cast<std::int32_t *>(env->GetDirectBufferAddress(buffer));
        if (!destination || count <= 0 || count > 65536 || env->GetDirectBufferCapacity(buffer) < count * 8LL ||
            reinterpret_cast<std::uintptr_t>(destination) % alignof(std::int32_t) != 0)
            throw std::runtime_error("Invalid direct PCM buffer");
        return static_cast<jint>(reader(handle)->read(frame, destination, static_cast<std::uint32_t>(count)));
    } catch (const std::exception &error) { fail(env, "read", error.what()); return 0; }
      catch (...) { fail(env, "read", "unknown native exception"); return 0; }
}
extern "C" JNIEXPORT void JNICALL
Java_app_stemwise_playback_engine_WavPackReader_nativeClose(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<WavPackReader *>(static_cast<std::uintptr_t>(handle));
}
