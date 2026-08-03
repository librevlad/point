// :core:flow — PURE Kotlin contracts (Executor, ExecutorRegistry, ObjectStore,
// LlmClient) + Flow Graph derivation. No Android APIs: side-effect boundaries
// are declared here and implemented in :data / :executors.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // ТОЛЬКО в тестах (#388): чужой декодер QR как экзаменатор нашего кодировщика. Чистая Java,
    // в артефакт не попадает — модуль остаётся без зависимостей.
    testImplementation(libs.zxing.core)
}

// Эталоны корпуса — ВХОД тестов, и Gradle обязан об этом знать (#262).
//
// `TableMetricTest` и `CorpusScoreCliTest` читают `tools/corpus/*.tsv` напрямую файлом, мимо
// classpath. Для Gradle такого входа не существует: правка эталона не меняет ни исходников, ни
// зависимостей, поэтому задача `test` остаётся UP-TO-DATE, а при `org.gradle.caching=true` —
// FROM-CACHE. Проверено щупом: заведомо сломанный эталон, положенный в `tools/corpus`,
// прошёл `./gradlew :core:flow:test` с BUILD SUCCESSFUL, ни разу не запустив проверку.
// Это ровно та тихая зелень, от которой сама метрика и лечит: сторож есть, а не сработал.
//
// Объявляем каталог входом — теперь новый или исправленный эталон перезапускает тесты самим
// фактом изменения. Чувствительность к пути относительная, чтобы кэш переносился между
// рабочими копиями и CI.
tasks.test {
    inputs.dir(rootProject.layout.projectDirectory.dir("tools/corpus"))
        .withPropertyName("corpusExpectations")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// Счётчик таблиц корпуса (#262): у метрики одна реализация — `scoreTable`, и харнесс
// `tools/table-score.sh` зовёт именно её, а не свою копию на awk. CLI живёт в тестовых исходниках,
// поэтому в артефакт приложения не попадает.
//
//   ./gradlew :core:flow:scoreTable -Ptable=out/23.tsv -Pexpected=tools/corpus/23.expected.tsv -Preport=out/report.md
//
// Свойства проекта, а не --args: путь с пробелом Gradle разрезал бы по пробелу.
tasks.register<JavaExec>("scoreTable") {
    group = "verification"
    description = "Сравнивает выгруженную таблицу (TSV) с эталоном кадра корпуса (#262)"
    mainClass.set("com.point.core.flow.TableScoreCliKt")
    classpath = sourceSets["test"].runtimeClasspath
    argumentProviders.add {
        listOf("table", "expected", "report").map { project.findProperty(it) as? String ?: "" }
    }
}

// Счётчик корпуса (#262): число «действие без правок» собиралось РУКАМИ по журналам флоу —
// человек открывал двадцать три файла и сверял их с действием, которое помнил. Метрика, которую
// считает человек, меряет прежде всего его терпение. Реализация одна и та же (`scoreCorpus`),
// CLI — рядом с её тестами, поэтому в артефакт приложения не попадает.
//
//   ./gradlew :core:flow:scoreCorpus -Prun=out -Pframes=tools/corpus/frames.tsv -Preport=out/report.md
tasks.register<JavaExec>("scoreCorpus") {
    group = "verification"
    description = "Считает долю кадров корпуса, где действие готово без правок (#262)"
    mainClass.set("com.point.core.flow.CorpusScoreCliKt")
    classpath = sourceSets["test"].runtimeClasspath
    argumentProviders.add {
        listOf("run", "frames", "report").map { project.findProperty(it) as? String ?: "" }
    }
}

// Свод чтений для опытов над тем, ЧЕМ читать (ICG-бенчмарк): та же `reconcile`, что в продукте,
// иначе опыт мерил бы качество скрипта, а не качество ансамбля.
//
//   ./gradlew :core:flow:reconcileTables -Pinputs=a.tsv,b.tsv -Pout=consensus.tsv
tasks.register<JavaExec>("reconcileTables") {
    group = "verification"
    description = "Сводит несколько прочтений одной таблицы в одну (#346)"
    mainClass.set("com.point.core.flow.ReconcileCliKt")
    classpath = sourceSets["test"].runtimeClasspath
    argumentProviders.add {
        listOf("inputs", "out").map { project.findProperty(it) as? String ?: "" }
    }
}
