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
