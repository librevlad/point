package com.point.core.flow

import com.point.core.model.ObjectKind
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
        val said = readerFailure("decode failed", ObjectKind.IMAGE)

        assertTrue(said.contains("не открылся"))
        assertFalse("латиницы в лице продукта нет", said.any { it in 'a'..'z' || it in 'A'..'Z' })
    }

    @Test
    fun `оборванное по времени чтение названо временем`() {
        assertEquals("Чтение заняло слишком долго и оборвалось", readerFailure("read timed out", ObjectKind.IMAGE))
    }

    @Test
    fun `слишком большой снимок назван размером`() {
        assertEquals("Снимок слишком большой, чтобы его прочитать", readerFailure("413 payload too large", ObjectKind.IMAGE))
    }

    @Test
    fun `непонятная причина всё равно человеческая`() {
        val said = readerFailure("java.lang.IllegalStateException at Foo.kt-42", ObjectKind.IMAGE)

        assertFalse(said.contains("Exception"))
        assertTrue(said.contains("не открылся"))
    }

    @Test
    fun `без причины тоже есть что сказать`() {
        assertTrue(readerFailure(null, ObjectKind.IMAGE).isNotBlank())
        assertTrue(readerFailure("", ObjectKind.IMAGE).isNotBlank())
    }

    // ---- #1033: отказ говорит о том объекте, который человек принёс, а не о картинке. ----

    @Test
    fun `битый PDF назван словами про PDF, а не про изображение`() {
        val said = readerFailure("decode failed", ObjectKind.PDF)

        assertTrue(said.contains("не открылся"))
        assertTrue("вид объекта назван", said.contains("PDF"))
        assertFalse("слов про картинку у PDF нет", said.contains("изображени"))
    }

    @Test
    fun `битый снимок по-прежнему назван словами про изображение`() {
        val said = readerFailure("corrupt stream", ObjectKind.IMAGE)

        assertTrue(said.contains("изображение"))
        assertFalse(said.contains("PDF"))
    }

    @Test
    fun `слово по виду не зависит от того, как именно ридер назвал поломку`() {
        val kinds = listOf(ObjectKind.PDF, ObjectKind.IMAGE)
        val reasons = listOf(null, "", "decode failed", "not an image", "malformed", "java.lang.IllegalStateException")

        kinds.forEach { kind ->
            val saidForKind = reasons.map { readerFailure(it, kind) }.toSet()
            assertEquals("у вида $kind одно слово отказа на все поломки", 1, saidForKind.size)
        }
        assertFalse("PDF и изображение объяснены разными словами", readerFailure(null, ObjectKind.PDF) == readerFailure(null, ObjectKind.IMAGE))
    }

    @Test
    fun `вид без своего слова получает факт поломки без догадки о том, чем файл не является`() {
        val said = readerFailure("decode failed", ObjectKind.ZIP)

        assertTrue(said.contains("не открылся"))
        assertFalse(said.contains("изображени"))
        assertFalse(said.contains("PDF"))
        assertFalse("догадки «это не …» нет", said.contains("это не"))
    }

    @Test
    fun `время и размер не зависят от вида объекта`() {
        assertEquals(readerFailure("read timed out", ObjectKind.IMAGE), readerFailure("read timed out", ObjectKind.PDF))
        assertEquals(readerFailure("413 payload too large", ObjectKind.IMAGE), readerFailure("413 payload too large", ObjectKind.PDF))
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
        assertEquals("В документе нет ни одной страницы", readerFailure(READER_NO_PAGES, ObjectKind.PDF))
    }

    @Test
    fun `документ без страниц — это про сам объект`() {
        assertTrue(readerFailureIsFatal(READER_NO_PAGES))
    }
}
