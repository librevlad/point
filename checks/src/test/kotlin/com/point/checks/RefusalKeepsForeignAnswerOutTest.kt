package com.point.checks

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Чужой ответ и код протокола не попадают в текст отказа (#1236).
 *
 * `FlowViewModel` показывает `message` исключения как есть, поэтому всё, что клиент положит
 * в текст, человек прочтёт на экране. Раньше правило держалось на ручном списке файлов, и
 * десятый клиент в него просто не попадал.
 *
 * Живёт в `:checks` (#1293): проверка читает исходники `:core:flow`, `:data` и `:desktop`.
 * Слова самих отказов проверяются тестами `:core:flow`, где они объявлены.
 */
class RefusalKeepsForeignAnswerOutTest {

    @Test
    fun `ни один клиент не кладёт код протокола и чужой ответ в текст исключения`() {
        val banned = listOf("HTTP ${'$'}", "res.body.take(", "res.body.substring(")

        val guilty = listOf("core/flow/src/main", "data/src/main", "desktop/src/main")
            .map { File(repo, it) }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.toList() }
            .flatMap { file ->
                val text = file.readText()
                banned.filter { it in text }.map { "${file.name}: $it" }
            }

        assertTrue("чужой ответ или код протокола в словах отказа: $guilty", guilty.isEmpty())
    }
}
