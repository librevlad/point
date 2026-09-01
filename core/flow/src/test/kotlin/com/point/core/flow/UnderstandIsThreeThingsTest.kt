package com.point.core.flow

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Понять» разложено на три части: спросить, разобрать, рассудить (#835).
 *
 * `UnderstandAction.kt` был 772 строки: сборка запроса к модели, вызов, чтение ответа,
 * судейство кандидатов, роли, слияние знания. Правило «нет в тексте — нет знания» (#809)
 * правилось там же, где собирается текст запроса, и каждая такая правка требовала прочитать
 * все 772 строки, чтобы понять, что ещё заденешь.
 */
class UnderstandIsThreeThingsTest {

    private val dir = File("src/main/kotlin/com/point/core/flow")

    @Test
    fun `у каждой части своё имя`() {
        listOf("UnderstandAsk.kt", "UnderstandAction.kt", "UnderstandJudge.kt").forEach {
            assertTrue("части нет: $it", File(dir, it).isFile)
        }
    }

    @Test
    fun `судейство не знает ни о сети, ни о файлах`() {
        val judge = File(dir, "UnderstandJudge.kt").readText()

        listOf("LlmClient", "withContext(Dispatchers", "java.io.File", "HttpURLConnection").forEach {
            assertTrue("в судейство пробралась работа с миром: $it", !judge.contains(it))
        }
    }

    @Test
    fun `сборка запроса не судит найденное`() {
        val ask = File(dir, "UnderstandAsk.kt").readText()

        assertTrue("судейство вернулось в сборку запроса", !ask.contains("fun judgeFields("))
        assertTrue("сборки запроса нет вовсе", ask.contains("fun understandPrompt("))
    }

    @Test
    fun `ни одна часть не разрастается снова`() {
        val big = listOf("UnderstandAsk.kt", "UnderstandAction.kt", "UnderstandJudge.kt")
            .map { File(dir, it) }
            .filter { it.readLines().size > 620 }
            .map { it.name + ":" + it.readLines().size }

        assertTrue("часть «Понять» снова стала складом: $big", big.isEmpty())
    }
}
