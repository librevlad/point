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

// Lets Gradle auto-provision the JDK 17 toolchain (jvmToolchain(17)) when only a
// newer JDK is on the machine — so `./gradlew` works without a manual JDK 17.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // tesseract4android (on-device OCR)
    }
}

rootProject.name = "Point"

include(":app")
include(":core:model")
include(":core:flow")
include(":core:ui")
include(":data")
include(":executors")
include(":desktop")
include(":checks")
