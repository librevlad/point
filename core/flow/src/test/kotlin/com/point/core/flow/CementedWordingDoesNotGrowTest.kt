package com.point.core.flow

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Цемент формулировок не растёт (#584).
 *
 * Владелец 05.08.2026: набор тестов существует ради продукта, а не ради себя. Две трети тестов
 * сверяли точную строку экрана — и одиннадцать правок текста роняли пять тестов, из которых
 * четыре просто цементировали формулировку.
 *
 * Но резать по признаку «сверяет строку» нельзя: пятый поймал настоящую ошибку. Поэтому здесь
 * не запрет, а замок: сколько таких проверок есть сегодня — столько и останется. Каждая новая
 * обязана быть либо сторожем обещания (проверяет отсутствие жаргона, наличие смысла), либо
 * контрактом данных, а не текстом для человека.
 *
 * Разбор существующих 826 — работа самой карточки; этот тест не даёт куче расти, пока она идёт.
 */
class CementedWordingDoesNotGrowTest {

    private val roots = listOf(
        "app/src/test", "app/src/testDebug",
        "core/flow/src/test", "core/ui/src/test", "core/ui/src/testDebug",
        "data/src/test", "executors/src/test", "desktop/src/test",
    )

    /** Замер 10.08.2026. Уменьшать эту цифру можно и нужно; увеличивать — нет. */
    private val measured = 826

    private val exactHumanString = Regex("""assertEquals\(\s*"([^"]{6,})"""")

    @Test
    fun `новых проверок точной строки экрана не появляется`() {
        val cemented = roots
            .map { File("../../$it") }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.toList() }
            .sumOf { file ->
                exactHumanString.findAll(file.readText())
                    .count { it.groupValues[1].any { ch -> ch in 'а'..'я' || ch in 'А'..'Я' } }
            }

        assertTrue(
            "проверок точной строки стало больше ($cemented против $measured): " +
                "новый тест обязан охранять обещание, а не цементировать формулировку",
            cemented <= measured,
        )
    }
}
