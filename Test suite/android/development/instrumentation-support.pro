# The runner shares this app dependency; retain it when testing the shrunken APK.
-keep class androidx.tracing.Trace { *; }
# AndroidJUnitRunner is outside the application shrink graph and calls Kotlin facades.
-keep class kotlin.** { *; }
# The external driver needs retained members and reflective names, but core classes still optimize/rename.
-keep,allowoptimization,allowobfuscation class app.stemwise.** { *; }
-keepclassmembernames class app.stemwise.** { *; }
-keep class androidx.** { public *; }
-keep class kotlinx.coroutines.** { public *; }
# The external artwork regression also calls these test-only disk cache APIs.
-keepclassmembers class coil.disk.DiskCache$Builder {
    public coil.disk.DiskCache$Builder maxSizeBytes(long);
}
-keepclassmembers interface coil.disk.DiskCache {
    public void clear();
}
-keepclassmembers interface coil.disk.DiskCache$Editor {
    public void commit();
}
-keepclassmembers interface coil.disk.DiskCache$Snapshot {
    public void close();
}
-keepclassmembers interface okio.BufferedSource {
    public java.lang.String readUtf8();
}
# Driver fixtures construct String response bodies and delegate the ImageLoader contract.
-keepclassmembers class okhttp3.ResponseBody$Companion {
    public static okhttp3.ResponseBody create$default(okhttp3.ResponseBody$Companion, java.lang.String, okhttp3.MediaType, int, java.lang.Object);
}
-keepclassmembers interface coil.ImageLoader {
    public coil.request.Disposable enqueue(coil.request.ImageRequest);
    public coil.disk.DiskCache getDiskCache();
    public coil.ImageLoader$Builder newBuilder();
    public void shutdown();
}
