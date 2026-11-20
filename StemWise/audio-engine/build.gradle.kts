plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val targetAbi = providers.gradleProperty("stemwise.developmentTargetAbi")
    .orElse(providers.gradleProperty("android.injected.build.abi")).orElse("arm64-v8a").get()
check(targetAbi in setOf("arm64-v8a", "x86_64")) { "Unsupported audio-engine ABI: $targetAbi" }

android {
    namespace = "app.stemwise.audioengine"
    compileSdk = (rootProject.extra["android.compileSdk"] as String).toInt()
    ndkVersion = rootProject.extra["android.ndkVersion"] as String
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
    testFixtures { enable = true }
    sourceSets {
        getByName("test").apply {
            val suite = rootProject.file("../Test suite/android")
            java.setSrcDirs(listOf(suite.resolve("audio-engine/unit/java"), suite.resolve("test-annotations/java")))
            resources.setSrcDirs(listOf(suite.resolve("unit/resources"), rootProject.file("contracts/audio")))
        }
        maybeCreate("testFixtures").java.setSrcDirs(listOf(rootProject.file("../Test suite/android/testFixtures/java")))
    }

    defaultConfig {
        minSdk = (rootProject.extra["android.minSdk"] as String).toInt()
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_shared" } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    buildTypes {
        create("development") {
            initWith(getByName("release"))
            ndk.abiFilters += targetAbi
            isJniDebuggable = false
            externalNativeBuild {
                cmake {
                    arguments += listOf("-DSTEMWISE_AUDIT_OPTIMIZED=ON", "-DSTEMWISE_CACHE_IO_PROFILING=ON")
                }
            }
        }
        getByName("release") {
            ndk.abiFilters += "arm64-v8a"
            externalNativeBuild { cmake {
                arguments += listOf("-DSTEMWISE_AUDIT_OPTIMIZED=OFF", "-DSTEMWISE_CACHE_IO_PROFILING=OFF")
            } }
        }
    }
}
kotlin { jvmToolchain(17) }
androidComponents.beforeVariants(androidComponents.selector().withBuildType("debug")) {
    it.enable = false
}
dependencyLocking {
    lockAllConfigurations()
    lockMode.set(org.gradle.api.artifacts.dsl.LockMode.STRICT)
}

val pythonExecutable = providers.gradleProperty("stemwisePcPython")
    .orElse(providers.environmentVariable("STEMWISE_PC_PYTHON"))
    .orElse(if (System.getProperty("os.name").startsWith("Windows", true)) "python" else "python3")
val pythonCommand = pythonExecutable.map { executable ->
    listOf(executable) + (if (File(executable).nameWithoutExtension.equals("py", true)) listOf("-3") else emptyList()) + "-B"
}
apply(from = rootProject.file("gradle/generated-checks.gradle.kts"))
tasks.matching { it.name == "preBuild" || it.name.startsWith("externalNativeBuild") }.configureEach {
    dependsOn("checkNativePacketSchema", "checkGeneratedContracts")
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260719")
}
