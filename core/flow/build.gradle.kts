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
