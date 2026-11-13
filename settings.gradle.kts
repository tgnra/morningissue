pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Morning Issue"
include(":core", ":server")
// `-PserverOnly` leaves out the Android modules, so the server builds with no Android SDK
// (Docker, Nix): ./gradlew -PserverOnly :server:installDist
if (!providers.gradleProperty("serverOnly").isPresent) {
    include(":app", ":macrobenchmark")
}
 