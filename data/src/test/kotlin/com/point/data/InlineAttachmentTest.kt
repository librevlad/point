package com.point.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineAttachmentTest {

    @Test
    fun `эталонная ведомость уезжает целиком — пиксели больше не повод ужимать`() {
        assertFalse(oversizedForModel(bytes = 3_200_000))
    }

    @Test
    fun `кадр в пределах бюджета не трогаем — ни пикселя, ни перекодировки`() {
        assertFalse(oversizedForModel(bytes = 900_000))
    }

    @Test
    fun `тяжёлый файл ужимается — вес остался единственным поводом`() {
        assertTrue(oversizedForModel(bytes = 9L * 1024 * 1024))
    }

    @Test
    fun `тяжёлая ведомость вписалась бы в 3072 на 2304`() {
        assertEquals(3072 to 2304, fittedSize(4000, 3000))
    }

    @Test
    fun `пропорции не плывут на вертикальном кадре`() {
        assertEquals(2304 to 3072, fittedSize(3000, 4000))
    }

    @Test
    fun `кадр мельче цели ужатия не растягивается`() {
        assertEquals(3000 to 2000, fittedSize(3000, 2000))
    }

    @Test
    fun `вырожденный размер не роняет расчёт`() {
        assertEquals(0 to 0, fittedSize(0, 0))
        assertEquals(1 to 1, fittedSize(1, 1))
    }

    @Test
    fun `жёсткий потолок инлайна остался прежним`() {
        assertEquals(15L * 1024 * 1024, MAX_INLINE_BYTES)
    }
}
