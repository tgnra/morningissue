// Macrobenchmarks for the app, run on a device against the app's `benchmark` build type:
//   ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest
// This installs (and replaces) the app's benchmark build, which shares the application ID with
// the debug and release builds, and the benchmark wipes its data.
plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "net.tgnra.morningissue.macrobenchmark"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // An emulator's numbers are only indicative, but the benchmark should still run there.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        // Matches the app's `benchmark` build type, which is what gets measured.
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}

androidComponents {
    // Only the benchmark variant exists: a debug build of the app would not be worth measuring.
    beforeVariants(selector().all()) {
        it.enable = it.buildType == "benchmark"
    }
}
