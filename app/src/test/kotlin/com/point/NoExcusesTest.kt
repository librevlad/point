package com.point

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Экран отвечает на вопрос, а не оправдывается (#575).
 *
 * Владелец: «все экраны пропитаны паранойей из-за того что ты в тексты льешь таски». Продукт
 * защищался там, где его никто не обвинял, — и от постоянных оговорок про «наружу» казалось,
 * что наружу уходит всё.
 *
 * Живёт в `:app` (#1293): проверка читает исходники `:app`, `:core:ui`, `:data` и `:executors`
 * — всё это `:app` и собирает. В `:core:flow` она читала модули выше себя, и оговорка,
 * написанная в `:executors`, роняла тест самого нижнего модуля.
 */
class NoExcusesTest {

    private val product = listOf("core/flow/src/main", "core/ui/src/main", "app/src/main", "data/src/main", "executors/src/main")

    /**
     * Объяснения уровней приватности живут в настройках — там человек и пришёл выбирать, и
     * рассказ о правилах уместен. Проверяется всё остальное: момент действия и первые экраны.
     */
    private val explaining = setOf("CloudPrivacy.kt")

    private fun speech(): List<Pair<String, String>> = product
        .map { File(repo, it) }
        .filter { it.isDirectory }
        .flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.toList() }
        .filterNot { it.name in explaining }
        .flatMap { file -> file.readLines().map { file.name to it } }

    @Test fun `в момент действия не пересказываются правила продукта`() {
        val excuses = listOf(
            "в настройках выбрано",
            "только по вашему решению",
            "письменно обещал",
        )

        val guilty = speech().filter { (_, line) ->
            line.trimStart().startsWith("//").not() && excuses.any { it in line }
        }

        assertTrue(
            "продукт оправдывается там, где его не обвиняли:\n" +
                guilty.joinToString("\n") { it.first + ": " + it.second.trim() },
            guilty.isEmpty(),
        )
    }

    @Test fun `дата замера не выходит человеку на глаза`() {
        val guilty = speech().filter { (_, line) ->
            line.trimStart().startsWith("//").not() && Regex("""проверено \d{2}\.\d{4}""").containsMatchIn(line)
        }

        assertTrue(
            "инвентарь читается тревогой:\n" + guilty.joinToString("\n") { it.first + ": " + it.second.trim() },
            guilty.isEmpty(),
        )
    }
}
