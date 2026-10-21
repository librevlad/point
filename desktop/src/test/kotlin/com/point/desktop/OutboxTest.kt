package com.point.desktop

import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OutboxTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun obj(content: String, name: String): PointObject {
        val f = File(tmp.root, "src-${System.nanoTime()}").apply { writeText(content) }
        return PointObject(
            "id", "text/plain", ScratchRef(f.absolutePath), ObjectState(ObjectKind.TEXT),
            metadata = mapOf("name" to name, "entity.phone" to "+380671234567"),
        )
    }

    private fun outbox() = Outbox(File(tmp.root, "outbox"))

    @Test
    fun `add returns growing ids and entries carry the full metadata`() {
        val box = outbox()
        assertEquals(1, box.add(obj("один", "чек.jpg")))
        assertEquals(2, box.add(obj("два", "заметка.txt")))

        val entries = box.entries()
        assertEquals(listOf(1, 2), entries.map { it.id })
        assertEquals("чек.jpg", entries[0].meta["name"])
        assertEquals("text/plain", entries[0].meta["mime"])
        assertEquals("+380671234567", entries[0].meta["entity.phone"])
    }

    @Test
    fun `file by id streams the copied bytes, unknown id is null`() {
        val box = outbox()
        val id = box.add(obj("содержимое", "a.txt"))
        assertEquals("содержимое", box.file(id)!!.readText())
        assertNull(box.file(999))
    }

    @Test
    fun `remove drops the pair and repeated remove is quiet`() {
        val box = outbox()
        val id = box.add(obj("x", "a.txt"))
        box.remove(id)
        assertTrue(box.entries().isEmpty())
        assertNull(box.file(id))
        box.remove(id)
    }

    /** Исход без объекта (#1073): запись из одних слов стоит в очереди наравне с вещами, файла у неё нет. */
    @Test
    fun `исход без объекта лежит в очереди словами, без файла, и убирается как все`() {
        val box = outbox()
        val thing = box.add(obj("x", "a.txt"))
        val words = mapOf("result.outcome" to "done", "result.detail" to "Отменено", "exec.home" to "obj-1")
        val outcome = box.addOutcome(words)

        assertEquals(listOf(thing, outcome), box.entries().map { it.id })
        assertEquals(words, box.entries().last().meta)
        assertNull("у исхода нет файла — забирать нечего", box.file(outcome))

        box.remove(outcome)
        assertEquals(listOf(thing), box.entries().map { it.id })
    }

    @Test
    fun `the source object keeps its file - the outbox owns a copy`() {
        val box = outbox()
        val source = obj("оригинал", "a.txt")
        box.add(source)
        assertTrue(File(source.uri.value).exists())
    }

    @Test
    fun `the to-phone action drops the object into the outbox`() {
        val box = outbox()
        val result = kotlinx.coroutines.runBlocking { PcToPhoneRealizer(box).perform(obj("тело", "чек.jpg"), null) }
        assertTrue(result is com.point.core.model.ActionResult.Done)
        assertEquals(listOf("чек.jpg"), box.entries().map { it.meta["name"] })
    }

    /**
     * Набор — папка, и `copyTo` клал её пустой (#1414): телефону на забор отдать было нечего,
     * и пустышка останавливала весь забор очереди. Набор едет архивом в порядке страниц.
     */
    @Test
    fun `набор встаёт в очередь архивом со всеми страницами по порядку`() {
        val box = outbox()
        val pages = File(tmp.root, "pages-1168").apply { mkdirs() }
        listOf("b.png", "a.png", "c.png").forEach { File(pages, it).writeText("страница $it") }
        val set = PointObject(
            "set", "inode/directory", ScratchRef(pages.absolutePath), ObjectState(ObjectKind.COLLECTION),
            metadata = mapOf(
                com.point.core.flow.META_COLLECTION_ORDER to com.point.core.flow.collectionOrderValue(listOf("c.png", "a.png")),
                "op" to "pages",
            ),
        )

        val id = box.add(set)

        val entry = box.entries().single()
        assertEquals("pages-1168.zip", entry.meta["name"])
        assertEquals("application/zip", entry.meta["mime"])
        assertEquals("знание набора едет вместе с ним", "pages", entry.meta["op"])
        val file = box.file(id)
        assertTrue("телефону на забор отдать нечего", file != null)
        val pagesIn = java.util.zip.ZipFile(file!!).use { zip ->
            zip.entries().toList().map { it.name to zip.getInputStream(it).readBytes().toString(Charsets.UTF_8) }
        }
        assertEquals(
            "порядок набора потерян",
            listOf("c.png" to "страница c.png", "a.png" to "страница a.png", "b.png" to "страница b.png"),
            pagesIn,
        )
    }

    /** Пустой набор не притворяется вещью: отказ, и в очереди не остаётся пустышки (#1414). */
    @Test
    fun `пустой набор не встаёт в очередь и не оставляет пустышки`() {
        val box = outbox()
        val empty = File(tmp.root, "пусто").apply { mkdirs() }
        val set = PointObject("set", "inode/directory", ScratchRef(empty.absolutePath), ObjectState(ObjectKind.COLLECTION))

        assertTrue(runCatching { box.add(set) }.isFailure)
        assertTrue(box.entries().isEmpty())
        assertTrue("пустышка осталась в папке очереди", File(tmp.root, "outbox").listFiles().orEmpty().isEmpty())
    }
}
