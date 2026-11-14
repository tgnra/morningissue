import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The self-hosted server: Ktor (CIO) over a multi-user Room 3 database on the JVM, with the
// bundled SQLite. `./gradlew -PserverOnly :server:installDist` builds it without the Android SDK.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room3)
    application
}

// No jvmToolchain: the Nix sandbox cannot download one. The build's JDK (21) compiles it.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

// `-PserverVersion=x.y.z` from a release tag; shown by /api/v1/health.
version = providers.gradleProperty("serverVersion").getOrElse("0.5.0")

tasks.jar {
    manifest {
        attributes("Implementation-Title" to "morningissue-server", "Implementation-Version" to project.version)
    }
}

application {
    mainClass.set("net.tgnra.morningissue.server.MainKt")
    applicationName = "morningissue-server"
}

// The embedding table and the discovery catalogue, shared with the app.
sourceSets {
    main {
        resources.srcDir("$rootDir/assets")
    }
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))
    // Core compiles against XmlPullParser only; kxml2 registers itself for XmlPullParserFactory
    // through META-INF/services.
    runtimeOnly(libs.kxml2)

    implementation(libs.androidx.room3.runtime)
    implementation(libs.androidx.sqlite.bundled)
    ksp(libs.androidx.room3.compiler)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.tomlj)
    implementation(libs.logback.classic)
    implementation(libs.twelvemonkeys.imageio.core)
    implementation(libs.twelvemonkeys.imageio.jpeg)
    implementation(libs.twelvemonkeys.imageio.webp)

    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kxml2)
}

tasks.test {
    // The bundled SQLite extracts its native library to java.io.tmpdir. The tests point that at a
    // directory of the build's own, as the launcher points it at <dataDir>/tmp, and the Room
    // spike checks the library lands there.
    val tmp = layout.buildDirectory.dir("tmp/test-jvm").get().asFile
    systemProperty("java.io.tmpdir", tmp.absolutePath)
    doFirst { tmp.mkdirs() }
    // `-PsimulationReport[=dir]` writes the ranking simulation's reports (SimulationReport).
    project.findProperty("simulationReport")?.let { dir -> systemProperty("morningissue.simulation.report", dir) }
}
