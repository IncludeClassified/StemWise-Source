plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
    id("com.google.devtools.ksp") version "2.3.10" apply false
}

val androidToolchain = java.util.Properties().apply {
    rootProject.file("gradle/android-toolchain.properties").inputStream().use(::load)
}
for (name in listOf("compileSdk", "targetSdk", "minSdk", "qaMinSdk", "ndkVersion")) {
    extra["android.$name"] = requireNotNull(androidToolchain.getProperty(name)) { "Missing Android toolchain property: $name" }
}
