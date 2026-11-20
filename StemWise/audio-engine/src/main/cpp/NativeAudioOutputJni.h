#pragma once
#include <jni.h>
extern "C" JNIEXPORT jlong JNICALL
Java_app_stemwise_playback_engine_NativeAudioOutput_nativeCreate(
    JNIEnv *, jobject, jint, jint, jlong);
