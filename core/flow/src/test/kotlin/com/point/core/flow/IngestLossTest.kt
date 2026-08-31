package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Потерянное при приёме называется человеку сразу (#1304).
 *
 * Он передал два фото — вышла одна страница, и узнавал он об этом страницей, которой нет.
 * Набор при этом принимается тем, что в нём открылось: один негодный файл не отменяет
 * остальных.
 *
 * Сторож охраняет обещание, а не формулировку: слово о потере называет оба числа и молчит
 * там, где терять было нечего.
 */
class IngestLossTest {

    @Test
    fun `потери не было — сказать нечего`() {
        assertEquals(emptyMap<String, String>(), ingestLoss(asked = 3, opened = 3))
        assertNull(ingestLossNote(mapOf("name" to "Набор (3)")))
    }

    @Test
    fun `слово о потере называет оба числа`() {
        val note = ingestLossNote(ingestLoss(asked = 3, opened = 2))

        assertTrue("потеря не названа", note != null)
        assertTrue("не сказано, сколько просили: $note", note!!.contains("3"))
        assertTrue("не сказано, сколько открылось: $note", note.contains("2"))
    }

    @Test
    fun `слово о потере согласовано с числом`() {
        val one = ingestLossNote(ingestLoss(asked = 2, opened = 1))
        val two = ingestLossNote(ingestLoss(asked = 3, opened = 2))
        val twenty = ingestLossNote(ingestLoss(asked = 21, opened = 20))

        assertTrue("«открылось 1» вместо «открылся 1»: $one", one!!.contains("открылся"))
        assertTrue("«открылся 2» вместо «открылось 2»: $two", two!!.contains("открылось"))
        assertTrue("«из 21 файлов» вместо «из 21 файла»: $twenty", twenty!!.contains("21 файла"))
    }
}
