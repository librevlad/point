plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)

    // Разбор ответов моделей переехал сюда вместе с цепочкой (#828, решение владельца
    // 12.08.2026 «Дать ядру org.json»). Это обычная Java-библиотека, а не Android: правило
    // «:core:flow — чистый Kotlin, Android-free» остаётся в силе. Переписывать 43 места
    // разбора на свой парсер значило бы рисковать тем самым кодом, где ошибка тихая:
    // модель ответила, а Point не прочёл.
    implementation(libs.json)

    // Телефон судит библиотека, а не самодельное «10–13 цифр» (#801). Тоже обычная Java.
    implementation(libs.libphonenumber)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    testImplementation(libs.zxing.core)
}

tasks.test {
    inputs.dir(rootProject.layout.projectDirectory.dir("tools/corpus"))
        .withPropertyName("corpusExpectations")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.register<JavaExec>("scoreTable") {
    group = "verification"
    description = "Сравнивает выгруженную таблицу (TSV) с эталоном кадра корпуса (#262)"
    mainClass.set("com.point.core.flow.TableScoreCliKt")
    classpath = sourceSets["test"].runtimeClasspath
    argumentProviders.add {
        listOf("table", "expected", "report").map { project.findProperty(it) as? String ?: "" }
    }
}

tasks.register<JavaExec>("scoreCorpus") {
    group = "verification"
    description = "Считает долю кадров корпуса, где действие готово без правок (#262)"
    mainClass.set("com.point.core.flow.CorpusScoreCliKt")
    classpath = sourceSets["test"].runtimeClasspath
    argumentProviders.add {
        listOf("run", "frames", "report").map { project.findProperty(it) as? String ?: "" }
    }
}

tasks.register<JavaExec>("reconcileTables") {
    group = "verification"
    description = "Сводит несколько прочтений одной таблицы в одну (#346)"
    mainClass.set("com.point.core.flow.ReconcileCliKt")
    classpath = sourceSets["test"].runtimeClasspath
    argumentProviders.add {
        listOf("inputs", "out").map { project.findProperty(it) as? String ?: "" }
    }
}
