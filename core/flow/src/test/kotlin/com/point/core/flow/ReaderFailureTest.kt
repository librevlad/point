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

    // ---- #1258: не завёлся наш движок — виноват не файл человека. ----

    @Test
    fun `непонятная причина — про попытку сейчас, а не про файл человека`() {
        val said = readerFailure("java.lang.IllegalStateException at Foo.kt-42", ObjectKind.IMAGE)

        assertFalse(said.contains("Exception"))
        assertFalse("файл человека здесь ни при чём", said.contains("повреждён"))
        assertEquals(READ_NOT_NOW, said)
    }

    @Test
    fun `не завёлся движок и внутренняя ошибка — тоже про попытку`() {
        assertEquals(READ_NOT_NOW, readerFailure("engine init failed", ObjectKind.IMAGE))
        assertEquals(READ_NOT_NOW, readerFailure("error: OutOfMemoryError", ObjectKind.IMAGE))
        assertEquals(READ_NOT_NOW, readerFailure("ppocr build does not match device abi", ObjectKind.PDF))
    }

    /**
     * Две функции одного файла давали противоположные ответы на один вход: словарь говорил
     * «файл повреждён», а годность объекта — «дело не в объекте». Человек шёл переснимать.
     */
    @Test
    fun `слова про поломку файла звучат ровно там, где дело в самом объекте`() {
        val signals = listOf(
            null, "", READER_NOT_DECODED, "not an image", "corrupt stream", READER_NO_PAGES,
            "read timed out", "413 payload too large", "engine init failed",
            "error: OutOfMemoryError", "java.lang.IllegalStateException",
        )

        val emptyDocument = readerFailure(READER_NO_PAGES, ObjectKind.PDF)

        signals.filter { readerFailureIsFatal(it) }.forEach {
            val said = readerFailure(it, ObjectKind.IMAGE)
            assertTrue("«$it» про объект, а сказано «$said»", said == emptyDocument || said.contains("повреждён"))
        }

        // Исключений у правила нет — включая молчание. Кто видел неразобранные байты, тот
        // называет сигнал ([READER_NOT_DECODED]); молчание не доказывает ничего про объект
        // (#684/#685), и слов про поломку у него быть не может.
        signals.filterNot { readerFailureIsFatal(it) }.forEach {
            val said = readerFailure(it, ObjectKind.IMAGE)
            assertFalse("«$it» не про объект, а сказано «$said»", said.contains("повреждён"))
        }
    }

    /**
     * Тот, кто видел неразобранные байты, говорит об этом сигналом, а не молчанием (#1258):
     * читатель кодов и очистка снимка звали `readerFailure(null)`, и словарь отвечал «файл
     * повреждён» там, где годность объекта отвечала «дело не в объекте».
     */
    @Test
    fun `неразобранные байты — про сам объект и словами, и годностью`() {
        assertTrue(readerFailureIsFatal(READER_NOT_DECODED))
        assertTrue(readerFailure(READER_NOT_DECODED, ObjectKind.IMAGE).contains("не открылся"))
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
        val reasons = listOf(READER_NOT_DECODED, "not an image", "malformed")

        kinds.forEach { kind ->
            val saidForKind = reasons.map { readerFailure(it, kind) }.toSet()
            assertEquals("у вида $kind одно слово отказа на все поломки", 1, saidForKind.size)
        }
        assertFalse(
            "PDF и изображение объяснены разными словами",
            readerFailure(READER_NOT_DECODED, ObjectKind.PDF) == readerFailure(READER_NOT_DECODED, ObjectKind.IMAGE),
        )
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

    // ---- #1101: тот же ответ по уже сказанным словам, когда сигнала больше нет. ----

    /**
     * Технический сигнал живёт один вызов, а с объектом остаётся фраза человеку. Дверь
     * чтения снимают по ней, и разойтись эти два ответа не имеют права: иначе экран говорит
     * «попробуйте ещё раз» там, где пробовать уже нечем, — или наоборот.
     */
    @Test
    fun `по сказанным словам ответ тот же, что и по сигналу`() {
        val signals = listOf(
            null, "", READER_NOT_DECODED, "not an image", "corrupt stream", "malformed", READER_NO_PAGES,
            "read timed out", "deadline exceeded", "413 payload too large", "engine init failed",
            "error: OutOfMemoryError", "java.lang.IllegalStateException", "Password required",
            "cannot create document: file not in PDF format",
        )

        ObjectKind.entries.forEach { kind ->
            signals.forEach { signal ->
                val said = readerFailure(signal, kind)
                assertEquals(
                    "«$signal» у вида $kind: по сигналу и по словам «$said» ответы разошлись",
                    readerFailureIsFatal(signal),
                    saidFailureIsFatal(said),
                )
            }
        }
    }

    /**
     * Пустой файл и обломок архива говорят о содержимом сами, без ридера, — и это знание
     * первого захода. Молчание же не сказало ничего: метка без слов дверь не закрывает.
     */
    @Test
    fun `свои слова о содержимом фатальны, а молчание — нет`() {
        assertTrue(saidFailureIsFatal(EMPTY_FILE_REASON))
        assertTrue(saidFailureIsFatal(BROKEN_ARCHIVE_REASON))
        assertFalse(saidFailureIsFatal(null))
        assertFalse(saidFailureIsFatal(""))
        assertFalse(saidFailureIsFatal("   "))
    }
}
