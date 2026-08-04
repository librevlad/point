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

// Шрифты живут в :core:ui (Android-ресурсы) и здесь ПЕРЕИСПОЛЬЗУЮТСЯ, а не копируются:
// один файл начертания на проект — иначе телефон и ПК однажды разойдутся, и никто не заметит,
// какой из них прав (#285).
sourceSets.main {
    resources.srcDir(rootProject.file("core/ui/src/main/res/font"))
}

// Живая проверка конвертации офисных файлов (#403) идёт по настоящему файлу, путь к нему
// передаётся снаружи: на CI ни файла, ни Office нет, и тест там сам себя пропускает.
tasks.test {
    System.getProperty("point.test.office")?.let { systemProperty("point.test.office", it) }
    // Живой сервер и пропуск тестового аккаунта (#473) — свойствами задачи, а не сгенерированным
    // исходником: в артефакт они не компилируются вовсе. Нет свойств — тесты сами себя пропускают, как на CI.
    System.getProperty("point.test.server")?.let { systemProperty("point.test.server", it) }
    System.getProperty("point.test.pass")?.let { systemProperty("point.test.pass", it) }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:flow"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    // mDNS advertising so the phone finds this PC by itself (#147 slice C).
    implementation("org.jmdns:jmdns:3.6.1")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Задачи `generateRelayEnv` больше нет — и вместе с ней нет `RelayEnv.APP_SECRET` (#419, #473).
//
// Она существовала ради одного: вкомпилировать в MSI общий пароль приложения — один на всех, и
// потому выкладывать такой установщик было нельзя. В мире с аккаунтами такой вещи не существует
// вовсе: пропуск у каждого свой, рождается при входе и лежит в `~/.point-pc/account`. Адрес сервера
// секретом не был никогда и теперь живёт константой `PointServer.DEFAULT_URL` с переопределением
// в `~/.point-pc/config`.

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
