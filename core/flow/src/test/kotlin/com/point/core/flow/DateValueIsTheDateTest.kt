package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Значение даты — сама дата, а не фраза вокруг неё (#782, решение владельца, вариант B).
 *
 * В списке найденного датами стояли целая фраза с номером акта, интервал одной строкой и
 * обрывок нумерации «4.». Рядом лежали те же дни в чистом виде — список выглядел так,
 * будто один день найден несколько раз в разных обёртках.
 *
 * Правило чтения — одно на все входы знания: модель, ML Kit, regex, focus-область.
 */
class DateValueIsTheDateTest {

    /** Строка из документа, как её отдаёт движок: дата внутри фразы с номером акта. */
    private val inTheAct = "зазначених в Акті від 03.01.2026 № 432/69"

    @Test
    fun `фраза обрезается до даты`() {
        assertEquals(listOf("03.01.2026"), readDates(inTheAct))
    }

    @Test
    fun `интервал даёт два дня, а не одну строку`() {
        assertEquals(
            listOf("05.06.2025 0:00:00", "04.06.2027 23:59:59"),
            readDates("Дійсний з 05.06.2025 0:00:00 по 04.06.2027 23:59:59"),
        )
    }

    @Test
    fun `обрывок нумерации датой не становится`() {
        assertTrue("«4.» прошло как дата: ${readDates("4.")}", readDates("4.").isEmpty())
        assertTrue(readDates("№ 432/69").isEmpty())
        assertTrue(readDates("Оплата 2500").isEmpty())
    }

    @Test
    fun `один и тот же день не возвращается дважды`() {
        assertEquals(listOf("26.04.2026"), readDates("26.04.2026 26.04.2026"))
        assertEquals(listOf("26.04.2026", "28.04.2026"), readDates("26.04.2026 28.04.2026"))
    }

    @Test
    fun `час остаётся при своей дате`() {
        assertEquals(listOf("26.04.2026 20:04"), readDates("26.04.2026 20:04"))
        assertEquals(listOf("01.12.2020 в 11:09"), readDates("01.12.2020 в 11:09"))
        assertEquals(listOf("29.07 до 18:00"), readDates("29.07 до 18:00"))
    }

    /** «Голое время это никогда не дата, это мусор» (#651) — но судят его свои правила. */
    @Test
    fun `время без даты читатель дат не трогает`() {
        assertEquals(listOf("11:09"), readDates("11:09"))
        assertEquals(listOf("завтра о 09:00"), readDates("завтра о 09:00"))
        assertEquals(listOf("вчера"), readDates("вчера"))
    }

    @Test
    fun `словесный месяц остаётся датой`() {
        assertEquals(listOf("15 августа 2026"), readDates("15 августа 2026"))
        assertEquals(listOf("3 січня 2026"), readDates("укладено 3 січня 2026 року"))
    }

    /**
     * Приёмка кадра прогона: шесть строк «Дата» становятся четырьмя днями, и ни одна
     * из них не является фразой.
     */
    @Test
    fun `шесть строк с кадра становятся четырьмя днями`() {
        val onScreen = listOf(
            inTheAct,
            "Дійсний з 05.06.2025 0:00:00 по 04.06.2027 23:59:59",
            "4.",
            "03.01.2026",
            "29.04.2026",
            "05.06.2025 0:00:00",
        )

        val days = onScreen.flatMap(::readDates)
            .mapNotNull { humanDayOf(it)?.toString() }
            .distinct()

        assertEquals(listOf("2026-01-03", "2025-06-05", "2027-06-04", "2026-04-29"), days)
    }

    @Test
    fun `правило формы знания судит дату тем же читателем`() {
        assertFalse("«4.» прошло гейт формы и встало датой", factFits(META_ENTITY_PREFIX + "date", "4."))
        assertTrue(factFits(META_ENTITY_PREFIX + "date", "03.01.2026"))
        assertTrue(factFits(META_ENTITY_PREFIX + "date", "29.07 до 18:00"))
    }

    /** Вход знания через сущности читает дату тем же правилом — движок отдаёт кусок текста. */
    @Test
    fun `сущность-дата приходит уже прочитанной`() {
        val found = plausibleEntities(
            listOf(
                Entity(EntityType.DATE_TIME, "Дійсний з 05.06.2025 0:00:00 по 04.06.2027 23:59:59"),
                Entity(EntityType.DATE_TIME, "4."),
            ),
        )

        assertEquals(listOf("05.06.2025 0:00:00", "04.06.2027 23:59:59"), found.map { it.value })
    }

    /** Подпись — контекст значения: она есть, но значением не является. */
    @Test
    fun `строка документа остаётся подписью, а не значением`() {
        val found = plausibleEntities(listOf(Entity(EntityType.DATE_TIME, inTheAct))).single()

        assertEquals("03.01.2026", found.value)
        assertEquals(inTheAct, found.line)
    }

    @Test
    fun `чистой дате подпись не выдумывается`() {
        val found = plausibleEntities(listOf(Entity(EntityType.DATE_TIME, "03.01.2026"))).single()

        assertTrue("подпись повторяет значение: ${found.line}", found.line == null)
    }

    /** Ключ подписи — аннотация: строкой знания и полем он не становится (граница #782). */
    @Test
    fun `подпись не становится отдельным знанием`() {
        val meta = mapOf(
            META_ENTITY_PREFIX + "date" to "03.01.2026",
            META_ENTITY_PREFIX + "date" + META_LINE_SUFFIX to inTheAct,
        )

        val rows = knowledgeRows(meta)

        assertEquals(1, rows.size)
        assertEquals("03.01.2026", rows.single().value)
        assertEquals(inTheAct, rows.single().said)
        assertTrue("подпись попала в спор", rows.single().disputed.isEmpty())
        assertTrue("подпись попала в «ещё»", rows.single().more.isEmpty())
    }
}
