import groovy.json.JsonSlurper
import com.android.build.api.artifact.SingleArtifact
import java.util.Properties
import org.gradle.api.Action
import org.gradle.api.execution.TaskExecutionGraph

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val presentationReports = providers.gradleProperty("stemwisePresentationReports")
    .map(String::toBooleanStrict).getOrElse(false)
val minifiedDevelopment = providers.gradleProperty("stemwiseMinifiedDevelopment")
    .map(String::toBooleanStrict).getOrElse(false)
val signingPropertiesFile = providers.gradleProperty("stemwiseSigningProperties")
    .orElse(providers.environmentVariable("STEMWISE_SIGNING_PROPERTIES"))
    .orElse(providers.provider {
        File(
            System.getProperty("user.home"),
            ".stemwise/signing/stemwise-signing.properties"
        ).absolutePath
    })
    .orNull
    ?.let(::file)
val projectPath = rootProject.projectDir.canonicalFile.toPath()
fun requireExternalSigningFile(file: File, role: String) {
    check(!file.canonicalFile.toPath().startsWith(projectPath)) {
        "$role signing material must remain outside the Android project."
    }
}
val signingProperties = Properties().apply {
    if (signingPropertiesFile?.isFile == true) {
        requireExternalSigningFile(signingPropertiesFile, "Retained")
        signingPropertiesFile.inputStream().use(::load)
    }
}
val releaseSigningReady = listOf(
    "storeFile",
    "storePassword",
    "keyAlias",
    "keyPassword"
).all { !signingProperties.getProperty(it).isNullOrBlank() }
val disposableSigningPropertiesFile = providers
    .gradleProperty("stemwiseDisposableSigningProperties")
    .orElse(providers.environmentVariable("STEMWISE_DISPOSABLE_SIGNING_PROPERTIES"))
    .orElse(providers.provider {
        File(
            System.getProperty("user.home"),
            ".stemwise/signing/stemwise-disposable-signing.properties"
        ).absolutePath
    })
    .get()
    .let(::file)
val disposableSigningProperties = Properties().apply {
    if (disposableSigningPropertiesFile.isFile) {
        requireExternalSigningFile(disposableSigningPropertiesFile, "Disposable")
        disposableSigningPropertiesFile.inputStream().use(::load)
    }
}
val disposableSigningReady = listOf(
    "storeFile",
    "storePassword",
    "keyAlias",
    "keyPassword",
    "certificateSha256"
).all { !disposableSigningProperties.getProperty(it).isNullOrBlank() }
val releaseStoreFile = if (releaseSigningReady) {
    signingPropertiesFile!!.parentFile.resolve(
        signingProperties.getProperty("storeFile")
    ).canonicalFile.also { store ->
        requireExternalSigningFile(store, "Retained keystore")
        check(store.isFile) { "Retained keystore does not exist." }
    }
} else null
val disposableStoreFile = if (disposableSigningReady) {
    disposableSigningPropertiesFile.parentFile.resolve(
        disposableSigningProperties.getProperty("storeFile")
    ).canonicalFile.also { store ->
        requireExternalSigningFile(store, "Disposable keystore")
        check(store.isFile) { "Disposable keystore does not exist." }
    }
} else null
val generatedPcBuilderAndroidTestAssets = layout.buildDirectory.dir(
    "generated/androidTestAssets/pcBuilderEnrichment"
)
val generatedBridgeAssets = layout.buildDirectory.dir("generated/mainAssets/bridgeContext")
val generateBridgeContext by tasks.registering(Copy::class) {
    from(rootProject.projectDir.parentFile.resolve("SEND_TO_AGENTS.md"))
    into(generatedBridgeAssets.map { it.dir("bridge") })
}
val testSuiteDirectory = rootProject.projectDir.parentFile.resolve("Test suite")
val configuredApplicationId = "app.stemwise.development"
val archiveBaseName = "stemwise"
val releaseSigningCertificateSha256 =
    "67CF677864A1BC66F940191E892CAB64CA41A614A9C09B41278FFCD9B8149D28"
val disposableSigningCertificateSha256 = disposableSigningProperties
    .getProperty("certificateSha256", "").uppercase()
if (disposableSigningReady) {
    check(Regex("^[0-9A-F]{64}$").matches(disposableSigningCertificateSha256)) {
        "Disposable signing certificate SHA-256 is invalid."
    }
    check(disposableSigningCertificateSha256 != releaseSigningCertificateSha256) {
        "Development and Release must use different signing identities."
    }
}
val signedArtifactTask = Regex(
    "^(?:assemble|package|bundle|install)(Development|Release)(?:AndroidTest|Bundle|UniversalApk)?$",
    RegexOption.IGNORE_CASE
)
gradle.taskGraph.whenReady(Action<TaskExecutionGraph> {
    val artifactTasks = allTasks.filter { it.project == project && signedArtifactTask.matches(it.name) }
    if (artifactTasks.any { it.name.contains("Release", ignoreCase = true) } && !releaseSigningReady) {
        throw GradleException("Release artifacts require the protected external signing-properties file.")
    }
    if (minifiedDevelopment && artifactTasks.any { !it.name.contains("Release", ignoreCase = true) } && !disposableSigningReady) {
        throw GradleException("Development signing requires stemwiseDisposableSigningProperties or STEMWISE_DISPOSABLE_SIGNING_PROPERTIES.")
    }
})
// Development runs on the selected device CPU; Release remains ARM64.
val developmentTargetAbi =
    providers.gradleProperty("stemwise.developmentTargetAbi")
        .orElse(providers.gradleProperty("android.injected.build.abi")).orElse("arm64-v8a").get().also {
        check(it in setOf("arm64-v8a", "x86_64")) { "Unsupported Development ABI: $it" }
    }

base {
    archivesName.set(archiveBaseName)
}

android {
    namespace = "app.stemwise"
    compileSdk = (rootProject.extra["android.compileSdk"] as String).toInt()

    sourceSets {
        getByName("main").assets.srcDir(generatedBridgeAssets)
        getByName("test").apply {
            java.setSrcDirs(listOf(testSuiteDirectory.resolve("android/unit/java"),
                testSuiteDirectory.resolve("android/playback-support/java"),
                testSuiteDirectory.resolve("android/test-annotations/java"),
                rootProject.file("qa-provider/src/main/java")))
            resources.setSrcDirs(listOf(testSuiteDirectory.resolve("android/unit/resources")))
            resources.srcDir(rootProject.file("contracts/audio"))
        }
        getByName("androidTest").apply {
            java.setSrcDirs(listOf(testSuiteDirectory.resolve("android/instrumentation/java"),
                testSuiteDirectory.resolve("android/playback-support/java"),
                testSuiteDirectory.resolve("android/test-annotations/java"),
                testSuiteDirectory.resolve("android/development/java"),
                rootProject.file("qa-provider/src/main/java")))
            assets.setSrcDirs(listOf(testSuiteDirectory.resolve("android/instrumentation/assets")))
            manifest.srcFile(testSuiteDirectory.resolve("android/instrumentation/AndroidManifest.xml"))
            assets.srcDir(testSuiteDirectory.resolve("android/fixtures"))
            assets.srcDir(generatedPcBuilderAndroidTestAssets)
        }
    }

    defaultConfig {
        applicationId = "app.stemwise"
        minSdk = (rootProject.extra["android.minSdk"] as String).toInt()
        targetSdk = (rootProject.extra["android.targetSdk"] as String).toInt()
        versionCode = 58
        versionName = "3.7.23"
        testInstrumentationRunner = "app.stemwise.qualification.DevelopmentStandardInstrumentationRunner"
        manifestPlaceholders["stemwiseInstrumentationTargetProcesses"] = configuredApplicationId
        manifestPlaceholders["stemwiseInstrumentationTargetPackage"] = configuredApplicationId
        manifestPlaceholders["stemwisePlaybackTestMediaAuthority"] = "$configuredApplicationId.test.playback.media"
        manifestPlaceholders["stemwiseQaDocumentsProviderProcess"] = "app.stemwise"
        manifestPlaceholders["stemwiseQaDocumentsProviderEnabled"] = "false"
        manifestPlaceholders["stemwiseQaLabActivitiesExported"] = "false"

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    testBuildType = "development"

    buildFeatures {
        compose = true
        buildConfig = true
    }

    ndkVersion = rootProject.extra["android.ndkVersion"] as String

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.pickFirsts += "/META-INF/LICENSE.md"
        jniLibs.useLegacyPackaging = true
        // These are intentionally Python/FFmpeg payload archives named as JNI
        // resources by the upstream package; they are not ELF objects.
        jniLibs.keepDebugSymbols += setOf(
            "**/libffmpeg.zip.so",
            "**/libpython.zip.so"
        )
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*setOf(developmentTargetAbi, "arm64-v8a").toTypedArray())
            isUniversalApk = false
        }
    }

    signingConfigs {
        create("disposable") {
            if (disposableSigningReady) {
                storeFile = disposableStoreFile
                storePassword = disposableSigningProperties.getProperty("storePassword")
                keyAlias = disposableSigningProperties.getProperty("keyAlias")
                keyPassword = disposableSigningProperties.getProperty("keyPassword")
            }
        }
        if (releaseSigningReady) {
            create("localRelease") {
                storeFile = releaseStoreFile
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        create("development") {
            initWith(getByName("release"))
            externalNativeBuild.cmake.abiFilters += developmentTargetAbi
            applicationIdSuffix = ".development"
            signingConfig = signingConfigs.getByName(if (disposableSigningReady) "disposable" else "debug")
            manifestPlaceholders["stemwiseQaDocumentsProviderProcess"] = configuredApplicationId
            manifestPlaceholders["stemwiseQaDocumentsProviderEnabled"] = "true"
            manifestPlaceholders["stemwiseQaLabActivitiesExported"] = "true"
            isDebuggable = true
            isJniDebuggable = false
            isMinifyEnabled = minifiedDevelopment
            isShrinkResources = minifiedDevelopment
            versionNameSuffix = "-development"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                testSuiteDirectory.resolve("android/development/instrumentation-support.pro")
            )
        }
        getByName("release") {
            externalNativeBuild.cmake.abiFilters += "arm64-v8a"
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("localRelease")
            }

        }
    }

    lint {
        xmlReport = true
        htmlReport = presentationReports
        textReport = presentationReports
        // AGP 8.13.2 is intentionally paired with its validated Gradle 8.13 runtime.
        disable += "AndroidGradlePluginVersion"
        // Adaptive-icon XML still requires the v26 resource grammar.
        disable += "ObsoleteSdkInt"
    }
}

tasks.matching { it.name == "packageDevelopment" }.configureEach {
    doLast {
        if (!minifiedDevelopment) {
            val staleMapping = layout.buildDirectory.file("outputs/mapping/development/mapping.txt").get().asFile
            check(!staleMapping.exists() || staleMapping.delete()) { "Cannot retire stale Development shrinker mapping." }
        }
    }
}

tasks.withType<Test>().configureEach {
    reports.html.required.set(presentationReports)
}

dependencyLocking {
    lockAllConfigurations()
    lockMode.set(org.gradle.api.artifacts.dsl.LockMode.STRICT)
}

androidComponents.beforeVariants(androidComponents.selector().withBuildType("debug")) {
    it.enable = false
}
androidComponents.onVariants { variant ->
    val abi = if (variant.buildType == "release") "arm64-v8a" else developmentTargetAbi
    variant.outputs.forEach { output ->
        output.enabled.set(output.filters.none { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI && it.identifier != abi })
    }
}
// AGP relocates injected-ABI builds; consume the actual artifact instead of a stale output path.
val developmentOutputDirectory = objects.directoryProperty()
androidComponents.onVariants(androidComponents.selector().withBuildType("development")) { variant ->
    developmentOutputDirectory.set(variant.artifacts.get(SingleArtifact.APK))
}
val developmentOutputFileName = "$archiveBaseName-$developmentTargetAbi-development.apk"
val stagedDevelopmentDirectory = layout.buildDirectory.dir(
    "outputs/stemwise-development/current"
)
val stagedDevelopmentApk = stagedDevelopmentDirectory.map { it.file(developmentOutputFileName) }

// Keep artifact qualification separate from the normal Android model.
extra["developmentOutputDirectory"] = developmentOutputDirectory
extra["developmentOutputFileName"] = developmentOutputFileName
extra["stagedDevelopmentApk"] = stagedDevelopmentApk
extra["configuredApplicationId"] = configuredApplicationId
extra["releaseSigningCertificateSha256"] = releaseSigningCertificateSha256
extra["disposableSigningReady"] = disposableSigningReady
extra["disposableSigningCertificateSha256"] = disposableSigningCertificateSha256
extra["developmentTargetAbi"] = developmentTargetAbi
extra["qualificationSdkDirectory"] = android.sdkDirectory
extra["qualificationBuildToolsVersion"] = android.buildToolsVersion
extra["qualificationNdkVersion"] = android.ndkVersion
extra["qualificationVersionCode"] = android.defaultConfig.versionCode
extra["qualificationVersionName"] = android.defaultConfig.versionName
apply(from = rootProject.file("build-logic/qualification.gradle.kts"))

val pythonExecutable = providers.gradleProperty("stemwisePcPython")
    .orElse(providers.environmentVariable("STEMWISE_PC_PYTHON"))
    .orElse(providers.provider {
        when {
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "python"
            else -> "python3"
        }
    })
val pythonCommand = pythonExecutable.map { executable ->
    buildList {
        add(executable)
        if (File(executable).nameWithoutExtension.equals("py", ignoreCase = true)) add("-3")
        add("-B")
    }
}

val bridgeDirectory = rootProject.file("third_party/phone-pc-bridge")
val bridgeArtifactManifest = bridgeDirectory.resolve("android/phone-pc-bridge.build.json")
val bridgeDeclaredInputs = providers.provider {
    val manifest = JsonSlurper().parse(bridgeArtifactManifest) as Map<*, *>
    (manifest["inputs"] as List<*>).map { row ->
        val name = (row as Map<*, *>)["path"] as String
        bridgeDirectory.resolve("source").resolve(name)
    }
}
val bridgeCandidateInputs = providers.provider {
    val manifest = JsonSlurper().parse(bridgeArtifactManifest) as Map<*, *>
    (manifest["candidateScopes"] as List<*>).map { entry ->
        val scope = entry as Map<*, *>
        fileTree(bridgeDirectory.resolve("source").resolve(scope["path"] as String)) {
            if (scope["embeds"] != true) include("*")
            exclude("**/*_test.go", "**/.work/**")
        }
    }
}
val bridgePythonExecutable = providers.exec {
    commandLine(pythonCommand.get() + listOf("-c", "import sys; print(sys.executable)"))
}.standardOutput.asText.map { File(it.trim()).canonicalFile }
val verifyBridgeSdkArtifact by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks the prebuilt Bridge SDK; changed candidates trigger exact dependency discovery."
    val verified = layout.buildDirectory.file("verification/bridge-sdk.verified")
    inputs.files(bridgeDeclaredInputs, bridgeCandidateInputs,
        fileTree(bridgeDirectory.resolve("source/bridge-core/android")) { include("*.java", "*.xml") })
    inputs.files(bridgeDirectory.resolve("android/phone-pc-bridge.aar"), bridgeArtifactManifest,
        rootProject.file("tools/verify-bridge-sdk.py"), bridgePythonExecutable)
    inputs.property("pythonCommand", pythonCommand)
    outputs.file(verified)
    workingDir(rootProject.projectDir)
    doFirst { commandLine(pythonCommand.get() + listOf("tools/verify-bridge-sdk.py")) }
    doLast {
        verified.get().asFile.apply { parentFile.mkdirs(); writeText("verified\n", Charsets.UTF_8) }
    }
}
val verifyBridgeSdkDependencies by tasks.registering(Exec::class) {
    group = "verification"
    description = "Qualifies the SDK against pinned Go's current Android dependency closure."
    workingDir(rootProject.projectDir)
    doFirst { commandLine(pythonCommand.get() + listOf("tools/verify-bridge-sdk.py", "--discover")) }
}

val generatePcBuilderEnrichmentFixtures by tasks.registering(Exec::class) {
    group = "verification"
    description = "Packages the exhaustive enrichment fixtures with the real StemWise PC builder."

    val builderDirectory = providers.gradleProperty("stemwisePcBuilderDir")
        .orElse(providers.environmentVariable("STEMWISE_PC_BUILDER_DIR"))
        .map(::file)
        .orElse(providers.provider {
            rootProject.projectDir.parentFile.resolve("PC side/stemwise-playlist-builder")
        })
    val supportScript = testSuiteDirectory.resolve(
        "android/instrumentation/support/generate_pc_builder_enrichment_fixtures.py"
    )
    val fixtureDirectory = file("src/development/assets/qa/enrichment")
    val regionalFixture = testSuiteDirectory.resolve("android/unit/resources/fixtures/enrichment/regional-lyrics.json")
    val generatedAssetDirectory = generatedPcBuilderAndroidTestAssets.map {
        it.dir("qa/pc-builder-enrichment")
    }

    inputs.file(supportScript)
    inputs.file(regionalFixture)
    inputs.files(
        builderDirectory.map { it.resolve("src/packages.py") },
        builderDirectory.map { it.resolve("src/original_delivery.py") },
        builderDirectory.map { it.resolve("src/contract.py") },
        builderDirectory.map { it.resolve("src/common.py") },
        builderDirectory.map { it.resolve("src/song_enrichment_v2.py") },
        builderDirectory.map { it.resolve("src/enrichment_constants.py") },
        builderDirectory.map { it.resolve("src/lyrics_tokenization.py") },
        builderDirectory.map { it.resolve("src/lyrics_unicode.py") },
        builderDirectory.map { it.resolve("src/model_registry.py") },
        builderDirectory.map { it.resolve("src/model-route-registry.json") }

    )
    inputs.files(fileTree(fixtureDirectory) { include("*.json") })
    inputs.file(testSuiteDirectory.resolve(
        "android/unit/resources/fixtures/enrichment/cover.png.base64"
    ))
    inputs.file(rootProject.projectDir.resolve("contracts/enrichment/song-enrichment-v2.schema.json"))
    inputs.file(testSuiteDirectory.resolve("android/instrumentation/assets/encoder/flac24-stereo-96000.flac"))
    outputs.dir(generatedPcBuilderAndroidTestAssets)
    inputs.property("pythonCommand", pythonCommand)
    inputs.property("pcFixtureRuntime", providers.exec {
        commandLine(pythonCommand.get() + listOf("-c", "import sys,PIL; print(sys.version); print(PIL.__version__)"))
    }.standardOutput.asText)
    inputs.files(builderDirectory.map { builder -> fileTree(builder.resolve("src")) { include("**/*.py", "**/*.json") } })

    doFirst {
        val builder = builderDirectory.get()
        check(builder.isDirectory) {
            "StemWise PC builder not found at $builder. Set stemwisePcBuilderDir or " +
                "STEMWISE_PC_BUILDER_DIR."
        }
        commandLine(buildList {
            addAll(pythonCommand.get())
            add(supportScript.absolutePath)
            add("--builder")
            add(builder.absolutePath)
            add("--fixtures")
            add(fixtureDirectory.absolutePath)
            add("--regional-fixture")
            add(regionalFixture.absolutePath)
            add("--cover-base64")
            add(testSuiteDirectory.resolve(
                "android/unit/resources/fixtures/enrichment/cover.png.base64"
            ).absolutePath)
            add("--schema")
            add(file(rootProject.projectDir.resolve("contracts/enrichment/song-enrichment-v2.schema.json")).absolutePath)
            add("--output")
            add(generatedAssetDirectory.get().asFile.absolutePath)
        })
    }
}

tasks.named("preBuild") { dependsOn(generateBridgeContext, verifyBridgeSdkArtifact) }

tasks.matching {
    it.name == "mergeDevelopmentAndroidTestAssets"
}.configureEach {
    dependsOn(generatePcBuilderEnrichmentFixtures)
}

// Android-test lint models consume the generated asset source directly, before asset merging.
// Declare the producer explicitly so parallel Gradle execution cannot inspect stale fixtures.
tasks.matching {
    it.name.endsWith("AndroidTestLintModel") ||
        (it.name.startsWith("lintAnalyze") && it.name.endsWith("AndroidTest"))
}.configureEach {
    dependsOn(generatePcBuilderEnrichmentFixtures)
}

kotlin {
    jvmToolchain(17)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":audio-engine"))
    implementation(files("../third_party/phone-pc-bridge/android/phone-pc-bridge.aar"))
    // Already bundled by youtubedl-android; also use its random-access zipapp reader directly.
    implementation("org.apache.commons:commons-compress:1.28.0") {
        because("Reject published malformed-archive denial-of-service defects in the old transitive version")
    }
    constraints {
        implementation("commons-io:commons-io:2.22.0") {
            because("Use maintained Java 8 compatible stream helpers with the XML resource fix")
        }
    }

    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // 1.19.0 requires compileSdk 37; this app intentionally targets the stable API 36 toolchain.
    //noinspection GradleDependency
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // 2.11.0 requires compileSdk 37; this is the newest API 36-compatible release.
    //noinspection GradleDependency
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.media3:media3-session:1.10.1")
    implementation("androidx.media3:media3-extractor:1.10.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // Room's schema migration reader is compiled against Serialization 1.8.x.
    // Keep runtime/test resolution aligned instead of allowing Lifecycle's older BOM to win.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("io.github.erdtman:java-json-canonicalization:1.1")
    implementation("org.bouncycastle:bcpg-jdk18on:1.85")
    implementation("org.bouncycastle:bcprov-jdk18on:1.85")
    implementation("org.bouncycastle:bcutil-jdk18on:1.85")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:common:0.18.1")

    ksp("androidx.room:room-compiler:2.8.4")
    testImplementation("org.apache.commons:commons-compress:1.28.0")
    testImplementation("commons-io:commons-io:2.22.0")
    testImplementation(testFixtures(project(":audio-engine")))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260719")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestCompileOnly("com.google.errorprone:error_prone_annotations:2.26.1")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("androidx.work:work-testing:2.11.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    add("developmentImplementation", "androidx.compose.ui:ui-tooling")
    add("developmentImplementation", "androidx.compose.ui:ui-test-manifest")
}

// Source generation is checked before compilation; stale generated contracts cannot ship.
apply(from = rootProject.file("gradle/generated-checks.gradle.kts"))
tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Kotlin") }.configureEach {
    dependsOn("checkGeneratedContracts", "checkNativePacketSchema", "checkLyricsUnicode")
}

// Only Development instrumentation can inspect actual Kotlin implementation producer outputs.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().matching {
    it.name == "compileDevelopmentAndroidTestKotlin"
}.configureEach {
    val appClasses = tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileDevelopmentKotlin")
        .flatMap { it.destinationDirectory }
    val engineClasses = project(":audio-engine").tasks
        .named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileDevelopmentKotlin")
        .flatMap { it.destinationDirectory }
    val dependencyLibraries = libraries.from.toList()
    // Kotlin grants friendship to the loaded module origin, so load the producer's actual classes first.
    libraries.setFrom(engineClasses, dependencyLibraries)
    compilerOptions.freeCompilerArgs.add(provider {
        "-Xfriend-paths=${appClasses.get().asFile.absolutePath},${engineClasses.get().asFile.absolutePath}"
    })
}
