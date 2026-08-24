package com.point.checks

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Корень проекта находят поиском, а не отсчётом уровней (#1301).
 *
 * Путь вида `../..` верен ровно для той глубины, на которой файл написали. Перенесли файл —
 * и тест либо не находит то, что читал, либо, если по новому пути что-то лежит, читает чужое
 * и остаётся зелёным. Так и вышло в #1293: `File("../..")` работал, пока проверка лежала в
 * `core/flow/src/test`, и перестал в ту секунду, когда она оттуда уехала.
 *
 * Поэтому в тестах каждого модуля корень объявлен один раз — в своём `Repo.kt`, поиском вверх
 * до `settings.gradle.kts`, — и все зовут его.
 */
class RootIsFoundNotCountedTest {

    private val testRoots = listOf(
        "app/src/test", "app/src/testDebug",
        "checks/src/test", "core/flow/src/test", "core/ui/src/test", "core/ui/src/testDebug",
        "data/src/test", "desktop/src/test", "executors/src/test",
    )

    private fun testSources(): List<File> = testRoots
        .map { File(repo, it) }
        .filter { it.isDirectory }
        .flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.toList() }

        // Свой же пример правила — не путь: проверка не считает саму себя.
        .filterNot { it.name == "RootIsFoundNotCountedTest.kt" }

    @Test
    fun `путь к чужому каталогу не набран точками вверх`() {
        val counted = Regex("""File\(\s*"\.\.""")

        val guilty = testSources()
            .filter { counted.containsMatchIn(it.readText()) }
            .map { it.relativeTo(repo).invariantSeparatorsPath }

        assertTrue(
            "путь верен только для сегодняшней глубины файла — зовите `repo` из Repo.kt:\n" +
                guilty.joinToString("\n"),
            guilty.isEmpty(),
        )
    }

    @Test
    fun `корень объявлен один раз на модуль`() {
        val declaring = testSources()
            .filter { it.readText().contains("\"settings.gradle.kts\").isFile") }
            .map { it.relativeTo(repo).invariantSeparatorsPath }

        val strangers = declaring.filterNot { it.endsWith("/Repo.kt") }

        assertTrue("корень найден на месте, а не в Repo.kt своего модуля: $strangers", strangers.isEmpty())
        assertTrue("Repo.kt не нашёлся ни в одном модуле — сломан поиск, а не проект", declaring.isNotEmpty())
    }
}
