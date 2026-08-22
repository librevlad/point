package com.point.desktop

import com.point.core.flow.META_ENTITY_URL
import com.point.core.flow.META_UNUSABLE_REASON
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InboxTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `uniqueChildName sanitises separators and traversal`() {
        assertEquals("_.._evil.sh", uniqueChildName(emptySet(), "/../evil.sh"))
        assertEquals("a_b_c.txt", uniqueChildName(emptySet(), "a\\b/c.txt"))
        assertEquals("объект", uniqueChildName(emptySet(), "  "))
    }

    @Test
    fun `uniqueChildName resolves collisions with a counter`() {
        assertEquals("чек (2).jpg", uniqueChildName(setOf("чек.jpg"), "чек.jpg"))
        assertEquals("чек (3).jpg", uniqueChildName(setOf("чек.jpg", "чек (2).jpg"), "чек.jpg"))
    }

    @Test
    fun `receive lands bytes under a cyrillic name with the right kind`() {
        val inbox = Inbox(tmp.root)
        val item = inbox.receive(
            name = "чек.jpg", mime = "image/jpeg",
            meta = mapOf("entity.phone" to "+380671234567"),
            source = byteArrayOf(7, 8, 9).inputStream(),
        )
        assertEquals("чек.jpg", item.obj.metadata["name"])
        assertEquals(ObjectKind.IMAGE, item.obj.state.kind)
        assertEquals("+380671234567", item.obj.metadata["entity.phone"])
        assertEquals(3, java.io.File(item.obj.uri.value).length())
    }

    @Test
    fun `addText creates a note file and classifies TEXT`() {
        val inbox = Inbox(tmp.root)
        val item = inbox.addText("привет с телефона")
        assertEquals(ObjectKind.TEXT, item.obj.state.kind)
        assertEquals("привет с телефона", java.io.File(item.obj.uri.value).readText())
    }

    @Test
    fun `addFile wraps a local file in place without copying`() {
        val f = tmp.newFile("local.pdf").apply { writeBytes(byteArrayOf(1)) }
        val item = Inbox(tmp.root).addFile(f.absolutePath)
        assertEquals(ObjectKind.PDF, item.obj.state.kind)
        assertEquals(f.absolutePath, item.obj.uri.value)
    }

    // ---- #684: та же пустота, что и на телефоне, называет себя и на компьютере. ----

    @Test
    fun `пустой файл на компьютере тоже называет свою причину сразу`() {
        val f = tmp.newFile("prazdno.txt")
        val item = Inbox(tmp.root).addFile(f.absolutePath)

        assertTrue(item.obj.state.has(Feature.UNUSABLE))
        assertEquals("Файл пустой — в нём нечего читать", item.obj.metadata[META_UNUSABLE_REASON])
    }

    @Test
    fun `файл с содержимым на компьютере не несёт пометки негодности`() {
        val f = tmp.newFile("local.pdf").apply { writeBytes(byteArrayOf(1)) }
        val item = Inbox(tmp.root).addFile(f.absolutePath)

        assertFalse(item.obj.state.has(Feature.UNUSABLE))
        assertNull(item.obj.metadata[META_UNUSABLE_REASON])
    }

    // ---- #999: приём с телефона — такая же дверь рождения объекта, адрес читается и здесь. ----

    @Test
    fun `ссылка, принятая с телефона, знает свой адрес`() {
        val address = "https://example.com/pointtest?a=1"
        val item = Inbox(tmp.root).receive(
            name = "ссылка.txt", mime = "text/uri-list", meta = emptyMap(),
            source = "# сохранено из браузера\r\n\r\n$address\r\n".byteInputStream(),
        )

        assertEquals(ObjectKind.URL, item.obj.state.kind)
        assertEquals(address, item.obj.metadata[META_ENTITY_URL])
        assertTrue("адрес есть — признак ссылки обязан стоять", item.obj.state.has(Feature.HAS_URL))
    }
}
