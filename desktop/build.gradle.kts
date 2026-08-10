import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

kotlin { jvmToolchain(17) }

sourceSets.main {
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

tasks.register<JavaExec>("fontSample") {
    group = "verification"
    description = "Показывает варианты сглаживания шрифтов рядом (#590)"
    mainClass.set("com.point.desktop.FontSampleKt")
    classpath = sourceSets["test"].runtimeClasspath
}
