# Room generates and references these implementations by name.
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }

# yt-dlp Android loads its Python/native bridge through reflection and JNI.
-keep class com.yausername.youtubedl_android.** { *; }

# Fixed owned-tool JNI symbols are reached through their class and method names.
-keep class app.stemwise.integration.OwnedToolNative { *; }

# The retained wrapper uses commons-compress ZIP classes only. These optional archive codecs
# are absent from the locked runtime and are not used by the bundled ZIP payloads.
-dontwarn com.github.luben.zstd.ZstdInputStream
-dontwarn org.tukaani.xz.MemoryLimitException
-dontwarn org.tukaani.xz.SingleXZInputStream
-dontwarn org.tukaani.xz.XZInputStream
