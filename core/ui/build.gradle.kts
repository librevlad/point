// :core:ui — Bubble UI components + design system (Compose, Material 3).
// Pure presentation: renders an object and its bubbles, zero business logic.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.point.core.ui"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Экраны под тестом (#114): Robolectric поднимает настоящий Android в JVM — ему нужны ресурсы.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))
    // Metadata-key constants (entity.* understood facts) — allowed by the module rule
    // (:core:flow ← :core:ui); still zero business logic here.
    implementation(project(":core:flow"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)

    // #114: здесь живут восемь экранов, и до сих пор ни один из них не рисовался ни в одном тесте.
    // Только debug — хост экрана приносит `ui-test-manifest`, которого в релизном манифесте нет;
    // сами экранные тесты лежат в `src/testDebug`.
    testDebugImplementation(libs.robolectric)
    testDebugImplementation(platform(libs.androidx.compose.bom))
    testDebugImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
