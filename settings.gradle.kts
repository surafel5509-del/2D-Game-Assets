// Lumen2D — a full 2D game engine for Android (and desktop).
// Root Gradle settings: declares every module of the engine monorepo.
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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Lumen2D"

// Pure-Kotlin engine core (no Android APIs -> unit-testable on any JVM).
include(":engine-core")
// Desktop/headless backends: AWT renderer, WAV audio, CLI preview & screenshot tools.
include(":engine-desktop")
// Android backends: hardware Canvas renderer, AudioTrack mixer, touch input, SAF storage.
include(":engine-android")
// Lumen2D Studio — the Android editor + runtime player (the shippable app).
include(":app-android")
