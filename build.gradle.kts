// Root build script. Plugins are declared here (applied false) and applied
// per-module so version alignment lives in gradle/libs.versions.toml.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.jetbrains.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.google.services) apply false
}

/**
 * Тесты читают русские фразы продукта (#1473): Point говорит на языке устройства, а машина CI
 * бывает английской. Язык тестов закреплён здесь, один раз для всех модулей, свойством
 * `point.lang` (`Lang.current`), а не подменой `Locale`: от него зависит разбор сумм и дат.
 * Как Point выбирает язык и что видит англичанин или украинец, проверяют `TrTest` и `:checks`.
 */
subprojects {
    tasks.withType<Test>().configureEach {
        systemProperty("point.lang", "ru")
    }
}
