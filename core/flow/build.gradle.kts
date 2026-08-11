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

    testImplementation(libs.zxing.core)
}

tasks.test {
    inputs.dir(rootProject.layout.projectDirectory.dir("tools/corpus"))
        .withPropertyName("corpusExpectations")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // Сторож цемента формулировок (#584) читает тесты всех модулей и список исключений.
    // Без этого он оставался бы UP-TO-DATE ровно тогда, когда цемент прирос в чужом модуле.
    inputs.files(
        fileTree(rootProject.layout.projectDirectory) {
            include("**/src/test/kotlin/**/*.kt", "**/src/testDebug/kotlin/**/*.kt")
            exclude("**/build/**")
        },
    )
        .withPropertyName("testsOfAllModules")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    inputs.file(rootProject.layout.projectDirectory.file("tools/cemented-wording.txt"))
        .withPropertyName("cementedWordingList")
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
