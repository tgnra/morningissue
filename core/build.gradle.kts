import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure JVM code shared by the Android app and the server: the model, the ranking engine, the
// embedder, feed fetching and parsing, the Kobo protocol, the archive format and the server API.
// Nothing here touches a database: the app's and the server's schemas differ.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // Test helpers (fixture loading, a hand-moved clock, the real embedder) shared with the
    // app's and the server's tests.
    `java-test-fixtures`
}

// No jvmToolchain: the Nix sandbox cannot download one. Java 17 because Android consumes this.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.readability4j)
    // Android supplies org.xmlpull from the platform, and bundling kxml2 in the APK trips lint's
    // DuplicatePlatformClasses; the server adds it at runtime.
    compileOnly(libs.kxml2)
    // Jsoup's nullness annotations, which the compiler must see where a Jsoup type is inferred.
    compileOnly(libs.jspecify)

    testImplementation(libs.junit)
    testImplementation(libs.kxml2)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)

    testFixturesApi(libs.junit)
    testFixturesApi(libs.okhttp.mockwebserver)
    testFixturesImplementation(libs.kotlinx.serialization.json)
}
