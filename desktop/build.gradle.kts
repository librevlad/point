// Point for PC (#147): a pure-JVM Compose Desktop app. It reuses the Android-free core
// (:core:model, :core:flow) untouched — the whole reason those modules stay pure.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:flow"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    // QR for pairing (pure-JVM zxing core — the phone scans the window).
    implementation("com.google.zxing:core:3.5.3")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

compose.desktop {
    application {
        mainClass = "com.point.desktop.MainKt"
    }
}
