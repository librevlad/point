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
    // mDNS advertising so the phone finds this PC by itself (#147 slice C).
    implementation("org.jmdns:jmdns:3.6.1")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

compose.desktop {
    application {
        mainClass = "com.point.desktop.MainKt"
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
            )
            packageName = "Point"
            packageVersion = "1.0.0"
            vendor = "Point"
            // ASCII only: WiX builds the msi database in code page 1252, so a Cyrillic
            // description fails the light.exe link (LGHT0311, exit 311).
            description = "Point for PC - share from your phone, act instantly"
            // The LAN receiver lives on the JDK's own http server — jpackage strips
            // unused modules, so it must be kept explicitly or /receive dies in the
            // packaged build (works fine from :run, fails silently in the exe).
            modules("jdk.httpserver")
            windows {
                iconFile.set(project.file("src/main/resources/point.ico"))
                menu = true
                shortcut = true
            }
        }
    }
}
