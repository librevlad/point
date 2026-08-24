plugins {
    alias(libs.plugins.kotlin.jvm)
}

/**
 * Проверки, которые смотрят на весь проект (#1293).
 *
 * Модуль намеренно пуст: ни одного `project(...)`, ни одной строки продуктового кода. Всё,
 * что здесь живёт, читает исходники и тесты текстом, поэтому стрелки модулей эти проверки
 * не трогают. Дом им нужен был не ради зависимостей, а ради адреса падения: пока такая
 * проверка лежала в `:core:flow`, строка, написанная в тестах компьютера, роняла ядро, и
 * человек шёл искать ошибку не туда.
 *
 * Проверка, которая читает только то, что её собственный модуль и так собирает, живёт в
 * своём модуле и сюда не переезжает.
 */
kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}

/**
 * Входы задачи — исходники и тесты всего проекта: без этого Gradle считал бы проверки
 * «уже пройденными» ровно тогда, когда нарушение появилось в чужом модуле.
 */
tasks.test {
    inputs.files(
        fileTree(rootProject.layout.projectDirectory) {
            // Файлы сборки тоже читаются: проверка «общий каталог подключён обеими сторонами»
            // смотрит на build.gradle.kts телефона и компьютера.
            include("**/src/*/kotlin/**/*.kt", "**/build.gradle.kts")
            exclude("**/build/**")
        },
    )
        .withPropertyName("sourcesOfAllModules")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    inputs.file(rootProject.layout.projectDirectory.file("tools/cemented-wording.txt"))
        .withPropertyName("cementedWordingList")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
