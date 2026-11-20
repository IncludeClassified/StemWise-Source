# Preserve the existing static JNI ABI through application shrinking.
-keep class app.stemwise.playback.engine.NativeAudioOutput { *; }
-keep class app.stemwise.playback.engine.WavPackReader { *; }
-keep class app.stemwise.playback.engine.NativePlaybackRuntimeJniBackend { *; }
