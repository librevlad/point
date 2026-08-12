package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Знание о типах файлов — одно на оба устройства (#840).
 *
 * Жило в трёх местах: таблица компьютера, короткая таблица истории телефона и классификатор.
 * Формат, добавленный в одном, в остальных не появлялся — и копия открывалась не тем
 * приложением, что исходник.
 */
class FileTypesTest {

    @Test
    fun `тип узнаётся по имени, регистр не мешает`() {
        assertEquals("image/jpeg", mimeForName("photo.JPG"))
        assertEquals("application/pdf", mimeForName("Договор.PDF"))
        assertEquals("text/markdown", mimeForName("notes.md"))
    }

    @Test
    fun `незнакомое честно называется потоком байтов, а не выдумывается`() {
        assertEquals(UNKNOWN_MIME, mimeForName("данные.чтотоновое"))
        assertEquals(UNKNOWN_MIME, mimeForName("безрасширения"))
    }

    @Test
    fun `расширение возвращается к своему типу — путь замкнут`() {
        listOf("photo.jpg", "doc.pdf", "pack.zip", "table.xlsx", "letter.docx", "note.txt")
            .forEach { name ->
                val mime = mimeForName(name)
                val ext = extensionForMime(mime)
                assertEquals("$name → $mime → $ext", mime, mimeForName("x.$ext"))
            }
    }

    @Test
    fun `имя знает больше таблицы — расширение берётся из него`() {
        assertEquals("heic", extensionForFile("снимок.heic", UNKNOWN_MIME))
        assertEquals("json", extensionForFile("ответ.json", UNKNOWN_MIME))
    }

    @Test
    fun `нет имени — расширение по типу`() {
        assertEquals("pdf", extensionForFile(null, "application/pdf"))
        assertEquals("txt", extensionForFile(null, "text/plain; charset=utf-8"))
        assertEquals("png", extensionForFile(null, "image/png"))
    }

    @Test
    fun `для неизвестного типа расширения нет — пустое честнее выдуманного`() {
        assertTrue(extensionForMime(UNKNOWN_MIME).isEmpty())
        assertTrue(extensionForFile(null, UNKNOWN_MIME).isEmpty())
    }

    @Test
    fun `подпись объекта не путается с расширением — длинный хвост именем не считается`() {
        assertEquals("pdf", extensionForFile("файл.оченьдлинныйхвост", "application/pdf"))
    }
}
