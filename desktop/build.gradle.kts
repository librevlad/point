import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

kotlin { jvmToolchain(17) }

sourceSets.main {

    // Значки и цвета действий — один исходник на два устройства (#849). До этого файл лежал
    // копией в `desktop/ui/BubbleIcons.kt` с припиской «меняется там — переносится сюда»:
    // сторож ловил пропавший ключ, но не ловил разъехавшийся цвет.
    kotlin.srcDir(rootProject.file("core/ui/src/shared/kotlin"))

    resources.srcDir(rootProject.file("core/ui/src/main/res/font"))

    // Звуки-порталы лежат там же, откуда их берёт телефон: общий файл — общий тембр (#650).
    resources.srcDir(rootProject.file("data/src/main/res/raw"))
}

tasks.test {
    System.getProperty("point.test.office")?.let { systemProperty("point.test.office", it) }

    System.getProperty("point.test.server")?.let { systemProperty("point.test.server", it) }
    System.getProperty("point.test.pass")?.let { systemProperty("point.test.pass", it) }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:flow"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    // Плашки действий на ПК рисуются теми же иконками, что и на телефоне (#626-соседнее):
    // общего модуля у Android-UI и Compose Desktop нет, значит набор берётся напрямую.
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.pdfbox)

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

            packageVersion = "3.0.0"

            vendor = "Point"

            description = "Point for PC - share from your phone, act instantly"

            windows {
                iconFile.set(project.file("src/main/resources/point.ico"))
                menu = true
                shortcut = true
            }
        }
    }
}

/**
 * Версия и дата сборки — в самой сборке (#822). Живой случай: у человека стоял Point от
 * 6 августа, действие приехало 10-го, и падение выглядело загадкой. Теперь «у меня старое»
 * видно в настройках окна, не спрашивая никого.
 */
val buildInfoDir = layout.buildDirectory.dir("generated/source/buildinfo")

val generateBuildInfo by tasks.registering {
    val version = providers.gradleProperty("pointVersion").get()
    val out = buildInfoDir
    inputs.property("version", version)
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile.resolve("com/point/desktop").apply { mkdirs() }
        val day = SimpleDateFormat("yyyy-MM-dd").format(Date())
        val text = buildString {
            appendLine("package com.point.desktop")
            appendLine()
            appendLine("/** Что за сборка сейчас работает (#822). */")
            appendLine("object BuildInfo {")
            appendLine("    const val VERSION: String = \"" + version + "\"")
            appendLine("    const val BUILT_ON: String = \"" + day + "\"")
            appendLine("}")
        }
        dir.resolve("BuildInfo.kt").writeText(text)
    }
}

kotlin.sourceSets["main"].kotlin.srcDir(buildInfoDir)

tasks.named("compileKotlin") { dependsOn(generateBuildInfo) }

tasks.register<JavaExec>("fontSample") {
    group = "verification"
    description = "Показывает варианты сглаживания шрифтов рядом (#590)"
    mainClass.set("com.point.desktop.FontSampleKt")
    classpath = sourceSets["test"].runtimeClasspath
}
