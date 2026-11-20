import java.util.zip.ZipFile

// Explicit values supplied by app configuration; this script owns qualification tasks only.
val developmentOutputDirectory: org.gradle.api.file.DirectoryProperty by extra
val developmentOutputFileName: String by extra
val stagedDevelopmentApk: org.gradle.api.provider.Provider<org.gradle.api.file.RegularFile> by extra
val configuredApplicationId: String by extra
val releaseSigningCertificateSha256: String by extra
val disposableSigningReady: Boolean by extra
val disposableSigningCertificateSha256: String by extra
val developmentTargetAbi: String by extra
val qualificationSdkDirectory: File by extra
val qualificationBuildToolsVersion: String by extra
val qualificationNdkVersion: String by extra
val qualificationVersionCode: Int by extra
val qualificationVersionName: String by extra

val signingConfiguration = layout.buildDirectory.file("generated/qualification/signing-configuration.json")
tasks.register("writeQualificationSigningConfiguration") {
    group = "verification"
    inputs.property("release", releaseSigningCertificateSha256)
    inputs.property("disposable", disposableSigningCertificateSha256)
    outputs.file(signingConfiguration)
    doLast {
        val destination = signingConfiguration.get().asFile
        destination.parentFile.mkdirs()
        destination.writeText(
            """{"formatVersion":1,"releaseCertificateSha256":"$releaseSigningCertificateSha256","disposableCertificateSha256":"$disposableSigningCertificateSha256"}""",
            Charsets.UTF_8
        )
    }
}

val releaseOutputDirectory = layout.buildDirectory.dir("outputs/apk/release")
val verifyReleaseSigningIdentity by tasks.registering {
    group = "verification"
    description = "Verifies consumer Release isolation and the configured release signer."

    doLast {
        val apks = releaseOutputDirectory.get().asFile
            .listFiles { file -> file.isFile && file.extension.equals("apk", true) }
            .orEmpty()
            .toList()
        check(apks.size == 1) {
            "Expected exactly one arm64 release APK, found ${apks.size}."
        }
        ZipFile(apks.single()).use { archive ->
            check(archive.entries().asSequence().none {
                it.name.startsWith("assets/demo_library/") || it.name.startsWith("assets/qa/")
            }) { "Consumer Release contains development fixture assets." }
            val forbiddenMarkers = listOf(
                "StemWise.audio.track.", "foregroundStemGraphBuildCount",
                "currentStemPrewarmBuildCount", "lastStemActivationTotalMs"
            )
            val dexEntries = archive.entries().asSequence().filter {
                it.name.matches(Regex("classes(?:[0-9]+)?\\.dex"))
            }.toList()
            check(dexEntries.isNotEmpty()) { "Consumer Release contains no DEX to inspect." }
            dexEntries.forEach { entry ->
                val dex = archive.getInputStream(entry).use { it.readBytes() }
                    .toString(Charsets.ISO_8859_1)
                check(forbiddenMarkers.none(dex::contains)) {
                    "Consumer Release contains development tracing or qualification accounting."
                }
            }
        }
        val mapping = layout.buildDirectory.file("outputs/mapping/release/mapping.txt").get().asFile
        check(mapping.isFile) { "Consumer Release has no shrinker mapping." }
        val developmentNamespaces = listOf(
            "app.stemwise.demo.", "app.stemwise.debug.", "app.stemwise.qa.",
            "androidx.compose.ui.tooling.", "androidx.test.", "org.junit.",
            "androidx.work.impl.diagnostics.", "androidx.profileinstaller.ProfileInstallReceiver"
        )
        check(mapping.useLines { lines ->
            lines.none { line -> developmentNamespaces.any(line::startsWith) }
        }) { "Consumer Release contains development-only classes." }
        val controller = "app.stemwise.playback.engine.NativePlaybackEngineController"
        val transformedController = Regex("(?m)^${Regex.escape(controller)} -> ([^ ]+):$")
            .find(mapping.readText(Charsets.UTF_8))?.groupValues?.get(1)
        check(transformedController != null && transformedController != controller) {
            "Consumer Release did not apply controller obfuscation."
        }
        val windowsHost = System.getProperty("os.name").startsWith("Windows", true)
        val aapt2 = qualificationSdkDirectory.resolve(
            "build-tools/${qualificationBuildToolsVersion}/${if (windowsHost) "aapt2.exe" else "aapt2"}"
        )
        check(aapt2.isFile) { "Android build tool was not found at $aapt2" }
        val manifestTree = providers.exec {
            commandLine(aapt2.absolutePath, "dump", "xmltree", apks.single().absolutePath,
                "--file", "AndroidManifest.xml")
        }.standardOutput.asText.get()
        check(!Regex(":debuggable\\([^)]*\\)=true").containsMatchIn(manifestTree) &&
            !Regex("(?m)^\\s*E: (?:profileable|instrumentation) ").containsMatchIn(manifestTree)) {
            "Consumer Release exposes debugging, profiling or instrumentation."
        }
        check(listOf(
            "app.stemwise.demo.", "app.stemwise.debug.", "app.stemwise.qa.",
            "androidx.compose.ui.tooling.", "androidx.test.",
            "androidx.work.impl.diagnostics.DiagnosticsReceiver",
            "androidx.profileinstaller.ProfileInstallReceiver"
        ).none(manifestTree::contains)) {
            "Consumer Release declares a development component."
        }
        val apksignerName = if (windowsHost) "apksigner.bat" else "apksigner"
        val apksigner = qualificationSdkDirectory.resolve(
            "build-tools/${qualificationBuildToolsVersion}/$apksignerName"
        )
        check(apksigner.isFile) { "Android signing tool was not found at $apksigner" }
        val signerOutput = providers.exec {
            commandLine(
                if (windowsHost) {
                    listOf(
                        "cmd.exe", "/d", "/c", apksigner.absolutePath,
                        "verify", "--print-certs", apks.single().absolutePath
                    )
                } else {
                    listOf(
                        apksigner.absolutePath, "verify", "--print-certs",
                        apks.single().absolutePath
                    )
                }
            )
        }.standardOutput.asText.get()
        val signerDigest = checkNotNull(Regex(
            """(?im)^Signer #1 certificate SHA-256 digest:\s*([0-9a-f]{64})\s*$"""
        ).find(signerOutput)) { "apksigner did not report the release APK certificate" }
        val expectedSigner = releaseSigningCertificateSha256
        check(signerDigest.groupValues[1].equals(expectedSigner, true)) {
            "Release APK signing certificate does not match the configured release signer."
        }
    }
}

tasks.matching { it.name == "packageRelease" }.configureEach {
    finalizedBy(verifyReleaseSigningIdentity)
}

val stageDevelopmentArtifact by tasks.registering {
    group = "build"
    description = "Copies the development APK into its role-specific staged output directory."
    dependsOn("assembleDevelopment")
    inputs.file(developmentOutputDirectory.map { it.file(developmentOutputFileName) })
    outputs.file(stagedDevelopmentApk)

    doLast {
        val source = developmentOutputDirectory.get().file(developmentOutputFileName).asFile
        check(source.isFile) { "Development APK was not produced at $source" }
        val destination = stagedDevelopmentApk.get().asFile
        destination.parentFile.mkdirs()
        source.copyTo(destination, overwrite = true)
    }
}

val verifyDevelopmentArtifactIdentity by tasks.registering {
    group = "verification"
    description = "Verifies the role-specific development APK identity before it is handed off."
    dependsOn(stageDevelopmentArtifact)

    doLast {
        val outputDirectory = developmentOutputDirectory.get().asFile
        val metadata = outputDirectory.resolve("output-metadata.json")
        check(metadata.isFile) { "Development APK metadata was not produced at $metadata" }
        val metadataText = metadata.readText()
        val expectedOutput = developmentOutputFileName
        check(Regex("\"applicationId\"\\s*:\\s*\"${Regex.escape(configuredApplicationId)}\"")
            .containsMatchIn(metadataText)) {
            "Development APK applicationId is not $configuredApplicationId"
        }
        check(Regex("\"outputFile\"\\s*:\\s*\"${Regex.escape(expectedOutput)}\"")
            .containsMatchIn(metadataText) && stagedDevelopmentApk.get().asFile.isFile) {
            "Role-specific development APK was not produced as $expectedOutput"
        }

        val apk = stagedDevelopmentApk.get().asFile
        val aapt2Name = if (System.getProperty("os.name").startsWith("Windows", true)) {
            "aapt2.exe"
        } else {
            "aapt2"
        }
        val aapt2 = qualificationSdkDirectory.resolve(
            "build-tools/${qualificationBuildToolsVersion}/$aapt2Name"
        )
        check(aapt2.isFile) { "Android build tool was not found at $aapt2" }
        fun aapt2(vararg arguments: String): String = providers.exec {
            commandLine(listOf(aapt2.absolutePath) + arguments)
        }.standardOutput.asText.get()

        val badging = aapt2("dump", "badging", apk.absolutePath)
        val packageIdentity = checkNotNull(Regex(
            "(?m)^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'"
        ).find(badging)) { "aapt2 did not report the staged APK package identity" }
        check(packageIdentity.groupValues[1] == configuredApplicationId) {
            "Staged development APK package is not $configuredApplicationId"
        }
        check(packageIdentity.groupValues[2] == qualificationVersionCode.toString()) {
            "Staged development APK versionCode is not ${qualificationVersionCode}"
        }
        val expectedVersionName = "${qualificationVersionName}-development"
        check(packageIdentity.groupValues[3] == expectedVersionName) {
            "Staged development APK versionName is not $expectedVersionName"
        }
        val launchableActivity = checkNotNull(Regex(
            "(?m)^launchable-activity: name='([^']+)'"
        ).find(badging)) { "Staged development APK has no launchable activity" }
        check(launchableActivity.groupValues[1] == "app.stemwise.MainActivity") {
            "Staged development APK launcher is not app.stemwise.MainActivity"
        }

        val windowsHost = System.getProperty("os.name").startsWith("Windows", true)
        val apksignerName = if (windowsHost) "apksigner.bat" else "apksigner"
        val apksigner = qualificationSdkDirectory.resolve(
            "build-tools/${qualificationBuildToolsVersion}/$apksignerName"
        )
        check(apksigner.isFile) { "Android signing tool was not found at $apksigner" }
        val signerOutput = providers.exec {
            commandLine(
                if (windowsHost) {
                    listOf(
                        "cmd.exe", "/d", "/c", apksigner.absolutePath,
                        "verify", "--print-certs", apk.absolutePath
                    )
                } else {
                    listOf(
                        apksigner.absolutePath, "verify", "--print-certs", apk.absolutePath
                    )
                }
            )
        }.standardOutput.asText.get()
        val signerDigest = checkNotNull(Regex(
            """(?im)^Signer #1 certificate SHA-256 digest:\s*([0-9a-f]{64})\s*$"""
        ).find(signerOutput)) { "apksigner did not report the staged APK certificate" }
        check(disposableSigningReady) { "Qualification signing configuration is unavailable." }
        val expectedCertificateSha256 = disposableSigningCertificateSha256
        check(signerDigest.groupValues[1].equals(expectedCertificateSha256, true)) {
            "Staged development APK signing certificate does not match role development"
        }

        ZipFile(apk).use { archive ->
            val nativeEntries = buildList {
                val entries = archive.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory && entry.name.startsWith("lib/")) {
                        add(entry)
                    }
                }
            }
            val nativeLibraries = nativeEntries.map { it.name }
            check("lib/$developmentTargetAbi/libstemwise_audio_output.so" in nativeLibraries) {
                "Staged development APK is missing the StemWise native playback library"
            }
            check(nativeLibraries.all { it.startsWith("lib/$developmentTargetAbi/") }) {
                "Staged development APK contains a native library outside $developmentTargetAbi"
            }
            val nativeEntriesByName = nativeEntries.associateBy { it.name.substringAfterLast('/') }

            val readelfName = if (windowsHost) "llvm-readelf.exe" else "llvm-readelf"
            val readelf = qualificationSdkDirectory
                .resolve("ndk/${qualificationNdkVersion}/toolchains/llvm/prebuilt")
                .listFiles()
                .orEmpty()
                .map { it.resolve("bin/$readelfName") }
                .firstOrNull(File::isFile)
            checkNotNull(readelf) { "The configured NDK has no $readelfName" }
            val extracted = layout.buildDirectory
                .dir("tmp/verifyDevelopmentArtifactIdentity/jni")
                .get().asFile
            check(!extracted.exists() || extracted.deleteRecursively()) {
                "Could not reset the development JNI verification directory"
            }
            check(extracted.mkdirs()) { "Could not create the development JNI verification directory" }
            nativeEntriesByName.forEach { (name, entry) ->
                archive.getInputStream(entry).use { input ->
                    extracted.resolve(name).outputStream().use(input::copyTo)
                }
            }
            val documentedNonElfPayloads = setOf("libffmpeg.zip.so", "libpython.zip.so")
            nativeEntriesByName.keys.filterNot { it in documentedNonElfPayloads }.forEach { name ->
                val programHeaders = providers.exec {
                    commandLine(readelf.absolutePath, "-lW", extracted.resolve(name).absolutePath)
                }.standardOutput.asText.get()
                val loadAlignments = programHeaders.lineSequence()
                    .map(String::trim)
                    .filter { it.startsWith("LOAD ") }
                    .map { line ->
                        checkNotNull(Regex("""(0x[0-9a-fA-F]+)\s*$""").find(line)) {
                            "$name has an unreadable ELF LOAD alignment"
                        }.groupValues[1].removePrefix("0x").toLong(16)
                    }
                    .toList()
                check(loadAlignments.isNotEmpty()) { "$name has no ELF LOAD segments" }
                check(loadAlignments.all { it >= 0x4000L }) {
                    "$name has an ELF LOAD segment aligned below 16 KiB"
                }
            }
            val speexSymbols = providers.exec {
                commandLine(
                    readelf.absolutePath,
                    "-Ws",
                    extracted.resolve("libstemwise_audio_output.so").absolutePath
                )
            }.standardOutput.asText.get().lineSequence()
                .map { it.trim().split(Regex("\\s+"), limit = 8) }
                .filter { it.size == 8 && it[4] == "GLOBAL" }
                .map { it[7] }
                .toSet()
            check(speexSymbols.none { it.startsWith("speex_resampler_") }) {
                "StemWise native playback exposes unprefixed SpeexDSP resampler symbols"
            }
            check(speexSymbols.any { it.startsWith("stemwise_speex_resampler_") }) {
                "StemWise native playback has no project-prefixed SpeexDSP resampler symbols"
            }
            check(speexSymbols.any { it.startsWith("ATrace_beginSection") }) {
                "Native cache tracing does not match the requested profiling build"
            }
            val systemLibraries = setOf(
                "libc.so", "libm.so", "libdl.so", "liblog.so", "libOpenSLES.so", "libandroid.so", "libaaudio.so"
            )
            val pending = ArrayDeque(listOf("libstemwise_audio_output.so"))
            val inspected = mutableSetOf<String>()
            while (pending.isNotEmpty()) {
                val library = pending.removeFirst()
                if (!inspected.add(library)) continue
                val dynamic = providers.exec {
                    commandLine(readelf.absolutePath, "-d", extracted.resolve(library).absolutePath)
                }.standardOutput.asText.get()
                Regex("Shared library: \\[([^]]+)]").findAll(dynamic).forEach { match ->
                    val dependency = match.groupValues[1]
                    if (dependency !in systemLibraries) {
                        check(dependency in nativeEntriesByName) {
                            "$library needs missing same-ABI dependency $dependency"
                        }
                        pending.addLast(dependency)
                    }
                }
            }
        }

        val manifestTree = aapt2(
            "dump", "xmltree", apk.absolutePath, "--file", "AndroidManifest.xml"
        )
        check(Regex(":debuggable\\([^)]*\\)=true").containsMatchIn(manifestTree)) {
            "Development APK must support debugging"
        }
        fun componentBlock(element: String, className: String): String {
            val lines = manifestTree.lines()
            val nameLine = lines.indexOfFirst { "\"$className\"" in it }
            check(nameLine >= 0) { "$className is missing from the staged development APK" }
            val elementLine = (nameLine downTo 0).firstOrNull {
                lines[it].trimStart().startsWith("E: $element ")
            }
            check(elementLine != null) { "$className is not declared as $element" }
            val indentation = lines[elementLine].indexOfFirst { !it.isWhitespace() }
            val end = (elementLine + 1 until lines.size).firstOrNull { index ->
                val line = lines[index]
                line.isNotBlank() &&
                    line.indexOfFirst { !it.isWhitespace() } <= indentation &&
                    line.trimStart().startsWith("E: ")
            } ?: lines.size
            return lines.subList(elementLine, end).joinToString("\n")
        }
        fun hasBooleanAttribute(block: String, name: String, expected: Boolean): Boolean =
            Regex(":${Regex.escape(name)}\\([^)]*\\)=$expected(?:\\s|$)")
                .containsMatchIn(block)
        fun hasStringAttribute(block: String, name: String, expected: String): Boolean =
            Regex(":${Regex.escape(name)}\\([^)]*\\)=\"${Regex.escape(expected)}\"")
                .containsMatchIn(block)
        fun componentBlocks(element: String): List<String> {
            val lines = manifestTree.lines()
            return lines.indices.filter {
                lines[it].trimStart().startsWith("E: $element ")
            }.map { elementLine ->
                val indentation = lines[elementLine].indexOfFirst { !it.isWhitespace() }
                val end = (elementLine + 1 until lines.size).firstOrNull { index ->
                    val line = lines[index]
                    line.isNotBlank() &&
                        line.indexOfFirst { !it.isWhitespace() } <= indentation &&
                        line.trimStart().startsWith("E: ")
                } ?: lines.size
                lines.subList(elementLine, end).joinToString("\n")
            }
        }

        val applicationBlock = componentBlock(
            "application", "app.stemwise.demo.DevelopmentStemwiseApplication"
        )
        check(hasBooleanAttribute(applicationBlock, "allowBackup", false)) {
            "Staged development APK allows application backup"
        }
        check(hasBooleanAttribute(applicationBlock, "usesCleartextTraffic", false)) {
            "Staged development APK allows cleartext network traffic"
        }

        val labsExported = true
        listOf(
            "app.stemwise.debug.ProcessRetirementProbeActivity",
            "app.stemwise.debug.UiStressLabActivity"
        ).forEach { activity ->
            val block = componentBlock("activity", activity)
            check(hasBooleanAttribute(block, "exported", labsExported)) {
                "$activity exported state does not match development role development"
            }
        }
        check(hasStringAttribute(
            componentBlock("activity", "app.stemwise.debug.UiStressLabActivity"),
            "permission", "android.permission.DUMP"
        )) { "UiStressLabActivity must remain protected by the shell-only DUMP permission" }
        listOf(
            "androidx.compose.ui.tooling.PreviewActivity",
            "androidx.activity.ComponentActivity"
        ).forEach { activity ->
            val block = componentBlock("activity", activity)
            check(hasBooleanAttribute(block, "enabled", labsExported)) {
                "$activity enabled state does not match development role development"
            }
            check(hasBooleanAttribute(block, "exported", labsExported)) {
                "$activity exported state does not match development role development"
            }
        }
        val qaProvider = componentBlock(
            "provider", "app.stemwise.qa.GoldenQaDocumentsProvider"
        )
        check(hasBooleanAttribute(qaProvider, "enabled", labsExported)) {
            "GoldenQaDocumentsProvider enabled state does not match development role development"
        }
        check(hasBooleanAttribute(qaProvider, "exported", true) &&
            hasBooleanAttribute(qaProvider, "grantUriPermissions", true) &&
            hasStringAttribute(qaProvider, "permission", "android.permission.MANAGE_DOCUMENTS")) {
            "GoldenQaDocumentsProvider trust boundary is not protected as expected"
        }
        val demoProvider = componentBlock(
            "provider", "app.stemwise.demo.UiDemoLibraryDocumentsProvider"
        )
        check(hasBooleanAttribute(demoProvider, "exported", true) &&
            hasBooleanAttribute(demoProvider, "grantUriPermissions", true) &&
            hasStringAttribute(
                demoProvider,
                "permission",
                "android.permission.MANAGE_DOCUMENTS"
            )) {
            "UiDemoLibraryDocumentsProvider trust boundary is not protected as expected"
        }

        mapOf(
            "androidx.work.impl.background.systemjob.SystemJobService" to
                "android.permission.BIND_JOB_SERVICE",
            "androidx.work.impl.diagnostics.DiagnosticsReceiver" to "android.permission.DUMP",
            "androidx.profileinstaller.ProfileInstallReceiver" to "android.permission.DUMP",
            "app.stemwise.debug.QualificationLibraryGrantReceiver" to
                "android.permission.DUMP"
        ).forEach { (component, permission) ->
            val block = componentBlock(
                if (component.endsWith("Service")) "service" else "receiver",
                component
            )
            check(hasStringAttribute(block, "permission", permission)) {
                "$component is missing required permission $permission"
            }
        }

        val expectedExportedComponents = buildSet {
            add("app.stemwise.MainActivity")
            add("app.stemwise.playback.PlaybackService")
            add("app.stemwise.demo.UiDemoLibraryDocumentsProvider")
            add("app.stemwise.qa.GoldenQaDocumentsProvider")
            add("androidx.work.impl.background.systemjob.SystemJobService")
            add("androidx.work.impl.diagnostics.DiagnosticsReceiver")
            add("androidx.profileinstaller.ProfileInstallReceiver")
            add("app.stemwise.debug.QualificationLibraryGrantReceiver")
            if (labsExported) {
                add("app.stemwise.debug.ProcessRetirementProbeActivity")
                add("app.stemwise.debug.UiStressLabActivity")
                add("androidx.compose.ui.tooling.PreviewActivity")
                add("androidx.activity.ComponentActivity")
            }
        }
        val actualExportedComponents = listOf(
            "activity", "activity-alias", "service", "receiver", "provider"
        ).flatMap(::componentBlocks)
            .filter { hasBooleanAttribute(it, "exported", true) }
            .map { block ->
                checkNotNull(Regex("android:name\\([^)]*\\)=\"([^\"]+)\"")
                    .find(block)) {
                    "Exported component has no readable android:name"
                }.groupValues[1]
            }
            .toSet()
        check(actualExportedComponents == expectedExportedComponents) {
            "Exported component allowlist changed: expected=" +
                expectedExportedComponents.sorted() + " actual=" +
                actualExportedComponents.sorted()
        }
    }
}
