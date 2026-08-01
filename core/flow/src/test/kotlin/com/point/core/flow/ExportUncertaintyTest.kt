package com.point.core.flow

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
}
