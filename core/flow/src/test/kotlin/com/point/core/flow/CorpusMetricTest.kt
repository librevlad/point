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
                case("18", "reply-to-letter"),
            ),
        )

        assertEquals(1, score.scored)
        assertEquals(listOf("06", "18"), score.unscored)
        assertEquals(1.0, score.share!!, 0.001)
    }

    // --- #262: шесть кадров вышли из unscored, потому что у их действий появились схемы ---

    @Test
    fun `кадры счётчика и маршрута больше не за скобками — их есть чем считать`() {
        val score = scoreCorpus(
            listOf(
                case("09", "meter-reading", META_ENTITY_METER to "154", META_ENTITY_METER_UNIT to "м³"),
                case("17", "meter-reading", META_ENTITY_METER to "20842", META_ENTITY_METER_UNIT to "кВт·ч"),
                case("12", "route", META_ENTITY_PREFIX + "address" to "Відділення №9, Олексіївка"),
                case("22", "route", META_ENTITY_GEO to "50.4501, 30.5234"),
            ),
        )

        assertTrue("измеримое не имеет права оставаться unscored", score.unscored.isEmpty())
        assertEquals(listOf("09", "17", "12", "22"), score.ready)
        assertEquals(1.0, score.share!!, 0.001)
    }

    @Test
    fun `непрочитанное показание — неготовый кадр, а не исчезнувший`() {
        // Кадр 15 корпуса: фото водомера, на табло цифры без единицы рядом. Правило формы
        // молчит — и метрика обязана сказать «не готово», а не спрятать кадр в unscored.
        val score = scoreCorpus(
            listOf(
                case("15", "meter-reading", META_ENTITY_PREFIX + "date" to "29.07"),
                case("14", "route", META_ENTITY_PREFIX + "date" to "18:24"),
            ),
        )

        assertEquals(listOf("15", "14"), score.notReady)
        assertTrue(score.unscored.isEmpty())
        assertEquals(0.0, score.share!!, 0.001)
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
