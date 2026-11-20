#include "NativeAudioOutputJni.h"

// JNI adapter for the one private output owner. Included once after the bridge definitions.
extern "C" JNIEXPORT jlong JNICALL
Java_app_stemwise_playback_engine_NativeAudioOutput_nativeCreate(
    JNIEnv *env,
    jobject,
    jint capacityFrames,
    jint requestedSampleRate,
    jlong initialGeneration) {
    try {
        auto engine = std::make_unique<NativeAudioOutputEngine>(
            static_cast<std::size_t>(capacityFrames),
            requestedSampleRate,
            static_cast<std::uint64_t>(initialGeneration));
        if (!engine->open()) {
            throwJavaException(
                env,
                "java/lang/IllegalStateException",
                "Unable to open the low-latency native audio output stream");
            return 0;
        }
        return handleFromEngine(engine.release());
    } catch (const std::bad_alloc &) {
        throwJavaException(
            env,
            "java/lang/OutOfMemoryError",
            "Unable to allocate the native audio output");
    } catch (const std::exception &error) {
        throwJavaException(env, "java/lang/IllegalArgumentException", error.what());
    }
    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_stemwise_playback_engine_NativeAudioOutput_nativeFlushToGeneration(
    JNIEnv *,
    jobject,
    jlong handle,
    jlong generation) {
    NativeAudioOutputEngine *engine = engineFromHandle(handle);
    return engine != nullptr &&
                   engine->flushToGenerationStatus(static_cast<std::uint64_t>(generation)) ==
                       stemwise::audio::NativeAudioFlushStatus::Accepted
               ? JNI_TRUE
               : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_stemwise_playback_engine_NativeAudioOutput_nativeCurrentGeneration(
    JNIEnv *env,
    jobject,
    jlong handle) {
    NativeAudioOutputEngine *engine = engineFromHandle(handle);
    if (engine == nullptr) {
        throwJavaException(
            env,
            "java/lang/IllegalStateException",
            "Native audio output is closed");
        return 0;
    }
    return static_cast<jlong>(engine->producerGeneration());
}


extern "C" JNIEXPORT void JNICALL
Java_app_stemwise_playback_engine_NativeAudioOutput_nativeFinalizeOutput(
    JNIEnv *env, jobject, jlong handle) {
    if (auto *engine = engineFromHandle(handle)) engine->close();
    else throwJavaException(env, "java/lang/IllegalStateException", "Invalid native output finalization handle");
}

extern "C" JNIEXPORT void JNICALL
Java_app_stemwise_playback_engine_NativeAudioOutput_nativeSetPlaying(
    JNIEnv *,
    jobject,
    jlong handle,
    jboolean playing) {
    NativeAudioOutputEngine *engine = engineFromHandle(handle);
    if (engine != nullptr) static_cast<void>(engine->setPlaying(playing == JNI_TRUE));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_stemwise_playback_engine_NativeAudioOutput_nativeQuiesceIfPaused(
    JNIEnv *,
    jobject,
    jlong handle) {
    NativeAudioOutputEngine *engine = engineFromHandle(handle);
    return engine != nullptr && engine->quiesceIfPaused() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_app_stemwise_playback_engine_NativeAudioOutput_nativeArmManualSelectionGate(
    JNIEnv *,
    jobject,
    jlong handle) {
    NativeAudioOutputEngine *engine = engineFromHandle(handle);
    if (engine != nullptr) engine->armManualSelectionGate();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_stemwise_playback_engine_NativeAudioOutput_nativeAuthorizeManualSelectionGate(
    JNIEnv *,
    jobject,
    jlong handle,
    jlong generation) {
    NativeAudioOutputEngine *engine = engineFromHandle(handle);
    return engine != nullptr && generation > 0 &&
                   engine->authorizeManualSelectionGate(
                       static_cast<std::uint64_t>(generation))
               ? JNI_TRUE
               : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_stemwise_playback_engine_NativeAudioOutput_nativeNeedsRecovery(
    JNIEnv *,
    jobject,
    jlong handle) {
    NativeAudioOutputEngine *engine = engineFromHandle(handle);
    return engine != nullptr && engine->needsRecovery() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_stemwise_playback_engine_NativeAudioOutput_nativeReopen(
    JNIEnv *env,
    jobject,
    jlong handle,
    jlong generation) {
    NativeAudioOutputEngine *engine = engineFromHandle(handle);
    try {
    return engine != nullptr && generation > 0 &&
                   engine->reopenForRouteChange(static_cast<std::uint64_t>(generation))
               ? JNI_TRUE
               : JNI_FALSE;
    } catch (const std::bad_alloc &) {
        throwJavaException(env, "java/lang/OutOfMemoryError", "Unable to reopen native audio stream");
    } catch (...) {
        throwJavaException(env, "java/lang/IllegalStateException", "Unable to reopen native audio stream");
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_stemwise_playback_engine_NativeAudioOutput_nativeRetireForStop(
    JNIEnv *, jobject, jlong handle, jlong generation) {
    auto *engine = engineFromHandle(handle);
    return engine != nullptr && generation > 0 &&
        engine->retireForStop(static_cast<std::uint64_t>(generation)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_app_stemwise_playback_engine_NativeAudioOutput_nativeGetDiagnostics(
    JNIEnv *env,
    jobject,
    jlong handle) {
    NativeAudioOutputEngine *engine = engineFromHandle(handle);
    if (engine == nullptr) {
        throwJavaException(env, "java/lang/IllegalStateException", "Native audio output is closed");
        return nullptr;
    }
    const auto fields = engine->diagnostics();
    jlongArray result = env->NewLongArray(static_cast<jsize>(fields.size()));
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, static_cast<jsize>(fields.size()), fields.data());
    }
    return result;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_app_stemwise_playback_engine_NativeAudioOutput_nativeReadPresentationJournal(
    JNIEnv *env,
    jobject,
    jlong handle,
    jlong afterSequence) {
    NativeAudioOutputEngine *engine = engineFromHandle(handle);
    if (engine == nullptr || afterSequence < 0) {
        throwJavaException(
            env,
            "java/lang/IllegalArgumentException",
            "Invalid native presentation-journal request");
        return nullptr;
    }
    try {
    const auto snapshot = engine->presentationJournal(
        static_cast<std::uint64_t>(afterSequence));
    const std::size_t fieldCount = kPresentationJournalHeaderFieldCount +
        snapshot.entries.size() * kPresentationJournalEntryFieldCount;
    std::vector<jlong> fields(fieldCount, 0);
    stemwise::audio::packet::PresentationJournalHeaderFields<jlong> header{};
    header.SCHEMA = kPresentationJournalSchemaVersion;
    header.CAPACITY = static_cast<jlong>(kPresentationJournalCapacity);
    header.REQUESTED_AFTER = saturatedCounter(snapshot.requestedAfterSequence);
    header.WRITE_SEQUENCE = saturatedCounter(snapshot.writeSequence);
    header.OLDEST_SEQUENCE = saturatedCounter(snapshot.oldestAvailableSequence);
    header.OVERFLOW_COUNT = saturatedCounter(snapshot.overflowCount);
    header.WRITER_COLLISIONS = saturatedCounter(snapshot.writerCollisionCount);
    header.INVALID_PUBLICATIONS = saturatedCounter(snapshot.invalidPublicationCount);
    header.SEQUENCE_EXHAUSTED = snapshot.sequenceExhausted ? 1 : 0;
    header.COHERENT = snapshot.coherent ? 1 : 0;
    header.ENTRY_COUNT = static_cast<jlong>(snapshot.entries.size());
    header.ENTRY_WIDTH = static_cast<jlong>(kPresentationJournalEntryFieldCount);
    const auto encodedHeader = stemwise::audio::packet::packPresentationJournalHeader(header);
    std::copy(encodedHeader.begin(), encodedHeader.end(), fields.begin());
    std::size_t offset = kPresentationJournalHeaderFieldCount;
    for (const auto &entry : snapshot.entries) {
        const auto encodedEntry = stemwise::audio::packet::packPresentationJournalEntry(stemwise::audio::packet::PresentationJournalEntryFields<jlong>{
            .SEQUENCE = saturatedCounter(entry.sequence),
            .GENERATION = saturatedCounter(entry.generation),
            .TOKEN = saturatedCounter(entry.selectionToken),
            .FIRST_FINGERPRINT = saturatedCounter(entry.firstFingerprintSequence),
            .LAST_FINGERPRINT = saturatedCounter(entry.lastFingerprintSequence),
            .CALLBACK_SEQUENCE = saturatedCounter(entry.callbackSequence),
            .PCM_FRAMES = saturatedCounter(entry.pcmFrames),
            .FIRST_LOOP_REVISION = saturatedCounter(entry.firstLoopPolicyRevision),
            .LAST_LOOP_REVISION = saturatedCounter(entry.lastLoopPolicyRevision),
            .FIRST_TIMELINE_FRAME = static_cast<jlong>(entry.firstTimelineFrame),
            .LAST_TIMELINE_FRAME = static_cast<jlong>(entry.lastTimelineFrame),
            .CONTINUITY_VIOLATIONS = saturatedCounter(entry.continuityViolationCount),
            .RENDER_SOURCE_START_BITS = std::bit_cast<std::int64_t>(entry.renderSourceStartFrame),
            .RENDER_SOURCE_END_BITS = std::bit_cast<std::int64_t>(entry.renderSourceEndFrameExclusive),
            .RENDER_FRAME_COUNT = entry.renderFrameCount,
            .FIRST_RENDER_FRAME_OFFSET = entry.firstRenderFrameOffset,
            .MAPPING_FIRST_RENDER_FRAME_OFFSET = entry.mappingFirstRenderFrameOffset,
            .ACTUAL_TEMPO_BITS = std::bit_cast<std::uint32_t>(entry.actualTempo),
            .LAST_NEXT_TIMELINE_FRAME = entry.lastNextTimelineFrame,
            .CALLBACK_PCM_FRAMES = saturatedCounter(entry.callbackPcmFrames),
        });
        std::copy(encodedEntry.begin(), encodedEntry.end(), fields.begin() + static_cast<std::ptrdiff_t>(offset));
        offset += encodedEntry.size();
    }
    jlongArray result = env->NewLongArray(static_cast<jsize>(fields.size()));
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, static_cast<jsize>(fields.size()), fields.data());
    }
    return result;
    } catch (const std::bad_alloc &) {
        throwJavaException(env, "java/lang/OutOfMemoryError", "Unable to allocate presentation journal");
    } catch (...) {
        throwJavaException(env, "java/lang/IllegalStateException", "Unable to read presentation journal");
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_app_stemwise_playback_engine_NativeAudioOutput_nativeClose(
    JNIEnv *,
    jobject,
    jlong handle) {
    std::unique_ptr<NativeAudioOutputEngine> engine(engineFromHandle(handle));
    if (engine != nullptr) engine->close();
}

extern "C" JNIEXPORT void JNICALL
Java_app_stemwise_playback_engine_NativeAudioOutput_nativeTrackControl(
    JNIEnv *env, jobject, jlong handle, jlongArray values) {
    auto *engine = engineFromHandle(handle);
    const auto count = values ? env->GetArrayLength(values) : 0;
    if (!engine || count != stemwise::audio::packet::TRACK_CONTROL_COUNT) {
        throwJavaException(env, "java/lang/IllegalArgumentException", "Invalid AudioTrack control buffer");
        return;
    }
    jlong fields[stemwise::audio::packet::TRACK_CONTROL_COUNT]{};
    engine->trackControl(fields);
    env->SetLongArrayRegion(values, 0, count, fields);
}

extern "C" JNIEXPORT void JNICALL
Java_app_stemwise_playback_engine_NativeAudioOutput_nativeTrackAwaitControl(
    JNIEnv *, jobject, jlong handle, jlong after) {
    if (auto *engine = engineFromHandle(handle)) engine->trackAwaitControl(static_cast<std::uint64_t>(after));
}
extern "C" JNIEXPORT void JNICALL
Java_app_stemwise_playback_engine_NativeAudioOutput_nativeTrackWake(
    JNIEnv *, jobject, jlong handle) {
    if (auto *engine = engineFromHandle(handle)) engine->trackWake();
}

extern "C" JNIEXPORT void JNICALL
Java_app_stemwise_playback_engine_NativeAudioOutput_nativeSetSessionSuspended(
    JNIEnv *, jobject, jlong handle, jboolean suspended) {
    if (auto *engine = engineFromHandle(handle)) engine->setSessionSuspended(suspended == JNI_TRUE);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_stemwise_playback_engine_NativeAudioOutput_nativeTrackBeginSourceTransition(
    JNIEnv *, jobject, jlong handle, jlong serial, jlong generation) {
    auto *engine = engineFromHandle(handle);
    return engine && serial > 0 && generation > 0 && engine->trackBeginSourceTransition(
        static_cast<std::uint64_t>(serial), static_cast<std::uint64_t>(generation)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_stemwise_playback_engine_NativeAudioOutput_nativePrepareSourceTransition(
    JNIEnv *, jobject, jlong handle, jlong outgoingGeneration, jlong token, jlong targetGeneration,
    jlong pausedFallbackFrame) {
    auto *engine = engineFromHandle(handle);
    if (!engine || outgoingGeneration <= 0 || token <= 0 || targetGeneration <= outgoingGeneration ||
        pausedFallbackFrame < -1) return -1;
    return engine->prepareSourceTransition(static_cast<std::uint64_t>(outgoingGeneration),
        static_cast<std::uint64_t>(token), static_cast<std::uint64_t>(targetGeneration), pausedFallbackFrame);
}

extern "C" JNIEXPORT void JNICALL
Java_app_stemwise_playback_engine_NativeAudioOutput_nativeCancelSourceTransition(
    JNIEnv *, jobject, jlong handle, jlong generation) {
    if (auto *engine = engineFromHandle(handle); engine && generation > 0)
        engine->cancelSourceTransition(static_cast<std::uint64_t>(generation));
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_stemwise_playback_engine_NativeAudioOutput_nativeTrackPrepareTransportSilence(
    JNIEnv *, jobject, jlong handle, jint kind) {
    auto *engine = engineFromHandle(handle);
    if (!engine || (kind != 0 && kind != 1)) return 0;
    return static_cast<jlong>(engine->preparePushTransportSilence(
        static_cast<stemwise::audio::NativeAudioOutputCallbackCore::PushTransportSilenceKind>(kind)));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_stemwise_playback_engine_NativeAudioOutput_nativeTrackAcceptTransportSilence(
    JNIEnv *, jobject, jlong handle, jlong sequence, jint frames) {
    auto *engine = engineFromHandle(handle);
    return engine && sequence > 0 && frames > 0 && engine->acceptPushTransportSilence(
        static_cast<std::uint64_t>(sequence), static_cast<std::size_t>(frames)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_stemwise_playback_engine_NativeAudioOutput_nativeTrackPrepare(
    JNIEnv *env, jobject, jlong handle, jobject pcm, jlongArray metadata) {
    auto *engine = engineFromHandle(handle);
    auto *destination = pcm ? env->GetDirectBufferAddress(pcm) : nullptr;
    if (!engine || !destination || env->GetDirectBufferCapacity(pcm) < static_cast<jlong>(stemwise::audio::packet::TRACK_BLOCK_FRAMES * stemwise::audio::packet::TRACK_CHANNELS * sizeof(float)) ||
        !metadata || env->GetArrayLength(metadata) != stemwise::audio::packet::TRACK_METADATA_COUNT) {
        throwJavaException(env, "java/lang/IllegalArgumentException", "Invalid AudioTrack PCM block");
        return 0;
    }
    const auto block = engine->preparePushBlock(stemwise::audio::packet::TRACK_BLOCK_FRAMES);
    if (block.frames > 0U) std::memcpy(destination, block.samples, block.frames * 2U * sizeof(float));
    const jlong fields[]{static_cast<jlong>(block.sequence), static_cast<jlong>(block.terminal)};
    env->SetLongArrayRegion(metadata, 0, stemwise::audio::packet::TRACK_METADATA_COUNT, fields);
    return static_cast<jint>(block.frames);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_stemwise_playback_engine_NativeAudioOutput_nativeTrackPrepareWrite(
    JNIEnv *env, jobject, jlong handle, jobject pcm, jint frames, jboolean transport) {
    auto *engine = engineFromHandle(handle);
    auto *samples = pcm ? static_cast<float *>(env->GetDirectBufferAddress(pcm)) : nullptr;
    return engine && frames > 0 && samples && env->GetDirectBufferCapacity(pcm) >= static_cast<jlong>(frames) * 2 * static_cast<jlong>(sizeof(float)) &&
        engine->preparePushWrite(samples, static_cast<std::size_t>(frames), transport == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_stemwise_playback_engine_NativeAudioOutput_nativeTrackAccept(
    JNIEnv *, jobject, jlong handle, jlong sequence, jint frames) {
    auto *engine = engineFromHandle(handle);
    return engine && sequence > 0 && frames >= 0 && engine->acceptPushPrefix(
        static_cast<std::uint64_t>(sequence), static_cast<std::size_t>(frames)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_app_stemwise_playback_engine_NativeAudioOutput_nativeTrackConsumerActive(
    JNIEnv *env, jobject, jlong handle, jboolean active) {
    auto *engine = engineFromHandle(handle);
    if (!engine || !engine->trackConsumerActive(active == JNI_TRUE))
        throwJavaException(env, "java/lang/IllegalStateException", "AudioTrack consumer did not acknowledge execution state");
}

extern "C" JNIEXPORT void JNICALL
Java_app_stemwise_playback_engine_NativeAudioOutput_nativeTrackDiscard(
    JNIEnv *env, jobject, jlong handle) {
    auto *engine = engineFromHandle(handle);
    if (!engine || !engine->discardPushPending(true))
        throwJavaException(env, "java/lang/IllegalStateException", "AudioTrack discard requires a quiescent consumer");
}

extern "C" JNIEXPORT void JNICALL
Java_app_stemwise_playback_engine_NativeAudioOutput_nativeTrackResetComplete(
    JNIEnv *, jobject, jlong handle, jlong serial) {
    if (auto *engine = engineFromHandle(handle); engine && serial > 0)
        engine->trackResetComplete(static_cast<std::uint64_t>(serial));
}

extern "C" JNIEXPORT void JNICALL
Java_app_stemwise_playback_engine_NativeAudioOutput_nativeTrackConfigure(
    JNIEnv *env, jobject, jlong handle, jlong instance, jintArray configuration) {
    auto *engine = engineFromHandle(handle);
    if (!engine || instance <= 0 || !configuration || env->GetArrayLength(configuration) != stemwise::audio::packet::TRACK_CONFIGURATION_COUNT) {
        throwJavaException(env, "java/lang/IllegalArgumentException", "Invalid AudioTrack configuration");
        return;
    }
    jint actual[stemwise::audio::packet::TRACK_CONFIGURATION_COUNT]{};
    env->GetIntArrayRegion(configuration, 0, stemwise::audio::packet::TRACK_CONFIGURATION_COUNT, actual);
    if (!env->ExceptionCheck()) engine->trackConfigure(static_cast<std::uint64_t>(instance), actual);
}

extern "C" JNIEXPORT void JNICALL
Java_app_stemwise_playback_engine_NativeAudioOutput_nativeTrackObserve(
    JNIEnv *env, jobject, jlong handle, jlong instance, jintArray configuration, jlongArray position,
    jboolean complete) {
    auto *engine = engineFromHandle(handle);
    if (!engine || instance <= 0 || !configuration || !position ||
        env->GetArrayLength(configuration) != stemwise::audio::packet::TRACK_ACTUAL_COUNT || env->GetArrayLength(position) != stemwise::audio::packet::TRACK_POSITION_COUNT) {
        throwJavaException(env, "java/lang/IllegalArgumentException", "Invalid AudioTrack observation");
        return;
    }
    jint actual[stemwise::audio::packet::TRACK_ACTUAL_COUNT]{};
    jlong frames[stemwise::audio::packet::TRACK_POSITION_COUNT]{};
    env->GetIntArrayRegion(configuration, 0, stemwise::audio::packet::TRACK_ACTUAL_COUNT, actual);
    env->GetLongArrayRegion(position, 0, stemwise::audio::packet::TRACK_POSITION_COUNT, frames);
    if (!env->ExceptionCheck()) engine->trackObserve(static_cast<std::uint64_t>(instance), actual, frames, complete == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_app_stemwise_playback_engine_NativeAudioOutput_nativeTrackFailed(
    JNIEnv *, jobject, jlong handle) {
    if (auto *engine = engineFromHandle(handle)) engine->trackFailed();
}
