package com.point.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Предел отправляемого моделям кадра — на числах эталонной ведомости владельца (фото 4000×3000,
 * 3.2 МБ). Сам разбор пикселей живёт в Android-коде и на JVM не запускается, поэтому проверяется
 * то, что решает судьбу байтов, — решение «ужимать или нет» и во что именно.
 */
class InlineAttachmentTest {

    @Test
    fun `эталонная ведомость крупнее того, на что модель смотрит`() {
        assertTrue(oversizedForModel(longEdgePx = 4000, bytes = 3_200_000))
        // 4000×3000 → 3072×2304 — предел зоркого читателя (Gemini), а не самого строгого (Claude).
        assertEquals(3072 to 2304, fittedSize(4000, 3000))
    }

    @Test
    fun `кадр в пределах не трогаем — ни пикселя, ни перекодировки`() {
        assertFalse(oversizedForModel(longEdgePx = 3072, bytes = 900_000))
        assertEquals(3000 to 2000, fittedSize(3000, 2000))
    }

    /** Скрин таблицы PNG'ом влезает по пикселям и не влезает по байтам — перекодировка честна:
     *  ни один пиксель разметки не теряется, а грузить вчетверо больше незачем. */
    @Test
    fun `тяжёлый файл ужимается, даже если по пикселям он в пределе`() {
        assertTrue(oversizedForModel(longEdgePx = 2000, bytes = 9L * 1024 * 1024))
    }

    @Test
    fun `пропорции не плывут на вертикальном кадре`() {
        assertEquals(2304 to 3072, fittedSize(3000, 4000))
    }

    @Test
    fun `вырожденный размер не роняет расчёт`() {
        assertEquals(0 to 0, fittedSize(0, 0))
        assertEquals(1 to 1, fittedSize(1, 1))
    }

    /** Потолок инлайна общий на всех клиентов — раньше константа была скопирована трижды,
     *  и правка предела в одном месте молча расходилась с двумя другими. */
    @Test
    fun `жёсткий потолок инлайна остался прежним`() {
        assertEquals(15L * 1024 * 1024, MAX_INLINE_BYTES)
    }
}
