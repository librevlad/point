package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingDoubtTest {

    private val akt = """
        | № | Найменування | Од. вим | Кі-ть | Ціна | Сума ПДВ | Сума |
        | --- | --- | --- | --- | --- | --- | --- |
        | 1 | Емаль Ролакс ПФ-115 Жовта 0,9кг | шт | 1 | 92,90 | -- | 92,90 |
        | 2 | Емаль Ролакс ПФ-115 Світло-зелена 2,8кг | шт | 6 | 152,90 | -- | 917,40 |
        | 3 | Емаль Ролакс ПФ-115 Яскраво-блакитна 2,8кг | шт | 6 | 144,90 | -- | 869,40 |
        | 4 | Емаль Ролакс ПФ-115 Біла 2,8кг | шт | 8 | 160,90 | -- | 1287,20 |
        | 5 | Розчинник Уайт-Спірit ХімРезерв 1л | шт | 1 | 49,90 | -- | 49,90 |
        | 6 | Кисть флейцова Чернівці (14х60мм) | шт | 3 | 11,90 | -- | 35,70 |
        | 7 | Кисть флейцова Чернівці (14х50мм) | шт | 3 | 10,90 | -- | 32,70 |
        | 8 | Валик Мікрофібра 8*48*250мм | шт | 6 | 39,90 | -- | 239,40 |
        | 9 | Ручка (8х250мм) | шт | 4 | 39,90 | -- | 159,60 |
        Разом з ПДВ 3684,20
    """.trimIndent()

    @Test
    fun `сошедшийся итог сомнения не вызывает`() {
        assertNull("настоящий акт объявлен подозрительным", totalMismatch(akt))
    }

    @Test
    fun `итог не сошёлся — человеку сказано, на сколько именно`() {

        val broken = akt.replace("| 1287,20 |", "| 287,20 |")

        val doubt = totalMismatch(broken)

        assertTrue("подмена суммы прошла молча", doubt != null)
        assertTrue("не названо, сколько дают строки: ${doubt?.what}", doubt!!.what.contains("2684,20"))
        assertTrue("не названо, что написано в итоге: ${doubt.what}", doubt.what.contains("3684,20"))
    }

    @Test
    fun `пробелы в разрядах не считаются расхождением`() {

        assertNull(totalMismatch(akt.replace("3684,20", "3 684,20")))
    }

    @Test
    fun `без строки итога проверка молчит, а не кричит`() {

        assertNull(totalMismatch(akt.substringBefore("Разом з ПДВ")))
    }

    @Test
    fun `на двух строках итог не судим — судить нечего`() {
        val tiny = """
            | 1 | Ручка | шт | 1 | 10,00 |
            | 2 | Папір | шт | 1 | 20,00 |
            Разом 999,00
        """.trimIndent()

        assertNull(totalMismatch(tiny))
    }

    @Test
    fun `слово из двух алфавитов названо поимённо`() {

        val mixed = mixedScriptWords(akt)

        assertEquals(listOf("Спірit"), mixed)
    }

    @Test
    fun `слова на одном алфавите подозрений не вызывают`() {
        val clean = "iPhone UA Ролакс ПФ-115 Чернівці Total 3684,20"

        assertTrue("оболгано честное слово: ${mixedScriptWords(clean)}", mixedScriptWords(clean).isEmpty())
    }

    @Test
    fun `самое опасное сомнение идёт первым`() {

        val broken = akt.replace("| 1287,20 |", "| 287,20 |")

        val doubts = readingDoubts(broken)

        assertTrue("сомнений не нашлось вовсе", doubts.isNotEmpty())
        assertTrue("итог не первым: ${doubts.first().what}", doubts.first().what.startsWith("итог не сошёлся"))
    }

    @Test
    fun `чистый текст без таблицы не вызывает ни одного сомнения`() {
        val letter = "Доброго дня! Надсилаю акт на підпис. З повагою, Кусик О.Б."

        assertTrue("оболган обычный текст: ${readingDoubts(letter)}", readingDoubts(letter).isEmpty())
    }
}
