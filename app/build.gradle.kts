plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room3)
}

// Release signing is configured only when all four variables are set (CI, or a local signed build).
val releaseKeystoreFile = providers.environmentVariable("KEYSTORE_FILE").orNull
val releaseKeystorePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    releaseKeystoreFile, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword
).none { it.isNullOrBlank() }

// The relay the Kobo sync goes through, and its registration token. The app has no setting for
// either: they are fixed when it is built, from a Gradle property (-P, or
// ~/.gradle/gradle.properties) or the environment (CI variables and secrets). The address
// defaults to the project's relay when neither is set, or is empty. The token is never kept in
// the repository: it only keeps bots from creating groups on the relay, and a public
// repository would hand it to them. Empty means none.
fun buildSetting(property: String, variable: String): String =
    providers.gradleProperty(property).orElse(providers.environmentVariable(variable)).getOrElse("").trim()
val defaultRelayAddress = "https://mirelay.tgnra.net"
val givenRelayAddress = buildSetting("morningissue.relayAddress", "MORNINGISSUE_RELAY_ADDRESS")
val builtInRelayAddress = givenRelayAddress.ifEmpty { defaultRelayAddress }
val builtInRelayToken = buildSetting("morningissue.relayToken", "MORNINGISSUE_RELAY_TOKEN")
fun kotlinString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""

android {
    namespace = "net.tgnra.morningissue"
    // Current AndroidX, Compose, Coil and OkHttp releases require compiling against API 37.
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "net.tgnra.morningissue"
        minSdk = 26
        targetSdk = 36
        // CI overrides these from the release tag: -PappVersionCode=<n> -PappVersionName=<x.y.z>
        versionCode = providers.gradleProperty("appVersionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("appVersionName").orNull ?: "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "RELAY_ADDRESS", kotlinString(builtInRelayAddress))
        buildConfigField("String", "RELAY_TOKEN", kotlinString(builtInRelayToken))
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystoreFile!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // What :macrobenchmark measures: the release build, profileable and signed with the
        // debug key so it installs anywhere, plus the seeding hook in src/benchmark. Never shipped.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
    }
    sourceSets {
        // The benchmark build shares the release build's empty debug-settings twin.
        getByName("benchmark") {
            kotlin.directories.add("src/release/java")
        }
        // MigrationTestHelper reads the exported schemas as assets. Unit tests (Robolectric) are
        // given the debug build's assets and have none of their own, so the schemas go into the
        // debug build; release builds do not carry them.
        getByName("debug") {
            assets.directories.add("schemas")
        }
        // The embedding table and the discovery catalogue are shared with the server, in the
        // top-level assets/.
        getByName("main") {
            assets.directories.add("$rootDir/assets")
        }
        // Fixtures of the code that moved to :core (feed documents, embedding references) that
        // the app's own tests still read.
        getByName("test") {
            resources.directories.add("$rootDir/core/src/test/resources")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // The embedding table (assets/embedding/*.bin) is memory-mapped out of the APK, which only
        // works for an entry stored as is. It is 8-bit noise to a compressor anyway: deflate
        // would save about an eighth of its 7.9 MB.
        noCompress += "bin"
    }
    testOptions {
        unitTests {
            // Robolectric needs merged resources and the manifest for Room/WorkManager tests.
            isIncludeAndroidResources = true
            all {
                // Robolectric's Android 16 (API 36) runtime reaches into this JDK-internal package.
                it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
                // `-PsimulationReport` (or `-PsimulationReport=<dir>`) makes the ranking simulation
                // write a report per persona, by default to build/reports/simulation/.
                project.findProperty("simulationReport")?.let { dir -> it.systemProperty("morningissue.simulation.report", dir) }
                // Whether this build was given a relay address, so a test can check the default otherwise.
                it.systemProperty("morningissue.relayAddressGiven", givenRelayAddress.isNotEmpty().toString())
            }
        }
    }
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.windowsize)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room3.runtime)
    implementation(libs.androidx.sqlite.framework)
    ksp(libs.androidx.room3.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.jsoup)
    implementation(libs.readability4j)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // Scanning a Kobo's relay pairing code, without Google Play services.
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room3.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.okhttp.mockwebserver)
    // A real XmlPullParser for plain JVM parser tests (android.jar only has stubs).
    testImplementation(libs.kxml2)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.room3.testing)
    androidTestImplementation(libs.androidx.work.testing)
}
