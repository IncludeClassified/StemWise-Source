plugins {
    id("com.android.application")
}

val stageCurrentFixtureAssets by tasks.registering(Sync::class) {
    from("../app/src/development/assets") { include("demo_library/**") }
    into(layout.buildDirectory.dir("generated/current-fixture-assets"))
}

android {
    namespace = "app.stemwise.testprovider"
    compileSdk = (rootProject.extra["android.compileSdk"] as String).toInt()

    defaultConfig {
        applicationId = "app.stemwise.testprovider"
        minSdk = (rootProject.extra["android.qaMinSdk"] as String).toInt()
        targetSdk = (rootProject.extra["android.targetSdk"] as String).toInt()
        versionCode = 1
        versionName = "1"
    }

    sourceSets.named("main") {
        assets.srcDir(stageCurrentFixtureAssets.map { it.destinationDir })
    }
    buildTypes {
        create("development") {
            initWith(getByName("debug"))
        }
    }
}
androidComponents.beforeVariants { variant ->
    variant.enable = variant.buildType == "development"
}
