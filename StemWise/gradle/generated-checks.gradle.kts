// A changed source or generated projection must rerun its verifier; unchanged compiles reuse Gradle's input snapshot.
val checkPython = providers.gradleProperty("stemwisePcPython")
    .orElse(providers.environmentVariable("STEMWISE_PC_PYTHON"))
    .orElse(if (System.getProperty("os.name").startsWith("Windows", true)) "python" else "python3")
    .map { executable -> listOf(executable) +
        (if (File(executable).nameWithoutExtension.equals("py", true)) listOf("-3") else emptyList()) + "-B" }
val checkInputs = mapOf(
    "checkGeneratedContracts" to listOf("tools/generate-contract-limits.py",
        "contracts/audio/playback-capacity.json", "contracts/enrichment/song-enrichment-v2.schema.json",
        "contracts/enrichment/installed-enrichment-v2.source.json", "contracts/enrichment/installed-enrichment-v2.schema.json",
        "audio-engine/src/main/java/app/stemwise/EnrichmentLimits.kt", "audio-engine/src/main/java/app/stemwise/PlaybackLimits.kt",
        "audio-engine/src/main/cpp/native_engine/NativePlaybackLimits.h",
        "app/src/main/java/app/stemwise/enrichment/EnrichmentConstants.kt",
        "../PC side/stemwise-playlist-builder/src/enrichment_constants.py"),
    "checkNativePacketSchema" to listOf("tools/native_packet_schema.py", "contracts/audio/playback-capacity.json",
        "audio-engine/src/main/cpp/NativePacketEnumChecks.h", "audio-engine/src/main/cpp/NativePacketSchema.h",
        "audio-engine/src/main/cpp/StemRoleLimits.h", "audio-engine/src/main/java/app/stemwise/playback/engine/NativePacketSchema.kt",
        "audio-engine/src/main/java/app/stemwise/playback/engine/NativeAudioDiagnostics.kt",
        "audio-engine/src/main/java/app/stemwise/playback/engine/NativePlaybackRuntimeCodecs.kt"),
    "checkLyricsUnicode" to listOf("tools/generate-lyrics-unicode.py", "contracts/enrichment/lyrics-unicode-18.json",
        "contracts/search/case-fold-17.json", "app/src/main/java/app/stemwise/enrichment/LyricsUnicode.kt",
        "app/src/main/java/app/stemwise/ui/SearchCaseFold.kt", "../PC side/stemwise-playlist-builder/src/lyrics_unicode.py")
)
for ((name, paths) in checkInputs) {
    tasks.register<Exec>(name) {
        workingDir(rootProject.projectDir)
        inputs.files(paths.map(rootProject::file))
        inputs.file(rootProject.file("gradle/generated-checks.gradle.kts"))
        inputs.property("pythonCommand", checkPython)
        val verified = layout.buildDirectory.file("checks/$name.passed")
        outputs.file(verified)
        doFirst { commandLine(checkPython.get() + listOf(paths.first(), "--check")) }
        doLast {
            verified.get().asFile.apply { parentFile.mkdirs(); writeText("checked\n", Charsets.UTF_8) }
        }
    }
}
