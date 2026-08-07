package com.point.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ObjectNameIsNotFileNameTest {

    @get:Rule val temp = TemporaryFolder()

    private fun inbox() = Inbox(temp.newFolder("Point-" + System.nanoTime()))

    private val human = "Счёт 4512 от ООО Ромашка. Оплатить до 20…"

    @Test fun `на экране остаётся имя, которое прислал телефон`() {
        val item = inbox().receive(human, "text/plain", emptyMap(), "текст".byteInputStream())

        assertEquals(human, item.obj.metadata["name"])
    }

    @Test fun `на диске лежит имя, пригодное для файла`() {
        val item = inbox().receive(human, "text/plain", emptyMap(), "текст".byteInputStream())
        val file = File(item.obj.uri.value)

        assertTrue("файл без расширения не открыть двойным щелчком: ${file.name}", file.name.endsWith(".txt"))
        assertTrue("многоточие осталось в имени файла: ${file.name}", "…" !in file.name)
        assertTrue("файла нет на диске", file.isFile)
    }

    @Test fun `расширение берётся у типа объекта, а второго не приписывается`() {
        val box = inbox()

        assertTrue(File(box.receive("снимок", "image/png", emptyMap(), "x".byteInputStream()).obj.uri.value).name.endsWith(".png"))
        assertTrue(File(box.receive("отчёт.pdf", "application/pdf", emptyMap(), "x".byteInputStream()).obj.uri.value).name.endsWith(".pdf"))
        assertEquals(
            "приписали второе расширение",
            1,
            File(box.receive("отчёт2.pdf", "application/pdf", emptyMap(), "x".byteInputStream()).obj.uri.value)
                .name.count { it == '.' },
        )
    }

    @Test fun `повторный приём не меняет имя объекта, а на диске разводит файлы`() {
        val box = inbox()

        val first = box.receive(human, "text/plain", emptyMap(), "раз".byteInputStream())
        val second = box.receive(human, "text/plain", emptyMap(), "два".byteInputStream())

        assertEquals("«(2)» попало человеку на экран", human, second.obj.metadata["name"])
        assertTrue("файлы затёрли друг друга", first.obj.uri.value != second.obj.uri.value)
        assertEquals("раз", File(first.obj.uri.value).readText())
        assertEquals("два", File(second.obj.uri.value).readText())
    }

    @Test fun `безымянное всё-таки получает имя файла`() {
        val item = inbox().receive("…", "text/plain", emptyMap(), "x".byteInputStream())

        assertTrue("файл остался без имени", File(item.obj.uri.value).name.isNotBlank())
    }
}
