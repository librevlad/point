package com.point.desktop

import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.META_READ_TEXT
import com.point.core.model.Feature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Прочитанное на телефоне остаётся прочитанным на компьютере (#811).
 *
 * Живой прогон 11.08.2026: снимок, прочитанный на телефоне, приезжал на компьютер
 * «непрочитанным» — там первым действием предлагали «Распознать текст». Знание ехало
 * ссылкой `ocr.text.ref` на scratch-файл телефона, а на той стороне такой ссылки нет и быть
 * не может.
 */
class KnowledgeTravelsWithObjectTest {

    @get:Rule val temp = TemporaryFolder()

    private fun inbox() = Inbox(temp.newFolder("inbox"))

    private val read = "X Detach tab · Fit to window size · 11/11/2025"

    @Test
    fun `приехавший текст становится знанием, а не строкой метаданных`() {
        val item = inbox().receive(
            name = "снимок.png",
            mime = "image/png",
            meta = mapOf(META_READ_TEXT to read),
            source = "PNG".byteInputStream(),
        )

        assertTrue("компьютер знает, что текст есть", item.obj.state.has(Feature.HAS_TEXT))

        val kept = item.obj.metadata[META_OCR_TEXT_REF]
        assertTrue("текст лежит файлом рядом", kept != null && File(kept).isFile)
        assertEquals(read, File(kept!!).readText())
    }

    @Test
    fun `значение в метаданных не остаётся — знание живёт своим местом`() {
        val item = inbox().receive(
            name = "снимок.png",
            mime = "image/png",
            meta = mapOf(META_READ_TEXT to read),
            source = "PNG".byteInputStream(),
        )

        assertFalse(item.obj.metadata.containsKey(META_READ_TEXT))
    }

    @Test
    fun `объект без прочитанного текста приезжает как раньше`() {
        val item = inbox().receive(
            name = "снимок.png",
            mime = "image/png",
            meta = mapOf("name" to "снимок.png"),
            source = "PNG".byteInputStream(),
        )

        assertFalse("текста не было — и признака нет", item.obj.state.has(Feature.HAS_TEXT))
        assertFalse(item.obj.metadata.containsKey(META_OCR_TEXT_REF))
    }
}
