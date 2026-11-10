// Top-level build file where you can add configuration options common to all sub-projects/modules.
// AGP 9 has built-in Kotlin support, so there is no org.jetbrains.kotlin.android plugin here.
// The Kotlin compiler plugins below set the Kotlin Gradle plugin version for the whole build.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.androidx.room3) apply false
}
