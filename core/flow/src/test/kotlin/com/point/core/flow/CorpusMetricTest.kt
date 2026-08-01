package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Метрика #262: доля кадров, где действие готово без правок. Число нельзя подделать
 * заполненностью полей, и корпус нельзя молча сузить до удобных кадров.
 */
class CorpusMetricTest {

    private fun case(frame: String, action: String, vararg facts: Pair<String, String>) =
        CorpusCase(frame, action, facts.toMap())

    @Test
    fun `готово — когда критическое поле действия прочитано`() {
        val score = scoreCorpus(
            listOf(
                case("11", "track-parcel", META_ENTITY_TRACK to "20 4514 9154 9395"),
                case("13", "track-parcel", META_GRAPH_ROLE_PREFIX + "carrier" to "Нова Пошта"),
            ),
        )

        assertEquals(listOf("11"), score.ready)
        assertEquals(listOf("13"), score.notReady)
        assertEquals(0.5, score.share!!, 0.001)
    }

    @Test
    fun `кадр без схемы не идёт в знаменатель, но назван поимённо`() {
        val score = scoreCorpus(
            listOf(
                case("11", "track-parcel", META_ENTITY_TRACK to "20 4514 9154 9395"),
                case("06", "extract-table"),
                case("09", "meter-reading"),
            ),
        )

        assertEquals(1, score.scored)
        assertEquals(listOf("06", "09"), score.unscored)
        assertEquals(1.0, score.share!!, 0.001)
    }

    @Test
    fun `измерять нечего — честный null, а не сто процентов`() {
        val score = scoreCorpus(listOf(case("06", "extract-table")))

        assertNull(score.share)
        assertTrue(score.ready.isEmpty() && score.notReady.isEmpty())
    }

    @Test
    fun `полнота полей числа не делает — важны только критические`() {
        // Перевозчик, дата и адрес прочитаны, трека нет: действие не готово, и точка.
        val score = scoreCorpus(
            listOf(
                case(
                    "13", "track-parcel",
                    META_GRAPH_ROLE_PREFIX + "carrier" to "Нова Пошта",
                    META_ENTITY_PREFIX + "date" to "29.07",
                    META_ENTITY_PREFIX + "address" to "Відділення №9",
                ),
            ),
        )

        assertEquals(listOf("13"), score.notReady)
        assertEquals(0.0, score.share!!, 0.001)
    }
}
