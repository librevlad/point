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
            // Версия та же, что у телефона: одно приложение на два устройства, и расхождение
            // версий читалось бы как «это разные продукты». Формат jpackage требует X.Y.Z и не
            // принимает ведущий ноль в первом разряде, поэтому 0.3.0 записывается как 3.0.0
            // до первого настоящего мажорного релиза.
            packageVersion = "3.0.0"

            vendor = "Point"
            // ASCII only: WiX builds the msi database in code page 1252, so a Cyrillic
            // description fails the light.exe link (LGHT0311, exit 311).
            description = "Point for PC - share from your phone, act instantly"
            // Модуля `jdk.httpserver` здесь больше нет (#475): он держался ради приёмника
            // локальной сети, а локальной сети у Point не осталось. Единственный путь между
            // устройствами — ящики сервера, и они ходят обычным `HttpURLConnection` из
            // `java.base`. Лишний модуль в образе — это вес, который никто не открывает.
            windows {
                iconFile.set(project.file("src/main/resources/point.ico"))
                menu = true
                shortcut = true
            }
        }
    }
}
