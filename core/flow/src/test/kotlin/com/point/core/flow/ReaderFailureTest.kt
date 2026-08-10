package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #686 (охота 2026-08-10): человек читал на экране «не удалось прочитать страницу —
 * decode failed». Чужой технический текст в лицо продукта не выходит.
 */
class ReaderFailureTest {

    @Test
    fun `битый файл назван своими словами`() {
        val said = readerFailure("decode failed")

        assertTrue(said.contains("не открылся"))
        assertFalse("латиницы в лице продукта нет", said.any { it in 'a'..'z' || it in 'A'..'Z' })
    }

    @Test
    fun `оборванное по времени чтение названо временем`() {
        assertEquals("Чтение заняло слишком долго и оборвалось", readerFailure("read timed out"))
    }

    @Test
    fun `слишком большой снимок назван размером`() {
        assertEquals("Снимок слишком большой, чтобы его прочитать", readerFailure("413 payload too large"))
    }

    @Test
    fun `непонятная причина всё равно человеческая`() {
        val said = readerFailure("java.lang.IllegalStateException at Foo.kt-42")

        assertFalse(said.contains("Exception"))
        assertTrue(said.contains("не открылся"))
    }

    @Test
    fun `без причины тоже есть что сказать`() {
        assertTrue(readerFailure(null).isNotBlank())
        assertTrue(readerFailure("").isNotBlank())
    }

    // ---- #685: только «сам объект испорчен» закрывает путь наружу насовсем. ----

    @Test
    fun `битый файл и не-изображение — это про сам объект`() {
        assertTrue(readerFailureIsFatal("decode failed"))
        assertTrue(readerFailureIsFatal("not an image"))
        assertTrue(readerFailureIsFatal("CORRUPT stream"))
    }

    @Test
    fun `долгое чтение и большой снимок — про попытку сейчас, не про объект`() {
        assertFalse(readerFailureIsFatal("read timed out"))
        assertFalse(readerFailureIsFatal("413 payload too large"))
    }

    @Test
    fun `движок не завёлся или бросил исключение — тоже не про объект`() {
        assertFalse(readerFailureIsFatal("engine init failed"))
        assertFalse(readerFailureIsFatal("error: OutOfMemoryError"))
        assertFalse(readerFailureIsFatal(null))
    }

    // ---- #570: документ без единой страницы — не «битый файл», а названная пустота. ----

    @Test
    fun `документ без страниц назван пустотой, а не поломкой`() {
        assertEquals("В документе нет ни одной страницы", readerFailure(READER_NO_PAGES))
    }

    @Test
    fun `документ без страниц — это про сам объект`() {
        assertTrue(readerFailureIsFatal(READER_NO_PAGES))
    }
}
