package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Экспорт не сглаживает неуверенность, а переносит её в документ (#267). Чистый, уверенно
 * выглядящий .docx из рукописи — документ, который тихо врёт: человек не отличит прочитанное
 * от угаданного.
 */
class ExportUncertaintyTest {

    @Test
    fun `цифры рукописи помечаются всегда — гарантия #236 там неисполнима`() {
        // На рукописи символы предложила зрячая модель: правило «модель не трогает цифры»
        // структурно неисполнимо, и честная пометка — единственная замена.
        assertTrue(uncertainInExport("Итого 1450 грн", ReadingMode.HANDWRITTEN))
        assertTrue(uncertainInExport("12.05", ReadingMode.HANDWRITTEN))
    }

    @Test
    fun `слова рукописи без цифр не помечаются — иначе метка перестаёт значить что-либо`() {
        assertFalse(uncertainInExport("Конспект лекции", ReadingMode.HANDWRITTEN))
    }

    @Test
    fun `печатные цифры не помечаются — их читал движок, а не модель`() {
        assertFalse(uncertainInExport("Итого 1450 грн", ReadingMode.PRINTED))
    }

    @Test
    fun `маркер модели переносится в документ на любом режиме`() {
        assertTrue(uncertainInExport("Гречка⚠", ReadingMode.PRINTED))
        assertTrue(uncertainInExport("Гречка⚠", ReadingMode.UNKNOWN))
    }

    @Test
    fun `неизвестный режим цифры не метит — не знаем не значит угадали`() {
        assertFalse(uncertainInExport("1450", ReadingMode.UNKNOWN))
    }

    @Test
    fun `блок по умолчанию уверен — пометка это решение, а не фон`() {
        assertFalse(DocBlock("текст", DocStyle.NORMAL).uncertain)
    }

    // -- #247: правка ручкой и происхождение всего документа --

    @Test
    fun `исправление ручкой помечается на любом режиме — какая версия верна, решает человек`() {
        assertTrue(uncertainInExport("Крупа гречневая ~~53~~ 40", ReadingMode.PRINTED))
        assertTrue(uncertainInExport("~~отменено~~", ReadingMode.UNKNOWN))
        assertFalse("обычный текст с тире правкой не считается", uncertainInExport("53 — 40", ReadingMode.PRINTED))
    }

    @Test
    fun `документ с рукописи говорит о своём происхождении первой строкой`() {
        val read = listOf(
            DocBlock("Недельный цикл", DocStyle.TITLE),
            DocBlock("Крупа 1450", DocStyle.NORMAL, uncertain = true),
        )

        val out = read.withReadingNote(ReadingMode.HANDWRITTEN)

        assertEquals("строка стоит раньше заголовка", 3, out.size)
        assertTrue(out.first().text.startsWith(HANDWRITTEN_NOTE))
        assertTrue("пометки в документе есть — о них и сказано", out.first().text.contains(HANDWRITTEN_MARKS))
        assertFalse("сама строка — наши слова, а не прочитанное", out.first().uncertain)
        assertEquals(read, out.drop(1))
    }

    @Test
    fun `пометок в документе нет — про жёлтое молчим, иначе строка врёт`() {
        val out = listOf(DocBlock("Конспект лекции", DocStyle.NORMAL)).withReadingNote(ReadingMode.HANDWRITTEN)

        assertEquals(HANDWRITTEN_NOTE, out.first().text)
    }

    @Test
    fun `печать и неизвестность документ не подписывают — подпись не про качество, а про рукопись`() {
        val read = listOf(DocBlock("Итого 1450", DocStyle.NORMAL))

        assertEquals(read, read.withReadingNote(ReadingMode.PRINTED))
        assertEquals(read, read.withReadingNote(ReadingMode.UNKNOWN))
        assertEquals(emptyList<DocBlock>(), emptyList<DocBlock>().withReadingNote(ReadingMode.HANDWRITTEN))
    }
}
