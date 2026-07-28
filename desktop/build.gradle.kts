import java.io.File
import java.util.Properties

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

// Relay config (#161 v2): generate RelayEnv from local.properties — Compose Desktop has no
// BuildConfig. The relay URL is public (it also travels in the QR); the secret is the shared
// app secret. Empty when local.properties is absent (CI), so the build still compiles.
val generateRelayEnv by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/relay")
    outputs.dir(outDir)
    val props = Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }
    val url = props.getProperty("RELAY_URL", "")
    val secret = props.getProperty("RELAY_APP_SECRET", "")
    doLast {
        val pkg = outDir.get().dir("com/point/desktop").asFile.apply { mkdirs() }
        File(pkg, "RelayEnv.kt").writeText(
            "package com.point.desktop\n\n" +
                "// Generated from local.properties — do not edit.\n" +
                "object RelayEnv {\n" +
                "    const val URL = \"$url\"\n" +
                "    const val APP_SECRET = \"$secret\"\n" +
                "}\n",
        )
    }
}
kotlin.sourceSets.named("main") { kotlin.srcDir(generateRelayEnv) }

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
