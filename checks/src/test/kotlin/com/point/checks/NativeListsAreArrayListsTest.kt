package com.point.checks

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Список имён, который OpenCV отдаёт нативному коду, — настоящий `ArrayList` (#1044).
 *
 * `Net.forward(outs, names)` передаёт `names` в JNI без перекладки, и нативная сторона зовёт
 * у него методы `java.util.ArrayList`. Котлиновский `listOf` — другой класс: на телефоне это
 * падение всего Point (CheckJNI, SIGABRT на кадре 18 корпуса, 24.10.2026), а в release без
 * CheckJNI — поведение, которое никто не обещал. На компьютере тот же код молчал.
 *
 * Живёт в `:checks`: зрение собирают и телефон, и компьютер, а падает оно только на первом.
 */
class NativeListsAreArrayListsTest {

    @Test
    fun `в forward сети имена выходов не передаются через listOf`() {
        val guilty = listOf("executors/src", "desktop/src/main")
            .map { File(repo, it) }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.toList() }
            .flatMap { file ->
                code(file.readText()).lines().withIndex()
                    .filter { (_, line) -> FORWARD.containsMatchIn(line) }
                    .map { (i, _) -> "${file.relativeTo(repo)}:${i + 1}" }
            }

        assertTrue("сеть получает неизменяемый список имён: $guilty", guilty.isEmpty())
    }

    private companion object {
        val FORWARD = Regex("""\.forward\([^)]*\blistOf\(""")
    }
}
